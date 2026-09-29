package com.hdf.cryptand.neoforge.opencomputers;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.AbstractManagedEnvironment;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * ===== 我们的 EEPROM 作为 OC 的 eeprom 组件（2026-09-26）=====
 *
 * <p>用户实测（截图）：把我们的 C 架构 EEPROM 插进机器后，机器报
 * {@code Unrecoverable Error / no bios found; install a configured EEPROM}。</p>
 *
 * <p><b>根因 1</b>（读 OC 源码 {@code lua/machine.lua:1492-1504} 确认）：OC 的 Lua 架构启动时做的是</p>
 * <pre>
 *   local eeprom = libcomponent.list("eeprom")()
 *   if eeprom then local code = libcomponent.invoke(eeprom, "get") ... end
 *   error("no bios found; install a configured EEPROM")
 * </pre>
 * <p>而我们的 {@code EepromDriver.createEnvironment} 一直返回 {@code noEnvironment()} —— 也就是说
 * <b>我们只占了 EEPROM 槽，从来没有把它变成一个 eeprom 组件</b>：{@code component.list("eeprom")}
 * 恒为空 ⇒ 直接走 error 分支。报错信息里那句 "no bios found" 和"内容不是 Lua"无关，
 * 是<b>组件根本不存在</b>。</p>
 *
 * <p><b>根因 2</b>（2026-09-26 第二次真机截图 {@code /init.lua:2: attempt to call a nil value
 * (field 'getBootAddress')}）：真 OpenOS 盘的 {@code /init.lua} 第 2 行就是
 * {@code computer.getBootAddress()}，而那个函数是 <b>BIOS 的职责</b>（OC 官方 BIOS 在启动时把它挂到
 * {@code computer} 表上，用 EEPROM 的 <b>data 段</b>做存储）。所以本组件必须提供
 * {@code getData / setData} —— 它们不是可选项，是 {@code computer.getBootAddress()} 的唯一落点。</p>
 *
 * <p><b>双模启动</b>（用户定案："EEPROM 既支持原版 OC 的 Lua 兼容启动，也支持 FATFS 盘找
 * bootloader 启动"）：本组件提供的 {@code get()} 返回 <b>Cryptand 自有的 Lua BIOS</b>
 * （{@code assets/cryptand/soc/bios.lua}，原创实现，不是 OC 的那份）。于是：
 * <ul>
 *   <li>架构 = OC Lua ⇒ machine.lua 拿到本组件 ⇒ {@code get()} 得到可编译的 Lua BIOS ⇒ 正常启动；</li>
 *   <li>架构 = Cryptand RV32 ⇒ 走宿主 BIOS（{@code CryptandBios}）把 C 固件刷进 guest ROM，
 *       <b>根本不读这个组件</b>（C 架构没有 Lua 解释器）。</li>
 * </ul>
 * 两条路互不干扰，靠的就是"架构决定谁来读 EEPROM"。</p>
 *
 * <h3>方法面 = OC 的 eeprom 组件（{@code server/component/EEPROM.scala}）</h3>
 * <p>照抄它的<b>名字与语义</b>（不是实现）：{@code get / set / getSize / getData / setData /
 * getDataSize / getLabel / setLabel / getChecksum}。名字必须一模一样 —— OpenOS 的
 * {@code bin/flash.lua} 调 {@code eeprom.getLabel()}、{@code lib/core/devfs} 读
 * {@code components/by-type/eeprom/0/data}，差一个字母就是"程序在别人机器上能跑、在我们机器上报
 * attempt to call a nil value"。</p>
 *
 * <p>⚠ 曾经把容量查询写成 {@code size}（OC 里叫 {@code getSize}）—— 已改回 OC 的名字，
 * 内测期不留别名（两套名字就是两套路径）。</p>
 *
 * <h3>为什么这 9 个回调全都 {@code direct = true}</h3>
 * <p>OC 官方的 eeprom 组件是<b>非 direct</b> 的 —— 它必须回主线程去读写<b>物品栈</b>（NBT 在物品上，
 * 只有服务端线程能碰）。我们的内容全在<b>这个环境对象自己的字段</b>里（{@code code/data/label}），
 * 没有任何世界 / 物品 / 网络访问 ⇒ 回主线程只是白白多等一个服务端 tick。</p>
 *
 * <p>线程安全的依据是<b>不可变替换</b>：{@code set}/{@code setData}/{@code setLabel} 一律
 * <em>换掉整个引用</em>（从不原地改数组内容），字段是 {@code volatile} —— Lua 线程写、主线程
 * {@code saveData} 读，最坏情况是存下上一版（下一次保存即修好），不会读到半个数组。</p>
 *
 * <p>这也是用户问"官方异步线程怎么和组件通讯"的直接落点：官方不得不跨线程，是因为它的状态在
 * <b>别人的线程</b>（服务端/物品栈）里；我们的状态在自己的字段里，就不该付那笔过路费。</p>
 *
 * <p>⚠ <b>容量口径（2026-09-27 收口）</b>：
 * {@code CryptandOcDrivers.EepromDriver} 传进来的是**芯片自己的容量**
 * （{@code BootPlan.eepromBytes(spec)} = {@code spec × 1024}，容量表唯一来源
 * {@code SocCapacities.EEPROM_KB}）—— 于是 128KB 档如实报 131072 字节。
 * 与"引导记录体积上限" {@code BootPlan.limitBytes(spec)} 是<b>两件事</b>，那个是引导判据用的
 * （min(EEPROM 容量, 引导区 64KB)），不能拿来当 {@code getSize()} 的答案。</p>
 */
public class CryptandEepromEnvironment extends AbstractManagedEnvironment {

    /** ⚠ 用 log4j（而不是 System.out）：只有 log4j 会进 run/logs/latest.log，排查时才能看到 */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    /** NBT：EEPROM 的 data 段（就是引导盘地址） */
    private static final String NBT_DATA = "CryptandBiosData";
    /** NBT：标签 */
    private static final String NBT_LABEL = "CryptandBiosLabel";

    /**
     * data 段容量 —— OC 的 {@code Settings.eepromDataSize} 默认 256 字节。
     *
     * <p>照同值的原因：这一段是 <b>OC 侧语义</b>（BIOS 拿它记引导盘地址、devfs 把它当
     * {@code /dev/eeprom-data} 暴露），不是我们自己定的档位 —— 自造一个数就是"第二份数值"。</p>
     */
    private static final int DATA_SIZE = 256;

    /** 容量（字节）：来自部件档位（{@code BootPlan.eepromBytes} = 档位 KB × 1024），OC 的 {@code getSize()} 返回它 */
    private final int capacity;

    /** BIOS 源码（UTF-8）—— 来自 common 的资源，见 {@link #loadData} 的说明 */
    private volatile byte[] code;

    /** data 段：引导盘地址等"需要跨重启记住"的小数据 */
    private volatile byte[] data = new byte[0];

    private volatile String label = "EEPROM";

    public CryptandEepromEnvironment(byte[] initial, int capacityBytes) {
        this.code = initial == null ? new byte[0] : initial.clone();
        this.capacity = capacityBytes > 0 ? capacityBytes : this.code.length;
        // 组件名必须是 "eeprom"：machine.lua 就是按这个名字 list 出来的。
        setNode(Network.newNode(this, Visibility.Neighbors)
                .withComponent("eeprom", Visibility.Neighbors)
                .create());
        // ★ 双模启动里"Lua 目标载体"这一侧的自我声明（2026-09-27）：
        //   本组件的内容 = Cryptand 自写的 Lua BIOS（common 资源 assets/cryptand/soc/bios.lua），
        //   Lua 架构会 get() 它并 load() 执行；C/RV32 架构**根本不读它**（走宿主 BIOS + 盘上 /boot/loader.bin）。
        //   "支持 FATFS"就落在 bios.lua 的能力上：component.list("filesystem", true) 列盘（含不可见组件）、
        //   按 /init.lua 找 Lua 入口，并按 /boot/loader.bin 区分"这块盘是给 C/RV32 的"。
        LOG.info("[OpenComputers] Cryptand EEPROM 组件就绪（目标 = Lua）：Lua BIOS {} 字节 / 容量 {} 字节；"
                        + "C/RV32 目标不读它（宿主 BIOS + 盘上的 /boot/loader.bin）",
                this.code.length, this.capacity);
        if (this.code.length > this.capacity) {
            // 明确报错、不静默截断：内容来自**固件资源**（不是这颗芯片），装不下就是装不下 ——
            // 此时 getSize() 说"容量 N"，get() 却吐出 N 以上的字节，两边自相矛盾，必须让人看见。
            LOG.error("[OpenComputers] ✖ 这颗 EEPROM 装不下 Cryptand Lua BIOS：{} 字节 > 容量 {} 字节"
                            + "（差 {} 字节）⇒ 换 >= {}KB 档的 EEPROM 部件（SocCapacities.EEPROM_KB：4/8/16/32/64/128）",
                    this.code.length, this.capacity, this.code.length - this.capacity,
                    (this.code.length + 1023) / 1024);
        }
    }

    /** {@code get()} → 当前 BIOS 源码（Lua 架构会 load() 它） */
    @Callback(value = "get", direct = true, doc = "function():string -- the BIOS code stored in this EEPROM")
    public Object[] get(Context context, Arguments args) {
        return new Object[]{new String(code, StandardCharsets.UTF_8)};
    }

    /** {@code set(code)} → 换 BIOS（照 OC：超出容量抛 "not enough space"，不静默截断） */
    @Callback(value = "set", direct = true, doc = "function(code:string) -- replace the BIOS code")
    public Object[] set(Context context, Arguments args) {
        final byte[] next = args.checkString(0).getBytes(StandardCharsets.UTF_8);
        if (next.length > capacity) {
            throw new IllegalArgumentException("not enough space");
        }
        this.code = next;
        return new Object[]{};
    }

    /** {@code getSize()} → 容量（字节）。OC 里名字就是 getSize，不是 size */
    @Callback(value = "getSize", direct = true, doc = "function():number -- the storage capacity of this EEPROM")
    public Object[] getSize(Context context, Arguments args) {
        return new Object[]{capacity};
    }

    /**
     * {@code getData()} → data 段（引导盘地址）。
     *
     * <p>这一对方法是 {@code computer.getBootAddress()} 的落点：我们的
     * {@code bios.lua} 用它读写引导盘地址，OpenOS 的 {@code /init.lua} 第 2 行读它。</p>
     */
    @Callback(value = "getData", direct = true, doc = "function():string -- the data stored in this EEPROM")
    public Object[] getData(Context context, Arguments args) {
        return new Object[]{new String(data, StandardCharsets.UTF_8)};
    }

    /** {@code setData(data)} → 写 data 段（地址为空串 = 清掉记忆） */
    @Callback(value = "setData", direct = true, doc = "function(data:string) -- overwrite the data stored in this EEPROM")
    public Object[] setData(Context context, Arguments args) {
        final byte[] next = args.optString(0, "").getBytes(StandardCharsets.UTF_8);
        if (next.length > DATA_SIZE) {
            throw new IllegalArgumentException("not enough space");
        }
        this.data = next;
        return new Object[]{};
    }

    /** {@code getDataSize()} → data 段容量 */
    @Callback(value = "getDataSize", direct = true, doc = "function():number -- the storage capacity of the data area")
    public Object[] getDataSize(Context context, Arguments args) {
        return new Object[]{DATA_SIZE};
    }

    @Callback(value = "getLabel", direct = true, doc = "function():string -- the label of this EEPROM")
    public Object[] getLabel(Context context, Arguments args) {
        return new Object[]{label};
    }

    @Callback(value = "setLabel", direct = true, doc = "function(data:string):string -- set the label of this EEPROM")
    public Object[] setLabel(Context context, Arguments args) {
        String next = args.optString(0, "EEPROM").trim();
        if (next.length() > 24) {
            next = next.substring(0, 24);
        }
        label = next.isEmpty() ? "EEPROM" : next;
        return new Object[]{label};
    }

    /**
     * {@code getChecksum()} → BIOS 源码的 CRC32（小写十六进制，8 位）。
     *
     * <p>格式对齐 OC（{@code Hashing.crc32().hashBytes(codeData).toString()}）—— 它是
     * {@code flash.lua} 判断"这颗 EEPROM 里装的是不是同一份固件"的依据。</p>
     */
    @Callback(value = "getChecksum", direct = true, doc = "function():string -- the checksum of the data on this EEPROM")
    public Object[] getChecksum(Context context, Arguments args) {
        final CRC32 crc = new CRC32();
        crc.update(code);
        return new Object[]{String.format("%08x", crc.getValue())};
    }

    // ==================== 持久化 ====================

    /**
     * ⚠ 这里**只恢复 data 段与标签，不恢复 BIOS 源码**。
     *
     * <p>为什么：BIOS 是**固件**，唯一来源是 common 资源里的 {@code bios.lua}
     * （与盘上的 {@code /boot/system.bin} 同理）。若把旧 NBT 里的文本也当来源，就会变成
     * "改了 bios.lua、机器却还在跑上一次那份" —— 修改不生效、且极难查（两个来源都对，
     * 版本不同）。这正是内测期纪律里"删掉第二份数值"要消掉的东西。</p>
     */
    @Override
    public void loadData(CompoundTag tag, HolderLookup.Provider registries)
            throws li.cil.oc.api.UnrecoverablePersistanceException {
        super.loadData(tag, registries);
        if (tag.contains(NBT_DATA)) {
            data = tag.getByteArray(NBT_DATA);
        }
        if (tag.contains(NBT_LABEL)) {
            label = tag.getString(NBT_LABEL);
        }
    }

    @Override
    public void saveData(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveData(tag, registries);
        tag.putByteArray(NBT_DATA, data);
        tag.putString(NBT_LABEL, label);
    }
}

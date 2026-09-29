package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.SocIsa;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.block.SocAssemblerBlock;
import com.hdf.cryptand.neoforge.soc.block.SocAssemblerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * ===== SoC 内容注册（2026-09-15 建立，2026-09-16 部件方块化）=====
 *
 * <p>物品体系参考 OpenComputers（机箱 / 主板 / 处理器 / 内存条 / 硬盘 / 扩展卡），映射到 MCU/SoC 语义。</p>
 *
 * <h3>两套机箱（2026-09-16 用户规则）</h3>
 * <ul>
 *   <li><b>SOC 机箱</b>（{@code soc_chip}）：只能装 <b>SOC 成品芯片 + 扩展卡</b>（不放内存/处理器等散件）；</li>
 *   <li><b>自由组装机箱</b>（{@code case_tower}，塔式）：可自由装 处理器 / 内存条 / 硬盘 / 显卡 / 底板 / 扩展卡 —— 参考 OC 玩法。</li>
 * </ul>
 *
 * <h3>部件都是纯物品（2026-09-17 用户定稿）</h3>
 * <p>每件部件 = {@link SocPartItem 普通物品}（保留 kind/tier/spec 语义），<b>只插进机箱，不放置到世界里</b>
 * —— 对齐 OC 原版 cpu1/hdd1 的形态。因此部件没有方块、没有方块状态，也不需要方块模型，
 * 外观直接由 {@code models/item/*.json} 提供。</p>
 *
 * <p>仍然是方块的只有三样<b>机器</b>：SOC 成品机箱（{@code soccase}）、自由组装塔式机箱（{@code case1}）、
 * 组装台（{@code assembler}）—— 它们有各自的方块状态与模型。</p>
 */
public final class SocContent {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cryptand.MOD_ID);

    // ==================== 部件注册辅助 ====================

    /**
     * 注册一件部件（**纯物品**）。
     *
     * <p>物品是 {@link SocPartItem}，{@code instanceof SocPartItem} 的槽位判断
     * （组装台 / 机箱）完全不受影响；它不再是 BlockItem，所以不会被放进世界。</p>
     */
    private static DeferredHolder<Item, SocPartItem> part(String id, SocPartKind kind, int tier, int spec, String desc) {
        return ITEMS.register(id, () -> new SocPartItem(kind, tier, spec, desc));
    }

    /**
     * 注册一颗**处理器**：比 {@link #part} 多一个<b>必填</b>的 ISA / 位宽声明。
     *
     * <p>为什么单独一个方法（2026-09-18 用户定案）："位宽由 CPU 部件决定" + "绝不静默按 RV32 跑"
     * ⇒ 位宽必须是芯片自己的结构化字段（{@link SocIsa}），不能靠 id 字符串猜、更不能由架构默认。
     * 用独立入口把 {@code isa} 变成**编译期必填参数**，漏声明就没有办法注册成功。</p>
     *
     * <p>⚠ 物品 id 一律带位宽后缀（{@code _32}/{@code _64}），与 {@code isa} 一一对应：
     * {@code rv32ec} 的 XLEN 也是 32 ⇒ 后缀仍是 {@code _32}（绝不写 {@code _16}）。</p>
     */

    // ==================== 处理器：MCU / SOC / CPU 三档 × RV32/RV64（2026-09-18 定案） ====================
    //
    // 规格口径（用户 2026-09-17：**描述里直接写 MHz**，方便比较性能）：
    //   spec = 每 tick 指令预算；MC 每秒 20 tick ⇒ 等效主频 = spec × 20
    //     MCU 极低端 spec    10_000 →  0.2 MHz（RV32EC：闪灯级别的极小固件）
    //     MCU 入门  spec    50_000 →   1 MHz（闪灯 / 定时器）
    //     MCU 标准  spec   200_000 →   4 MHz（PID 控电机）
    //     MCU 8051  spec    20_000 → 0.4 MHz（MCS-51；SDCC / Keil C51；占位）
    //     SOC       spec 1_000_000 →  20 MHz（应用级 / 图形界面 / FreeRTOS+LVGL）
    //     CPU       spec 50_000_000 → 1000 MHz = 1 GHz（高频；用户 2026-09-25："CPU 增加到最高 1 GHz"）
    //   ⚠ 档位描述**不绑定具体系统**（用户 2026-09-25："不要写 linux 挡位之类的"）——
    //   ⚠ "标称 MHz" 是预算口径；实际吞吐受宿主线程分配器与解释器限制（见 SocScheduler），
    //     预算跑不完的部分顺延到下一 tick，不会拖累主线程。
    //
    // 用户 2026-09-18 命名规则（**id 一律带位宽后缀 _32/_64**）与位宽矩阵：
    //   id        ISA       档位      位宽  内核状态
    //   mcu0_32   rv32ec    极低端     32    ✖ 未实现（RV32E 的 16 寄存器规则）⇒ 占位、拒绝开机
    //   mcu1_32   rv32im    入门       32    ✔ 完整可用
    //   mcu2_32   rv32imc   标准       32    △ 可开机，C 扩展未实现
    //   mcu3_8051 mcs51     极低端     8     ✖ 未实现（8051 架构）⇒ 占位、拒绝开机
    //   soc1_32   rv32imac  应用       32    △ 可开机，A/C 扩展未实现
    //   soc1_64   rv64imac  应用       64    ✖ 未实现（RV64 位宽）⇒ 占位、拒绝开机
    //   cpu1_32   rv32imac  高频       32    △ 可开机，A/C 扩展未实现
    //   cpu1_64   rv64imac  高频       64    ✖ 未实现（RV64 位宽）⇒ 占位、拒绝开机
    //   ⚠ MCU 档**只有 RV32**（用户定案）+ 一个 8051 架构档：MCU 定位是单片机，没有 64 位变体。
    //   ⚠ 8051 的 id 后缀用 `_8051` 而不是 `_8`：8051 的 8 位与 RISC-V 的 XLEN 体系无关，
    //     写 `_8` 会被误读成"8 位 RISC-V"（RISC-V 里没有这个位宽）。沙箱定位是"多 ISA 虚拟机平台"，
    //     将来还会有别的非 RISC-V 架构 ⇒ 非 RISC-V 一律用**架构族名后缀**。
    //   ⚠ 位宽/架构由**处理器部件**决定（不是 EEPROM、不是盘）：架构只读 {@link SocPartItem#isa()}。
    //   ⚠ tier 语义**保持不变**（mcu 入门=1 / mcu 标准=2 / soc=3 / cpu=4；极低端与入门同档；
    //     64 位变体与其 32 位版本**同档同频**，唯一区别是位宽）。

    // ==================== 芯片：每种类型一颗示例（2026-09-29 用户定案）====================
    //
    // 用户原话："芯片定义多个，先定义 GPU、CPU 这两种"、"去除目前所有已经有的芯片，只给每个种类一种芯片，
    // 并且有大多数经典模块作为示例，为后续铺路，所有包括芯片种类的等详细信息写入 CPU 中，
    // 方便沙箱进行读取来展开"。
    //
    // ⇒ ① **不再一档一个物品**：旧的 mcu*/soc*/cpu* 共 26 颗芯片物品全部退役
    //      （档位表 {@code SocCpuTiers} 保留 —— 蓝图设计机将来用它给玩家选频率/族范围）；
    //    ② 每种 {@code ChipType} 一颗**示例芯片**：CPU（族 CPU = 能力最全，带大多数经典模块）与
    //      GPU（自带显存 + DP 输出通道）；
    //    ③ 规格（类型 + 族 + ISA + 频率 + 模块集 + 配置连接）随物品本身的 CUSTOM_DATA 走
    //      （{@code ChipSpecs.sample} → {@link SocSpec}）—— 沙箱开机读它来展开模块/外设，不按族猜；
    //    ④ 蓝图设计机（后续）产出的芯片走同一个类与同一份规格。
    public static final DeferredHolder<Item, SocAssembledItem> CHIP_CPU =
            registerSampleChip("chip_cpu", com.hdf.cryptand.soc.board.ChipType.CPU,
                    "示例芯片：族 CPU · RV32IM · 1 GHz · 全模块（含 PCIe / USB / DP）");
    public static final DeferredHolder<Item, SocAssembledItem> CHIP_GPU =
            registerSampleChip("chip_gpu", com.hdf.cryptand.soc.board.ChipType.GPU,
                    "示例芯片：GPU · 自带 8 MB 显存 · 2 路 DP 输出");

    private static DeferredHolder<Item, SocAssembledItem> registerSampleChip(
            String id, com.hdf.cryptand.soc.board.ChipType type, String desc) {
        return ITEMS.register(id, () -> new SocAssembledItem(ChipSpecs.sample(type), desc));
    }



    // ==================== 内存条：**不再自己定义**（2026-09-17 用户指令） ====================
    //
    // 用户："注意下原版有内存条，不重复定义额外的"。
    // OC 原版已经有 ram1..ram6 / ramcreative（每档容量在 OC 的 Settings.ramSizes 里），
    // 机箱内存槽本来就认它们 ⇒ 我们只需**复用**。我们的架构在做内存核算时按
    // "所有实现 OC Memory 驱动的组件求和"（见 CryptandOcArchitecture.recomputeMemory），
    // 所以玩家插 OC 内存条，Cryptand OS 一样能拿到容量。
    //
    // 原先的 cryptand:ram1..ram5 已删除（物品 / 语言 / 资源 / OC 驱动全部移除）。
    // SocPartKind.RAM 保留：组装台（soc_assembler，已置区）的内部槽位语义仍然用它。

    // ==================== 底板 / 硬盘 ====================

    public static final DeferredHolder<Item, SocPartItem> BOARD_MATX = part("componentbus1",
            SocPartKind.BOARD, 1, 2, "Micro-ATX 底板 · 2 个扩展槽");
    public static final DeferredHolder<Item, SocPartItem> BOARD_ATX = part("componentbus2",
            SocPartKind.BOARD, 2, 4, "ATX 底板 · 4 个扩展槽");

    /** 硬盘：**不区分机械 / 固态**，统一型号按容量分三档（模型为 OC 风格统一外观） */
    public static final DeferredHolder<Item, SocPartItem> DISK_1 = part("hdd1",
            SocPartKind.FLASH, 1, 8, "硬盘 · 8 KB");
    public static final DeferredHolder<Item, SocPartItem> DISK_2 = part("hdd2",
            SocPartKind.FLASH, 2, 64, "硬盘 · 64 KB");
    public static final DeferredHolder<Item, SocPartItem> DISK_3 = part("hdd3",
            SocPartKind.FLASH, 3, 256, "硬盘 · 256 KB");

    /**
     * 硬盘的**完整容量序列**（用户 2026-09-25："存储这样：提供 4K 开始，8K、16K、32K、64K…
     * 一直到 1GB"）。档位表在 common 的 {@link com.hdf.cryptand.soc.part.SocCapacities}，这里只负责按表注册。
     *
     * <p>⚠ 与 hdd1/2/3 有**同容量**的条目（8K/64K/256K）：用户明确"8K 重复定义也行" ——
     * 旧 id 是存档里已有的、不能撤；新序列是给"按容量直接挑盘"用的。
     * 外观复用同一批 OC 风格贴图（OC 原版物品也没有描述，将来靠合成/转换处理）。</p>
     */
    public static final java.util.Map<Integer, DeferredHolder<Item, SocPartItem>> HDD_SIZES =
            registerStorageTiers();

    private static java.util.Map<Integer, DeferredHolder<Item, SocPartItem>> registerStorageTiers() {
        final java.util.Map<Integer, DeferredHolder<Item, SocPartItem>> out = new java.util.LinkedHashMap<>();
        for (final int kb : com.hdf.cryptand.soc.part.SocCapacities.STORAGE_KB) {
            final int tier = kb <= 64 ? 1 : (kb <= 1024 ? 2 : 3);
            out.put(kb, part("hdd_" + com.hdf.cryptand.soc.part.SocCapacities.idSuffix(kb), SocPartKind.FLASH, tier, kb,
                    "硬盘 · " + com.hdf.cryptand.soc.part.SocCapacities.label(kb)));
        }
        return java.util.Map.copyOf(out);
    }

    // ==================== C 专属启动介质（2026-09-17） ====================

    /**
     * Cryptand 的 C 专属启动盘（**EEPROM 里只放 BIOS / Bootloader**，2026-09-17 定稿）。
     *
     * <p>用户："EEPROM 仅加载类似 BIOS 以及 bootloader 的程序，其余全部通过软盘/硬盘加载
     * —— 类似官方 OC 的实现。" 所以：</p>
     * <ul>
     *   <li><b>EEPROM（本物品）</b> = {@code cryptand-boot.bin} —— **Cryptand Boot**，
     *       上电后按 BIOS 语义从机箱里的硬盘逐块找可引导程序；</li>
     *   <li><b>硬盘（{@code cryptand:hdd1/2/3}）</b> = 系统盘：
     *       Cryptand OS / Cryptand UI OS / 扩展系统分别放上去（改配置就能换）；</li>
     *   <li>与官方 OC 一致：EEPROM 是 BIOS，系统在磁盘上，BIOS 把控制权交给磁盘里的系统。</li>
     * </ul>
     *
     * <p>所以**不再有 cbios1/2/3/4**（那套是"每张盘绑一个系统"的旧设计）。</p>
     */
    public static final DeferredHolder<Item, SocPartItem> EEPROM_C = part("cbios",
            SocPartKind.EEPROM, 1, 64, "C 启动 EEPROM · Cryptand Boot（BIOS：从软盘/硬盘引导系统）");

    /**
     * EEPROM 的容量档位（用户 2026-09-25："EEPROM 提供 4kb、8kb、16kb、32kb、64kb、128kb 这几种"）。
     *
     * <p>容量的实际含义 = <b>这颗芯片能承载多大的引导固件</b>：Cryptand BIOS 只认盘上的引导记录
     * {@code /boot/loader.bin}，而 EEPROM 决定"引导记录可以有多大"。
     * 参考：OC 原版 EEPROM 固定 8 KB（{@code application.conf: eepromSize: 8192}）。</p>
     */
    public static final java.util.Map<Integer, DeferredHolder<Item, SocPartItem>> EEPROM_SIZES =
            registerEepromTiers();

    private static java.util.Map<Integer, DeferredHolder<Item, SocPartItem>> registerEepromTiers() {
        final java.util.Map<Integer, DeferredHolder<Item, SocPartItem>> out = new java.util.LinkedHashMap<>();
        for (final int kb : com.hdf.cryptand.soc.part.SocCapacities.EEPROM_KB) {
            out.put(kb, part("eeprom_" + com.hdf.cryptand.soc.part.SocCapacities.idSuffix(kb), SocPartKind.EEPROM, 1, kb,
                    "启动 EEPROM · " + com.hdf.cryptand.soc.part.SocCapacities.label(kb) + "（Cryptand Boot / 引导记录上限）"));
        }
        return java.util.Map.copyOf(out);
    }

    // ==================== 软盘：**系统盘**（2026-09-17，对齐官方 OC） ====================
    //
    // 用户："Cryptand OS 不是放软盘吗" —— 对，系统装**软盘**（插 OC 的 floppy 槽），
    // 硬盘（hdd1/2/3）留给数据/文件系统。Cryptand Boot 的扫描顺序：
    //   **机箱内软盘 → 外部软盘 → 机箱内硬盘 → 外部硬盘**
    // 规格 = 容量(KB)：软盘 1/2/3 分别装 Cryptand OS / Cryptand UI OS / （第 3 张留作扩展）。

    public static final DeferredHolder<Item, SocPartItem> FLOPPY_1 = part("floppy1",
            SocPartKind.FLOPPY, 1, 512, "系统软盘 · ①Cryptand OS（FreeRTOS + 命令行）");

    public static final DeferredHolder<Item, SocPartItem> FLOPPY_2 = part("floppy2",
            SocPartKind.FLOPPY, 2, 1024, "系统软盘 · ②Cryptand UI OS（FreeRTOS + LVGL 桌面）");

    public static final DeferredHolder<Item, SocPartItem> FLOPPY_3 = part("floppy3",
            SocPartKind.FLOPPY, 3, 4096, "系统软盘 · ③ 扩展（4 MB，预留）");

    // ==================== 空软盘：多个容量，供"程序加载器 / 命令"写入 ====================
    //
    // 用户 2026-09-18："软盘可以定义多个大小的，方便使用"。空盘**不带任何预设系统** ——
    // 盘里装什么由内容决定（/cryptand disk load <盘> <程序>），引导读的就是盘里的 /boot/system.bin。
    // 所以这一组盘是"介质"，不是"系统盘"：容量不同只为装得下不同的程序
    // （Cryptand OS 约 16KB、UI OS 约 400KB）。
    //
    // ⚠ **tier = 0 是"空盘"的语义**（用户 2026-09-26："空软盘保证空"）。
    //   原先这一组写的 tier 是 1/1/2/3 —— 与系统软盘撞车，而
    //   CryptandOcDrivers.mountDisk 的预装条件是 tier 1..3 ⇒ **空软盘一放进机箱就被自动
    //   写进系统/镜像**，拿到手就不是空的了。tier 归 0 之后：不预装、不写任何东西，
    //   只有"程序加载器烧录"或"命令写入"才会往盘上落内容（容量由 spec 决定，不受 tier 影响）。
    public static final DeferredHolder<Item, SocPartItem> BLANK_64K = part("floppy_b64k",
            SocPartKind.FLOPPY, 0, 64, "空软盘 · 64 KB（空白介质，烧录后才有内容）");
    public static final DeferredHolder<Item, SocPartItem> BLANK_256K = part("floppy_b256k",
            SocPartKind.FLOPPY, 0, 256, "空软盘 · 256 KB（空白介质，烧录后才有内容）");
    public static final DeferredHolder<Item, SocPartItem> BLANK_1M = part("floppy_b1m",
            SocPartKind.FLOPPY, 0, 1024, "空软盘 · 1 MB（空白介质，烧录后才有内容）");
    public static final DeferredHolder<Item, SocPartItem> BLANK_4M = part("floppy_b4m",
            SocPartKind.FLOPPY, 0, 4096, "空软盘 · 4 MB（空白介质，烧录后才有内容）");

    // ==================== 扩展卡（含图形卡） ====================

    public static final DeferredHolder<Item, SocPartItem> CARD_GPIO = part("gpiocard",
            SocPartKind.CARD_GPIO, 1, 8, "数字 IO：接红石/引脚");
    public static final DeferredHolder<Item, SocPartItem> CARD_PWM = part("pwmcard",
            SocPartKind.CARD_PWM, 1, 4, "驱动电机转速");
    public static final DeferredHolder<Item, SocPartItem> CARD_ADC = part("adccard",
            SocPartKind.CARD_ADC, 1, 8, "采样电网电压/电流");
    public static final DeferredHolder<Item, SocPartItem> CARD_UART = part("uartcard",
            SocPartKind.CARD_UART, 1, 100, "串口（可接真实 MCU）");
    public static final DeferredHolder<Item, SocPartItem> CARD_GPU_ITX = part("graphicscard1",
            SocPartKind.CARD_GPU, 1, 1, "单风扇短卡 · 1 路输出");
    public static final DeferredHolder<Item, SocPartItem> CARD_GPU = part("graphicscard2",
            SocPartKind.CARD_GPU, 2, 2, "双风扇显卡 · 2 路输出");
    public static final DeferredHolder<Item, SocPartItem> CARD_GPU_PRO = part("graphicscard3",
            SocPartKind.CARD_GPU, 3, 4, "三风扇显卡 · 4 路输出");

    // ==================== 成品 SOC（组装台产物：处理器 + 内存集成） ====================

    /** 完整 SOC：组装台的产出，装入 SOC 机箱即运行（携带规格 NBT） */
    public static final DeferredHolder<Item, SocAssembledItem> SOC_ASSEMBLED = ITEMS.register("soc",
            SocAssembledItem::new);

    // ==================== 机箱：自由组装机箱（塔式，参考 OC：可自由插硬件） ====================
    // ⚠ 2026-09-28 用户定案：「删除 SOC，所有的走 oc 原版」——我方自造的 {@code soccase}（SOC 机箱）
    //   方块/物品/方块实体与它的运行面板、硬件调节器已整层退役，机器一律用 OC 原版机箱。

    /**
     * 自由组装机箱（{@code case_tower}）。
     *
     * <p>玩法：可自由装 处理器 / 内存条 / 硬盘 / 显卡 / 底板 / 扩展卡（组装槽位逻辑下一步接入）；
     * 当前先作为可放置方块提供模型展示。</p>
     */
    public static final DeferredHolder<Block, Block> CASE_TOWER = BLOCKS.register("case1",
            () -> new Block(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> CASE_TOWER_ITEM = ITEMS.register("case1",
            () -> new BlockItem(CASE_TOWER.get(), new Item.Properties()));

    // ==================== 组装台（参考 OC 组装机） ====================

    public static final DeferredHolder<Block, SocAssemblerBlock> SOC_ASSEMBLER = BLOCKS.register("assembler",
            () -> new SocAssemblerBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> SOC_ASSEMBLER_ITEM = ITEMS.register("assembler",
            () -> new BlockItem(SOC_ASSEMBLER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SocAssemblerBlockEntity>> SOC_ASSEMBLER_BE =
            BE_TYPES.register("assembler", () -> BlockEntityType.Builder
                    .of(SocAssemblerBlockEntity::new, SOC_ASSEMBLER.get()).build(null));

    // ==================== 程序加载器（烧录器，2026-09-25） ====================

    /**
     * 程序加载器：**手持介质右键** ⇒ 把内置程序写进该盘的 {@code /boot/system.bin}。
     * 现实对标 SEGGER J-Flash（固件烧录器）；面板（填宿主文件地址 / 校验 / 擦除 / 读回 / 日志）
     * 与方块的槽位界面是下一步。
     */
    public static final DeferredHolder<Block, com.hdf.cryptand.neoforge.soc.block.ProgramLoaderBlock> PROGRAM_LOADER =
            BLOCKS.register("program_loader", () -> new com.hdf.cryptand.neoforge.soc.block.ProgramLoaderBlock(
                    BlockBehaviour.Properties.of().strength(2.0f).noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> PROGRAM_LOADER_ITEM =
            ITEMS.register("program_loader", () -> new BlockItem(PROGRAM_LOADER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.hdf.cryptand.neoforge.soc.block.ProgramLoaderBlockEntity>> PROGRAM_LOADER_BE =
            BE_TYPES.register("program_loader", () -> BlockEntityType.Builder
                    .of(com.hdf.cryptand.neoforge.soc.block.ProgramLoaderBlockEntity::new,
                            PROGRAM_LOADER.get()).build(null));

    // ==================== 真彩屏：**已收编到 Scala 移植件**（2026-09-27 任务 C）====================
    //
    // 旧的自研真彩屏（cryptand:truescreen 方块 + 物品 + BE 类型，2026-09-26 建立）在这里的
    // 注册**整条删除**了：真彩屏改由 OC 屏幕的 Scala 移植件提供，注册入口是
    // com.hdf.cryptand.neoforge.truescreen.TrueScreenContent.register（见本类 register 里的调用）。
    //   · id：cryptand:truescreen1..4 / truescreenflat1..4 / truescreenflatback1..4，BE truescreen_display；
    //   · 旧实现类（TrueScreenBlock/TrueScreenBlockEntity/TrueScreenBlockItem/TrueScreenRenderer/
    //     CryptandScreenModel/CryptandScreenEnvironment/TrueScreenInputPayload）**已于
    //     2026-09-27 任务 F-2 整体删除**（用户批准）——本仓现在只有 Scala 移植件这一套屏幕实现；
    //   · 旧 BE 的 OC 组件注册（CryptandOcDrivers.registerScreenComponent）同步删除 ——
    //     **绝不允许**同时存在两条能生效的注册路径（那正是"两套屏幕抢同一个 id"的来源）。
    //
    // ⚠ 删除的后果（符合"内测开发期不做旧内容兼容"）：老存档里的 cryptand:truescreen 方块
    //   会变成空气，需要重新放置新屏。

    // ==================== 高级分析器（2026-09-27，OC 原版 Analyzer 的高级版）====================

    /**
     * 高级分析器：**右键打开 LDLib2 面板**，看的是沙箱内部（设备表 / 各设备缓存 / 内存占用 /
     * 消息缓存区 / 执行状态）—— 数据来自 common 的 {@code SandboxInspect}，与无人化 MCP 工具
     * {@code soc_inspect} 共用同一份快照。
     *
     * <p>朝向用于"面板屏幕朝哪边"（照 OC Analyzer 的形态）；目标机在每次右键时重扫
     * （6 邻居 → 半径 8 内最近的 OC 机器）。</p>
     */
    public static final DeferredHolder<Block, com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlock>
            ADVANCED_ANALYZER = BLOCKS.register("advanced_analyzer",
            () -> new com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlock(
                    BlockBehaviour.Properties.of().strength(1.5f)));

    public static final DeferredHolder<Item, BlockItem> ADVANCED_ANALYZER_ITEM =
            ITEMS.register("advanced_analyzer", () -> new BlockItem(ADVANCED_ANALYZER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlockEntity>> ADVANCED_ANALYZER_BE =
            BE_TYPES.register("advanced_analyzer", () -> BlockEntityType.Builder
                    .of(com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlockEntity::new,
                            ADVANCED_ANALYZER.get()).build(null));

    // ==================== 芯片蓝图设计机 + 芯片蓝图（2026-09-29，用户定案）====================
    //
    // 用户原话："设置一个芯片蓝图设计机的方块……然后定义蓝图物品……完成的蓝图物品暂时右键可以直接变成相关芯片"。
    // 前端 = LDLib2 面板（ChipDesignerUi），后端 = common 的 ChipFactory 句柄（申请 / 参数 / 组件表 /
    // 输出最终 CPU / 停止销毁），蓝图内容 = common 的 ChipBlueprint。

    public static final DeferredHolder<Block, com.hdf.cryptand.neoforge.soc.block.ChipDesignerBlock>
            CHIP_DESIGNER = BLOCKS.register("chip_designer",
            () -> new com.hdf.cryptand.neoforge.soc.block.ChipDesignerBlock(
                    BlockBehaviour.Properties.of().strength(1.5f)));

    public static final DeferredHolder<Item, BlockItem> CHIP_DESIGNER_ITEM =
            ITEMS.register("chip_designer", () -> new BlockItem(CHIP_DESIGNER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.hdf.cryptand.neoforge.soc.block.ChipDesignerBlockEntity>> CHIP_DESIGNER_BE =
            BE_TYPES.register("chip_designer", () -> BlockEntityType.Builder
                    .of(com.hdf.cryptand.neoforge.soc.block.ChipDesignerBlockEntity::new,
                            CHIP_DESIGNER.get()).build(null));

    /** 芯片蓝图物品（内容在物品自身的数据组件里；完成的蓝图右键直接变芯片） */
    public static final DeferredHolder<Item, com.hdf.cryptand.neoforge.soc.content.BlueprintItem> BLUEPRINT =
            ITEMS.register("blueprint", () -> new com.hdf.cryptand.neoforge.soc.content.BlueprintItem());

    // ==================== 硬件连接器（2026-09-28，用户定案）====================

    /**
     * 硬件连接器：**手动连线**的物品（用户定案："连线必须手动，自动只限外设 ↔ RV 芯片"）。
     *
     * <p>右键屏（主块）选中 → 右键机箱建立链路；协议按双方支持接口列表的优先级协商
     * （屏 DP/SPI/UART/I2C/8080、宿主 DP/SPI ⇒ 默认 DP）。潜行右键 = 清空选择 / 断开链路。</p>
     *
     * <p>交互在游戏总线上（{@code ConnectorLinkHandler}），注册时机与真彩屏一致
     * （屏幕属于 OC 软依赖，没装 OC 就没有屏可连）。</p>
     */
    public static final DeferredHolder<Item, ConnectorItem> CONNECTOR =
            ITEMS.register("connector", () -> new ConnectorItem(new Item.Properties().stacksTo(1)));

    // ==================== 创造标签栏：Cryptand 芯片（独立页） ====================

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SOC_TAB = TABS.register("soc",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cryptand.soc"))
                    .icon(() -> new ItemStack(CHIP_CPU.get()))
                    .displayItems((params, output) -> {
                        // ⚠ 2026-09-16 用户决策：「组装器机箱全部采用 oc 的，包括组件也是 oc 兼容组件，
                        //   暂时先不做自己独立」⇒ 自建**容器**（soc_chip 芯片机箱 / case_tower 塔式机箱 /
                        //   soc_assembler 组装台）**不再进创造栏**：
                        //     · 容器层用 OC 的（OC 的机箱/组装机本身就是方块，槽位体系也由它提供）；
                        //     · 我们的**部件**继续存在并保持 **OC 兼容** —— opencomputers 子包已为
                        //       chip_*/ram_*/disk_*/card_*/board_* 注册 OC 驱动（见 CryptandOcDrivers），
                        //       可直接插进 OC 机箱，且处理器会启用我们的 C/RV32 架构。
                        //   ⚠ 这三个方块仍**保留注册**（不破坏已有存档与世界里的实例），只是不主动展示。
                        // ★ 关键物品同时进「创造搜索」页（用户 2026-09-29：创造栏里找不到就没法用）
                        accept(output, SOC_ASSEMBLED.get());   // 空白芯片（组装台输出 / 蓝图产物基座）
                        accept(output, CHIP_CPU.get());         // CPU 芯片 · 示例（族 CPU + 全模块）
                        accept(output, CHIP_GPU.get());         // GPU 芯片 · 示例（自带显存 + DP）
                        accept(output, BLUEPRINT.get());        // 芯片蓝图（完成的右键直接变芯片）
                        accept(output, CHIP_DESIGNER_ITEM.get()); // 芯片蓝图设计机
                        accept(output, CONNECTOR.get());        // 硬件连接器（右键机箱开硬件配置页面）
                        accept(output, ADVANCED_ANALYZER_ITEM.get());
                        accept(output, PROGRAM_LOADER_ITEM.get());
                        // 芯片不再"一档一个物品"（2026-09-29 用户定案：每种类型一颗示例）；
                        // 档位表 SocCpuTiers 保留 —— 蓝图设计机将来用它给玩家选族与频率范围。
                        // 内存条：用 OC 原版的（ram1..ram6/ramcreative），我们不重复定义
                        // 底板（组件总线）
                        output.accept(BOARD_MATX.get());
                        output.accept(BOARD_ATX.get());
                        // 硬盘：旧三档（存档兼容）+ 完整容量序列 4K..1GB（用户 2026-09-25）
                        output.accept(DISK_1.get());
                        output.accept(DISK_2.get());
                        output.accept(DISK_3.get());
                        for (final DeferredHolder<Item, SocPartItem> h : HDD_SIZES.values()) {
                            output.accept(h.get());
                        }
                        // 启动 EEPROM：旧的 cbios + 容量档位 4K..128K（用户 2026-09-25）
                        output.accept(EEPROM_C.get());
                        for (final DeferredHolder<Item, SocPartItem> e : EEPROM_SIZES.values()) {
                            output.accept(e.get());
                        }
                        // 系统软盘（①Cryptand OS ②Cryptand UI OS ③ 扩展/预留）
                        output.accept(FLOPPY_1.get());
                        output.accept(FLOPPY_2.get());
                        output.accept(FLOPPY_3.get());
                        // 空软盘（2026-09-25 用户："空软盘需要提供几种大小的即可"）：
                        //   ⚠ 存储容量档位有 19 档（4K..1GB），但空软盘是**介质**，几种就够
                        //   （Cryptand OS ≈ 17.9 KB、UI OS ≈ 408 KB、扩展盘留 4 MB）。
                        //   这四种此前只注册了物品、**没进创造栏** ⇒ 创造模式里根本拿不到。
                        output.accept(BLANK_64K.get());
                        output.accept(BLANK_256K.get());
                        output.accept(BLANK_1M.get());
                        output.accept(BLANK_4M.get());
                        // 扩展卡
                        output.accept(CARD_GPIO.get());
                        output.accept(CARD_PWM.get());
                        output.accept(CARD_ADC.get());
                        output.accept(CARD_UART.get());
                        output.accept(CARD_GPU_ITX.get());
                        output.accept(CARD_GPU.get());
                        output.accept(CARD_GPU_PRO.get());
                        // 程序加载器（烧录器）：把程序写进介质
                        output.accept(PROGRAM_LOADER_ITEM.get());
                        // 真彩屏（Scala 移植件）不在这里：它有自己的注册与（后续的）创造栏条目。
                        // 高级分析器（沙箱内部诊断；面板 + soc_inspect 共用同一份快照）
                        output.accept(ADVANCED_ANALYZER_ITEM.get());
                        // 硬件连接器（手动连线：屏 ↔ 宿主/GPU）
                        output.accept(CONNECTOR.get());
                        // 芯片蓝图 + 设计机（2026-09-29）
                        output.accept(BLUEPRINT.get());
                        output.accept(CHIP_DESIGNER_ITEM.get());
                    })
                    .build());

    /** 进创造栏的"父页 + 搜索页"两份（搜索能按名字/ id 找到，父页保持分组顺序） */
    private static void accept(CreativeModeTab.Output output, Item item) {
        output.accept(new ItemStack(item), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }

    private SocContent() {
    }

    /** 注册全部内容（由 SocEntry.init 调用） */
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
        TABS.register(bus);
        registerTrueScreenContent(bus);
    }

    /**
     * ===== 真彩屏（Scala 移植件）的**唯一集成动作**（2026-09-27 任务 C）=====
     *
     * <p>一行调用装好整套屏幕：方块 / 物品 / BE 类型 / 屏幕 DataComponent / OC 的
     * {@code opencomputers:sided_environment} capability / 我方屏幕驱动（{@code Driver.add}）/
     * 报文通道 / 客户端三条注册线。**不要**在别处再注册任何屏幕条目。</p>
     *
     * <p><b>时机</b>：本方法由 {@code SocEntry.init} 在 <b>mod 构造期</b>调用，
     * 与 {@code OpenComputersEntry.init → CryptandOcDrivers.register()} 同一时机 ——
     * OC 的驱动表在 init 结束后上锁，晚一步 {@code Driver.add} 就会抛
     * {@code IllegalStateException("Please register all drivers in the init phase.")}。</p>
     *
     * <p><b>软依赖纪律</b>：移植件整个建立在 OC 的类型上（方块实体实现 OC 的 SidedEnvironment、
     * 驱动实现 OC 的 DriverItem），所以**只有 OC 在场才调用** —— 没装 OC 时本文件不会加载
     * {@code com.hdf.cryptand.neoforge.truescreen.*} 的任何类型（与下面老注释同一条纪律）。
     * 失败只记日志，不影响启动。</p>
     */
    private static void registerTrueScreenContent(IEventBus bus) {
        if (!com.hdf.cryptand.neoforge.opencomputers.OpenComputersEntry.ocLoaded()) {
            return;
        }
        try {
            // ===== 自注册（JDK SPI）：移植件自己声明注册实现，本文件只负责"何时调" =====
            // ⚠ 2026-09-27 真机踩坑（任务 C 的教训，任务 D 修根因）：
            //   这里原先写的是 Class.forName(...) 反射桥，真机 runClient 报
            //     java.lang.ClassNotFoundException: com.hdf.cryptand.neoforge.truescreen.TrueScreenContent
            //   根因**不是反射本身**，而是 dev 运行期的 mod 类路径（MOD_CLASSES）里没有
            //   build/classes/scala/main（旧生成的运行配置写死了 java 类目录）⇒ 整个移植件在 dev 里
            //   不可见（编译过、生产 jar 里也有）。已按根因修复：neoforge/build.gradle 让 scalac 直接
            //   产出到 build/classes/java/main（任何 MOD_CLASSES 形态都可见；生产 jar 本来不受影响）。
            //   反射桥随之删除：实现类 TrueScreenRegistrarImpl 放在 scala 源集里（联合编译 ⇒ 能直接
            //   调用 TrueScreenContent.register），由 META-INF/services 声明，本文件用 ServiceLoader
            //   发现并调用。这样既不需要反射，也不碰本仓禁止的 @EventBusSubscriber（它会绕过子包开关）。
            final java.util.List<com.hdf.cryptand.neoforge.truescreen.TrueScreenRegistrar> registrars =
                    java.util.ServiceLoader
                            .load(com.hdf.cryptand.neoforge.truescreen.TrueScreenRegistrar.class,
                                    com.hdf.cryptand.neoforge.truescreen.TrueScreenRegistrar.class.getClassLoader())
                            .stream().map(java.util.ServiceLoader.Provider::get).toList();
            if (registrars.isEmpty()) {
                // 响亮失败：SPI 声明丢失/实现类不可见时，屏幕会"静默不存在"，必须在日志里点名。
                throw new IllegalStateException("找不到真彩屏注册实现（META-INF/services/"
                        + "com.hdf.cryptand.neoforge.truescreen.TrueScreenRegistrar 缺失或实现类不可见）");
            }
            for (final com.hdf.cryptand.neoforge.truescreen.TrueScreenRegistrar registrar : registrars) {
                // java 侧判定桥（2026-09-29）：让 java 源集能问"这是不是真彩屏"，而不必 instanceof scala 类
                com.hdf.cryptand.neoforge.truescreen.TrueScreenBridge.install(registrar);
                registrar.install(bus);
            }
            // 硬件连接器的右键交互挂**游戏总线**（PlayerInteractEvent）。
            // ⚠ 放在 OC 判定的里面：本处理器要识别"我方屏幕 BE"（它建立在 OC 类型上），
            //   OC 缺席时既没有屏，也不该加载这些类型。
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                    com.hdf.cryptand.neoforge.soc.link.ConnectorLinkHandler.class);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("cryptand/soc")
                    .error("[SoC] 真彩屏（Scala 移植件）注册失败 —— 屏幕不可用，其余内容不受影响", t);
        }
    }

}

package com.hdf.cryptand.neoforge.soc.link;

import com.hdf.cryptand.soc.link.PortKind;
import com.hdf.cryptand.soc.link.SocLinkTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 配对面板的数据面（2026-09-28 用户定案）=====
 *
 * <p>用户原话：「配对采用一个硬件然后有图标，图标里面有颜色点可以连接，<b>两个同样的颜色点（同接口）
 * 可以被连接上</b>」。所以面板要的就是：**设备清单 + 每台设备的接口点 + 已连的线**。</p>
 *
 * <p>谁在什么时候算：<b>只在服务端算一次，写进连接器物品的 NBT</b>，再开 LDLib2 的手持物品面板 ——
 * LDLib2 的 {@code HeldItemUIMenuType.create} 会把**服务端那份物品栈**（含 NBT）发给客户端，
 * 两端拿到的是同一份数据，面板两根树不会各算一套（这是选它而不是自造网络包的理由）。</p>
 */
public final class SocPairingData {

    /**
     * 一台设备：坐标 + 设备类型 id（可带 {@code #n} 区分同坐标同类）+ 中文名 + 接口点 +
     * <b>图标物品</b>（用户定案「图标为最好是物品图标」：画布上直接画这台设备对应的物品）。
     */
    public record Device(BlockPos pos, String deviceId, String label,
                         List<SocDeviceInterfaces.Port> ports, ItemStack icon) {
        public Device {
            ports = ports == null ? List.of() : List.copyOf(ports);
            icon = icon == null ? ItemStack.EMPTY : icon;
        }
    }

    /** 已连线（两端各是"设备 + 端口"）。 */
    public record Link(BlockPos aPos, String aDev, String aPort, BlockPos bPos, String bDev, String bPort) {
    }

    /** 数据整个装在一个 CompoundTag 里（连接器物品的 CUSTOM_DATA 键 {@code PairPanel}）。 */
    public record Panel(BlockPos anchor, List<Device> devices, List<Link> links) {
    }

    public static final String TAG_PANEL = "LinkPanel";

    private SocPairingData() {
    }

    // ------------------------------------------------------------------ 服务端：枚举 + 落 NBT

    /**
     * 枚举锚点设备与它周围（±8 格）的设备，并把机器里插着的**扩展卡**也作为独立设备列出来。
     *
     * <p>只在服务端调用（要读机器槽位里的物品）。</p>
     */
    public static Panel collect(Level level, BlockPos anchor) {
        final Map<String, Device> devices = new LinkedHashMap<>();
        addDevice(level, anchor, anchor, devices);
        for (final BlockPos p : BlockPos.betweenClosed(anchor.offset(-8, -6, -8), anchor.offset(8, 6, 8))) {
            final BlockPos at = p.immutable();
            if (at.equals(anchor)) {
                continue;
            }
            addDevice(level, at, anchor, devices);
        }
        // 已连的线：只在"这两台设备都在清单里"时列出（面板只画自己能看见的线）
        final List<Link> links = new ArrayList<>();
        for (final SocLinkTable.Link l : SocLinkStore.linksAt(level, anchor)) {
            links.add(new Link(toPos(l.a().pos()), l.a().deviceId(), l.a().port(),
                    toPos(l.b().pos()), l.b().deviceId(), l.b().port()));
        }
        return new Panel(anchor, List.copyOf(devices.values()), List.copyOf(links));
    }

    /**
     * 加一台设备。用户定案「我们的芯片即虚拟机为单独一个」⇒ <b>周边其它机器不进清单</b>
     * （邻机不是本机的外设，画在接线图上没有意义），只有锚点那台机箱 = 唯一的芯片/虚拟机。
     */
    private static void addDevice(Level level, BlockPos pos, BlockPos anchor, Map<String, Device> out) {
        final String dev = deviceAt(level, pos);
        if (dev == null) {
            return;
        }
        if (SocDeviceInterfaces.CHIP.equals(dev) && !pos.equals(anchor)) {
            return;
        }
        out.put(key(pos, dev), new Device(pos, dev, labelOf(level, pos, dev),
                SocDeviceInterfaces.ports(dev, 1), iconOf(level, pos, dev)));
        if (SocDeviceInterfaces.CHIP.equals(dev)) {
            addCards(level, pos, out);
        }
    }

    /**
     * 设备中文名（用户定案"每个外设的名称和图标对应起来"）：名称 + {@code HwCanvasLayout.NodeKind}
     * 一起构成"这一台是什么"。屏要分开真彩屏 / OC 原版屏（两者是不同方块，图标也不同）。
     */
    private static String labelOf(Level level, BlockPos pos, String dev) {
        if (SocDeviceInterfaces.SCREEN.equals(dev)) {
            final BlockEntity be = level.getBlockEntity(pos);
            return com.hdf.cryptand.neoforge.truescreen.TrueScreenBridge.isTrueScreen(be)
                    ? "真彩屏" : "OC 屏";
        }
        return SocDeviceInterfaces.label(dev);
    }

    /**
     * 设备的**图标物品**（画布用它当器件图标）：机器取机箱里插的那块芯片，方块设备取方块物品。
     *
     * <p>取不到就返回空栈 —— 画布会退回画线条符号，不编一个不存在的物品。</p>
     */
    private static ItemStack iconOf(Level level, BlockPos pos, String dev) {
        if (SocDeviceInterfaces.CHIP.equals(dev)) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    final ItemStack s = container.getItem(i);
                    if (s.getItem() instanceof com.hdf.cryptand.neoforge.soc.content.SocAssembledItem) {
                        return s.copy();
                    }
                }
            }
            return ItemStack.EMPTY;
        }
        return new ItemStack(level.getBlockState(pos).getBlock().asItem());
    }

    /**
     * 机器槽位里的扩展卡 → 独立设备（显卡 = 1 个 RV 点 + N 个 DP 点）。
     *
     * <p>⚠ 槽位按 {@link Container} 读：OC 机箱不注册 NeoForge 的 item handler capability
     * （真机实测 capability 恒为 null ⇒ 卡永远列不出来），与 {@code ConnectorLinkHandler} 的
     * CPU 架构判定同一口径 —— 只留一条路。</p>
     */
    private static void addCards(Level level, BlockPos pos, Map<String, Device> out) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.Container container)) {
            return;
        }
        int gpu = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            final ItemStack card = container.getItem(slot);
            final int channels = gpuChannels(card);
            if (channels <= 0) {
                continue;
            }
            gpu++;
            final String dev = gpu == 1 ? SocDeviceInterfaces.CARD_GPU : SocDeviceInterfaces.CARD_GPU + "#" + gpu;
            out.put(key(pos, dev), new Device(pos, dev, "图形扩展卡 #" + gpu,
                    SocDeviceInterfaces.ports(SocDeviceInterfaces.CARD_GPU, channels), card.copy()));
        }
    }

    /**
     * 该物品是显卡就返回它的**通道数**（= DP 点数），否则 0。
     *
     * <p>我方显卡读 {@code SocPartItem.spec()}（1/2/4 通道）；OC 原版显卡按 id 后缀
     * {@code graphicscard1/2/3} → 1/2/4（与 OC 的档位一致）。</p>
     */
    private static int gpuChannels(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (stack.getItem() instanceof com.hdf.cryptand.neoforge.soc.content.SocPartItem part
                && part.kind() == com.hdf.cryptand.neoforge.soc.content.SocPartKind.CARD_GPU) {
            // 显卡物品的 spec 就是"输出通道数"（1/2/4），见 SocContent.part(graphicscard1..3)
            return Math.max(1, part.spec());
        }
        final String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (id.startsWith("graphicscard")) {
            return switch (id) {
                case "graphicscard2" -> 2;
                case "graphicscard3" -> 4;
                default -> 1;
            };
        }
        return 0;
    }

    /** 这个坐标上是什么设备（真彩屏主块 / OC 机器 / OC 屏）；都不是返回 null。 */
    public static String deviceAt(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        if (com.hdf.cryptand.neoforge.truescreen.TrueScreenBridge.isTrueScreen(be)) {
            return com.hdf.cryptand.neoforge.truescreen.TrueScreenBridge.isTrueScreenOrigin(be)
                    ? SocDeviceInterfaces.SCREEN : null;
        }
        if (implementsOcMachine(be)) {
            return SocDeviceInterfaces.CHIP;
        }
        // OC 原版屏（opencomputers:screen1..3）也认：它同样有 SidedEnvironment 接口
        if (be instanceof li.cil.oc.api.network.Environment || be instanceof li.cil.oc.api.network.SidedEnvironment) {
            final String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(pos).getBlock()).getPath();
            if (id.startsWith("screen")) {
                return SocDeviceInterfaces.SCREEN;
            }
        }
        return null;
    }

    private static boolean implementsOcMachine(Object be) {
        // 2026-09-29 修：以前拿 Machine 类型去 isInstance(方块实体) 永远为假（见 OcMachineReflect 的说明）
        return OcMachineReflect.isMachineHost(be);
    }

    private static String key(BlockPos pos, String dev) {
        return pos.asLong() + "|" + dev;
    }

    /** 供 {@link SocPairingData} 内部与面板用：链路表的 Pos → MC BlockPos。 */
    public static BlockPos toPos(SocLinkTable.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    // ------------------------------------------------------------------ NBT（连接器物品里那一份）

    public static CompoundTag write(Panel panel, HolderLookup.Provider provider) {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray("Anchor", new int[]{panel.anchor().getX(), panel.anchor().getY(), panel.anchor().getZ()});
        final ListTag devices = new ListTag();
        for (final Device d : panel.devices()) {
            final CompoundTag dt = new CompoundTag();
            dt.putIntArray("P", new int[]{d.pos().getX(), d.pos().getY(), d.pos().getZ()});
            dt.putString("Dev", d.deviceId());
            dt.putString("Label", d.label());
            final ListTag ports = new ListTag();
            for (final SocDeviceInterfaces.Port p : d.ports()) {
                final CompoundTag pt = new CompoundTag();
                pt.putString("Id", p.id());
                pt.putString("Kind", p.kind().name());
                pt.putString("Label", p.label());
                ports.add(pt);
            }
            dt.put("Ports", ports);
            if (!d.icon().isEmpty()) {
                dt.put("Icon", d.icon().save(provider));   // 图标物品（画布直接画它）
            }
            devices.add(dt);
        }
        tag.put("Devices", devices);
        final ListTag links = new ListTag();
        for (final Link l : panel.links()) {
            final CompoundTag lt = new CompoundTag();
            lt.putIntArray("A", new int[]{l.aPos().getX(), l.aPos().getY(), l.aPos().getZ()});
            lt.putString("ADev", l.aDev());
            lt.putString("APort", l.aPort());
            lt.putIntArray("B", new int[]{l.bPos().getX(), l.bPos().getY(), l.bPos().getZ()});
            lt.putString("BDev", l.bDev());
            lt.putString("BPort", l.bPort());
            links.add(lt);
        }
        tag.put("Links", links);
        return tag;
    }

    public static Panel read(CompoundTag tag, HolderLookup.Provider provider) {
        if (tag == null || !tag.contains("Devices")) {
            return null;
        }
        final int[] a = tag.getIntArray("Anchor");
        final BlockPos anchor = a.length == 3 ? new BlockPos(a[0], a[1], a[2]) : BlockPos.ZERO;
        final List<Device> devices = new ArrayList<>();
        final ListTag devs = tag.getList("Devices", Tag.TAG_COMPOUND);
        for (int i = 0; i < devs.size(); i++) {
            final CompoundTag dt = devs.getCompound(i);
            final int[] p = dt.getIntArray("P");
            final List<SocDeviceInterfaces.Port> ports = new ArrayList<>();
            final ListTag ps = dt.getList("Ports", Tag.TAG_COMPOUND);
            for (int j = 0; j < ps.size(); j++) {
                final CompoundTag pt = ps.getCompound(j);
                final PortKind kind = PortKind.byId(pt.getString("Id"));
                ports.add(new SocDeviceInterfaces.Port(pt.getString("Id"),
                        kind == null ? PortKind.RV : kind, pt.getString("Label")));
            }
            final ItemStack icon = dt.contains("Icon")
                    ? ItemStack.parse(provider, dt.get("Icon")).orElse(ItemStack.EMPTY)
                    : ItemStack.EMPTY;
            devices.add(new Device(new BlockPos(p.length == 3 ? p[0] : 0, p.length == 3 ? p[1] : 0, p.length == 3 ? p[2] : 0),
                    dt.getString("Dev"), dt.getString("Label"), List.copyOf(ports), icon));
        }
        final List<Link> links = new ArrayList<>();
        final ListTag ls = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int i = 0; i < ls.size(); i++) {
            final CompoundTag lt = ls.getCompound(i);
            links.add(new Link(pos(lt.getIntArray("A")), lt.getString("ADev"), lt.getString("APort"),
                    pos(lt.getIntArray("B")), lt.getString("BDev"), lt.getString("BPort")));
        }
        return new Panel(anchor, List.copyOf(devices), List.copyOf(links));
    }

    private static BlockPos pos(int[] p) {
        return p.length == 3 ? new BlockPos(p[0], p[1], p[2]) : BlockPos.ZERO;
    }
}

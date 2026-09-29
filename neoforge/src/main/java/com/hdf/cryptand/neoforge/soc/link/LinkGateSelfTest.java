package com.hdf.cryptand.neoforge.soc.link;

import com.hdf.cryptand.soc.link.LinkProtocol;
import com.hdf.cryptand.soc.link.PortKind;
import com.hdf.cryptand.soc.link.SocLinkTable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 硬件链路（设备接口表 + 端口身份落盘）离线闸门（2026-09-29）=====
 *
 * <p>跑法：{@code ./gradlew :neoforge:runLinkGateTest}（纯数据，不需要 MC 客户端）。</p>
 *
 * <p>钉住 09-29 实况调研暴露的缺口修复：</p>
 * <ol>
 *   <li>设备接口点表与「支持接口列表」一致（点的协议必须属于该设备的接口列表；显卡按通道数出点）；</li>
 *   <li>端口端点收敛成<b>单协议</b> ⇒ 同色才能连在链路表层自然成立（异色交集为空）；</li>
 *   <li>{@link SocLinkStore} 的落盘行<b>带端口身份</b>：NBT 往返后 port 不丢，重放出来的链路
 *       用「坐标 + 设备 + 端口」仍能精确命中（这正是世界重载后断开/高亮失配的根因）。</li>
 * </ol>
 */
public final class LinkGateSelfTest {

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) {
        System.out.println("=== Hardware link gate (device ports + port identity persistence) ===");

        devicePorts();
        portConvergence();
        rowCodec();
        replay();

        System.out.println("=== LinkGate " + passed + "/" + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 设备接口点

    private static void devicePorts() {
        section = "设备接口点";

        final List<SocDeviceInterfaces.Port> chip = SocDeviceInterfaces.ports(SocDeviceInterfaces.CHIP, 1);
        check("芯片 5 个点（rv/usb1/spi1/uart1/i2c1）", chip.size() == 5
                && ids(chip).equals(List.of("rv", "usb1", "spi1", "uart1", "i2c1")));
        check("芯片 rv 点 = RV(PCIE)", chip.get(0).kind() == PortKind.RV
                && chip.get(0).kind().protocol() == LinkProtocol.PCIE);

        final List<SocDeviceInterfaces.Port> screen = SocDeviceInterfaces.ports(SocDeviceInterfaces.SCREEN, 1);
        check("真彩屏只有 1 个 dp1（用户定案：屏幕为一个 DP 输入点）",
                screen.size() == 1 && screen.get(0).id().equals("dp1")
                        && screen.get(0).kind() == PortKind.DP);

        for (final int ch : new int[]{1, 2, 4}) {
            final List<SocDeviceInterfaces.Port> gpu =
                    SocDeviceInterfaces.ports(SocDeviceInterfaces.CARD_GPU, ch);
            check("显卡 " + ch + " 通道 = 1 个 rv + " + ch + " 个 dp（共 " + (ch + 1) + " 点）",
                    gpu.size() == ch + 1 && gpu.get(0).id().equals("rv")
                            && gpu.get(gpu.size() - 1).id().equals("dp" + ch));
        }
        check("显卡通道数 clamp 到 8（99 ⇒ dp1..dp8）",
                SocDeviceInterfaces.ports(SocDeviceInterfaces.CARD_GPU, 99).size() == 9);

        boolean consistent = true;
        for (final String dev : SocDeviceInterfaces.all().keySet()) {
            final List<LinkProtocol> ifaces = SocDeviceInterfaces.of(dev).interfaces();
            for (final SocDeviceInterfaces.Port p : SocDeviceInterfaces.ports(dev, 1)) {
                consistent &= ifaces.contains(p.kind().protocol());
            }
        }
        check("每个接口点的协议都写在该设备的支持接口列表里", consistent);
        check("未知设备没有点（不猜）",
                SocDeviceInterfaces.ports("ghost_device", 1).isEmpty());
    }

    // ------------------------------------------------------------------ 端口收敛

    private static void portConvergence() {
        section = "端口收敛";
        final BlockPos chipPos = new BlockPos(10, 64, 10);
        final BlockPos cardPos = new BlockPos(10, 64, 10);   // 机箱与槽位里的卡同坐标（用户定案）
        final BlockPos screenPos = new BlockPos(18, 64, 10);

        final SocLinkTable.Endpoint chipRv =
                SocDeviceInterfaces.endpoint(chipPos, SocDeviceInterfaces.CHIP, "rv", 1);
        final SocLinkTable.Endpoint cardRv =
                SocDeviceInterfaces.endpoint(cardPos, SocDeviceInterfaces.CARD_GPU, "rv", 4);
        final SocLinkTable.Endpoint cardDp1 =
                SocDeviceInterfaces.endpoint(cardPos, SocDeviceInterfaces.CARD_GPU, "dp1", 4);
        final SocLinkTable.Endpoint screenDp1 =
                SocDeviceInterfaces.endpoint(screenPos, SocDeviceInterfaces.SCREEN, "dp1", 1);

        check("端口端点带上端口 id", chipRv != null && cardRv != null && cardDp1 != null && screenDp1 != null
                && "rv".equals(cardRv.port()) && "dp1".equals(cardDp1.port()));
        check("端口端点只认一个协议（rv=PCIE / dp1=DP）",
                cardRv.interfaces().equals(List.of(LinkProtocol.PCIE))
                        && cardDp1.interfaces().equals(List.of(LinkProtocol.DP)));
        check("同坐标不同设备是两个端点（chip@pos ≠ card_gpu@pos）",
                !cardRv.key().equals(chipRv.key()) && cardRv.key().pos().equals(chipRv.key().pos()));
        check("同色（RV↔RV）可连、协议 PCIE",
                SocLinkTable.of().connect(chipRv, cardRv).ok());
        check("同色（DP↔DP）可连、协议 DP",
                SocLinkTable.of().connect(cardDp1, screenDp1).ok());
        final SocLinkTable.Result cross = SocLinkTable.of().connect(cardRv, cardDp1);
        check("异色（RV↔DP）连不上且给中文原因", !cross.ok() && cross.reason().contains("没有公共协议"));
        check("PortKind.sameKind 与链路判定一致",
                PortKind.sameKind("rv", "rv") && PortKind.sameKind("dp1", "dp2")
                        && !PortKind.sameKind("rv", "dp1") && !PortKind.sameKind("ghost", "rv"));
        check("端口 id 大小写不敏感（DP1 ⇒ dp1）",
                SocDeviceInterfaces.portOf(SocDeviceInterfaces.CARD_GPU, "DP1", 4) != null);
        check("通道数之外的点不存在（4 通道没有 dp5）",
                SocDeviceInterfaces.endpoint(cardPos, SocDeviceInterfaces.CARD_GPU, "dp5", 4) == null);
        check("按上界通道数重建则能还原 dp5（落盘读回的口径）",
                SocDeviceInterfaces.endpoint(cardPos, SocDeviceInterfaces.CARD_GPU, "dp5",
                        SocLinkStore.PORT_CHANNELS) != null);
        check("未知端口/未知设备 ⇒ null",
                SocDeviceInterfaces.endpoint(chipPos, SocDeviceInterfaces.CHIP, "ghost", 1) == null
                        && SocDeviceInterfaces.endpoint(chipPos, "ghost_device", "rv", 1) == null);
    }

    // ------------------------------------------------------------------ 落盘行编解码

    private static void rowCodec() {
        section = "落盘行编解码";
        final List<SocLinkTable.Row> rows = List.of(
                new SocLinkTable.Row(new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CHIP, "rv",
                        new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "rv", "pcie"),
                new SocLinkTable.Row(new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "dp1",
                        new SocLinkTable.Pos(18, 64, 10), SocDeviceInterfaces.SCREEN, "dp1", "dp"),
                new SocLinkTable.Row(new SocLinkTable.Pos(18, 64, 10), SocDeviceInterfaces.SCREEN, "dp3",
                        new SocLinkTable.Pos(19, 64, 10), SocDeviceInterfaces.CARD_GPU, "dp3", "dp"));

        final CompoundTag tag = new CompoundTag();
        tag.put("Links", SocLinkStore.encodeRows(rows));
        final List<SocLinkTable.Row> back = SocLinkStore.decodeRows(tag);
        check("行数往返一致（" + back.size() + "）", back.size() == rows.size());
        boolean same = back.size() == rows.size();
        for (int i = 0; same && i < rows.size(); i++) {
            final SocLinkTable.Row x = rows.get(i);
            final SocLinkTable.Row y = back.get(i);
            same = x.a().equals(y.a()) && x.b().equals(y.b())
                    && x.aDevice().equals(y.aDevice()) && x.bDevice().equals(y.bDevice())
                    && x.aPort().equals(y.aPort()) && x.bPort().equals(y.bPort())
                    && x.protocolId().equals(y.protocolId());
        }
        check("端口身份 / 协议 id 全字段往返一致（不再只剩坐标）", same);
        check("空 tag ⇒ 空表（不抛）",
                SocLinkStore.decodeRows(new CompoundTag()).isEmpty()
                        && SocLinkStore.decodeRows(null).isEmpty());

        // 端口为空的设备级行：字符串层面照原样往返（语义上会被 resolver 丢弃，见 replay）
        final CompoundTag legacy = new CompoundTag();
        legacy.put("Links", SocLinkStore.encodeRows(List.of(new SocLinkTable.Row(
                new SocLinkTable.Pos(1, 2, 3), "screen", "", new SocLinkTable.Pos(4, 5, 6), "chip", "", "spi"))));
        final List<SocLinkTable.Row> legacyBack = SocLinkStore.decodeRows(legacy);
        check("空端口行往返为空串（不是 null、不错位）", legacyBack.size() == 1
                && legacyBack.get(0).aPort().isEmpty() && legacyBack.get(0).bPort().isEmpty());
    }

    // ------------------------------------------------------------------ 重放（世界读盘）

    private static void replay() {
        section = "读盘重放";
        final SocLinkTable.Resolver resolver = LinkGateSelfTest::resolve;

        final List<SocLinkTable.Row> rows = List.of(
                new SocLinkTable.Row(new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CHIP, "rv",
                        new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "rv", "pcie"),
                new SocLinkTable.Row(new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "dp1",
                        new SocLinkTable.Pos(18, 64, 10), SocDeviceInterfaces.SCREEN, "dp1", "dp"));

        final List<String> warns = new ArrayList<>();
        final SocLinkTable t = SocLinkTable.of();
        final int n = t.importRows(rows, resolver, warns::add);
        check("两条线全部重放成功（" + n + "）", n == 2 && t.size() == 2);
        check("重放没有告警", warns.isEmpty());

        // 关键断言：按「坐标 + 设备 + 端口」查询必须命中（09-29 缺口）
        final SocLinkTable.EndpointKey chipRv = new SocLinkTable.EndpointKey(
                new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CHIP, "rv");
        final SocLinkTable.EndpointKey cardRv = new SocLinkTable.EndpointKey(
                new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "rv");
        final SocLinkTable.EndpointKey cardDp1 = new SocLinkTable.EndpointKey(
                new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "dp1");
        final SocLinkTable.EndpointKey screenDp1 = new SocLinkTable.EndpointKey(
                new SocLinkTable.Pos(18, 64, 10), SocDeviceInterfaces.SCREEN, "dp1");
        check("端口级 findEdge 命中（chip:rv ↔ card_gpu:rv）", t.findEdge(chipRv, cardRv) != null);
        check("端口级 findEdge 命中（card_gpu:dp1 ↔ screen:dp1）", t.findEdge(cardDp1, screenDp1) != null);
        check("端口不全的键查不到（端口身份真的参与了匹配）",
                t.findEdge(new SocLinkTable.EndpointKey(new SocLinkTable.Pos(10, 64, 10),
                        SocDeviceInterfaces.CARD_GPU), cardDp1) == null);
        check("重放后导出与输入逐行相等", t.rows().equals(rows));
        check("断开按端口精确生效（解掉 dp1 那条）",
                t.disconnect(cardDp1, screenDp1) && t.size() == 1
                        && t.findEdge(chipRv, cardRv) != null);

        // 坏行：设备/端口已不存在 ⇒ 丢弃并回报（不静默、不抛）
        final List<String> warns2 = new ArrayList<>();
        final SocLinkTable t2 = SocLinkTable.of();
        final int n2 = t2.importRows(List.of(
                rows.get(0),
                new SocLinkTable.Row(new SocLinkTable.Pos(50, 64, 50), "ghost_device", "rv",
                        new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CHIP, "rv", "pcie"),
                new SocLinkTable.Row(new SocLinkTable.Pos(18, 64, 10), SocDeviceInterfaces.SCREEN, "",
                        new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CHIP, "rv", "spi")), resolver, warns2::add);
        check("坏行被丢弃、好行保留（" + n2 + " 导入 / " + warns2.size() + " 告警）",
                n2 == 1 && t2.size() == 1 && warns2.size() == 2);
        check("丢弃原因说清是设备/端口不存在",
                warns2.get(0).contains("设备或端口已不存在"));

        // 协议对不上：仍然导入（端口身份说了算），但回报不一致
        final List<String> warns3 = new ArrayList<>();
        final SocLinkTable t3 = SocLinkTable.of();
        t3.importRows(List.of(new SocLinkTable.Row(
                new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CHIP, "rv",
                new SocLinkTable.Pos(10, 64, 10), SocDeviceInterfaces.CARD_GPU, "rv", "usb")), resolver, warns3::add);
        check("存档协议与重建不一致 ⇒ 导入 + 告警",
                t3.size() == 1 && warns3.size() == 1 && warns3.get(0).contains("协议与存档不一致"));
    }

    /** 与 {@code SocLinkStore.resolve} 同口径：按端口身份重建端点（通道数取上界）。 */
    private static SocLinkTable.Endpoint resolve(SocLinkTable.Pos pos, String deviceId, String port) {
        return SocDeviceInterfaces.endpoint(
                new BlockPos(pos.x(), pos.y(), pos.z()), deviceId, port, SocLinkStore.PORT_CHANNELS);
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> ids(List<SocDeviceInterfaces.Port> ports) {
        final List<String> out = new ArrayList<>();
        for (final SocDeviceInterfaces.Port p : ports) {
            out.add(p.id());
        }
        return out;
    }

    private static void check(String what, boolean ok) {
        check_quiet(ok);
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + section + " | " + what);
    }

    private static void check_quiet(boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}

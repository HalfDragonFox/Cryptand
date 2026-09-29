package com.hdf.cryptand.soc.peripheral;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ===== USB 设备树与带宽沙盒自测（纯 Java 零 MC）=====
 *
 * <p>把用户定案的三条规则钉住：一个设备只挂一个控制器、带宽是硬上限、速度只能降不能升。</p>
 */
public final class UsbFabricSelfTest {

    private static int passed;
    private static int failed;

    private static final PcieFabric.UsbSpeed U1 = PcieFabric.UsbSpeed.USB1_1;
    private static final PcieFabric.UsbSpeed U2 = PcieFabric.UsbSpeed.USB2_0;
    private static final PcieFabric.UsbSpeed U3 = PcieFabric.UsbSpeed.USB3_0;

    public static void main(String[] args) {
        // ---- 1. 基本挂载：端点按顺序分配 ----
        final List<UsbFabric.Controller> ctrls = List.of(
                new UsbFabric.Controller("usb2-0", U2, 4),
                new UsbFabric.Controller("usb1-0", U1, 2));
        final List<UsbFabric.Device> devs = List.of(
                new UsbFabric.Device("disk-0", "disk", U2, 100_000_000L),
                new UsbFabric.Device("cam-0", "camera", U2, 200_000_000L));

        final List<UsbFabric.Attachment> a1 = UsbFabric.attach(ctrls, devs, Map.of());
        check("两个设备都挂上了", a1.size() == 2);
        check("端点按顺序 0/1", a1.get(0).endpoint() == 0 && a1.get(1).endpoint() == 1);
        check("都挂在第一个放得下的控制器上", a1.get(0).controllerId().equals("usb2-0"));
        check("带宽按标称计入（无降级）", a1.get(0).allocatedBps() == 100_000_000L);
        check("按 id 查询挂载结果", UsbFabric.of(a1, "cam-0").endpoint() == 1);

        // ---- 2. ★ 降级兼容：USB 3.0 设备插 USB 2.0 控制器 ⇒ 按 2.0 跑、带宽按 2.0 折算 ----
        final UsbFabric.Device fast = new UsbFabric.Device("nvme-0", "storage", U3, 5_000_000_000L);
        final List<UsbFabric.Attachment> a2 = UsbFabric.attach(List.of(new UsbFabric.Controller("usb2-1", U2, 4)),
                List.of(fast), Map.of());
        check("USB3.0 设备插 2.0 控制器 ⇒ 有效速度降为 2.0", a2.get(0).effectiveSpeed() == U2);
        check("带宽按有效速度折算（5Gbps → 480Mbps）", a2.get(0).allocatedBps() == 480_000_000L);

        // ---- 3. 带宽硬上限：超了就报错，且说明还差多少 ----
        final UsbFabric.Device hog1 = new UsbFabric.Device("h1", "disk", U2, 300_000_000L);
        final UsbFabric.Device hog2 = new UsbFabric.Device("h2", "disk", U2, 300_000_000L);
        boolean over = false;
        try {
            UsbFabric.attach(List.of(new UsbFabric.Controller("small", U2, 4)), List.of(hog1, hog2), Map.of());
        } catch (IllegalStateException e) {
            over = e.getMessage().contains("带宽不足");
        }
        check("带宽不足 ⇒ 报错（不是静默降级）", over);

        // ---- 4. 端点用尽 ⇒ 报错 ----
        boolean noEp = false;
        try {
            UsbFabric.attach(List.of(new UsbFabric.Controller("e1", U3, 1)),
                    List.of(new UsbFabric.Device("d1", "disk", U2, 1_000L),
                            new UsbFabric.Device("d2", "disk", U2, 1_000L)), Map.of());
        } catch (IllegalStateException e) {
            noEp = e.getMessage().contains("放不下");
        }
        check("端点用尽 ⇒ 报错", noEp);

        // ---- 5. 手动指定控制器：放不下时**报错而不是偷偷换一个 / 也不是超卖** ----
        //    ⚠ 用"端点用尽"构造：降级后带宽会按比例缩小（真实 USB 行为），
        //    所以 USB1.1 控制器上"带宽不足"反而很难发生 —— 用错场景会写出假失败的测试。
        boolean refused = false;
        try {
            UsbFabric.attach(List.of(new UsbFabric.Controller("tiny", U3, 1)),
                    List.of(new UsbFabric.Device("occupy", "disk", U2, 1_000L),
                            new UsbFabric.Device("extra", "disk", U2, 1_000L)),
                    Map.of("extra", "tiny"));
        } catch (IllegalStateException e) {
            refused = e.getMessage().contains("端点用尽");
        }
        check("手动指定的控制器放不下 ⇒ 报错（不偷偷换一个、也不超卖）", refused);

        // ---- 6. 指定不存在的控制器 ⇒ 报错；未知设备 ⇒ 报错 ----
        boolean badCtrl = false;
        try {
            UsbFabric.attach(ctrls, List.of(hog1), Map.of("h1", "no-such"));
        } catch (IllegalArgumentException e) {
            badCtrl = e.getMessage().contains("unknown controller");
        }
        check("指定不存在的控制器 ⇒ 报错", badCtrl);

        boolean badDev = false;
        try {
            UsbFabric.attach(ctrls, List.of(hog1), Map.of("ghost", "usb2-0"));
        } catch (IllegalArgumentException e) {
            badDev = e.getMessage().contains("unknown usb device");
        }
        check("覆盖里出现未知设备 ⇒ 报错", badDev);

        // ---- 7. 自动挑选：第一个放不下的会被跳过，落到下一个控制器 ----
        final List<UsbFabric.Controller> twoCtrls = List.of(
                new UsbFabric.Controller("c-small", U2, 1),
                new UsbFabric.Controller("c-big", U3, 8));
        final List<UsbFabric.Attachment> a3 = UsbFabric.attach(twoCtrls, List.of(
                new UsbFabric.Device("d1", "disk", U2, 1_000_000L),
                new UsbFabric.Device("d2", "disk", U2, 1_000_000L)), Map.of());
        check("第一个控制器端点满 ⇒ 自动落到第二个",
                a3.get(0).controllerId().equals("c-small") && a3.get(1).controllerId().equals("c-big"));

        // ---- 8. 一个设备只出现一次（USB 是树不是网状）----
        final List<String> ids = new ArrayList<>();
        for (final UsbFabric.Attachment a : a3) {
            ids.add(a.deviceId());
        }
        check("每个设备只挂一次", ids.size() == ids.stream().distinct().count());

        System.out.println("[USB] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

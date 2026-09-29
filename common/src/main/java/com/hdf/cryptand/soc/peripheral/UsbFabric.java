package com.hdf.cryptand.soc.peripheral;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ===== USB 设备树与带宽（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-17/18）："USB 需要从 1.0 到 USB3.0"、"USB 多接口、每接口多设备、
 * 有带宽上限，一个设备只能绑定一个 USB 接口"。</p>
 *
 * <h3>三条规则</h3>
 * <ol>
 *   <li><b>一个设备只挂一个控制器</b>（USB 是树，不是网状 —— 同一设备挂两处会让"它到底在哪"失去意义）；</li>
 *   <li><b>带宽是硬上限</b>：Σ 已挂设备带宽 ≤ 控制器带宽，超了直接报错
 *       （真机上是"带宽不足"的枚举失败，我们不能静默让设备半死不活）；</li>
 *   <li><b>速度可以降级但不能升格</b>：USB 3.0 设备插在 USB 2.0 控制器上按 2.0 跑
 *       （真实 USB 行为），带宽也按 2.0 计算 —— 但 USB 3.0 设备绝不能"假装"跑 5Gbps。</li>
 * </ol>
 *
 * <p>与 {@link PcieFabric} 的分工：控制器本身是一张 PCIe 卡（吃 lane），
 * 控制器**之内**的端点与带宽归本类管。两层分别测试，各自只关心自己那一层。</p>
 */
public final class UsbFabric {

    /**
     * 一个 USB 控制器（本身是 PCIe 设备，见 {@link PcieFabric}）。
     *
     * @param id        控制器标识（日志用）
     * @param speed     控制器支持的**最高**速度等级
     * @param endpoints 端点数量（= {@link PeripheralMap.Capacity#usbSlots()}）
     */
    public record Controller(String id, PcieFabric.UsbSpeed speed, int endpoints) {
    }

    /**
     * 一个要挂上去的 USB 设备。
     *
     * @param bandwidthBps 设备**在自身最高速度下**占用的带宽（bit/s）；实际按有效速度折算
     */
    public record Device(String id, String kind, PcieFabric.UsbSpeed speed, long bandwidthBps) {
    }

    /** 挂载结果：设备挂在哪个控制器的哪个端点上，以及**实际**跑多快、占多少带宽 */
    public record Attachment(String deviceId, String kind, String controllerId, int endpoint,
                             PcieFabric.UsbSpeed effectiveSpeed, long allocatedBps) {
    }

    private UsbFabric() {
    }

    /**
     * 把设备挂到控制器上。
     *
     * @param controllers 机箱里的控制器（顺序 = 优先序，稳定可复现）
     * @param devices     要挂的设备（顺序 = 分配优先序）
     * @param preferred   设备 → 指定控制器 id 的手动覆盖（用户："装机或 OS 内可手动配置"）
     * @throws IllegalArgumentException 覆盖里出现未知设备，或设备指定了不存在的控制器
     * @throws IllegalStateException    端点用尽、带宽不足（消息里说明还差多少）
     */
    public static List<Attachment> attach(List<Controller> controllers, List<Device> devices,
                                          Map<String, String> preferred) {
        final Map<String, String> want = preferred == null ? Map.of() : preferred;
        for (final Map.Entry<String, String> e : want.entrySet()) {
            boolean known = false;
            for (final Device d : devices) {
                if (d.id().equals(e.getKey())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalArgumentException("override for unknown usb device: " + e.getKey());
            }
            boolean hasCtrl = false;
            for (final Controller c : controllers) {
                if (c.id().equals(e.getValue())) {
                    hasCtrl = true;
                    break;
                }
            }
            if (!hasCtrl) {
                throw new IllegalArgumentException("device " + e.getKey()
                        + " prefers unknown controller: " + e.getValue());
            }
        }

        final List<Attachment> out = new ArrayList<>();
        for (final Device d : devices) {
            final String wanted = want.get(d.id());
            final Controller target;
            if (wanted != null) {
                // 手动指定：**也要过容量检查**（第一版漏了这一步，于是"指定了也照样挂上去"，
                // 表面像成功、实际超卖 —— 比静默换一个更糟）
                final Controller c = controllerById(controllers, wanted);
                if (!hasRoom(c, d, out)) {
                    throw new IllegalStateException("device " + d.id() + " 指定的控制器 " + wanted
                            + describeShortfall(c, d, out) + " —— 不会偷偷换一个控制器，也不会超卖");
                }
                target = c;
            } else {
                Controller pick = null;
                for (final Controller c : controllers) {
                    if (hasRoom(c, d, out)) {
                        pick = c;
                        break;
                    }
                }
                if (pick == null) {
                    throw new IllegalStateException("device " + d.id() + "：所有 USB 控制器都放不下它"
                            + "（端点或带宽不足）—— 加控制器或减设备，别指望静默降级");
                }
                target = pick;
            }
            out.add(makeAttachment(target, d, out));
        }
        return out;
    }

    private static Controller controllerById(List<Controller> controllers, String id) {
        for (final Controller c : controllers) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown controller: " + id);
    }

    /** 有效速度 = min(设备速度, 控制器速度)：真机上的降级兼容 */
    private static PcieFabric.UsbSpeed effectiveSpeed(Controller c, Device d) {
        return d.speed().bitsPerSecond() <= c.speed().bitsPerSecond() ? d.speed() : c.speed();
    }

    /** 实际占用带宽：按**有效速度**折算（设备标称带宽按速度比例缩放，向下取整） */
    private static long allocated(Controller c, Device d) {
        final PcieFabric.UsbSpeed eff = effectiveSpeed(c, d);
        if (eff == d.speed()) {
            return d.bandwidthBps();
        }
        final double ratio = (double) eff.bitsPerSecond() / (double) d.speed().bitsPerSecond();
        return (long) (d.bandwidthBps() * ratio);
    }

    private static int usedEndpoints(Controller c, List<Attachment> soFar) {
        int n = 0;
        for (final Attachment a : soFar) {
            if (a.controllerId().equals(c.id())) {
                n++;
            }
        }
        return n;
    }

    private static long usedBandwidth(Controller c, List<Attachment> soFar) {
        long sum = 0;
        for (final Attachment a : soFar) {
            if (a.controllerId().equals(c.id())) {
                sum += a.allocatedBps();
            }
        }
        return sum;
    }

    private static boolean hasRoom(Controller c, Device d, List<Attachment> soFar) {
        if (usedEndpoints(c, soFar) >= c.endpoints()) {
            return false;
        }
        return usedBandwidth(c, soFar) + allocated(c, d) <= c.speed().bitsPerSecond();
    }

    private static String describeShortfall(Controller c, Device d, List<Attachment> soFar) {
        final int ep = usedEndpoints(c, soFar);
        if (ep >= c.endpoints()) {
            return "：端点用尽（" + ep + "/" + c.endpoints() + "）";
        }
        final long used = usedBandwidth(c, soFar);
        final long need = allocated(c, d);
        return "：带宽不足（还需 " + need + " bps，已用 " + used + "/" + c.speed().bitsPerSecond() + " bps）";
    }

    private static Attachment makeAttachment(Controller c, Device d, List<Attachment> soFar) {
        return new Attachment(d.id(), d.kind(), c.id(), usedEndpoints(c, soFar),
                effectiveSpeed(c, d), allocated(c, d));
    }

    /** 按设备 id 取挂载结果（诊断/上层查询） */
    public static Attachment of(List<Attachment> attachments, String deviceId) {
        for (final Attachment a : attachments) {
            if (a.deviceId().equals(deviceId)) {
                return a;
            }
        }
        return null;
    }
}

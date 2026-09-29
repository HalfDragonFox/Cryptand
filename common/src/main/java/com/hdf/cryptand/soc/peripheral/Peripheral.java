package com.hdf.cryptand.soc.peripheral;

/**
 * ===== 外设层（分层第一层，2026-09-18）=====
 *
 * <p>用户定案："沙箱可以是这样：<b>外设层 → 模块接口层 → RV 内核</b>"。</p>
 *
 * <pre>
 *   外设层（本接口）     真实/模拟的设备本体：屏、盘、网卡、GPU、传感器…
 *                        它只知道"收发一段字节"，不知道总线、速率、寄存器在哪。
 *   模块接口层           SocModule + SocModuleImpl + SharedBus + PeripheralMap
 *                        把外设包成 RV 能看见的模块：接口 + 速率 + 寄存器映射 + 内存映射 + 流控。
 *   RV 内核             只会两件事：写内存窗口、写寄存器（**不操作 GPIO**）。
 * </pre>
 *
 * <p>为什么外设层要这么薄：外设是"有无穷多种"的东西，而模块层是"少而稳定"的抽象。
 * 让外设只实现收发，新外设（比如将来的真彩屏控制器）就不必重复实现流控/限速/寄存器语义 ——
 * 那些在模块层一次做好，所有外设共享。</p>
 */
public interface Peripheral {

    /** 外设名（日志/诊断） */
    String name();

    /**
     * 模块层把一段数据推给外设。
     *
     * @return true = 收下了；false = 当前不收（缓冲满/忙）—— 模块层必须重试或按流控处理，
     *         **不允许静默丢弃**
     */
    boolean accept(byte[] data, int len);

    /** 外设侧是否有数据等着交给模块层（RV 读取方向） */
    default int available() {
        return 0;
    }

    /**
     * 外设把数据放进 out（RV 读取方向）。
     *
     * @return 实际写入字节数（0 = 暂时没有）
     */
    default int produce(byte[] out, int cap) {
        return 0;
    }
}

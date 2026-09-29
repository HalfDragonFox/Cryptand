package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.device.AutoForwardWindowDevice;
import com.hdf.cryptand.soc.peripheral.PeripheralMap;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== PCIe 模块：上游窗口 → 下游目标转接（具体模块示例，2026-09-18）=====
 *
 * <p>用户点名的另一类模块："PCIE 的转接这种"。</p>
 *
 * <h3>转接是什么意思</h3>
 * <p>PCIe 桥自己不产生数据：它把<b>上游（RV 侧）的一次写</b>转成<b>下游某个设备的一次操作</b>。
 * 现实里这靠 BAR（基址寄存器）+ 配置空间；这里按用户定案简化成"目标选择 + 数据窗口"两件事
 * —— <b>不建模链路训练/通道/差分对</b>，因为它们对游戏没有收益。</p>
 *
 * <h3>RV 看到的东西</h3>
 * <ul>
 *   <li>{@code window + 0}（写 8 位）= 目标索引（0..N-1），等价"把 BAR 指向某个下游设备"</li>
 *   <li>{@code window + 1}（写 16 位）= 交给该目标的数据字</li>
 * </ul>
 */
public final class PcieBridgeModule implements SocModuleImpl {

    /** 一个下游目标（显卡、网卡、存储控制器…） */
    public interface Downstream {

        String name();

        /** 收到一个 16 位数据字 */
        void write(int value);
    }

    private final SocModule spec;
    private final AutoForwardWindowDevice window;
    private final List<Downstream> targets = new ArrayList<>();

    private int selectedTarget = -1;
    private long forwarded;

    public PcieBridgeModule(String name, long baseAddress, int spanBytes, long bytesPerSecond) {
        this.spec = new SocModule(name, PeripheralMap.Carrier.PCIE, bytesPerSecond,
                baseAddress, spanBytes, -1, null);
        this.window = new AutoForwardWindowDevice(name + "-UP", 2, bytesPerSecond);
        this.window.sink(new AutoForwardWindowDevice.Sink() {
            @Override
            public void command(int value) {
                selectedTarget = value;
            }

            @Override
            public void data(int value) {
                forward(value);
            }
        });
    }

    public PcieBridgeModule downstream(Downstream target) {
        targets.add(target);
        return this;
    }

    /**
     * 把这座 PCIe 桥接到**共享总线**上：带宽与流控由总线统一裁决（下游设备竞争同一条 PCIe 带宽）。
     *
     * <p>绑定之后 {@code store()} 只入队，目标选择与数据要等 {@code bus.tick(dt)} 才转接到下游设备。</p>
     */
    public PcieBridgeModule withBus(com.hdf.cryptand.soc.peripheral.SharedBus bus) {
        window.withBus(bus, spec.name());
        return this;
    }

    @Override
    public SocModule spec() {
        return spec;
    }

    @Override
    public void attach(SocBoard.Builder board) {
        board.device(spec.baseAddress(), window, spec.name() + " (PCIe upstream)");
    }

    @Override
    public void tick(long elapsedNanos) {
        window.tick(elapsedNanos);
    }

    public int selectedTarget() {
        return selectedTarget;
    }

    public long forwarded() {
        return forwarded;
    }

    public AutoForwardWindowDevice window() {
        return window;
    }

    /** 把一次上游写转接到选中的下游目标（越界/未选都要明确报错） */
    private void forward(int value) {
        if (selectedTarget < 0 || selectedTarget >= targets.size()) {
            throw new IllegalStateException(spec.name() + ": 目标索引 " + selectedTarget
                    + " 越界（下游设备共 " + targets.size() + " 个）—— 先写目标寄存器");
        }
        targets.get(selectedTarget).write(value);
        forwarded++;
    }
}

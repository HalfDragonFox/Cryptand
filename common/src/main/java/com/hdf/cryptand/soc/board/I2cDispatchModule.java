package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.device.AutoForwardWindowDevice;
import com.hdf.cryptand.soc.peripheral.PeripheralMap;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== I2C 模块：一条总线 + 按地址分发（具体模块示例，2026-09-18）=====
 *
 * <p>用户点名的两类"需要自己写模拟代码"的模块之一："I2C 不同设备的分发这种"。</p>
 *
 * <h3>为什么必须在模块里做分发</h3>
 * <p>I2C 的物理现实是：<b>一条总线（SCL/SDA 共享）上挂多个从设备，靠 7 位地址区分</b>。
 * 所以"写总线"这件事本身没有意义 —— 必须先发地址字节，再发数据字节，只有地址匹配的从设备才收。
 * 这个分发逻辑是模块的事，RV 侧（固件）只会看到"往窗口写两个字节"。</p>
 *
 * <h3>RV 看到的东西（寄存器/窗口映射）</h3>
 * <ul>
 *   <li>{@code window + 0}（写 8 位）= I2C 地址字节（低 7 位是从设备地址）</li>
 *   <li>{@code window + 1}（写 8 位）= 数据字节，分发给当前选中的从设备</li>
 * </ul>
 * <p>写地址/数据都走同一段窗口（{@link AutoForwardWindowDevice} 的 bit0 = 命令/数据语义），
 * 于是固件的代码就是两次 store —— <b>没有 GPIO，没有引脚 toggle</b>。</p>
 */
public final class I2cDispatchModule implements SocModuleImpl {

    /** 一个 I2C 从设备 */
    public interface I2cSlave {

        /** 7 位地址（0x00..0x7F） */
        int address7();

        /** 名字（诊断用） */
        String name();

        /** 收到一个数据字节 */
        void write(int value);
    }

    private final SocModule spec;
    private final AutoForwardWindowDevice window;
    private final List<I2cSlave> slaves = new ArrayList<>();

    private int selectedAddress = -1;
    private long delivered;
    private long dropped;

    public I2cDispatchModule(String name, long baseAddress, int spanBytes, long bytesPerSecond) {
        this.spec = new SocModule(name, PeripheralMap.Carrier.I2C, bytesPerSecond,
                baseAddress, spanBytes, -1, null);
        this.window = new AutoForwardWindowDevice(name + "-DATA", 1, bytesPerSecond);
        this.window.sink(new AutoForwardWindowDevice.Sink() {
            @Override
            public void command(int value) {
                // 地址字节：低 7 位
                selectedAddress = value & 0x7F;
            }

            @Override
            public void data(int value) {
                dispatch(value);
            }
        });
    }

    /** 挂一个从设备（装配期调用；地址重复要报错，否则"谁收到"就成了随机） */
    public I2cDispatchModule slave(I2cSlave slave) {
        for (final I2cSlave s : slaves) {
            if (s.address7() == slave.address7()) {
                throw new IllegalArgumentException("I2C 地址冲突：0x"
                        + Integer.toHexString(slave.address7()) + " 已被 " + s.name() + " 占用");
            }
        }
        slaves.add(slave);
        return this;
    }

    /**
     * 把这条 I2C 总线接到**共享总线**上：带宽竞争与协议流控交给总线统一裁决。
     *
     * <p>绑定之后 {@code store()} 只入队，地址字节与数据字节要等 {@code bus.tick(dt)} 才真正分发到
     * 从设备 —— tick 由模块层/板级**统一调一次**（不要每个模块各 tick 自己那条总线）。</p>
     */
    public I2cDispatchModule withBus(com.hdf.cryptand.soc.peripheral.SharedBus bus) {
        window.withBus(bus, spec.name());
        return this;
    }

    @Override
    public SocModule spec() {
        return spec;
    }

    @Override
    public void attach(SocBoard.Builder board) {
        board.device(spec.baseAddress(), window, spec.name() + " (I2C data)");
    }

    @Override
    public void tick(long elapsedNanos) {
        window.tick(elapsedNanos);
    }

    /** 当前选中的从设备地址（-1 = 还没发过地址） */
    public int selectedAddress() {
        return selectedAddress;
    }

    /** 成功分发的字节数 */
    public long delivered() {
        return delivered;
    }

    /** 没有从设备接收的字节数（地址没选中任何从设备）——**不静默丢** */
    public long dropped() {
        return dropped;
    }

    public AutoForwardWindowDevice window() {
        return window;
    }

    /** 按地址分发一个数据字节 */
    private void dispatch(int value) {
        if (selectedAddress < 0) {
            dropped++;
            throw new IllegalStateException(spec.name()
                    + ": 还没选从设备就写数据 —— I2C 必须先发地址字节（这条不许静默丢弃）");
        }
        for (final I2cSlave s : slaves) {
            if (s.address7() == selectedAddress) {
                s.write(value);
                delivered++;
                return;
            }
        }
        // 地址上没有设备：真实总线会 NACK ⇒ 这里也必须可观测，而不是当作成功
        dropped++;
        throw new IllegalStateException(spec.name() + ": 地址 0x"
                + Integer.toHexString(selectedAddress) + " 上没有从设备（NACK）");
    }
}

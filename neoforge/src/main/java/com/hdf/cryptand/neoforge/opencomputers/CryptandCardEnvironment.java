package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.neoforge.soc.content.SocPartKind;
import com.hdf.cryptand.soc.device.AdcDevice;
import com.hdf.cryptand.soc.device.PwmDevice;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.AbstractManagedEnvironment;

import net.minecraft.core.Direction;

import java.util.ArrayDeque;

/**
 * ===== Cryptand 扩展卡 → OC 组件（2026-09-26）=====
 *
 * <p>用户定案："组件 id 按照原版 oc 显示"、"调研原版 oc 看看组件哪些是有的我们也加上"。
 * 这张类把四张扩展卡注册成 **OC 组件**（名字取 {@link SocPartKind#ocComponent()} 这个单一来源），
 * 于是 Lua 的 {@code component.list()} 与我们的固件看到的是同一批组件。</p>
 *
 * <table border="1">
 *   <tr><th>部件</th><th>组件名</th><th>依据</th></tr>
 *   <tr><td>GPIO 卡</td><td>{@code redstone}</td><td>OC 自己的红石卡就叫这个名字，方法面也照它</td></tr>
 *   <tr><td>PWM 卡</td><td>{@code pwm}</td><td>OC 无对应物 ⇒ 自定名（小写下划线，避开 OC 的 45 个名字）</td></tr>
 *   <tr><td>ADC 卡</td><td>{@code adc}</td><td>同上</td></tr>
 *   <tr><td>串口卡</td><td>{@code serial}</td><td>同上</td></tr>
 * </table>
 *
 * <h3>哪些是真的、哪些还没接</h3>
 * <ul>
 *   <li><b>redstone（GPIO）</b>：真红石读写 —— 走宿主方块的 {@code RedstoneAware}
 *       （OC 的机箱/服务器本身就实现了这个 trait，OC 的红石卡也是这么干的）；
 *       宿主不支持就**明确报错**，绝不假装成功。</li>
 *   <li><b>pwm / adc</b>：真设备模型 —— 每通道一个 common 的 {@link PwmDevice} / {@link AdcDevice}
 *       （与 SoC 外设路径**同一份模型**，不另写一套寄存器语义）。ADC 的采样源（电网电压/电流）
 *       与 PWM 的落点（电机转速）属于电力仿真那条链，接上去之前读数是 0 / 输出不驱动世界 —— 这一点写在
 *       下面的 {@code adc.getRaw} 文档里，不装成"已经能测"。</li>
 *   <li><b>serial</b>：本组件管**收发与波特率**（帧 = 字节流），物理口（jSerialComm 那条桥）
 *       是后续项；没接物理口时数据留在组件自己的 FIFO 里，读得出来、不丢、不静默。</li>
 * </ul>
 */
public final class CryptandCardEnvironment extends AbstractManagedEnvironment {

    /** OC 的红石强度上限（0..15） */
    private static final int MAX_REDSTONE = 15;

    private final SocPartKind kind;
    private final String cardId;
    private final int spec;
    private final EnvironmentHost host;

    /** PWM / ADC：每通道一个设备（spec = 通道数） */
    private final PwmDevice[] pwms;
    private final AdcDevice[] adcs;

    /** 串口：TX/RX 都是字节 FIFO（容量按"每字节周期"无关，固定 4KB 够用；满了计数不静默丢） */
    private final ArrayDeque<Byte> rx = new ArrayDeque<>();
    private final ArrayDeque<Byte> tx = new ArrayDeque<>();
    private static final int FIFO_LIMIT = 4096;
    private long txDropped;
    private int baud = 115200;

    public CryptandCardEnvironment(SocPartKind kind, String cardId, int spec, EnvironmentHost host) {
        this.kind = kind;
        this.cardId = cardId == null ? kind.ocComponent() : cardId;
        this.spec = Math.max(1, spec);
        this.host = host;
        this.pwms = kind == SocPartKind.CARD_PWM ? new PwmDevice[this.spec] : null;
        this.adcs = kind == SocPartKind.CARD_ADC ? new AdcDevice[this.spec] : null;
        for (int i = 0; pwms != null && i < pwms.length; i++) {
            pwms[i] = new PwmDevice(cardId + ":ch" + i);
        }
        for (int i = 0; adcs != null && i < adcs.length; i++) {
            adcs[i] = new AdcDevice(cardId + ":ch" + i);
        }
        // 组件名来自 SocPartKind（单一来源）：能对上 OC 的照 OC 叫，对不上的用我们自己的名字。
        setNode(Network.newNode(this, Visibility.Network)
                .withComponent(kind.ocComponent(), Visibility.Network)
                .create());
    }

    // ==================== GPIO / redstone ====================

    /**
     * {@code setOutput(side, value)} —— 照 OC 红石卡：写某一边的输出强度。
     *
     * <p>值域 0..15（OC 的语义），非法值明确拒绝；宿主方块不支持红石输出（没实现
     * {@code RedstoneAware}）时也明确报错 —— 不静默变成"记下来但不发"。</p>
     */
    @Callback(value = "setOutput", direct = true, doc = "function(side:string, value:number):number -- previous output")
    public Object[] setOutput(Context context, Arguments args) {
        requireRedstone();
        final Direction side = side(args.checkAny(0));
        final int value = args.checkInteger(1);
        if (value < 0 || value > MAX_REDSTONE) {
            throw new IllegalArgumentException("redstone value out of range 0..15: " + value);
        }
        final Object ra = redstoneHost();
        final int previous = callInt(ra, "getOutput", side);
        callSetOutput(ra, side, value);
        return new Object[]{previous};
    }

    @Callback(value = "getOutput", direct = true, doc = "function([side:string]):number -- current output")
    public Object[] getOutput(Context context, Arguments args) {
        requireRedstone();
        final Object ra = redstoneHost();
        if (args.count() == 0) {
            int max = 0;
            for (final Direction d : Direction.values()) {
                max = Math.max(max, callInt(ra, "getOutput", d));
            }
            return new Object[]{max};
        }
        return new Object[]{callInt(ra, "getOutput", side(args.checkAny(0)))};
    }

    @Callback(value = "getInput", direct = true, doc = "function([side:string]):number -- current input")
    public Object[] getInput(Context context, Arguments args) {
        requireRedstone();
        final Object ra = redstoneHost();
        if (args.count() == 0) {
            int max = 0;
            for (final Direction d : Direction.values()) {
                max = Math.max(max, callInt(ra, "getInput", d));
            }
            return new Object[]{max};
        }
        return new Object[]{callInt(ra, "getInput", side(args.checkAny(0)))};
    }

    private void requireRedstone() {
        if (kind != SocPartKind.CARD_GPIO) {
            throw new IllegalStateException(kind.ocComponent() + " 不是红石组件");
        }
    }

    private Object redstoneHost() {
        if (!(host instanceof li.cil.oc.common.blockentity.traits.RedstoneAware)) {
            throw new IllegalStateException("宿主方块不支持红石（没实现 RedstoneAware）⇒ "
                    + cardId + " 无法读写红石");
        }
        return host;
    }

    private static int callInt(Object ra, String method, Direction side) {
        final li.cil.oc.common.blockentity.traits.RedstoneAware aware =
                (li.cil.oc.common.blockentity.traits.RedstoneAware) ra;
        return "getOutput".equals(method) ? aware.getOutput(side) : aware.getInput(side);
    }

    private static void callSetOutput(Object ra, Direction side, int value) {
        final li.cil.oc.common.blockentity.traits.RedstoneAware aware =
                (li.cil.oc.common.blockentity.traits.RedstoneAware) ra;
        aware.setOutputEnabled(true);
        if (!aware.setOutput(side, value)) {
            throw new IllegalStateException("setOutput(" + side + ", " + value + ") 被宿主拒绝");
        }
    }

    /** OC 的 side 表示法：字符串 "up"/"down"/"north"/"south"/"east"/"west" */
    private static Direction side(Object raw) {
        if (raw instanceof Number n) {
            return switch (n.intValue()) {
                case 0 -> Direction.DOWN;
                case 1 -> Direction.UP;
                case 2 -> Direction.NORTH;
                case 3 -> Direction.SOUTH;
                case 4 -> Direction.WEST;
                case 5 -> Direction.EAST;
                default -> throw new IllegalArgumentException("bad side index: " + n);
            };
        }
        final String s = String.valueOf(raw).toLowerCase(java.util.Locale.ROOT);
        for (final Direction d : Direction.values()) {
            if (d.getName().equals(s)) {
                return d;
            }
        }
        throw new IllegalArgumentException("bad side: " + raw);
    }

    // ==================== PWM ====================

    @Callback(value = "setPeriod", direct = true, doc = "function(channel:number, ticks:number) -- PWM period")
    public Object[] setPeriod(Context context, Arguments args) {
        final PwmDevice d = pwm(args.checkInteger(0));
        final int period = args.checkInteger(1);
        d.store(0x00, period, 4);
        return new Object[]{period};
    }

    @Callback(value = "setDuty", direct = true, doc = "function(channel:number, ticks:number) -- PWM high time")
    public Object[] setDuty(Context context, Arguments args) {
        final PwmDevice d = pwm(args.checkInteger(0));
        final int duty = args.checkInteger(1);
        d.store(0x04, duty, 4);
        return new Object[]{d.getDuty()};
    }

    @Callback(value = "setEnabled", direct = true, doc = "function(channel:number, enabled:boolean)")
    public Object[] setEnabled(Context context, Arguments args) {
        final PwmDevice d = pwm(args.checkInteger(0));
        final boolean on = args.checkBoolean(1);
        d.store(0x08, on ? 1 : 0, 4);
        return new Object[]{on};
    }

    @Callback(value = "getPeriod", direct = true, doc = "function(channel:number):number")
    public Object[] getPeriod(Context context, Arguments args) {
        return new Object[]{pwm(args.checkInteger(0)).getPeriod()};
    }

    @Callback(value = "getDuty", direct = true, doc = "function(channel:number):number")
    public Object[] getDuty(Context context, Arguments args) {
        return new Object[]{pwm(args.checkInteger(0)).getDuty()};
    }

    /** 当前输出电平（0/1）—— 由宿主按周期推进设备后才有意义（step 由外设路径驱动） */
    @Callback(value = "getOutputLevel", direct = true, doc = "function(channel:number):number -- 0/1")
    public Object[] getOutputLevel(Context context, Arguments args) {
        return new Object[]{pwm(args.checkInteger(0)).outputHigh() ? 1 : 0};
    }

    @Callback(value = "getStatus", direct = true, doc = "function(channel:number):number -- READY|EDGE")
    public Object[] getStatus(Context context, Arguments args) {
        return new Object[]{pwm(args.checkInteger(0)).status()};
    }

    private PwmDevice pwm(int channel) {
        if (pwms == null) {
            throw new IllegalStateException(kind.ocComponent() + " 不是 PWM 组件");
        }
        if (channel < 0 || channel >= pwms.length) {
            throw new IllegalArgumentException("PWM 通道超出范围：0.." + (pwms.length - 1) + "，给了 " + channel);
        }
        return pwms[channel];
    }

    // ==================== ADC ====================

    /**
     * {@code getRaw(channel)} —— 通道原始读数。
     *
     * <p>⚠ 采样源（电网电压/电流那条仿真链）还没接到这里：没接之前读数是设备里当前的值（默认 0）。
     * 这里不编造"看起来像电压"的数字 —— 接上电源仿真后它才有意义。</p>
     */
    @Callback(value = "getRaw", direct = true, doc = "function(channel:number):number -- raw sample")
    public Object[] getRaw(Context context, Arguments args) {
        return new Object[]{adc(args.checkInteger(0)).getChannel(args.checkInteger(0))};
    }

    @Callback(value = "getNormalized", direct = true, doc = "function(channel:number):number -- 0..1")
    public Object[] getNormalized(Context context, Arguments args) {
        final int ch = args.checkInteger(0);
        return new Object[]{adc(ch).normalized(ch)};
    }

    @Callback(value = "getFullScale", direct = true, doc = "function(channel:number):number")
    public Object[] getFullScale(Context context, Arguments args) {
        return new Object[]{adc(args.checkInteger(0)).fullScale()};
    }

    @Callback(value = "setFullScale", direct = true, doc = "function(channel:number, value:number)")
    public Object[] setFullScale(Context context, Arguments args) {
        final int ch = args.checkInteger(0);
        final AdcDevice d = adc(ch);
        final int value = args.checkInteger(1);
        d.store(0x28, value, 4);                    // REG_FULL_SCALE（与 SoC 外设路径同一套寄存器）
        return new Object[]{d.fullScale()};
    }

    private AdcDevice adc(int channel) {
        if (adcs == null) {
            throw new IllegalStateException(kind.ocComponent() + " 不是 ADC 组件");
        }
        if (channel < 0 || channel >= adcs.length) {
            throw new IllegalArgumentException("ADC 通道超出范围：0.." + (adcs.length - 1) + "，给了 " + channel);
        }
        return adcs[channel];
    }

    // ==================== 串口 ====================

    /** {@code write(data)} —— 发一段字节（返回真正入队的字节数；满了不静默丢，见 {@link #getStats}） */
    @Callback(value = "write", direct = true, doc = "function(data:string):number -- bytes queued")
    public Object[] write(Context context, Arguments args) {
        final byte[] data = args.checkString(0).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int queued = 0;
        for (final byte b : data) {
            if (tx.size() >= FIFO_LIMIT) {
                txDropped++;
                break;
            }
            tx.add(b);
            queued++;
        }
        return new Object[]{queued};
    }

    /** {@code read([count])} —— 取最多 count 个已收到的字节（默认全取） */
    @Callback(value = "read", direct = true, doc = "function([count:number]):string -- received bytes")
    public Object[] read(Context context, Arguments args) {
        final int want = args.count() > 0 ? Math.max(1, args.checkInteger(0)) : rx.size();
        final byte[] out = new byte[Math.min(want, rx.size())];
        for (int i = 0; i < out.length; i++) {
            out[i] = rx.poll();
        }
        return new Object[]{new String(out, java.nio.charset.StandardCharsets.ISO_8859_1)};
    }

    @Callback(value = "getBaud", direct = true, doc = "function():number")
    public Object[] getBaud(Context context, Arguments args) {
        return new Object[]{baud};
    }

    @Callback(value = "setBaud", direct = true, doc = "function(bps:number)")
    public Object[] setBaud(Context context, Arguments args) {
        final int bps = args.checkInteger(0);
        if (bps <= 0 || bps > 4_000_000) {
            throw new IllegalArgumentException("非法波特率：" + bps);
        }
        baud = bps;
        return new Object[]{bps};
    }

    /** 收发计数（无人化断言用：rx/tx 队列占用 + 因满丢弃） */
    @Callback(value = "getStats", direct = true, doc = "function():number, number, number")
    public Object[] getStats(Context context, Arguments args) {
        return new Object[]{rx.size(), tx.size(), txDropped};
    }

    /** 宿主侧投递收到的字节（物理口接上以后由桥调用；现在留给测试与将来的串口桥） */
    public int deliver(byte[] data) {
        int n = 0;
        for (final byte b : data) {
            if (rx.size() >= FIFO_LIMIT) {
                break;
            }
            rx.add(b);
            n++;
        }
        return n;
    }

    /** 诊断：卡名 + 组件名 + 规格 */
    @Override
    public String toString() {
        return "CryptandCard[" + cardId + " component=" + kind.ocComponent() + " spec=" + spec + "]";
    }
}

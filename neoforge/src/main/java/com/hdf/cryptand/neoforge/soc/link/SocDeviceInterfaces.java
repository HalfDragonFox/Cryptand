package com.hdf.cryptand.neoforge.soc.link;

import com.hdf.cryptand.soc.link.PortRole;
import com.hdf.cryptand.soc.link.LinkProtocol;
import com.hdf.cryptand.soc.link.PortKind;
import com.hdf.cryptand.soc.link.SocLinkRole;
import com.hdf.cryptand.soc.link.SocLinkTable;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * ===== 设备「支持接口列表」表（neoforge 侧单一来源，2026-09-28）=====
 *
 * <p>《接口准入》裁定 C：<b>支持接口列表放 neoforge</b>（与 MC 组件有关），common 只放抽象。</p>
 *
 * <p>每一行 = 设备类型 → {角色, 支持接口列表, 中文名}。**列表顺序即优先级**，书写顺序必须与
 * {@link LinkProtocol} 的 priority 升序一致（common 的 {@code SocLinkSelfTest} 断言枚举顺序，
 * 这里的每条列表也按同一顺序写；{@code SocLinkTable.Endpoint} 会再规范一次，写乱了也不会出错）。</p>
 *
 * <p>这是**第一版**（定案原话"我出一版 + 你调"）：先覆盖已存在的设备（芯片/真彩屏/五类扩展卡/
 * 存储/键盘），数值与分类可直接改这张表。</p>
 */
public final class SocDeviceInterfaces {

    /** 设备类型 id（也是链路表里记录的 id）。 */
    public static final String CHIP = "chip";
    public static final String SCREEN = "screen";
    public static final String CARD_GPU = "card_gpu";
    public static final String CARD_UART = "card_uart";
    public static final String CARD_PWM = "card_pwm";
    public static final String CARD_ADC = "card_adc";
    public static final String CARD_GPIO = "card_gpio";
    public static final String BOARD = "board";
    public static final String DISK = "disk";
    public static final String FLASH = "flash";
    public static final String EEPROM = "eeprom";
    public static final String KEYBOARD = "keyboard";

    /** 一行：角色 + 支持接口列表 + 中文名。 */
    public record Entry(SocLinkRole role, List<LinkProtocol> interfaces, String label) {
    }

    private static final Map<String, Entry> TABLE = Map.ofEntries(
            // 芯片：对外接口最全（PCIE 接扩展卡 / USB 接外设 / SPI / UART / I2C）
            Map.entry(CHIP, new Entry(SocLinkRole.SOURCE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.USB, LinkProtocol.SPI,
                            LinkProtocol.UART, LinkProtocol.I2C), "虚拟机")),
            // 真彩屏：用户定案"屏幕支持 DP/SPI/UART/I2C/8080"
            Map.entry(SCREEN, new Entry(SocLinkRole.SOURCE,
                    List.of(LinkProtocol.DP, LinkProtocol.SPI, LinkProtocol.UART,
                            LinkProtocol.I2C, LinkProtocol.P8080), "真彩屏")),
            // 扩展卡：中间组件（可双向、可级联）；GPU 卡插 PCIE、输出 DP
            Map.entry(CARD_GPU, new Entry(SocLinkRole.MIDDLE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.DP, LinkProtocol.SPI), "图形扩展卡")),
            Map.entry(CARD_UART, new Entry(SocLinkRole.MIDDLE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.USB, LinkProtocol.SPI,
                            LinkProtocol.UART), "串口扩展卡")),
            Map.entry(CARD_PWM, new Entry(SocLinkRole.MIDDLE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.SPI, LinkProtocol.I2C), "PWM 扩展卡")),
            Map.entry(CARD_ADC, new Entry(SocLinkRole.MIDDLE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.SPI, LinkProtocol.I2C), "ADC 扩展卡")),
            Map.entry(CARD_GPIO, new Entry(SocLinkRole.MIDDLE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.SPI, LinkProtocol.I2C), "GPIO 扩展卡")),
            // 底板：机箱内的总线（中间组件）
            Map.entry(BOARD, new Entry(SocLinkRole.MIDDLE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.USB, LinkProtocol.SPI), "底板")),
            Map.entry(DISK, new Entry(SocLinkRole.SOURCE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.USB), "磁盘")),
            Map.entry(FLASH, new Entry(SocLinkRole.SOURCE,
                    List.of(LinkProtocol.PCIE, LinkProtocol.USB), "存储")),
            Map.entry(EEPROM, new Entry(SocLinkRole.SOURCE,
                    List.of(LinkProtocol.SPI, LinkProtocol.I2C), "启动 EEPROM")),
            // 键盘：设计裁定 H —— 键盘支持 USB/UART，**仅 USB 时自动接入**
            Map.entry(KEYBOARD, new Entry(SocLinkRole.SOURCE,
                    List.of(LinkProtocol.USB, LinkProtocol.UART), "键盘")));

    /**
     * 一个**接口点**（2026-09-28 用户定案「显卡 = 1 个 RV 点 + N 个 DP 点，屏 = 1 个 DP 输入点」）。
     *
     * <p>{@code id} 是端口 id（{@code rv} / {@code dp1}…），{@code kind} 决定颜色与协议 ——
     * <b>同色（同 kind）才能连</b>，见 {@link PortKind#sameKind}。</p>
     */
    public record Port(String id, PortKind kind, String label) {
    }

    /**
     * 设备的接口点清单。{@code channels} 只对"按档位变点数"的设备有意义（显卡 1/2/4 个 DP 输出）。
     *
     * <p>表里没有的设备：按其支持接口列表各给一个点（保底，不猜数量）。</p>
     */
    public static List<Port> ports(String deviceId, int channels) {
        final Entry e = of(deviceId);
        if (e == null) {
            return List.of();
        }
        final int n = Math.max(1, Math.min(channels, 8));
        switch (baseId(deviceId)) {
            case CARD_GPU -> {
                final List<Port> out = new java.util.ArrayList<>();
                out.add(new Port(PortKind.RV.portId(1), PortKind.RV, "RV（接芯片）"));
                for (int i = 1; i <= n; i++) {
                    out.add(new Port(PortKind.DP.portId(i), PortKind.DP, "DP" + i));
                }
                return List.copyOf(out);
            }
            // 用户定案原话：「屏幕为一个 DP 输入点」⇒ 屏就一个 DP 点（其余接口暂不作为点出现）
            case SCREEN -> {
                return List.of(new Port(PortKind.DP.portId(1), PortKind.DP, "DP 输入"));
            }
            case CHIP -> {
                return List.of(
                        new Port(PortKind.RV.portId(1), PortKind.RV, "RV 总线"),
                        new Port(PortKind.USB.portId(1), PortKind.USB, "USB"),
                        new Port(PortKind.SPI.portId(1), PortKind.SPI, "SPI"),
                        new Port(PortKind.UART.portId(1), PortKind.UART, "UART"),
                        new Port(PortKind.I2C.portId(1), PortKind.I2C, "I2C"));
            }
            default -> {
                final List<Port> out = new java.util.ArrayList<>();
                for (final LinkProtocol p : e.interfaces()) {
                    final PortKind k = PortKind.of(p);
                    if (k != null) {
                        out.add(new Port(k.portId(1), k, k.label()));
                    }
                }
                return List.copyOf(out);
            }
        }
    }

    /** 按端口 id 找点（找不到返回 null）。 */
    public static Port portOf(String deviceId, String portId, int channels) {
        if (portId == null || portId.isBlank()) {
            return null;
        }
        for (final Port p : ports(deviceId, channels)) {
            if (p.id().equalsIgnoreCase(portId.trim())) {
                return p;
            }
        }
        return null;
    }

    /**
     * 按**端口**造端点：端点的 interfaces 只含该端口的协议 ⇒ 协商必然得到该协议，
     * 所以"同色才能连"在链路表这一层是自然成立的（异色两端交集为空 ⇒ 连线失败并给中文原因）。
     */
    public static SocLinkTable.Endpoint endpoint(BlockPos pos, String deviceId, String portId, int channels) {
        final Entry e = of(deviceId);
        final Port p = portOf(deviceId, portId, channels);
        if (e == null || p == null) {
            return null;
        }
        return new SocLinkTable.Endpoint(
                new SocLinkTable.Pos(pos.getX(), pos.getY(), pos.getZ()),
                deviceId, p.id(), e.role(), List.of(p.kind().protocol()), portRole(deviceId));
    }

    /**
     * 端口在总线上的角色（用户 2026-09-29：「连接分主从…一般从设备端口仅能连接一个，比如 I2C」）。
     *
     * <ul>
     *   <li>芯片/虚拟机 = {@link PortRole#MASTER}：主机侧按协议上限挂多台从设备；</li>
     *   <li>扩展卡 = {@link PortRole#PEER}：PCIe 侧它是从设备、DP 侧它是主机，按协议默认最准；</li>
     *   <li>其它外设（屏/键盘/盘/EEPROM…）= {@link PortRole#SLAVE}：一条总线只能挂一次。</li>
     * </ul>
     */
    private static PortRole portRole(String deviceId) {
        final String base = baseId(deviceId);
        if (CHIP.equals(base)) {
            return PortRole.MASTER;
        }
        return base.startsWith("card_") ? PortRole.PEER : PortRole.SLAVE;
    }

    private SocDeviceInterfaces() {
    }

    /**
     * 查表；未知设备返回 null（调用方报"未识别的设备"）。
     *
     * <p>设备 id 允许带 {@code #n} 后缀来区分**同坐标的同类多台设备**（一台机器里插了两张显卡：
     * {@code card_gpu} 与 {@code card_gpu#2}）—— 查表时把后缀剥掉，端点身份仍然互不相同。</p>
     */
    public static Entry of(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        final int hash = deviceId.indexOf('#');
        return TABLE.get(hash < 0 ? deviceId : deviceId.substring(0, hash));
    }

    /** 去掉 {@code #n} 后缀的纯设备类型 id（给端点/端口表用）。 */
    public static String baseId(String deviceId) {
        if (deviceId == null) {
            return "";
        }
        final int hash = deviceId.indexOf('#');
        return hash < 0 ? deviceId : deviceId.substring(0, hash);
    }

    public static String label(String deviceId) {
        final Entry e = of(deviceId);
        return e == null ? String.valueOf(deviceId) : e.label();
    }

    /** 便利：按设备类型构造链路端点（查不到返回 null）。 */
    public static SocLinkTable.Endpoint endpoint(BlockPos pos, String deviceId) {
        final Entry e = of(deviceId);
        if (e == null) {
            return null;
        }
        return new SocLinkTable.Endpoint(
                new SocLinkTable.Pos(pos.getX(), pos.getY(), pos.getZ()),
                deviceId, e.role(), e.interfaces());
    }

    /** 全部已登记的设备类型（UI/分析器列举用）。 */
    public static Map<String, Entry> all() {
        return TABLE;
    }
}

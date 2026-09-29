package com.hdf.cryptand.soc.link;

/**
 * ===== 端口在链路里的角色（2026-09-29，纯 Java 零 MC）=====
 *
 * <p>用户定案：「连接分主从或者任意两种方式，一般从设备端口仅能连接一个，比如 I2C」——
 * 同一条总线上：<b>主机</b>端口可以挂多个从设备，<b>从机</b>端口只能挂一条，<b>对等</b>端口
 * （UART/DP 这类点对点，或未声明的主机性）按协议默认值。</p>
 *
 * <p>上限优先级：端点显式 {@code maxLinks} &gt; 角色（SLAVE=1） &gt; 协议默认（{@link LinkProtocol#maxDevices()}）。</p>
 */
public enum PortRole {

    /** 总线主机：I2C/SPI/USB/PCIe 的控制器侧，按协议上限挂多个从设备。 */
    MASTER("master"),
    /** 从设备：挂在总线上的一端，**只能接一条**（I2C 从机、SPI 从机、USB 设备）。 */
    SLAVE("slave"),
    /** 对等/未声明（点对点链路，或老调用方）：按协议默认值。 */
    PEER("peer");

    private final String id;

    PortRole(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 中文名（面板/提示用）。 */
    public String label() {
        return switch (this) {
            case MASTER -> "主机";
            case SLAVE -> "从设备";
            case PEER -> "对等";
        };
    }

    public static PortRole byId(String id) {
        if (id == null) {
            return PEER;
        }
        for (final PortRole r : values()) {
            if (r.id.equalsIgnoreCase(id.trim())) {
                return r;
            }
        }
        return PEER;
    }
}

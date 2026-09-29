package com.hdf.cryptand.integratednetwork;

/**
 * 网络接口（2026-08-29 集成网络核心改进）：管道 / BE 的一个输入或输出口。
 * <p>
 * 轻量不可变，只存必要信息（省内存）：
 * id / 能力类型 / 输入输出 / 方向 / 位置 / 速率。
 * 挂接的图节点按 id 对应；与容器的一对一关系见
 * {@link TransportGraph#addContainer}/{@link TransportGraph#container}
 * （即「一条接口→容器信息」）。
 * <p>
 * 纯虚拟：核心只持有元数据，绝不触碰真实方块/容器（核心铁律）。
 */
public final class NetworkInterface {

    /** 接口 id（平台层键：如 管道位置+面 的编码） */
    public final Object id;
    /** 接口能力类型（通道过滤；与网络能力 {@link TransportGraph#capability()} 相关） */
    public final TransferType type;
    /** true = 输入口（供方/注入）；false = 输出口（接收/写出） */
    public final boolean input;
    /** 方向（平台层约定：0..5 = 下上北南西东，-1 = 无方向） */
    public final int direction;
    /** 位置（世界坐标，仅元数据/可视化） */
    public final double x, y, z;
    /** 该口速率（单位/帧；&lt;=0 = 无限制） */
    public final double rate;
    /** 平台层接口参数（2026-08-30 用户：Object 类参数——过滤表/长度等，由对应
     *  解析器强转具体参数类使用；null = 无参数） */
    public final Object param;

    public NetworkInterface(Object id, TransferType type, boolean input, int direction,
                            double x, double y, double z, double rate) {
        this(id, type, input, direction, x, y, z, rate, null);
    }

    public NetworkInterface(Object id, TransferType type, boolean input, int direction,
                            double x, double y, double z, double rate, Object param) {
        this.id = id;
        this.type = type == null ? TransferType.GENERIC : type;
        this.input = input;
        this.direction = direction;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rate = Math.max(0.0, rate);
        this.param = param;
    }

    /** 便捷：无位置/方向 */
    public NetworkInterface(Object id, TransferType type, boolean input, double rate) {
        this(id, type, input, -1, 0, 0, 0, rate);
    }

    @Override
    public String toString() {
        return "NetworkInterface{" + id + " " + (input ? "IN" : "OUT")
                + " dir=" + direction + " rate=" + rate + "}";
    }
}
package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

/**
 * 环境 ID（EnvId）—— 物理结构内的运行时缓存（2026-09-01 V2 架构）。
 *
 * <p><b>语义：</b>指向【全局固有环境表】（{@link GlobalEnvTable}）条目的轻量索引
 * （int）。环境具体参数不入结构 —— 结构只存 EnvId；计算时按 EnvId 查表得到环境参数
 * （重力/介质密度/气压/浮力/阻力），改变结构参数实现不同效果（入水/空中/宇宙）。
 *
 * <p><b>缓存性质：</b>EnvId 解析值（环境参数派生量、网格/碰撞加速结构）为运行时缓存
 * —— 进入世界或环境变更时解析填充、退出世界时丢弃、不持久化（加速运算减少重复索引）。
 */
public final class EnvId {

    /** 无效/未解析 EnvId。 */
    public static final int NONE = -1;

    /** 默认（主世界陆地）。 */
    public static final int DEFAULT = 0;

    /** 主世界陆地。 */
    public static final int OVERWORLD_GROUND = 0;
    /** 海洋（近水/水下）。 */
    public static final int OCEAN_WATER = 1;
    /** 下界。 */
    public static final int NETHER = 2;
    /** 末地。 */
    public static final int END = 3;
    /** 宇宙空间。 */
    public static final int SPACE = 4;
    /** 高空大气。 */
    public static final int HIGH_ATMOSPHERE = 5;

    private EnvId() {
    }

    /** 校验是否是有效 EnvId（>=0 且 < 表条目数）。 */
    public static boolean isValid(final int envId) {
        return envId >= 0 && envId < GlobalEnvTable.instance().size();
    }
}

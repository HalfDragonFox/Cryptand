package com.hdf.cryptand.waterphysics;

/**
 * 「世界方块里的水位」与「侧表里的水位」的合并规则 —— 三个状态都要对上。
 *
 * <p>背景：侧表是求解权威，但世界可以被外部改动（玩家用桶、别的 mod、活塞）。
 * 只认侧表会让外部改动永远进不来；只认世界等于侧表白存。正确的规则是
 * <b>用「世界有没有水」当存在性门控，用「侧表有没有值」当水位来源</b>：
 *
 * <ul>
 *   <li>世界没水 → 返回 0：<b>外部清除</b>（玩家拿桶把水收走、别的 mod 放方块顶掉），
 *       侧表必须跟着清零；否则会留下一格「幽灵水」继续往邻居渗 —— 表现就是
 *       「用桶清不掉 / 清完水又回来了」。</li>
 *   <li>世界有水、侧表为 0 → 返回世界的值：<b>外部注入</b>（玩家倒水、桶、别的 mod）。</li>
 *   <li>世界有水、侧表也非 0 → 返回侧表的值：求解器是权威。</li>
 * </ul>
 *
 * <p>纯 Java，离线闸门见 {@code WaterphysicsSelfTest#testResolveLevel}。
 */
public final class FluidLevels {

    private FluidLevels() {
    }

    /**
     * @param worldLevel 世界方块里的水位（0 = 没有流体）
     * @param fieldLevel 侧表里的水位
     * @return 该格应当使用的水位
     */
    public static int resolve(final int worldLevel, final int fieldLevel) {
        // 兼容旧调用：把「侧表非 0」当成「有记录」
        return resolve(worldLevel, fieldLevel, fieldLevel > 0);
    }

    /**
     * @param worldLevel   世界方块里的水位（0 = 世界没水）
     * @param fieldLevel   侧表里的水位
     * @param fieldPresent 侧表里**有没有**这一格的记录（{@code WaterLevelField#isPresent}）
     * @return 该格应当使用的水位
     */
    public static int resolve(final int worldLevel, final int fieldLevel, final boolean fieldPresent) {
        if (worldLevel <= 0) {
            return 0;                   // 外部清除：世界没水 ⇒ 侧表跟着清
        }
        // ★ 有记录就认记录（哪怕是 0）：写回有滞后窗口，期间世界的 LEVEL 还是旧投影
        //   （源方块恒为 8），认世界就会让水位反复跳回 8。
        return fieldPresent ? fieldLevel : worldLevel;
    }
}

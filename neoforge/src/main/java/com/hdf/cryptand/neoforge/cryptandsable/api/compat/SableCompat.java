package com.hdf.cryptand.neoforge.cryptandsable.api.compat;

import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Sable 兼容门面（SableCompat）—— 让旧依赖 sable 语义的调用能映射到 CryptandSable 核心。
 *
 * <p><b>策略说明</b>：官方 {@code dev.ryanhcode.sable.*} 是 runtimeOnly 依赖且第三方 mod 会自带
 * 各自 sable → 本项目【不作同包名替代】（会撞类）。改由本门面以 CryptandSable 包名提供
 * "sable 风格"的查询/操作，旧调用方可改 import 接入；工程内旧反射（SableSubLevelObserver 等）
 * 仍走官方包名（官方 jar 保留在 classpath）。
 *
 * <p>覆盖旧 sable 常用语义：getContaining（位置→所在体）、质量/位姿查询、块注册。
 */
public final class SableCompat {
    private SableCompat() {}

    /**
     * 旧 sable 语义的 {@code getContaining(level, pos)} → 找到所在物理体的 runtimeId。
     *
     * @return 刚体 runtimeId；-1 表示该位置没有已注册物理体
     */
    public static int getContaining(ServerLevel level, BlockPos pos) {
        CryptandSable core = CryptandSable.instance();
        if (!core.isStarted()) return -1;
        // 从核心位姿快照里找包含该点的体（简化：最近质心；MVP 用首个非空）
        return core.lookupBodyAt(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 查询刚体当前质量（旧 sable MassTracker 风格的替代）。 */
    public static double getMass(int runtimeId) {
        return CryptandSable.instance().queryMass(runtimeId);
    }

    /** 查询刚体当前位置（写 dest）。 */
    public static boolean getPosition(int runtimeId, double[] dest3) {
        return CryptandSable.instance().queryPosition(runtimeId, dest3);
    }

    /** 查询刚体是否为柔体。 */
    public static boolean isSoft(int runtimeId) {
        return CryptandSable.instance().queryIsSoft(runtimeId);
    }
}
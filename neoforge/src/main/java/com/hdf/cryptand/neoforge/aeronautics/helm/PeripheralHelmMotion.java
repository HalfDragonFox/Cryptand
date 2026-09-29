/**
 * ===== 船体运动采样（客户端，2026-09-13） =====
 *
 * 力反馈要"随船体加速度"，就必须先拿到【载具在世界坐标里的运动】：
 * 物理化亚层（Sable/Aeronautics 的船）内的方块 BlockPos 是亚层局部坐标，
 * 方块本身不动 —— 必须把局部坐标投到真实世界坐标再逐 tick 差分。
 *
 * 投射走 SableCompanion.projectOutOfSubLevel 的【反射】（与 cee/CeePoseUtil 同手法，
 * 本包自持一份以免跨子包依赖——子包删除隔离铁律）：未装 sable / 非物理化结构 →
 * 原样返回局部坐标（方块不动 ⇒ 加速度自然为 0，力反馈不触发）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

public final class PeripheralHelmMotion {

    private static final String SABLE_COMPANION = "dev.ryanhcode.sable.companion.SableCompanion";

    /** 每 m/s² 对应多少 blocks/tick²（1 block = 1 m，1 tick = 0.05 s ⇒ ×400） */
    public static final float BLOCKS_PER_TICK2_TO_MPS2 = 400f;

    private static volatile Object companion;
    private static volatile boolean tried;
    private static volatile Method projectOut;

    private PeripheralHelmMotion() {
    }

    private static Object companion() {
        if (companion != null) {
            return companion;
        }
        if (tried) {
            return null;
        }
        tried = true;
        try {
            Class<?> c = Class.forName(SABLE_COMPANION);
            companion = c.getField("INSTANCE").get(null);
            for (Class<?>[] sig : new Class<?>[][]{
                    {Level.class, Vec3.class},
                    {Level.class, net.minecraft.core.Position.class}}) {
                try {
                    projectOut = c.getMethod("projectOutOfSubLevel", sig);
                    break;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            companion = null;
        }
        return companion;
    }

    /**
     * 方块中心在【真实世界】中的坐标（物理化结构经 pose 投出；非物理化/未装 sable → 原样）。
     */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        Vec3 local = Vec3.atCenterOf(pos);
        try {
            Object inst = companion();
            Method m = projectOut;
            if (inst == null || m == null) {
                return local;
            }
            Object r = m.invoke(inst, level, local);
            return r instanceof Vec3 v ? v : local;
        } catch (Throwable ignored) {
            return local;
        }
    }

    /** blocks/tick² → m/s² */
    public static float toMps2(float blocksPerTickSquared) {
        return blocksPerTickSquared * BLOCKS_PER_TICK2_TO_MPS2;
    }
}

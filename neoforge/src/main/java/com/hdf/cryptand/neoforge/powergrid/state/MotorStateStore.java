package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 电机机械状态持久存储（2026-08-24 用户：断开导线后转速/应力【不应立马归零】——
 * 现实是惯性滑行慢慢降。原实现网络重建（断线→版本变化→重建）创建新模型 →
 * reset() → 转速 0 → 应力 0。本 store 跨重建保存转子转速/EMF：
 *   - 写入：EngineBus ROTOR_SPEED 处理（主线程，引擎每次转速事件）→ 本 store
 *   - 恢复：MotorAssembler 组装模型（后台）→ restoreMotorState（状态还原）
 * 线程安全：ConcurrentHashMap（主线程写/后台读）。
 */
public final class MotorStateStore {

    /** pos → {rotorSpeedRadS, emfAmplitude, stressSU}（EMF 带方向；后台读，主线程写） */
    private static final ConcurrentHashMap<BlockPos, double[]> STATE =
            new ConcurrentHashMap<>();

    /** 诊断节流（5s） */
    private static volatile long DBG_LAST;

    private MotorStateStore() {
    }

    /** 主线程（EngineBus ROTOR_SPEED）写入最新转速+EMF+应力（BE/组装器只交互转速与应力） */
    public static void put(BlockPos pos, double radS, double emf, double stress) {
        if (pos == null) return;
        try {
            STATE.put(pos.immutable(), new double[]{radS, emf, stress});
            long now = System.currentTimeMillis();
            if (now - DBG_LAST >= 5000) {
                DBG_LAST = now;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[MotorState] save pos={} radS={} rpm={} emf={} stress={}",
                        pos, String.format("%.3f", radS),
                        String.format("%.1f", radS * 60.0 / (2.0 * Math.PI)),
                        String.format("%.1f", emf),
                        String.format("%.0f", stress));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 后台（组装器）恢复状态到模型；无记录 → 不修改（新电机默认 0） */
    public static void restore(BlockPos pos, ElectroMachineModel em) {
        if (pos == null || em == null) return;
        try {
            double[] s = STATE.get(pos);
            if (s == null) return;
            // ⚠ 2026-08-30 审计 C8：恢复前【范围校验】——引擎脏数据/配置变更
            // 后残留的 radS 1e30、emf 巨值、stress 超上限会恢复出爆表转速/EMF
            // → 巨电流/爆炸。拒绝对新模型规格无效的状态（radS 超软钳上限 3×ω_r、
            // EMF 超 maxVoltage、stress 非有限），stress 钳制 0..16384。
            double radS = s[0];
            double emf = s[1];
            double stress = s.length >= 3 ? s[2] : 0;
            if (!Double.isFinite(radS) || !Double.isFinite(emf)) return;
            double maxRad = Math.max(Math.abs(em.ratedRadS) * 3.0, 1e-9);
            if (Math.abs(radS) > maxRad) return; // 脏转速拒绝恢复
            if (Math.abs(emf) > Math.max(em.maxVoltage, 1e-9)) return; // EMF 超上限拒绝
            em.restoreMotorState(radS, emf);
            // 2026-08-27：恢复应力（断电滑行从断电前应力继续线性衰减，不瞬 0）
            if (s.length >= 3 && Double.isFinite(stress)) {
                em.restoreStress(Math.min(Math.max(stress, 0), 16384.0));
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[MotorState] restore pos={} radS={} rpm={} emf={} stress={}",
                    pos, String.format("%.3f", radS),
                    String.format("%.1f", radS * 60.0 / (2.0 * Math.PI)),
                    String.format("%.1f", emf),
                    s.length >= 3 ? String.format("%.0f", stress) : "?");
        } catch (Throwable ignored) {
        }
    }

    /** 读引擎当前状态（{ω, emf, stress}；无 → null）——PowerGrid 电机接管
     *  Mixin 每 tick 读此写 BE 字段（2026-08-24）。 */
    public static double[] get(BlockPos pos) {
        return pos == null ? null : STATE.get(pos);
    }

    /** 移除（设备销毁/世界切换） */
    public static void remove(BlockPos pos) {
        if (pos != null) STATE.remove(pos);
    }

    /** 清空（世界切换/关闭） */
    public static void clearAll() {
        STATE.clear();
    }

    /** 诊断 */
    public static int size() {
        return STATE.size();
    }
}

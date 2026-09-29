/**
 * ===== 通用电机 BE（2026-09-13 用户基础 BE 架构）=====
 *
 * 用户设计：通过定义【通用电机类】表示通用电机，可以定义多种更具体的电机类继承此类
 * 来达到不同效果；具体电机通过继承不同电机类来实现具体的电机 BE 侧建模；
 * 其他元件类似；不再使用静态工具类，而是直接集成进相关类。
 *
 * Java 约束：BE 必须继承原版类（NBT/生态兼容）⇒ 本层做成【接口 + default 方法】：
 *
 *   ICryptandCircuitBe        BE 基类：基础消息 + 爆炸（通用层→具体层）
 *     └ ICryptandMotorBE      通用电机：ROTOR 协议 + 通用驱动写回 + 可覆写钩子
 *         ├ CryptandElectricMotorBE        extends ElectricMotorBlockEntity
 *         ├ CryptandConstantSpeedMotorBE   extends ConstantSpeedMotorBlockEntity
 *         └ CryptandServoMotorBE           extends ServoBlockEntity
 *
 * 具体电机只需覆写意愿不同的钩子（如 cryptandMaxRpm / cryptandApplyDrive /
 * cryptandHandleMessage），其余通用行为由本接口提供。
 *
 * TODO（用户要求）：把 MotorTakeoverBase 这类静态工具类的方法逐步搬进本接口的
 * default 方法（当前先委托，保证行为不变、可小步迁移）。
 */
package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase;
import com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore;

public interface ICryptandMotorBE extends ICryptandCircuitBe {

    /** 引擎下发的电机驱动消息（ROTOR 协议：[方向, 转速rpm, 应力SU, 温度C]）；
     *  默认按通用电机语义应用（具体电机可覆写做自己的解释）。 */
    default void cryptandApplyDrive(double rpm, double stressSu, double tempC) {
        try {
            MotorTakeoverBase.applyValues(cryptandSelf(),
                    rpm * (2.0 * Math.PI) / 60.0, stressSu, tempC);
        } catch (Throwable ignored) {
        }
    }

    /**
     * ===== 电机驱动电流（通用电机层，2026-09-13）=====
     *
     * 三个原版电机的机械输出都由【自管电流】代入原版公式算出，因此"电流从哪来"
     * 是共同需求，统一在本层提供（通用电机类 → 具体电机类，用户设想的架构）：
     *
     *   ① {@code DeviceCurrent}（引擎每轮求解写入的端子0 净电流）—— 首选
     *   ② {@code DevicePowerStore}（引擎每轮的设备损耗功率 W）→ I = √(P/R)
     *      —— 兜底：求解覆盖不到的设备/回退路径（P 即绕组损耗 I²R）
     *
     * 两者都无（未建模/未接线）→ 0（电机不转，属正常）。
     */
    default double cryptandMotorCurrent(double resistance) {
        try {
            double i = cryptandDeviceCurrent();
            if (Double.isFinite(i) && i != 0) return i;
            if (!(resistance > 0)) return 0;
            net.minecraft.world.level.block.entity.BlockEntity be = cryptandSelf();
            if (be == null) return 0;
            double pw = com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore
                    .get(be.getBlockPos());
            if (Double.isFinite(pw)) {
                double ii = Math.sqrt(Math.max(0.0, pw) / resistance);
                if (Double.isFinite(ii) && ii > 0) return ii;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 本电机允许的转速上限（默认原版 maxRPM；具体电机可覆写）。 */
    default double cryptandMaxRpm() {
        try {
            return org.patryk3211.powergrid.PowerGrid.maxRPM();
        } catch (Throwable ignored) {
            return 256.0;
        }
    }

    /** 通用电机消息处理：识别 ROTOR 协议并应用（具体电机可覆写接管）。
     *  ⚠ 消息尺寸校验与 NaN 防御在内（原 PowerGridMotorParser.onMessage 的语义）。 */
    /**
     * ===== 通用电机层默认行为：引擎转速/应力是【权威值】直接写回 =====
     *
     * ⚠ 2026-09-13 回归修复（"所有的都不转了"）：本方法一度被改成空实现，结果
     *   【自研电机】全灭 —— 关键事实：
     *   {@code SinglePhaseAsyncMotorBlockEntity}（单相异步电机）也是
     *   {@code ElectricMotorBlockEntity} 的子类、走同一个 PowerGridMotorParser，
     *   而它的机械转速【只能来自引擎】（InductionMotorModel 的 EMF/滑差模型），
     *   本方法就是它唯一的字段写入者。空实现 ⇒ 无人写 generatedSpeed ⇒ 不转。
     *
     * 因此分工必须是：
     *   - 默认（本方法）= 引擎权威值写回 —— 自研电机、以及任何"引擎算好了给我"的电机；
     *   - 原版三电机的【寄生接管自有 BE】覆写为空 —— 它们走"自管电流代入原版公式"
     *     （即开即停/固定应力/无反馈），引擎消息不参与，避免双写 avgSpeed 语义冲突。
     */
    default void cryptandApplyRotorMessage(double dir, double rpm, double stress, double tempC) {
        try {
            // stress 允许 NaN（引擎不提供应力；原版电机应力由原版提供，不可覆盖）
            if (!Double.isFinite(rpm)) return;
            double max = cryptandMaxRpm();
            double clamped = Math.max(-max, Math.min(max, rpm));
            cryptandApplyDrive(dir * clamped, stress, tempC);
        } catch (Throwable ignored) {
        }
    }
}

/**
 * ===== Cryptand 自有普通电机 BE（2026-09-13 寄生式接管）=====
 *
 * 用户方案：必须加载交错电网 mod（避嫌），但原本全套加载机制由我们接管；
 * 2026-09-13 追加："原版内容全部放弃，全部使用我们的自定义的 BE 相关内容"。
 *
 * 做法（不 fork、不重写类体系）：
 *   - 直接【继承】原版 ElectricMotorBlockEntity → NBT 读写/字段/接口
 *     （IElectricEntity / GeneratingKineticBlockEntity）全部继承 ⇒
 *     旧存档零迁移；instanceof ElectricMotorBlockEntity 仍成立（生态不断）
 *   - 把自己注册进原版的 BlockEntityType 工厂（见 BlockEntityTypeAccessor
 *     + PowergridModule 的工厂替换）⇒ 之后【新建/存档加载】出来的电机 BE 都是本类
 *   - 行为接管：本类提供算法主体，由 PowerGridMotorTakeoverMixin 的 @Overwrite
 *     tick/lazyTick 委派调用 ⇒ PowerGrid 原版电气算法体【一行都不执行】，
 *     只保留 Create 的 kinetic 机制（super.tick/super.lazyTick）
 *
 * 算法（对齐原版语义：即开即停 / 固定应力 / 无反馈）：
 *   tick     : avgSpeed += P / torque() × 60π/2 × sign(I)，P = I²·R（自管电流）
 *   lazyTick : newSpeed = clamp(avgSpeed/5, ±maxRPM) → generatedSpeed
 *              → updateGeneratedRotation（Create 网络传播）
 *   应力      : 由原版 torque() / Create BlockStressValues 固定给出，不写 load
 */
package com.hdf.cryptand.neoforge.powergrid.motor;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore;
import com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity;

public class CryptandElectricMotorBE extends ElectricMotorBlockEntity
        implements com.hdf.cryptand.neoforge.powergrid.device.ICryptandMotorBE {

    /** POC 诊断节流（证明实例化的确实是本类） */
    private static volatile long DBG_LAST;
    /** 驱动诊断节流（定位"转速不转"：R / torque / I / rpm） */
    private static volatile long DRIVE_DBG_LAST;

    /* ============ 引擎消息 → 驱动电流（2026-09-13 "原版电机不转"修复）============
     * 自定义电机会转、原版电机不转的差异：自定义走"引擎算 → EngineBus 消息 → BE 应用"；
     * 原版电机此前做成"BE 自己读 DeviceCurrent 缓存"——该缓存无槽/未写入时电流恒 0
     * → rpm 恒 0 → 不转。
     * 现把原版电机也接回消息链：引擎每 tick 广播 [powerW]（PhasorPipeline
     * .syncDevicePowerMessages），纯电阻电机 P = I²R ⇒ I = √(P/R)。
     * 电流来源优先级：① 近期 [powerW] 消息 ② DeviceCurrent 缓存（回退）。
     * ========================================================================== */
    /* ⚠ 2026-09-13：电流来源统一到通用电机层 ICryptandMotorBE.cryptandMotorCurrent(R)
     *   （① DeviceCurrent 引擎端子电流 ② DevicePowerStore 功率反推 I=√(P/R)）。
     *   原先在此截取 [powerW] 消息的兜底【无效】：PipelinePostProcess
     *   .syncDevicePowerMessages 只对 protocol()==POWER 的桥下发，电机是 ROTOR
     *   协议 → 永远收不到该消息（保留在注释里以记教训）。 */

    /* ==================== 行为实现（由 PowerGridMotorTakeoverMixin @Overwrite 委派）====================
     * 用户设计："不再使用静态工具类，而是直接集成进相关类"。
     * 原版语义（2026-09-12 用户："原版三种电机效果和原版类似，即开即停，并且输出固定
     * 应力以及转速，不会有反馈，总之参考原版代码"）：
     *   rpm = (I²·R) / torque() × 60π/2 × sign(I)，限幅 ±maxRPM
     * 应力由原版 torque()/Create BlockStressValues 固定提供，不写 load。
     * ⚠ 对应的 @Overwrite mixin 已把原版 tick/lazyTick 方法体整体替换 → 原版算法不执行。
     * ========================================================================================== */

    /**
     * tick 主体 —— 主线程【不计算】（2026-09-13 用户："每次异步线程计算完成转速后
     * 发送消息更新到主线程"。）
     *
     * 转速的唯一真源 = 引擎侧的 {@code VanillaMotorModel}（组装器建，机械侧就在
     * 那里算）：每轮求解后用端子电压算 I → 原版公式 rpm = I²R/torque × 60π/2
     * → 经 {@code EngineBus.post(ROTOR_SPEED)} 把 BE 引用 + 转速送到主线程 →
     * {@code ICryptandMotorBE.cryptandApplyRotorMessage}（通用层默认实现）
     * → {@code MotorTakeoverBase.applyValues} 写 generatedSpeed/avgSpeed +
     * Create 网络同步。
     *
     * 本方法因此只做诊断（旧版在这里自算公式 → 与消息路径【双写同一字段】，
     * 实测互相覆盖导致转速只剩 1/5 甚至 0 —— 已彻底移除）。
     */
    public void cryptandMotorTick() {
        try {
            cryptandDriveDiag((int) cryptandReadFloat("generatedSpeed"));
        } catch (Throwable ignored) {
        }
    }

    /**
     * lazyTick 主体 —— 有意留空（2026-09-13 修复"电机时不时出现 0 rpm / 应力闪 0"）。
     *
     * 原实现照搬原版语义：`avg = avgSpeed; avgSpeed = 0; newSpeed = avg/5;
     * generatedSpeed = newSpeed`。那套是【配套】的 —— 原版 `tick()` 每 tick 都在
     * `avgSpeed += …` 累加，lazyTick 每 5 tick 折算一次再清零，正好是 5 次平均。
     *
     * 但我们的 `avgSpeed` 现在是【引擎消息每轮覆盖】（`applyValues` 写 rpm×5），
     * 不是累加 ⇒ 清零语义失效，时序一错就归零：
     *
     *     t1  applyValues: avgSpeed = rpm×5, generatedSpeed = rpm
     *     t2  lazyTick   : avg = rpm×5 → avgSpeed = 0 → generatedSpeed = rpm   ✅
     *     t3  （消息未到）lazyTick: avg = 0 → generatedSpeed = 0            ❌ 转速归零
     *          → 引擎下轮按 σ = σ_max×min(1,|ω|/ω_r) 算出 σ = 0 → 应力也闪 0
     *
     * 引擎消息（`applyValues`）已经是唯一权威写入者（它同时写 avgSpeed/generatedSpeed
     * 并做 Create 网络同步），主线程不需要、也不应该再折算一次。
     */
    public void cryptandMotorLazyTick() {
        // 有意留空：转速由引擎消息写入（见方法注释）
    }

    /**
     * 驱动诊断（2026-09-13 用户："电机还是会闪应力值，建议每 tick 打印一次电机具体
     *  数值来判断" → 该轮排查已结束，节流恢复为 5s）。
     *
     * 每台电机每 5 秒一行，看单台用日志搜索 `pos=BlockPos{x=..}` 过滤；
     * 要临时恢复每 tick，把下面三行节流注释掉即可。
     */
    private void cryptandDriveDiag(int newSpeed) {
        try {
            // 2026-09-13 恢复 5s 节流：定位"应力闪烁/0rpm"的那轮诊断已结束，
            //   每 tick 一行会淹没其它日志。要临时看每 tick，把下面两行注释掉即可。
            long dbgNow = System.currentTimeMillis();
            if (dbgNow - DRIVE_DBG_LAST < 5000) return;
            DRIVE_DBG_LAST = dbgNow;
            double resistance = cryptandCallNumber("resistance");
            double torque = cryptandCallNumber("torque");
            // 诊断：cache = 组装器缓存槽是否存在；term = 端子节点是否注册
            String cacheState;
            String termState;
            try {
                cacheState = com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry.get(getBlockPos()) == null
                                ? "NO-CACHE" : "ok";
            } catch (Throwable t) {
                cacheState = "err";
            }
            try {
                boolean t0 = com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry.get(getBlockPos(), 0) != null;
                boolean t1 = com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry.get(getBlockPos(), 1) != null;
                termState = "t0=" + t0 + " t1=" + t1;
            } catch (Throwable t) {
                termState = "err";
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[MotorDrive] side={} pos={} R={} torque={} I={} dcI={} msgW={} "
                            + "rpm={} genSU={} load={} cache={} {}",
                    // side：客户端 BE 不参与仿真（generatedSpeed/generatedSU 靠同步包更新，
                    //   滞后与 0 都是正常的）—— 排查时必须区分，否则会被客户端日志误导。
                    (getLevel() != null && getLevel().isClientSide) ? "CLIENT" : "SERVER",
                    getBlockPos(), String.format("%.4f", resistance),
                    String.format("%.2f", torque),
                    // I：驱动电流（含 DevicePowerStore 反推兜底）
                    String.format("%.3f", cryptandMotorCurrent(resistance)),
                    // dcI：纯 DeviceCurrent 缓存值 —— 用来定位"62857A 垃圾电流"来自哪条路径
                    String.format("%.3f", cryptandDeviceCurrent()),
                    String.format("%.3f", com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore.get(getBlockPos())),
                    newSpeed,
                    // genSU/load：护目镜读的就是这两个（应力闪烁要盯这里）
                    String.format("%.1f", cryptandReadFloat("generatedSU")),
                    String.format("%.1f", cryptandReadFloat("load")),
                    cacheState, termState);
        } catch (Throwable ignored) {
        }
    }



    public CryptandElectricMotorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        try {
            long now = System.currentTimeMillis();
            if (now - DBG_LAST >= 1000) {
                DBG_LAST = now;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[MotorBE] Cryptand 自有电机 BE 实例化 pos={} type={}",
                        pos, type == null ? "null" : String.valueOf(type));
            }
        } catch (Throwable ignored) {
        }
    }
}

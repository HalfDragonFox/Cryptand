package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

import com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 【中间层】交错电网（PowerGrid）电机 BE 处理基类（2026-08-24 用户三层架构）：
 * <p>
 * 组装器 → BeMessageParser（最基础 BE 基类）→ 本中间层 → 三种具体电机 BE 子类：
 * <pre>
 *   BeMessageParser（组装器只与它交互）
 *    └─ PowerGridMotorParser（本类：PG 电机公共处理——温度/转速回读）
 *        ├─ PowerGridElectricMotorParser       普通     ElectricMotorBlockEntity
 *        ├─ PowerGridConstantSpeedMotorParser  恒速     ConstantSpeedMotorBlockEntity
 *        └─ PowerGridServoMotorParser          伺服     ServoBlockEntity
 * </pre>
 * 公共函数在此实现（回读/诊断），具体差异（onState 如何处理收到的数据）由三个
 * 子类继承实现；新增电机类型 = 再加一个子类，中间层/组装器零改动。
 * <p>
 * ⚠ 不调 applyNewSpeed（PowerGrid 0.5.5.1 覆写 NPE，见 BeMessageParser 注释）。
 */
public abstract class PowerGridMotorParser extends BeMessageParser {

    protected PowerGridMotorParser(BlockEntity be) {
        super(be);
    }

    @Override
    public float currentRadS() {
        // Create GeneratingKineticBlockEntity.getGeneratedSpeed()（所有 PG 电机共有）
        try {
            java.lang.reflect.Method m = be.getClass().getMethod("getGeneratedSpeed");
            Object v = m.invoke(be);
            if (v instanceof Number n) {
                return n.floatValue() * (float) (2.0 * Math.PI) / 60.0f;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    @Override
    public double temperatureC() {
        // 2026-09-12 用户："温度全部走自管"——原版 ThermalBehaviour 的温度已停推
        // （ThermalBehaviour.tick 在自管模式被取消），读它只会拿到冻结的假值。
        // 统一读 Cryptand 温度模型（组装器建的那一份）。
        try {
            return com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore
                    .thermalFor(be.getBlockPos()).tempCelsius();
        } catch (Throwable ignored) {
            return 25;
        }
    }

    @Override
    public void addHeat(double joules) { /* BE 温度由线圈功率驱动 */ }

    @Override
    public String describe() { return be.getClass().getSimpleName(); }

    /** 机电转子协议（引擎→BE [方向, rpm, 应力, 温度]） */
    @Override
    public Protocol protocol() { return Protocol.ROTOR; }

    @Override
    protected void onMessage(Object raw) {
        BeMessage m = BeMessage.of(raw);
        // ⚠ 2026-08-25 引擎完全接管：收到消息【直接覆写】（零计算）——
        //   计算全部由异步引擎完成（ElectroMachineModel），主线程只做
        //   "读值→写字段→触发 Create 同步"（2026-08-25 用户）。
        //   协议：[方向(+1/-1), 转速(rpm), 应力(SU), 温度(°C)]（BeMessageParser 常量）。
        // ⚠ 2026-08-26 修复：PhasorPipeline.syncDevicePowerMessages 每 tick 也会
        //   向所有设备发 [powerW]（1 元素）消息 → 按 4 元素解析时 num(1) 越界
        //   NaN → generatedSpeed=NaN → Create 网络应力 NaNsu（截图实证）。
        //   必须校验尺寸 >= 3（dir/rpm/stress 至少），temp 缺省 NaN 可接受。
        try {
            if (m == null || m.size() < MOT_STRESS + 1) return;
            double dir = m.num(MOT_DIR);
            double rpm = m.num(MOT_RPM);
            double stress = m.num(MOT_STRESS);
            double tempC = m.size() > MOT_TEMP ? m.num(MOT_TEMP) : Double.NaN;
            // NaN 防御：rpm 非有限 → 丢弃（避免 NaN 写入 Create 网络）。
            // ⚠ 2026-09-13：stress 允许 NaN —— 原版电机的应力由原版 torque()/
            //   BlockStressValues 固定提供，引擎不提供也不得覆盖；若因 stress=NaN
            //   整条丢弃，转速会一起丢（电机不转）。
            if (!Double.isFinite(rpm))
                return;
            // ===== ⚠ 2026-09-13 用户："原版内容全部放弃，全部使用我们的自定义的
            //   BE 相关内容" =====
            // 旧实现：引擎 ω 直接写机械字段（avgSpeed/generatedSpeed/load）——
            //   与自有 BE 的"自管电流代入原版公式"路径【互相覆盖】：
            //   applyValues 把 avgSpeed 写成【已经是 rpm 的值】，而 lazyTick 再
            //   做 avgSpeed/5 → 结果只有引擎转速的 1/5（实测"转轴几乎不转"）。
            // 新实现：引擎 ROTOR 消息【不再写任何机械字段】，只转发给具体 BE
            //   （ICryptandMotorBE.cryptandApplyRotorMessage）——三个原版电机的
            //   机械输出一律由自有 BE 用自管电流（DeviceCurrent）代入原版公式算出
            //   （即开即停 / 固定应力 / 无反馈，用户 2026-09-12 要求）。
            //   想让某个具体电机改用引擎转速 → 在它的 cryptandApplyRotorMessage
            //   里覆写并调用 cryptandApplyDrive 即可（钩子保留）。
            if (be instanceof com.hdf.cryptand.neoforge.powergrid.device
                    .ICryptandMotorBE im) {
                im.cryptandApplyRotorMessage(dir, rpm, stress, tempC);
            } else {
                // ⚠ 2026-09-13 回归修复：未挂通用电机接口、但仍是 ElectricMotorBlockEntity
                //   子类的 BE（典型：自研 SinglePhaseAsyncMotorBlockEntity——它直接
                //   继承原版类、没实现我们的接口）→ 引擎权威值【必须】写回字段，
                //   否则没有任何人写 generatedSpeed → 电机不转。
                double omega = dir * rpm * (2.0 * Math.PI) / 60.0;
                MotorTakeoverBase.applyValues(be, omega, stress, tempC);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * ===== 应力发回引擎（2026-08-25 用户：应力消耗发回方便计算） =====
     *
     * 主线程每 tick：读 Create 网络【真实机械应力消耗】→ sendToEngine 发回。
     * 引擎（ElectroMachineModel.loadTorque/loadRatio）用它建模：
     *   - 启动：负载重（λ 大）→ 启动转矩不足 → 【堵转】
     *   - 停机：τ = 30s / λ（负载越重停得越快）
     *
     * 读取 API（Create KineticNetwork，均主线程安全）：
     *   - calculateStress()   = 网络总应力消耗（SU，所有负载之和）
     *   - sources.get(本源)    = 该源的【额定容量】（SU，固定值，与转速无关）
     * ⚠ 不能用 getActualCapacityOf()：它返回 额定容量×|generatedSpeed|（×速度
     *   因子）——电机刚启动 generatedSpeed≈0 → capacity≈0 → λ=stress/0=∞ →
     *   引擎误判"带大负载"→ 永久堵转（2026-08-26 电机不转根因！）。
     * 负载率 λ = stress / 额定容量（0..1；>1 = 过应力；钳制 3 防爆）。
     * 消息协议（BE→引擎）：[λ负载率, 网络应力 SU, 额定容量 SU]。
     */
    @Override
    public void serverTick(net.minecraft.world.level.Level level) {
        // 2026-09-13 用户架构铁律："主线程永远是被动端……必须遵循主线程接收到信息后
        // 才允许发送一次，不能主动发送"。原来的"每 tick 主动上报"已搬到
        // replyAfterMessage()（收到引擎消息后回一次），此处不再发起任何发送。
    }

    /** 被动回复：引擎消息到达后回发一次机械应力状态（协议见上） */
    @Override
    protected void replyAfterMessage() {
        try {
            net.minecraft.world.level.Level level = be == null ? null : be.getLevel();
            if (be == null || level == null || level.isClientSide) return;
            if (!(be instanceof com.simibubi.create.content.kinetics.base
                    .KineticBlockEntity kb)) return;
            double stress = 0, capacity = 0;
            boolean hasNet = false; // 网络存在标记（2026-08-26 断电门控修复）
            double netRadS = 0; // 网络当前转速（rad/s，2026-08-26 用户：发来判堵转）
            try {
                var net = kb.getOrCreateNetwork();
                if (net != null) {
                    hasNet = true;
                    stress = net.calculateStress();
                    // 网络转速：Create kb.getSpeed() = RPM（含方向）→ rad/s
                    netRadS = kb.getSpeed() * (2.0 * Math.PI / 60.0);
                    // ⚠ 2026-08-26 修复：读【额定容量】（sources map 原始值），
                    // 不用 getActualCapacityOf（×|generatedSpeed| 速度因子 →
                    // 启动时 capacity≈0 → λ=∞ → 误堵转）
                    Object capV = MotorTakeoverBase.getField(net, "sources");
                    if (capV instanceof java.util.Map<?, ?> sm) {
                        Object v = sm.get(kb);
                        if (v instanceof Number n) capacity = n.doubleValue();
                    }
                }
            } catch (Throwable ignored) {
            }
            // ===== 2026-08-26 BE 每 tick 主动发；组装器收到后回发 =====
            // ⚠ 修复（2026-08-26）"断电后应力消耗不生效"：原实现 capacity>0 才发
            // 数据，断电后 generatedSpeed 衰减 → calculateCapacity() 兜底 → 0 →
            // 变空心跳 → 引擎收不到 [stress] → networkStressSU 恒 0 → 惯性制动
            // 只剩 30s 空载基准。改为【网络存在即上报数据】：断电后负载机械仍
            // 在消耗应力（calculateStress 绝对值不随源转速衰减）→ 引擎拿到真实
            // 消耗驱动惯性制动。λ 单独钳制（capacity≤0 时 λ=0，应力仍上报）。
            if (hasNet) {
                double lambda = (capacity > 0 && stress > 0)
                        ? Math.min(stress / capacity, 3.0) : 0; // 钳制（过应力）
                // 协议 [λ, 网络应力SU, 容量SU, 网络转速rad/s]：第 3 元素网络转速
                // 供引擎判过载堵转（断电网络卡死 → 应力归零）。
                sendToEngine(new BeMessage(lambda, stress, capacity, netRadS));
            } else {
                sendHeartbeat(); // 无网络 → 空心跳（组装器回发引擎状态）
            }
        } catch (Throwable ignored) {
        }
    }
}

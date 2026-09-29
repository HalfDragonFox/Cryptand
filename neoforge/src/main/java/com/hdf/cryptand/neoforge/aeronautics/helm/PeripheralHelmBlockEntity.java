/**
 * ===== 外设船舵方块实体（2026-09-13） =====
 *
 * 两个职责：
 * <ol>
 *   <li><b>Create 转速输出</b>：继承 {@link GeneratingKineticBlockEntity}，把"外设输入 →
 *       目标舵角"翻译成输出轴的旋转（转速 {@link #RPM}、按角度差决定转向与持续时间），
 *       机制与航空学（simulated）的船舵 SteeringWheelBlockEntity 一致：用 Create 的
 *       {@code sequenceContext(TURN_ANGLE)} 让网络按"转多少角度"消耗，转到位即停。
 *       接上传动即可像船舵那样驱动方向舵/螺旋桨。</li>
 *   <li><b>力反馈（船体加速度）</b>：每 tick 采样方块所在【物理化结构】的世界坐标差分
 *       得到加速度（{@link PeripheralHelmMotion}，零 Sable 编译期依赖）；超过
 *       {@code binding.ffbMinAccel} 的部分线性映射到 {@code ffbStrength} 满力度，
 *       经采样线程下发给方向盘的阻尼效果。</li>
 * </ol>
 *
 * 数据流：
 * <pre>
 *   客户端：采样 SDL 设备 → 死区/反向 → 本地舵角（渲染插值 + UI 实时）
 *          → 每 tick 检测，变化 &gt; {@link #SYNC_EPSILON} 度即上报（不限期数，保证红石跟得上）
 *            发 {@link PeripheralHelmStatePayload} 到服务端
 *   服务端：targetAngleToUpdate → updateTargetAngle → generatedSpeed + updateGeneratedRotation
 *          → 输出轴按角度积分（integrateAngle），转到位停转
 * </pre>
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.hdf.cryptand.gameinput.GameInputState;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionPayload;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PeripheralHelmBlockEntity extends GeneratingKineticBlockEntity
        implements com.hdf.cryptand.neoforge.aeronautics.PeripheralTickable {

    /** 输出轴转速（RPM；与航空学船舵一致 = 16） */
    public static final int RPM = 16;

    /** 客户端 → 服务端舵角同步间隔（tick） */
    // （原 SYNC_INTERVAL 上报限流已移除：红石/比较器需要每 tick 跟随外部设备）
    /** 舵角变化阈值（度）：小于它不发包（静止时不占用带宽） */
    public static final float SYNC_EPSILON = 1.0f;
    /** 加速度平滑系数（0..1；越大越灵敏） */
    private static final float ACCEL_SMOOTHING = 0.35f;

    // ==================== 绑定 ====================

    private PeripheralHelmBinding binding = PeripheralHelmBinding.DEFAULT;

    /** 舵轮木料（官方语义：默认云杉 `Blocks.SPRUCE_PLANKS`，玩家可用木板右键更换） */
    private BlockState material = Blocks.SPRUCE_PLANKS.defaultBlockState();

    /**
     * 绑定归属玩家（多人语义：设备在【玩家客户端】上，只有该玩家的客户端采样并上发舵角；
     * null = 未限定 → 任何客户端都可驱动，单人游戏零感）。
     */
    private java.util.UUID owner;
    /** 服务端向其它客户端广播角度的节流计数（tick） */
    private int broadcastCooldown;

    // ==================== 舵角 / 动能输出（服务端权威，参考航空学船舵） ====================

    /** 命令目标角（clamp 后） */
    private float targetAngle;
    /** 外设输入命令（客户端发包写入；每 tick 作为 updateTargetAngle 的输入） */
    private float targetAngleToUpdate;
    /** 输出轴实际角度（度） */
    private float angle;
    /** 当前输出转速（带符号；{@link #getGeneratedSpeed()} 的门控源） */
    private float generatedSpeed;
    private float logicalSpeed;
    private int inUse;
    private double sequencedAngleLimit;

    // ==================== 客户端渲染 / 采样 ====================

    private float renderAngle;
    private float prevRenderAngle;
    /** ⚠ 以下字段由【客户端异步核心线程】写、主线程读 → 必须 volatile */
    private volatile float clientSteer;
    /** 核心算出的目标舵角（主线程渲染插值用；不复用服务端的 targetAngle） */
    private volatile float clientWantedAngle;
    private float[] clientAxes = new float[GameInputState.AXIS_COUNT];
    private boolean clientDeviceConnected;
    /** 设备被【其它船舵】占用（本客户端申请不到 → 界面提示） */

    private float lastSentAngle;

    // ==================== 客户端运动（力反馈来源） ====================

    /** 上一 tick 的世界坐标（物理化结构经 pose 投出） */
    private Vec3 lastWorldPos;
    /** 上一 tick 的世界速度（blocks/tick） */
    private Vec3 lastVelocity;
    /** 平滑后的结构加速度（blocks/tick²） */
    private float structureAccel = 0f;
    /** 结构加速度（m/s²；UI 显示 + 力反馈判据） */
    private float structureAccelMps2 = 0f;
    /** 最近一帧的世界速度/加速度（blocks/tick；力反馈多通道合成用） */
    private Vec3 velocityVec;
    private Vec3 accelVec;
    /** 结构速度（m/s） */
    private float speedMps;
    /** 转向角速度（每 tick 的舵量变化；阻尼通道用） */
    private float steerRate;
    private float lastSteerForRate;
    /** 上一帧垂向加速度（m/s²；算 jerk → 颠簸/上下坡冲击） */
    private float lastVerticalAccel;
    /** 冲击脉冲强度（0..1，指数衰减 ~0.4s） */
    private float impulse;
    /** 打滑（推头）平滑量（0..1）：舵量大但侧向 G 起不来 → 方向盘变轻 */
    private float slipFactor;
    /** 当前力反馈强度（0..1，UI 显示） */
    private float forceStrength = 0f;

    public PeripheralHelmBlockEntity(BlockPos pos, BlockState state) {
        super(PeripheralHelmRegistry.PERIPHERAL_HELM_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 无 Create 行为（ValueBox 等）——外设输入 + 自身转速输出
    }

    // ==================== tick ====================

    @Override
    public void tick() {
        super.tick();
        if (level == null) {
            return;
        }
        prevRenderAngle = renderAngle;
        if (level.isClientSide) {
            this.clientTick();
        } else {
            this.serverTick();
        }
    }

    /**
     * 主线程：<b>只做渲染插值</b>。
     * <p>2026-09-13 用户定稿：外设的全部计算已搬到客户端异步核心（{@link PeripheralCore}，
     * 固定频率、串行遍历、接收→计算→发送），主线程不再做采样/上传/力反馈 ——
     * 既符合"主线程=纯同步"的项目铁律，也让服务端只当交互枢纽、省下服务器性能。
     */
    private void clientTick() {
        handler$renderSyncedAngle();
    }

    /** 客户端异步核心周期（核心线程）：采样设备 → 算舵角 → 力反馈 → 排上行队列。 */
    @Override
    public void coreTick(long nowMs) {
        // 多人：设备只在【归属玩家的客户端】上 —— 其它客户端不采样、不上发，只按服务端同步的角度渲染
        String userKey = userKey();
        if (!isLocalDriver()) {
            PeripheralHelmInput.releaseUser(userKey);
            return;
        }
        String deviceId = binding.deviceId();
        if (deviceId.isEmpty()) {
            PeripheralHelmInput.releaseUser(userKey);
            this.clientDeviceConnected = false;
            this.clientSteer = 0f;
            return;
        }
        // 读取直接走【客户端全局设备池】（零阻塞）。设备是<b>共享</b>的：同一设备可被多个船舵同时绑定，
        // 大家读同一份快照（方向盘只有一个物理位置），不存在"被抢占"这回事。
        PeripheralHelmInput.Sample sample = PeripheralHelmInput.sample(deviceId, userKey);
        this.clientDeviceConnected = sample.connected();
        this.clientAxes = sample.axes();

        float steer = PeripheralHelmBlockEntity.applyDeadzone(sample.axis(binding.steerAxis()), binding.deadzone());
        if (binding.invertSteer()) {
            steer = -steer;
        }
        this.clientSteer = steer;
        // 告诉设备池"转向轴是哪个轴、是否反向"——回中闭环要靠它判断方位（池与绑定解耦，由此传入）
        PeripheralHelmInput.setSteerAxis(deviceId, userKey, binding.steerAxis(), binding.invertSteer());
        // 强制设备类型（0 = 自动 → null = 按后端判定）
        PeripheralHelmInput.setForcedKind(deviceId, userKey,
                com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds.kindOf(binding.forceKind()));

        float wanted = binding.angleOf(steer);
        // ⚠ 不写 targetAngle：那是【服务端】的权威字段（updateTargetAngle 用），客户端只上传 wanted。
        //   渲染插值（renderAngle）也留在主线程的 clientTick，核心只算数值。
        this.clientWantedAngle = wanted;

        // 每 tick 检测、一变就上报（服务端据此输出转速 + 红石）。
        // ⚠ 刻意不做"每 N tick 发一次"的限流：比较器读的是服务端保存的命令角度，
        //   限流会让红石比设备慢半拍，快速打舵时跟不上。
        // ★ 会话式上行（2026-09-13 用户定稿）：不再"变化才发"，改由客户端（主动端）按配置频率
        //   定时上行 —— 有数据发数据、未绑定设备时发空包保活；服务端（被动端）限流接收并回 sable 数据。
        boolean angleChanged = wanted != this.lastSentAngle;
        this.lastSentAngle = wanted;
        PeripheralSessionClient.tickSend(worldPosition,
                PeripheralSessionPayload.KIND_HELM, wanted, binding.bound(), angleChanged);

        this.sampleMotion();
    }

    /** 服务端：外设命令 → 目标推进 → 输出轴角度积分（Create 网络） */
    private void serverTick() {
        if (getGeneratedSpeed() != 0f) {
            this.integrateAngle();
        }
        if (inUse > 0) {
            this.inUse--;
            if (this.inUse == 0) {
                this.sequenceContext = null;
                this.generatedSpeed = 0f;
                this.updateGeneratedRotation();
            }
        } else {
            this.updateTargetAngle(this.targetAngleToUpdate);
        }
    }

    /**
     * 目标舵角 → 输出转速（照搬航空学船舵语义）：
     * 相对当前轴角算出转向与所需时间（inUse），以恒定 {@link #RPM} 转过去，转到位停转。
     */
    public void updateTargetAngle(float absoluteTarget) {
        float limit = Math.abs(binding.maxAngleDeg());
        absoluteTarget = Mth.clamp(absoluteTarget, -limit, limit);

        if (this.targetAngle == absoluteTarget) {
            return;
        }
        this.targetAngle = absoluteTarget;
        final float relativeAngle = absoluteTarget - this.angle;

        if (Math.abs(relativeAngle) < 0.001f && this.inUse <= 0) {
            this.generatedSpeed = 0f;
            this.updateGeneratedRotation();
            return;
        }

        final float rotationSpeed = RPM * Math.signum(relativeAngle);
        if (rotationSpeed == 0f) {
            return;
        }
        final float relativeValue = relativeAngle / rotationSpeed;
        if (relativeValue <= 0f && this.inUse <= 0) {
            this.generatedSpeed = 0f;
            this.updateGeneratedRotation();
            return;
        }

        final double degreesPerTick = convertToAngular(rotationSpeed);
        this.inUse = (int) Math.ceil(relativeAngle / degreesPerTick) + 2;
        this.sequenceContext = new SequencedGearshiftBlockEntity.SequenceContext(
                SequencerInstructions.TURN_ANGLE, relativeValue);
        this.sequencedAngleLimit = Math.abs(relativeAngle);
        this.logicalSpeed = rotationSpeed;

        final Direction facing = this.getBlockState().getValue(PeripheralHelmBlock.FACING);
        final boolean floor = this.getBlockState().getValue(PeripheralHelmBlock.ON_FLOOR);
        if ((facing == Direction.NORTH || facing == Direction.WEST) == floor) {
            this.generatedSpeed = -this.logicalSpeed;
        } else {
            this.generatedSpeed = this.logicalSpeed;
        }
        this.updateGeneratedRotation();
        this.setChanged();
    }

    /** 按当前输出转速线性积分轴角（限幅由 sequencedAngleLimit 控制，与航空学船舵一致） */
    private void integrateAngle() {
        float angularSpeed = this.getAngularSpeed();
        if (this.sequencedAngleLimit >= 0) {
            angularSpeed = (float) Mth.clamp(angularSpeed, -this.sequencedAngleLimit, this.sequencedAngleLimit);
            this.sequencedAngleLimit = Math.max(0, this.sequencedAngleLimit - Math.abs(angularSpeed));
        }
        this.angle += angularSpeed;
    }

    public float getAngularSpeed() {
        float speed = convertToAngular(this.getLogicalSpeed());
        if (this.getSpeed() == 0f || this.getLogicalSpeed() == 0f) {
            speed = 0f;
        }
        return speed;
    }

    public float getLogicalSpeed() {
        return this.inUse == 0 ? 0f : this.logicalSpeed;
    }

    /** Create 转速源出口：仅在被命令转动期间输出（{@link #RPM} rpm） */
    @Override
    public float getGeneratedSpeed() {
        return this.inUse == 0 ? 0f : this.generatedSpeed;
    }

    @Override
    protected Block getStressConfigKey() {
        return PeripheralHelmRegistry.PERIPHERAL_HELM.get();
    }

    // ==================== 结构运动 → 力反馈 ====================

    /** 采样方块所在结构的运动（世界坐标差分：位置 → 速度 → 加速度）并合成力反馈 */
    private void sampleMotion() {
        Vec3 world = PeripheralHelmMotion.worldCenter(level, worldPosition);
        if (lastWorldPos != null) {
            Vec3 velocity = world.subtract(lastWorldPos);
            this.velocityVec = velocity;
            this.speedMps = (float) velocity.length() * 20f;   // blocks/tick → m/s
            if (lastVelocity != null) {
                Vec3 accel = velocity.subtract(lastVelocity);
                this.accelVec = accel;
                float magnitude = (float) accel.length();
                this.structureAccel += (magnitude - this.structureAccel) * ACCEL_SMOOTHING;
                this.structureAccelMps2 = PeripheralHelmMotion.toMps2(this.structureAccel);
            }
            this.lastVelocity = velocity;
        }
        this.lastWorldPos = world;

        // 会话式：服务端下发的 sable 数据是【权威】的（多人下本地推算会与真实结构不同步）。
        // 收到过就以它为准，未收到时保留本地采样（单人游戏两者一致）。
        if (this.serverSableTick >= 0) {
            this.speedMps = this.serverSpeedMps;
            this.velocityVec = this.serverVelocityVec;
            this.accelVec = this.serverAccelVec;
            this.structureAccelMps2 = this.serverAccelMps2;
        }
        this.updateFeelChannels();
    }

    /**
     * ===== 运动学 → 多通道力反馈（模拟赛车方向盘的合成方式）=====
     *
     * <pre>
     *   ① 侧向 G / 对齐力矩 → CONSTANT（带左右符号）  ∝ 水平加速度中垂直于速度的分量
     *   ② 回中弹簧           → SPRING              ∝ 舵角
     *   ③ 阻尼               → DAMPER              ∝ 转向角速度
     *   ④ 路面纹理           → SINE                ∝ 脚下方块粗糙度 × 车速（车速越高频率越高）
     * </pre>
     * 总增益取绑定的"力反馈强度"（0..1）；G 力阈值沿用"最小/满力度加速度"两个滑块。
     * 上下坡冲击（垂向加速度 + jerk 脉冲）留待下一阶段（见 OV 记忆的规划）。
     */
    private void updateFeelChannels() {
        float total = Mth.clamp(binding.ffbStrength(), 0f, 1f);
        int gain = Math.round(total * 10000f);
        if (gain <= 0) {
            PeripheralHelmInput.requestChannels(binding.deviceId(), userKey(),
                    PeripheralHelmInput.ForceChannels.NONE);
            this.forceStrength = 0f;
            return;
        }

        // ① 侧向 G：水平加速度 − 沿速度方向的分量；叉积符号决定往左还是往右
        int constant = 0;
        float latMps2 = 0f;
        if (velocityVec != null && accelVec != null && speedMps > 0.5f) {
            Vec3 horizontal = new Vec3(velocityVec.x, 0, velocityVec.z);
            if (horizontal.lengthSqr() > 1.0E-6) {
                Vec3 vHat = horizontal.normalize();
                Vec3 accelHorizontal = new Vec3(accelVec.x, 0, accelVec.z);
                Vec3 lateral = accelHorizontal.subtract(vHat.scale(accelHorizontal.dot(vHat)));
                latMps2 = (float) lateral.length() * PeripheralHelmMotion.BLOCKS_PER_TICK2_TO_MPS2;
                float norm = binding.forceStrengthFor(latMps2) / Math.max(1.0E-4f, total);
                double side = Math.signum(vHat.cross(lateral).y);
                constant = (int) (side * Mth.clamp(norm, 0f, 1f) * gain);
            }
        }

        // 预设比例（只调通道配比；总增益仍是"力反馈强度"）
        FeelProfile profile = profileOf(binding.feelPreset());

        // ①b 打滑近似（推头）：舵打得大、侧向 G 却没跟上 ⇒ 方向盘变轻（真实赛车"失抓"的手感）
        float steerAbs = Math.abs(clientSteer);
        float latNorm = binding.forceStrengthFor(latMps2) / Math.max(1.0E-4f, total);
        float slip = Mth.clamp(steerAbs - latNorm - 0.25f, 0f, 1f);
        this.slipFactor += (slip - this.slipFactor) * 0.15f;
        constant = Math.round(constant * profile.g() * (1f - this.slipFactor * 0.6f));

        // ②③ 回中（∝舵角）与阻尼（∝转向角速度）——一律用【恒定力闭环】合成，
        //     不再用 SDL 的 SPRING / DAMPER 条件效果：
        //     ⚠ 实测部分设备（FXN-V12lite）把 SPRING 的 center=0 处理成"推向一侧"而不是"拉向中位"，
        //       于是成了正反馈 —— 舵角略偏 → 弹簧把它推得更偏 → 一路顶到 ±满舵并卡住不动
        //       （用户报的"一直跑到负数最高、回正也只是临时回 0°"的根因）。
        //     恒定力的方向由我们自己算（∝ 偏差、始终指向绝对 0），行为可预期、可诊断。
        float delta = clientSteer - lastSteerForRate;
        this.lastSteerForRate = clientSteer;
        this.steerRate = Math.abs(delta);
        // 更仿真（2026-09-13 用户："船舵反馈更仿真"）：
        //   真车上【回正力矩随车速增强】（前轮自回正效应），【阻尼也随车速提高】（高速更稳）。
        //   系数取保守值：静止/低速 ≈ 0.4 倍，中速 ≈ 1 倍，高速封顶 1.6 / 1.5 倍。
        float speedForForce = Mth.clamp(0.4f + speedMps / 8f, 0.4f, 1.6f);
        float speedForDamp = Mth.clamp(0.5f + speedMps / 10f, 0.5f, 1.5f);
        float centerTerm = -clientSteer * 0.6f * profile.spring() * speedForForce;      // 指向绝对 0（中心）
        float dampTerm = -Mth.clamp(delta * 6f, -1f, 1f) * 0.8f * profile.damper() * speedForDamp;
        int steerForce = Math.round((centerTerm + dampTerm) * gain);
        constant = Mth.clamp(constant + steerForce, -10000, 10000);
        int spring = 0;   // 停用 SDL SPRING 通道（设备行为不可靠）
        int damper = 0;   // 停用 SDL DAMPER 通道（方向反了会变成负阻尼）

        // ④ 路面纹理：脚下方块粗糙度 × 车速
        float roughness = groundRoughness();
        int sine = Math.round(roughness * Math.min(1f, speedMps / 8f) * gain * 0.35f * profile.road());
        int period = Math.round(Mth.clamp(70f - speedMps * 4f, 25f, 80f));

        // ⑤ 垂向冲击（颠簸 / 落地 / 上下坡顶底）：垂向加速度的【突变】(jerk) → 惯性力脉冲，
        //    随后指数衰减（约 0.4 秒回落）—— 即"过坡顶一轻、落地一沉"的手感。
        float verticalAccel = accelVec != null
                ? (float) accelVec.y * PeripheralHelmMotion.BLOCKS_PER_TICK2_TO_MPS2 : 0f;
        float jerk = Math.abs(verticalAccel - this.lastVerticalAccel);
        this.lastVerticalAccel = verticalAccel;
        float impulseTarget = Mth.clamp(jerk / Math.max(1f, binding.ffbFullAccel() * 1.5f), 0f, 1f);
        this.impulse = Math.max(impulseTarget, this.impulse * 0.72f);
        int inertia = Math.round(this.impulse * gain * 0.9f * profile.impulse());

        PeripheralHelmInput.requestChannels(binding.deviceId(), userKey(),
                new PeripheralHelmInput.ForceChannels(constant, spring, damper, sine, period, inertia));

        // UI 显示用：取各通道里最强的那个（G 力用阈值映射，其余按总增益归一）
        float gForce = latMps2 > 0f ? binding.forceStrengthFor(latMps2) : 0f;
        float other = Math.max(Math.abs(steerForce),
                Math.max(spring, Math.max(damper, Math.max(sine, inertia)))) / (float) gain;
        this.forceStrength = Mth.clamp(Math.max(gForce, other), 0f, 1f);
    }

    /**
     * 手感预设：各力反馈通道的比例因子（总增益仍由"力反馈强度"决定）。
     *
     * @param g       侧向 G（对齐力矩）
     * @param spring  回中弹簧
     * @param damper  阻尼
     * @param road    路面纹理
     * @param impulse 颠簸/上下坡冲击
     */
    private record FeelProfile(float g, float spring, float damper, float road, float impulse) {
    }

    private static FeelProfile profileOf(int preset) {
        return switch (preset) {
            // 拉力：路感与 G 力最强、回中偏轻（砂石路面信息量最大）
            case 1 -> new FeelProfile(1.25f, 0.45f, 0.9f, 0.55f, 1.2f);
            // 重卡：回中沉重、几乎滤掉路面细碎振动
            case 2 -> new FeelProfile(0.8f, 1.0f, 0.7f, 0.15f, 0.8f);
            // 细腻：保留 G 力与阻尼，几乎无振动（公路巡航）
            case 3 -> new FeelProfile(1.0f, 0.6f, 0.8f, 0.08f, 0.6f);
            // 均衡（默认）
            default -> new FeelProfile(1.0f, 0.6f, 0.8f, 0.35f, 1.0f);
        };
    }

    /** 脚下方块"路面粗糙度"（0..1）：沙/砾/草=粗，木板/石=中，金属/光滑=细 */
    private float groundRoughness() {
        try {
            net.minecraft.world.level.block.state.BlockState below =
                    level.getBlockState(worldPosition.below());
            if (below.isAir()) {
                return 0f;
            }
            String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(below.getBlock()).getPath();
            if (name.contains("sand") || name.contains("gravel") || name.contains("dirt")
                    || name.contains("grass") || name.contains("farmland") || name.contains("snow")
                    || name.contains("soul")) {
                return 0.9f;
            }
            if (name.contains("stone") || name.contains("deepslate") || name.contains("cobble")
                    || name.contains("planks") || name.contains("log") || name.contains("brick")
                    || name.contains("concrete")) {
                return 0.45f;
            }
            if (name.contains("metal") || name.contains("iron") || name.contains("steel")
                    || name.contains("smooth")) {
                return 0.15f;
            }
            return 0.3f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 本方块在设备池里的使用者 key（一个方块一份绑定） */
    public String userKey() {
        return "block:" + worldPosition.asLong();
    }

    /** 非驱动者：把同步来的目标角度平滑到渲染角度（旁观者能看到舵轮转动） */
    private void handler$renderSyncedAngle() {
        // 驱动者本人：用异步核心算出的目标角度插值；其它客户端：用服务端同步来的 targetAngle
        float target = isLocalDriver() && !binding.deviceId().isEmpty()
                ? clientWantedAngle
                : targetAngle;
        this.renderAngle += Mth.wrapDegrees(target - renderAngle) * 0.25f;
    }

    // ==================== 客户端采样工具 ====================

    /** 死区：|v| &lt;= deadzone 视为 0，之后线性重映射到 0..1（无跳变） */
    public static float applyDeadzone(float v, float deadzone) {
        float dz = Mth.clamp(deadzone, 0f, 0.9f);
        if (dz <= 0f) {
            return Mth.clamp(v, -1f, 1f);
        }
        float a = Math.abs(v);
        if (a <= dz) {
            return 0f;
        }
        float scaled = (a - dz) / (1f - dz);
        return Math.copySign(Mth.clamp(scaled, 0f, 1f), v);
    }

    // ==================== 服务端接口 ====================

    /** 服务端：接收客户端舵角（写入命令目标，转由 updateTargetAngle 推进） */
    public void setClientAngle(float angleDegrees) {
        float limit = Math.abs(binding.maxAngleDeg());
        float clamped = Mth.clamp(angleDegrees, -limit, limit);
        boolean changed = Math.abs(clamped - this.targetAngleToUpdate) >= SYNC_EPSILON;
        this.targetAngleToUpdate = clamped;
        // 限流广播：让【其它客户端】也能看到舵轮转动（驱动者本人忽略同步值，用自己的本地采样）
        if (changed && --broadcastCooldown <= 0) {
            this.broadcastCooldown = 4;
            this.sendData();
        }
    }

    /**
     * 方向性模拟输出 —— 与航空学官方 `SteeringWheelBlock.getAnalogOutputSignalFrom` <b>同语义</b>：
     * <ul>
     *   <li><b>朝向面</b>（{@code facing == side}）：有外部设备在驱动 ⇒ 15（对应官方"玩家正握着舵轮"的 held），
     *       否则 0；</li>
     *   <li><b>顺时针面</b>：右舵（frac &gt; 0）时给出 1..15；</li>
     *   <li><b>逆时针面</b>：左舵（frac &lt; 0）时给出 1..15；</li>
     *   <li>回正（|angle| &lt; 0.99 或 |frac| ≈ 0）⇒ 两侧都是 0，谁都不输出。</li>
     * </ul>
     * ⚠ MC 的比较器只读【单值】（{@link #analogOutput()}），不区分方向；本方法是给"按方向读取"的
     * 系统（其它子包 / 未来的定向信号读取器）用的，方块侧入口见
     * {@link PeripheralHelmBlock#getAnalogSignalFrom}。
     */
    public int analogOutputFor(Direction side) {
        if (side == null || level == null) {
            return 0;
        }
        Direction facing = getBlockState().getValue(PeripheralHelmBlock.FACING);
        float limit = Math.max(1f, Math.abs(binding.maxAngleDeg()));
        // ⚠ 用【最新命令角度】而不是机械轴已转到的 targetAngle：红石要跟设备同步，
        //   等机械慢慢转到位（16 RPM）的话比较器输出会滞后一大截。
        float frac = Mth.clamp(commandAngle() / limit, -1f, 1f);

        if (facing == side) {
            // 官方此处是 be.held（玩家握着舵轮）；外设船舵用"已绑定外部设备"等价表示有人在操作
            return binding.deviceId().isEmpty() ? 0 : 15;
        }
        // 回正（舵量≈0）→ 左右两侧都不输出
        if (Math.abs(frac) < 1.0E-3f) {
            return 0;
        }
        // 0..最大转角 → 0..15 红石信号（线性映射，四舍五入）
        int signal = Mth.clamp(Math.round(Math.abs(frac) * 15f), 0, 15);
        if (signal <= 0) {
            return 0;
        }
        // 右舵（正）→ 顺时针面；左舵（负）→ 逆时针面。面是否"右"取决于玩家面向 facing 时的右手方向，
        // 与官方 SteeringWheelBlock 的取值一致；若实测左右相反，把下面两行的两个面互换即可。
        if (frac > 0f && facing.getClockWise() == side) {
            return signal;
        }
        if (frac < 0f && facing.getCounterClockWise() == side) {
            return signal;
        }
        return 0;
    }

    /** 是否有外部设备在驱动（供方向性输出的"朝向面"判据与其它系统查询） */
    public boolean hasBoundDevice() {
        return !binding.deviceId().isEmpty();
    }

    /** MC 比较器单值模拟输出：舵角 → 0..15（回正 = 8，左舵 &lt; 8 &lt; 右舵）；同样取最新命令角度 */
    public int analogOutput() {
        float limit = Math.max(1f, Math.abs(binding.maxAngleDeg()));
        float norm = Mth.clamp(commandAngle() / limit, -1f, 1f);
        return Mth.clamp(Math.round(8f + norm * 7f), 0, 15);
    }

    /**
     * 红石/比较器用的<b>最新命令角度</b>：客户端每 tick 上发的目标（服务端 {@link #setClientAngle} 写入）。
     * <p>它与"机械轴实际转到的角度"（{@link #targetAngle} / {@link #angle}）刻意分开：
     * 机械按固定转速慢慢转，而红石必须紧跟外部设备，否则快速打舵时输出跟不上。
     */
    private float commandAngle() {
        return targetAngleToUpdate;
    }

    /** 绑定归属玩家（null = 未限定） */
    public java.util.UUID getOwner() {
        return owner;
    }

    /** 记录绑定归属（服务端在收到玩家的绑定保存时调用） */
    /** 绑定归属玩家（null = 未限定）；会话上行只接受归属玩家自己的客户端数据。 */
    public java.util.UUID owner() {
        return owner;
    }

    public void setOwner(java.util.UUID owner) {
        this.owner = owner;
        this.setChanged();
        this.notifyUpdate();
        if (level != null && !level.isClientSide) {
            this.sendData();
        }
    }

    /**
     * 本客户端是否负责驱动（采样 + 上发）。
     * <p>无归属 → true（单人/未绑定者语义）；有归属 → 仅归属玩家本人。
     * 设备在玩家自己的机器上，其它客户端既不该枚举设备也不该抢着发舵角。
     */
    private boolean isLocalDriver() {
        if (owner == null) {
            return true;
        }
        try {
            net.minecraft.client.player.LocalPlayer player =
                    net.minecraft.client.Minecraft.getInstance().player;
            return player != null && owner.equals(player.getUUID());
        } catch (Throwable t) {
            return false;   // 专用服务端/异常 → 不驱动
        }
    }

    /** 舵轮木料（BER / Flywheel visual 按它重建舵轮模型） */
    public BlockState getMaterial() {
        return material;
    }

    /** 手持物是否可用于更换木料（必须是木板类方块，且与当前不同） */
    public boolean isMaterialValid(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState candidate = blockItem.getBlock().defaultBlockState();
        if (candidate == this.material) {
            return false;
        }
        return candidate.is(BlockTags.PLANKS);
    }

    /** 更换舵轮木料（客户端直接 SUCCESS 让本地立即重建模型；服务端写字段 + 同步追踪玩家） */
    public ItemInteractionResult applyMaterialIfValid(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !isMaterialValid(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level != null && level.isClientSide && !isVirtual()) {
            return ItemInteractionResult.SUCCESS;
        }
        BlockState previous = this.material;
        this.material = blockItem.getBlock().defaultBlockState();
        this.setChanged();
        this.notifyUpdate();
        if (level != null) {
            level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, worldPosition, Block.getId(previous));
        }
        return ItemInteractionResult.SUCCESS;
    }

    public PeripheralHelmBinding getBinding() {
        return binding;
    }

    public void setBinding(PeripheralHelmBinding binding) {
        this.binding = binding == null ? PeripheralHelmBinding.DEFAULT : binding;
        if (level != null && level.isClientSide) {
            // 客户端：在设备池里申请绑定（独占；旧使用者自动退出）——O(1)，无 SDL 调用；
            // 设备 id 为空 = 断开 → 直接释放（否则 bind("") 会提前返回，旧绑定残留）
            if (this.binding.deviceId().isEmpty()) {
                PeripheralHelmInput.releaseUser(userKey());
            } else {
                PeripheralHelmInput.bind(this.binding.deviceId(), userKey());
            }
        }
        this.setChanged();
        this.notifyUpdate();
        if (level != null && !level.isClientSide) {
            this.sendData();
        }
    }

    /** 渲染用插值角度（度） */
    public float getRenderAngle(float partialTick) {
        return Mth.lerp(partialTick, prevRenderAngle, renderAngle);
    }

    /**
     * 渲染包围盒放大 0.4 格（官方船舵同款）：舵轮模型伸出方块本体，不放大时在视锥边缘会被剔除、
     * 表现为"转个视角舵轮突然消失"。
     */
    @Override
    public net.minecraft.world.phys.AABB createRenderBoundingBox() {
        return super.createRenderBoundingBox().inflate(0.4);
    }

    /** 转动音效（官方 SteeringWheelBlockEntity.tickAudio 同款：每越过一根辐条（45°）响一次） */
    private int lastPlayedIncrement = 0;

    @Override
    public void tickAudio() {
        super.tickAudio();
        if (level == null || !level.isClientSide) {
            return;
        }
        int increment = (int) Math.floor(getRenderAngle(0f) / 45f);
        if (lastPlayedIncrement != increment) {
            int spokeCrossed = increment;
            if (lastPlayedIncrement - increment > 0) {
                spokeCrossed++;
            }
            int travel = (int) Math.signum(lastPlayedIncrement - increment) * 4;
            if (spokeCrossed != travel) {
                switch (spokeCrossed) {
                    case -4, 4 -> com.simibubi.create.AllSoundEvents.CRANKING
                            .playAt(level, worldPosition, 1.25f, 0.85f, true);
                    case 0 -> com.simibubi.create.AllSoundEvents.CRANKING
                            .playAt(level, worldPosition, 1.25f, 0.5f, true);
                    default -> com.simibubi.create.AllSoundEvents.CRANKING
                            .playAt(level, worldPosition, 0.5f, 1.25f, true);
                }
            }
            lastPlayedIncrement = increment;
        }
    }

    public float getTargetAngle() {
        return targetAngle;
    }

    public float getOutputAngle() {
        return angle;
    }

    // ==================== 会话：服务端下发的 sable 结构数据 ====================

    /** 服务端最近一次下发的结构运动数据（{@code serverSableTick < 0} = 尚未收到） */
    private float serverSpeedMps;
    private float serverAccelMps2;
    private Vec3 serverAccelVec = Vec3.ZERO;
    private Vec3 serverVelocityVec = Vec3.ZERO;
    private int serverSableTick = -1;

    /**
     * 会话下行：写入服务端采集的结构运动数据（由 {@code PeripheralSablePayload} 在客户端调用）。
     * <p>力反馈优先使用这份数据 —— 它是服务端物理结构的权威状态。
     */
    public void applySableData(float speedMps, float accelMps2, float ax, float ay, float az,
                               float vx, float vy, float vz, int tick) {
        this.serverSpeedMps = speedMps;
        this.serverAccelMps2 = accelMps2;
        this.serverAccelVec = new Vec3(ax, ay, az);
        this.serverVelocityVec = new Vec3(vx, vy, vz);
        this.serverSableTick = tick;
    }

    /** 是否已收到服务端的 sable 数据（界面诊断用） */
    public boolean hasServerSableData() {
        return serverSableTick >= 0;
    }

    /** 客户端：当前 8 轴原始值（诊断用；未采样时为空数组） */
    public float[] getClientAxes() {
        return clientAxes == null ? new float[0] : clientAxes;
    }

    /** 客户端当前使用的转向轴索引（诊断显示用） */
    public int getSteerAxisIndex() {
        return Mth.clamp(binding.steerAxis(), 0, 7);
    }

    public float getClientSteer() {
        return clientSteer;
    }

    public boolean isClientDeviceConnected() {
        return clientDeviceConnected;
    }

    /** 结构加速度（m/s²；UI 显示） */
    public float getStructureAccelMps2() {
        return structureAccelMps2;
    }

    /** 当前力反馈强度（0..1；UI 显示） */
    public float getForceStrength() {
        return forceStrength;
    }

    // ==================== 生命周期 ====================

    /**
     * 方块被移除 / 区块卸载（客户端）：若本方块正持有该外部设备 → 释放采样线程与设备句柄。
     * <p>⚠ 不能覆写 {@code setRemoved()}——Create 的 {@code SmartBlockEntity} 把它标成 final
     * （实测编译错误："无法覆盖 ... 被覆盖的方法为 final"），改用 destroy/onChunkUnloaded。
     */
    @Override
    public void destroy() {
        releaseDeviceOnClient();
        super.destroy();
    }

    @Override
    public void onChunkUnloaded() {
        releaseDeviceOnClient();
        super.onChunkUnloaded();
    }

    /** 客户端：方块进入世界即注册到异步核心（核心按固定频率串行遍历计算）。 */
    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide) {
            PeripheralCore.register(worldPosition.asLong(), this);
        }
    }

    private void releaseDeviceOnClient() {
        if (level != null && level.isClientSide) {
            PeripheralCore.unregister(worldPosition.asLong());
            PeripheralSessionClient.forget(worldPosition);
            // 释放本方块在设备池里的绑定（设备若无其它使用者 → 后台线程关闭它）
            PeripheralHelmInput.releaseUser(userKey());
        }
    }

    // ==================== 持久化 / 同步 ====================

    /**
     * 进世界时的区块同步：把完整客户端数据（含绑定表）写进 update tag。
     * <p>绑定表是外设驱动的唯一凭据：客户端拿不到它就不会去设备池申请引用，
     * 表现就是"重进世界后舵轮不再跟随方向盘"。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (tag != null) {
            write(tag, registries, true);
        }
        return tag;
    }

    /**
     * 存档只保存<b>固定化的参数</b>（用户 2026-09-14："保存只保存必要的固定化的参数"）：
     * <pre>
     *   Binding              设备绑定（轴/死区/反转/按键…）—— 用户配置
     *   Owner                归属玩家
     *   Material             船舵外观材质 —— 用户用扳手选的
     *   SequencedAngleLimit  序列角度上限 —— 用户设置
     * </pre>
     * 舵角与运行态（{@code Angle} / {@code TargetAngle} / {@code TargetAngleToUpdate} /
     * {@code InUse} / {@code GeneratedSpeed}）<b>不落盘</b>，只随 {@code clientPacket} 同步包走 ——
     * 客户端要它们渲染，存档不要它们：重进世界舵角从 0 起，由外部设备数据重建。
     * 磁盘路径下这些键根本不存在，{@code read} 里 {@code getFloat} 自然得到 0。
     */
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Binding", binding.save());
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.put("Material", NbtUtils.writeBlockState(material));
        tag.putDouble("SequencedAngleLimit", sequencedAngleLimit);
        if (clientPacket) {
            tag.putFloat("Angle", angle);
            tag.putFloat("TargetAngle", targetAngle);
            tag.putFloat("TargetAngleToUpdate", targetAngleToUpdate);
            tag.putInt("InUse", inUse);
            tag.putFloat("GeneratedSpeed", generatedSpeed);
            tag.putFloat("ClientSteer", clientSteer);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.binding = PeripheralHelmBinding.load(tag.getCompound("Binding"));
        if (tag.contains("Material")) {
            BlockState read = NbtUtils.readBlockState(this.blockHolderGetter(), tag.getCompound("Material"));
            if (!read.isAir()) {
                this.material = read;
            }
        }
        // 运行态只在客户端同步包里存在（存档不写）⇒ 磁盘路径下这些 getFloat 全部得到 0，
        // 即"重进世界舵角归零"，随后由客户端异步核心按设备实际位置重建。
        this.angle = tag.getFloat("Angle");
        this.targetAngle = tag.getFloat("TargetAngle");
        this.targetAngleToUpdate = tag.contains("TargetAngleToUpdate")
                ? tag.getFloat("TargetAngleToUpdate") : this.targetAngle;
        this.inUse = tag.getInt("InUse");
        this.sequencedAngleLimit = tag.getDouble("SequencedAngleLimit");
        this.generatedSpeed = tag.getFloat("GeneratedSpeed");
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        if (!isLocalDriver()) {
            // 非驱动者（含专用服务端）：直接采用服务端同步的角度
            this.renderAngle = this.targetAngle;
            this.prevRenderAngle = this.targetAngle;
        }
        if (clientPacket) {
            this.clientSteer = tag.getFloat("ClientSteer");
            if (isLocalDriver()) {
                // 设备在玩家自己机器上：只有驱动者客户端去池里申请（独占，旧使用者退出）
                PeripheralHelmInput.bind(this.binding.deviceId(), userKey());
            } else {
                PeripheralHelmInput.releaseUser(userKey());
            }
        }
    }
}

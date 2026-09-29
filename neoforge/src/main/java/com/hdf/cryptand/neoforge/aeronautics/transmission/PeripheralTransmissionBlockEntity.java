/**
 * ===== 外设模拟传动器方块实体（2026-09-14）=====
 *
 * **忠实复制原版** `simulated:analog_transmission`（"航空学"的 modid 就是 simulated）的
 * {@code ExtraKinetics} 双 KBE 结构 —— 用户定稿："最好使用原版的"。
 *
 * <h2>双 KBE 在这里做什么</h2>
 * Create 的 {@code RotationPropagator} 按【方块坐标】工作（一个坐标 = 一个动力学节点）。
 * simulated 的 mixin 让实现了 {@link ExtraKinetics} 的方块在传播器里占据<b>两个</b>节点
 * （本体 + {@link Cogwheel}），两者之间靠 {@link #propagateRotationTo} 交换转速 ——
 * 于是"传动杆"与"齿轮"可以有<b>不同转速</b>，甚至<b>完全分离成两个独立网络</b>
 * （原版 Ponder："在满信号输入或传动杆的速度将要超过转速上限时，传动杆和齿轮会分离……
 * 然后可以各自独立旋转"）。这套机制全部由 simulated 自己的 mixin 提供，
 * <b>我们一行 Mixin 都不用写</b>。
 *
 * <h2>与原版的唯一差异</h2>
 * 原版传动比来自红石信号：`modifier = 1 - (signal + 1) / 16f`；
 * 外设版来自外部设备轴经"输入范围 + 可编辑曲线"算出的 {@link #targetRatio}：
 * <pre>
 *   轴原始值 → t = (raw-inMin)/(inMax-inMin) → curve(t) → modifier（0%..100%）
 * </pre>
 * 0 = 分离（原版满信号的行为），1 = 1:1。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralTickable;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.infrastructure.config.AllConfigs;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraBlockPos;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PeripheralTransmissionBlockEntity extends KineticBlockEntity
        implements ExtraKinetics, PeripheralTickable {

    /** 附加动力学节点：内部齿轮（原版 AnalogTransmissionCogwheel 的等价物） */
    private final Cogwheel extraWheel;

    /**
     * 输出转速比例 0..1（0%..100%）—— 直接就是传动比，替代原版的红石 `signal`。
     * <p>0 = 分离；1 = 1:1。由客户端上行（{@link #setRatioFromDevice}）写入。
     */
    private float targetRatio = 0f;

    /** 超速：反向后速度将超过 Create 上限 ⇒ 分离（原版同款保护） */
    private boolean oversaturated = false;

    private PeripheralTransmissionBinding binding = PeripheralTransmissionBinding.DEFAULT;
    private UUID owner;

    private volatile boolean clientDeviceConnected;
    private volatile float clientAxisRaw;
    private volatile float clientRatio;

    public PeripheralTransmissionBlockEntity(BlockPos pos, BlockState state) {
        super(PeripheralTransmissionRegistry.PERIPHERAL_TRANSMISSION_BE.get(), pos, state);
        this.extraWheel = new Cogwheel(PeripheralTransmissionRegistry.PERIPHERAL_TRANSMISSION_BE.get(),
                new ExtraBlockPos(pos), state, this);
    }

    // ==================== ExtraKinetics（双 KBE 接线） ====================

    @Override
    public @NotNull KineticBlockEntity getExtraKinetics() {
        return this.extraWheel;
    }

    @Override
    public boolean shouldConnectExtraKinetics() {
        return true;
    }

    @Override
    public String getExtraKineticsSaveName() {
        return "ExtraCogwheel";
    }

    // ==================== 传动比 ====================

    /** 传动比（原版 `getRotationModifier` 的外设版：曲线输出直接就是比例） */
    public float getRotationModifier() {
        return Mth.clamp(targetRatio, 0f, 1f);
    }

    /**
     * 原版同款：本体 ⇄ 附加齿轮之间按传动比交换转速。
     * <ul>
     *   <li>朝齿轮方向（target == extraWheel）⇒ <b>减速</b> × modifier；</li>
     *   <li>反向（target == this）⇒ <b>加速</b> × 1/modifier，并做超速保护；</li>
     *   <li>modifier == 0 ⇒ 返回 0 = 两边分离，各自独立旋转。</li>
     * </ul>
     */
    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom,
                                     BlockState stateTo, BlockPos diff,
                                     boolean connectedViaAxes, boolean connectedViaCogs) {
        float modifier = getRotationModifier();
        if (target == this.extraWheel) {
            if (modifier <= 0f) {
                return 0f;
            }
            return oversaturated ? 0f : modifier;
        }
        if (target == this) {
            if (modifier <= 1.0E-4f) {
                return 0f;
            }
            float boost = 1f / modifier;
            if (Math.abs(this.extraWheel.getTheoreticalSpeed() * boost)
                    > AllConfigs.server().kinetics.maxRotationSpeed.get()) {
                this.oversaturated = true;
                return 0f;
            }
            this.oversaturated = false;
            return boost;
        }
        return 0f;
    }

    @Override
    public boolean isOverStressed() {
        if (level != null && level.isClientSide) {
            return this.oversaturated || this.overStressed;
        }
        return super.isOverStressed();
    }

    /** 会话上行到达（服务端）：写入权威比例 */
    public void setRatioFromDevice(float ratio) {
        float clamped = Mth.clamp(ratio, 0f, 1f);
        if (Math.abs(clamped - targetRatio) < 1.0E-4f) {
            return;
        }
        this.targetRatio = clamped;
        setChanged();
        sendData();
    }

    public float getTargetRatio() {
        return targetRatio;
    }

    public void setClientSample(boolean connected, float axisRaw, float ratio) {
        this.clientDeviceConnected = connected;
        this.clientAxisRaw = axisRaw;
        this.clientRatio = ratio;
    }

    public boolean isClientDeviceConnected() {
        return clientDeviceConnected;
    }

    public float getClientAxisRaw() {
        return clientAxisRaw;
    }

    public float getClientRatio() {
        return clientRatio;
    }

    // ==================== tick ====================

    /** 上次重建接线时生效的传动比（-1 = 本会话还没接过线） */
    private float appliedRatio = -1f;

    @Override
    public void tick() {
        // ★ 传动比是【接线参数】，不是缓存值：Create 的速度只在接线/传播那一刻按
        //   propagateRotationTo 求值，光改 targetRatio 不会让已接好的网络重算。
        //   原版 AnalogTransmissionBlockEntity#tick 因此在比例变化时重建接线
        //   （detachKinetics + extraWheel.detachKinetics + removeSource ×2 + attachKinetics ×2）。
        //   外设版的比例由客户端实时上行，同样必须重建 —— 否则传动比改了、输出轴转速纹丝不动
        //   （用户实测："测试转速无法调节"）。两端都做：客户端网络也要按新比例重算才能显示对。
        if (level != null) {
            float effective = getRotationModifier();
            if (Math.abs(effective - appliedRatio) > 1.0E-4f) {
                appliedRatio = effective;
                rebuildKinetics();
            }
        }
        // 附加齿轮必须跟着 tick（原版同款），否则它的动力学不推进。
        this.extraWheel.tick();
        super.tick();
    }

    /** 原版同款：拆掉本体与附加齿轮的接线，再用新传动比接回去（网络随即重算速度） */
    private void rebuildKinetics() {
        detachKinetics();
        this.extraWheel.detachKinetics();
        removeSource();
        this.extraWheel.removeSource();
        attachKinetics();
        this.extraWheel.attachKinetics();
    }

    // ==================== 绑定 ====================

    public PeripheralTransmissionBinding getBinding() {
        return binding;
    }

    public void setBinding(PeripheralTransmissionBinding value) {
        this.binding = value == null ? PeripheralTransmissionBinding.DEFAULT : value;
        setChanged();
        sendData();
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID value) {
        this.owner = value;
        setChanged();
    }

    // ==================== 持久化 ====================
    // 只存固定化参数（绑定）；targetRatio 是临时值，重进世界从 0 起，由客户端按设备位置立刻重建。

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("Binding", binding.save());
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (clientPacket) {
            tag.putFloat("Ratio", targetRatio);
            tag.putBoolean("Oversaturated", oversaturated);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Binding")) {
            this.binding = PeripheralTransmissionBinding.load(tag.getCompound("Binding"));
        }
        this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        this.targetRatio = clientPacket ? tag.getFloat("Ratio") : 0f;
        this.oversaturated = clientPacket && tag.getBoolean("Oversaturated");
    }

    @Override
    public AABB getRenderBoundingBox() {
        return AABB.ofSize(this.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }

    // ==================== 客户端异步核心 ====================

    @Override
    public void coreTick(long nowMs) {
        PeripheralTransmissionInput.tickClient(this);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide) {
            PeripheralCore.register(worldPosition.asLong(), this);
        }
    }

    @Override
    public void destroy() {
        releaseOnClient();
        super.destroy();
    }

    @Override
    public void onChunkUnloaded() {
        releaseOnClient();
        super.onChunkUnloaded();
    }

    private void releaseOnClient() {
        if (level != null && level.isClientSide) {
            PeripheralCore.unregister(worldPosition.asLong());
            com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient.forget(worldPosition);
            PeripheralTransmissionInput.forget(worldPosition);
            com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                    .releaseUser(PeripheralTransmissionInput.userKey(worldPosition));
        }
    }

    /**
     * 附加动力学节点（内部齿轮）—— 原版 {@code AnalogTransmissionCogwheel} 的等价物。
     * <p>坐标用 {@link ExtraBlockPos} 标记，simulated 的 mixin 据此把它识别为"同一方块里的第二个 KBE"。
     */
    public static class Cogwheel extends KineticBlockEntity
            implements ExtraKinetics.ExtraKineticsBlockEntity {

        /** 附加节点的机械形态：沿 AXIS 旋转、但不像轴那样朝任何面伸轴（原版同款） */
        public static final ICogWheel EXTRA_COGWHEEL_CONFIG = new ICogWheel() {
            @Override
            public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state,
                                           Direction face) {
                return false;
            }

            @Override
            public Direction.Axis getRotationAxis(BlockState state) {
                return state.getValue(PeripheralTransmissionBlock.AXIS);
            }
        };

        private final KineticBlockEntity parentBlockEntity;

        public Cogwheel(BlockEntityType<?> type, ExtraBlockPos pos, BlockState state,
                        KineticBlockEntity parentBlockEntity) {
            super(type, pos, state);
            this.parentBlockEntity = parentBlockEntity;
        }

        @Override
        public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom,
                                         BlockState stateTo, BlockPos diff,
                                         boolean connectedViaAxes, boolean connectedViaCogs) {
            return this.parentBlockEntity.propagateRotationTo(target, stateFrom, stateTo, diff,
                    connectedViaAxes, connectedViaCogs);
        }

        @Override
        protected boolean canPropagateDiagonally(IRotate block, BlockState state) {
            return true;
        }

        @Override
        public KineticBlockEntity getParentBlockEntity() {
            return this.parentBlockEntity;
        }
    }
}

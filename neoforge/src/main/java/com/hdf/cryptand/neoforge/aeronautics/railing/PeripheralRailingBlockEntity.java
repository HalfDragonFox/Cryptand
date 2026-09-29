/**
 * ===== 外设栏杆（按钮）方块实体（2026-09-13） =====
 *
 * 与"外设拉杆"共用同一套输出语义（红石信号源 0..15、变化才写、扳手反转、音效），
 * 区别只在<b>驱动方式</b>：拉杆是一个连续轴映射成档位，栏杆是<b>按键映射表</b> ——
 * 一组按键 ⇒ 一个信号（可多组，用于换挡器）。
 *
 * <p>计算在客户端异步核心（{@link com.hdf.cryptand.neoforge.aeronautics.PeripheralCore}）里执行；
 * 主线程只做视觉插值。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.hdf.cryptand.neoforge.aeronautics.PeripheralCore;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralTickable;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

public class PeripheralRailingBlockEntity extends SmartBlockEntity implements PeripheralTickable {

    /** 输出信号 0..15（= 红石强度） */
    protected int state = 0;
    /** 邻居更新延时（防抖） */
    protected int lastChange;
    /** 客户端视觉插值 */
    protected final LerpedFloat clientAngle;

    /** 按键映射表绑定 */
    private PeripheralRailingBinding binding = PeripheralRailingBinding.DEFAULT;
    /** 归属玩家（只有他的客户端能驱动这个栏杆） */
    private UUID owner;
    /** 客户端：设备是否连通（界面显示用） */
    private volatile boolean clientDeviceConnected = false;

    public PeripheralRailingBlockEntity(BlockPos pos, BlockState state) {
        super(PeripheralRailingRegistry.PERIPHERAL_RAILING_BE.get(), pos, state);
        this.clientAngle = LerpedFloat.linear();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 无 Create 行为（纯红石输出 + 外设输入）
    }

    /** 本次加载是否已执行"进入世界归位"（见 {@link #resetOnEnter()}） */
    private boolean entryResetDone;

    @Override
    public void tick() {
        super.tick();
        if (!entryResetDone) {
            entryResetDone = true;
            resetOnEnter();
        }
        if (lastChange > 0) {
            lastChange--;
            if (lastChange == 0) {
                updateOutput();
            }
        }
        if (level != null && level.isClientSide) {
            // 主线程只做视觉插值；采样/匹配计算在客户端异步核心
            clientAngle.tickChaser();
        }
    }

    private void updateOutput() {
        if (level != null) {
            PeripheralRailingBlock.updateNeighbors(getBlockState(), level, worldPosition);
        }
    }

    /**
     * ===== 进入世界归位（2026-09-14 用户要求）=====
     *
     * 用户原话："要求进入时全部归位到0格的情况，强制刷0" + "保存只保存必要的固定化的参数"。
     *
     * <p>信号是<b>临时值</b>（磁盘存档不写 {@code State}，见 {@link #write}），
     * 所以重进世界读回来必然是 0；本方法再无条件把 {@code lastChange} 设为 2，
     * 走官方同一条防抖通道在 2 tick 后通知一次邻居 —— <b>即使原本就是 0 也通知</b>，
     * 不让红石线/灯沿用上一个世界的电平（这就是"强制刷 0"）。
     */
    private void resetOnEnter() {
        int zero = getBlockState().getValue(PeripheralRailingBlock.INVERTED) ? 15 : 0;
        this.state = zero;
        this.lastChange = 2;
        this.clientAngle.chase(zero, 0.5f, LerpedFloat.Chaser.EXP);
        if (level != null && !level.isClientSide) {
            setChanged();
            sendData();
        }
    }

    /** 进世界时的区块同步：把完整客户端数据（含按键映射表）写进 update tag，见拉杆同名方法。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (tag != null) {
            write(tag, registries, true);
        }
        return tag;
    }

    // ==================== 核心线程 ====================

    @Override
    public void coreTick(long nowMs) {
        PeripheralRailingInput.tickClient(this);
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
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
            PeripheralRailingInput.forget(worldPosition);
            com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                    .releaseUser(PeripheralRailingInput.userKey(worldPosition));
        }
    }

    // ==================== 信号输出 ====================

    public int getState() {
        return state;
    }

    /** 官方语义：设定信号（音效 + 同步 + 延迟通知邻居） */
    public void setSignal(int signal) {
        int clamped = Mth.clamp(signal, 0, 15);
        this.state = getBlockState().getValue(PeripheralRailingBlock.INVERTED)
                ? 15 - clamped : clamped;
        this.lastChange = 2;
        if (level != null) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.2F,
                    0.25F + (this.state + 5) / 15.0F * 0.5F);
        }
        setChanged();
        sendData();
    }

    /** 外设驱动入口：信号没变化就什么都不做（不刷音效、不发包） */
    public void setSignalFromDevice(int signal) {
        int clamped = Mth.clamp(signal, 0, 15);
        int target = getBlockState().getValue(PeripheralRailingBlock.INVERTED)
                ? 15 - clamped : clamped;
        if (target == this.state) {
            return;
        }
        setSignal(clamped);
        // ★ 立即刷新红石（同拉杆）：外设驱动已做"变化才写"的去重，不必再叠官方 2 tick 防抖。
        //   只在服务端做 —— 客户端调用来自异步核心线程，碰 Level 违反"主线程 = 纯同步"。
        if (level != null && !level.isClientSide) {
            this.lastChange = 0;
            updateOutput();
        }
    }

    /** 客户端视觉角度（0..15，含插值） */
    public float getClientAngle(float partialTicks) {
        return clientAngle.getValue(partialTicks);
    }

    // ==================== 绑定 ====================

    public PeripheralRailingBinding getBinding() {
        return binding;
    }

    public void setBinding(PeripheralRailingBinding binding) {
        this.binding = binding == null ? PeripheralRailingBinding.DEFAULT : binding;
        setChanged();
        sendData();
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public void setClientDeviceConnected(boolean connected) {
        this.clientDeviceConnected = connected;
    }

    public boolean isClientDeviceConnected() {
        return clientDeviceConnected;
    }

    // ==================== 持久化 ====================

    /**
     * 存档只保存<b>固定化的参数</b>（用户 2026-09-14："保存只保存必要的固定化的参数"）：
     * <ul>
     *   <li>{@code Binding} —— 设备与按键映射表（用户配置）；</li>
     *   <li>{@code Owner} —— 归属玩家。</li>
     * </ul>
     * 输出信号与防抖计时是<b>运行时状态</b>，只随 {@code clientPacket} 同步包走、不落盘 ——
     * 重进世界读回来必然是 0。
     */
    @Override
    public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) {
            compound.putInt("State", this.state);
            compound.putInt("ChangeTimer", this.lastChange);
        }
        compound.put("Binding", binding.save());
        if (owner != null) {
            compound.putUUID("Owner", owner);
        }
        super.write(compound, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        this.state = clientPacket ? compound.getInt("State") : 0;
        this.lastChange = clientPacket ? compound.getInt("ChangeTimer") : 0;
        if (compound.contains("Binding")) {
            this.binding = PeripheralRailingBinding.load(compound.getCompound("Binding"));
        }
        this.owner = compound.hasUUID("Owner") ? compound.getUUID("Owner") : null;
        this.clientAngle.chase(
                getBlockState().getValue(PeripheralRailingBlock.INVERTED) ? 15 - this.state : this.state,
                0.5f, LerpedFloat.Chaser.EXP);
        super.read(compound, registries, clientPacket);
    }

    @Override
    public AABB getRenderBoundingBox() {
        return AABB.ofSize(this.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }
}

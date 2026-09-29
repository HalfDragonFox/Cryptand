/**
 * ===== 外设拉杆方块实体（2026-09-13） =====
 *
 * 语义与航空学（simulated）的 `ThrottleLeverBlockEntity` **完全一致**：
 * <ul>
 *   <li>`state` = 0..15 的档位（NBT `State`），直接作为红石信号强度输出；</li>
 *   <li>改动后 `lastChange = 15`（或 `setSignal` 的 2）tick 再通知邻居 —— 连续推动时不刷屏；</li>
 *   <li>`setSignal(int)` 是"外部设定档位"的官方入口（原本给扳手反转与网络包用）；
 *       外设拉杆正是从这里把**踏板轴/按键**映射出的档位喂进来。</li>
 * </ul>
 * 额外区别（外设场景必需）：{@link #setSignalFromDevice} —— 外设每 tick 都在算档位，
 * 只有档位**真的变化**时才写状态/放音效/发同步包，否则音效与网络包会被刷爆。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

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

public class PeripheralLeverBlockEntity extends SmartBlockEntity
        implements com.hdf.cryptand.neoforge.aeronautics.PeripheralTickable {

    /** 档位 0..15（= 官网拉杆的 state，直接当红石强度输出） */
    protected int state = 0;
    /** 邻居更新延时（防抖） */
    protected int lastChange;
    /** 客户端视觉插值（手柄角度） */
    protected final LerpedFloat clientAngle;

    /** 外部设备绑定（客户端与服务端各存一份；服务端以包里收到的为准） */
    private PeripheralLeverBinding binding = PeripheralLeverBinding.DEFAULT;
    /** 绑定归属玩家（只有他的客户端能驱动这个拉杆，避免别人乱推你的油门） */
    private UUID owner;

    public PeripheralLeverBlockEntity(BlockPos pos, BlockState state) {
        super(PeripheralLeverRegistry.PERIPHERAL_LEVER_BE.get(), pos, state);
        this.clientAngle = LerpedFloat.linear();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 无 Create 行为（纯红石设备 + 外设输入）
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
            // 主线程只做视觉插值；采样/计算已搬到客户端异步核心（PeripheralCore）
            clientAngle.tickChaser();
        }
    }

    private void updateOutput() {
        if (level != null) {
            PeripheralLeverBlock.updateNeighbors(getBlockState(), level, worldPosition);
        }
    }

    /**
     * ===== 进入世界归位（2026-09-14 用户要求）=====
     *
     * 用户原话："外设拉杆退出重进世界后会输出失效，要求进入时全部归位到0格的情况，强制刷0"
     *
     * <p>做法：档位无条件归 0（{@code INVERTED} 时是 15 —— 那是"0 档"在该反转态下的表示），
     * 并把 {@code lastChange} 设为 2 —— 走官方同一条防抖通道，2 tick 后必定调用一次
     * {@link #updateOutput()}，<b>即使原本就是 0 也会通知邻居</b>（这就是"强制刷 0"：
     * 红石线/灯不会因为"值没变"而残留上一次世界的电平）。
     *
     * <p>与"档位不落盘"是配套的两道保险：存档里没有 {@code State} 字段（见 {@link #write}），
     * 所以重进世界本来就只能是 0；本方法再主动刷一次邻居，确保红石线/灯不会因为
     * "值看起来没变"而沿用上一个世界的电平。
     *
     * <p>服务端顺带 {@code sendData()} 一次，把"档位 0 + 绑定表"一起推给客户端：
     * 客户端异步核心正是靠这份绑定表才知道该驱动哪个设备（拿不到 ⇒ 输出失效）。
     */
    private void resetOnEnter() {
        int zero = getBlockState().getValue(PeripheralLeverBlock.INVERTED) ? 15 : 0;
        this.state = zero;
        this.lastChange = 2;
        this.clientAngle.chase(zero, 0.5f, LerpedFloat.Chaser.EXP);
        if (level != null && !level.isClientSide) {
            setChanged();
            sendData();
        }
    }

    /**
     * 进世界时的区块同步：把本方块实体的完整客户端数据（含绑定表）写进 update tag。
     * <p>绑定表是外设驱动的唯一凭据；一旦客户端没拿到它，{@code tickClient} 会认为
     * "未绑定设备"而直接返回 ⇒ 输出失效。这里显式再写一遍（{@code write} 是幂等的 put），
     * 不依赖第三方基类是否同步了自定义字段。
     */
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        net.minecraft.nbt.CompoundTag tag = super.getUpdateTag(registries);
        if (tag != null) {
            write(tag, registries, true);
        }
        return tag;
    }

    // ==================== 档位 ====================

    public int getState() {
        return state;
    }

    /** 官方入口：设定档位（放音效 + 同步 + 延迟通知邻居）。界面绑定与红石/命令都走这里。 */
    public void setSignal(int signal) {
        int clamped = Mth.clamp(signal, 0, 15);
        this.state = getBlockState().getValue(PeripheralLeverBlock.INVERTED) ? 15 - clamped : clamped;
        this.lastChange = 2;
        if (level != null) {
            level.playSound(null, worldPosition, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.2F,
                    0.25F + (this.state + 5) / 15.0F * 0.5F);
        }
        setChanged();
        sendData();
    }

    /**
     * 外设驱动入口：档位没变化就<b>什么都不做</b>（不放音效、不发包）。
     * <p>外设每 tick 都在算档位，若直接用 {@link #setSignal} 会把音效与网络包刷爆。
     */
    public void setSignalFromDevice(int signal) {
        int clamped = Mth.clamp(signal, 0, 15);
        int target = getBlockState().getValue(PeripheralLeverBlock.INVERTED) ? 15 - clamped : clamped;
        if (target == this.state) {
            return;
        }
        setSignal(clamped);
        // ★ 立即刷新红石（2026-09-14 用户实测"红石反应最慢 1~2 秒"的最后一环）：
        //   setSignal 走的是官方 2 tick 防抖（原本为"连续推动时不刷屏"），而外设驱动
        //   在这之前已经做了"变化才写"的去重 ⇒ 再叠一层防抖只是白白多等 100ms。
        //   ⚠ 刻意【只在服务端】做：客户端的那次调用来自异步核心线程（非主线程），
        //     在那边碰 Level / 邻居更新会违反"主线程 = 纯同步"铁律。
        if (level != null && !level.isClientSide) {
            this.lastChange = 0;
            updateOutput();
        }
    }

    /** 逐档调节（官方 `changeState`；手抓取消后暂无调用方，保留供命令/后续使用） */
    public void changeState(boolean back) {
        int prev = this.state;
        this.state = Mth.clamp(this.state + (back ? -1 : 1), 0, 15);
        if (prev != this.state) {
            this.lastChange = 15;
            setChanged();
        }
        sendData();
    }

    /** 客户端视觉角度（0..15，含插值） */
    public float getClientAngle(float partialTicks) {
        return clientAngle.getValue(partialTicks);
    }

    // ==================== 绑定 ====================

    public PeripheralLeverBinding getBinding() {
        return binding;
    }

    public void setBinding(PeripheralLeverBinding binding) {
        this.binding = binding == null ? PeripheralLeverBinding.DEFAULT : binding;
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

    // ==================== 持久化 / 同步 ====================

    @Override
    public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        // ★ 档位是【临时值，不存档】（用户 2026-09-14 定稿："保存时可以设置当前挡位是临时值，
        //   每次进入世界初始化就是0，不保存"）。
        //   clientPacket=false 的磁盘写盘路径因此【不含 State】—— 存档里根本没有这个字段，
        //   重进世界读回来必然是 0（字段默认值），从根上杜绝"停在旧档位"。
        //   clientPacket=true 的同步包仍要带：客户端要拿它渲染手柄角度与界面。
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
        // 档位只在客户端同步包里存在（磁盘存档不写 State）——见 write()。
        this.state = clientPacket ? compound.getInt("State") : 0;
        this.lastChange = clientPacket ? compound.getInt("ChangeTimer") : 0;
        if (compound.contains("Binding")) {
            this.binding = PeripheralLeverBinding.load(compound.getCompound("Binding"));
        }
        this.owner = compound.hasUUID("Owner") ? compound.getUUID("Owner") : null;
        this.clientAngle.chase(
                getBlockState().getValue(PeripheralLeverBlock.INVERTED) ? 15 - this.state : this.state,
                0.5f, LerpedFloat.Chaser.EXP);
        super.read(compound, registries, clientPacket);
    }

    @Override
    public AABB getRenderBoundingBox() {
        return AABB.ofSize(this.getBlockPos().getCenter(), 1.5, 1.5, 1.5);
    }

    /** 客户端异步核心周期：采样设备 → 算档位 → 排上行队列（核心线程，禁止碰 Level/渲染）。 */
    @Override
    public void coreTick(long nowMs) {
        PeripheralLeverInput.tickClient(this);
    }

    /** 客户端：方块进入世界即注册到异步核心。 */
    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide) {
            com.hdf.cryptand.neoforge.aeronautics.PeripheralCore.register(
                    worldPosition.asLong(), this);
        }
    }

    // ==================== 会话：服务端下发的 sable 结构数据 ====================

    private float serverSpeedMps;
    private float serverAccelMps2;
    private int serverSableTick = -1;

    /** 会话下行：写入服务端采集的结构运动数据（由 {@code PeripheralSablePayload} 在客户端调用）。 */
    public void applySableData(float speedMps, float accelMps2, int tick) {
        this.serverSpeedMps = speedMps;
        this.serverAccelMps2 = accelMps2;
        this.serverSableTick = tick;
    }

    public float getServerSpeedMps() {
        return serverSpeedMps;
    }

    public float getServerAccelMps2() {
        return serverAccelMps2;
    }

    public boolean hasServerSableData() {
        return serverSableTick >= 0;
    }

    // ==================== 生命周期：方块被破坏/卸载 → 强制释放设备（照抄外设船舵） ====================

    /**
     * 方块实体被销毁（方块被破坏 / 被替换）时释放本方块在设备池里的引用。
     * <p>⚠ 不能覆写 {@code setRemoved()}：Create 的 {@code SmartBlockEntity} 把它标成 final
     * （实测编译错误："被覆盖的方法为 final"），所以与船舵一样改用 destroy / onChunkUnloaded。
     * <p>不释放的后果（用户实测）：引用计数永不归零 ⇒ 设备句柄不关、**力反馈残留在方向盘上**。
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

    /** 只在本方块所在客户端释放池里的使用者（设备池在客户端；服务端无需处理）。 */
    private void releaseDeviceOnClient() {
        if (level != null && level.isClientSide) {
            com.hdf.cryptand.neoforge.aeronautics.PeripheralCore.unregister(worldPosition.asLong());
            com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient.forget(worldPosition);
            PeripheralLeverInput.forget(worldPosition);
            com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                    .releaseUser(PeripheralLeverInput.userKey(worldPosition));
        }
    }
}

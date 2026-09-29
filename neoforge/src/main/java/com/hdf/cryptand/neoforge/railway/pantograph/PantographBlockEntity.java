package com.hdf.cryptand.neoforge.railway.pantograph;

import com.hdf.cryptand.neoforge.cee.PantographTapLogic;
import com.hdf.cryptand.neoforge.railway.RailwayRegistry;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;

/**
 * 受电弓方块实体（移植自 CEE PantographBlockEntity）。
 * - 三段升弓动画状态（prev/current/target），每 tick 逼近 0.05
 * - extended + color（NBT 持久化，sendData 同步客户端）
 * - 几何计算（getConnectorPos / closestPointOnWire / checkCatenary）供渲染与后续
 *   接触网对齐使用（纯数学，无客户端引用，可安全在服务端类里）
 * <p>
 * M1：只做升弓动画 + 持久化 + 染色。接触网滑触头电气接入见 M2
 * （服务端把触点接入 Cryptand 自管 WireNetwork，客户端经安全通道对齐高度）。
 */
public class PantographBlockEntity extends SmartBlockEntity {
    public float prevExtensionState = 0;
    public float currentExtensionState = 0;
    public float targetExtensionState = 0.85f;
    public boolean extended = true;
    public DyeColor color = DyeColor.WHITE;

    public PantographBlockEntity(BlockPos pos, BlockState state) {
        super(RailwayRegistry.CEE_PANTOGRAPH_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        prevExtensionState = currentExtensionState;
        currentExtensionState = currentExtensionState < targetExtensionState
                ? Math.min(targetExtensionState, currentExtensionState + 0.05f)
                : Math.max(targetExtensionState, currentExtensionState - 0.05f);

        if (level.isClientSide && currentExtensionState == targetExtensionState && prevExtensionState != currentExtensionState)
            level.playLocalSound(worldPosition.getX() + 0.5, worldPosition.getY() + 0.25,
                    worldPosition.getZ() + 0.5, SoundEvents.CHAIN_HIT, SoundSource.NEUTRAL, 0.1f, 1f, false);

        if (!extended) {
            targetExtensionState = 0;
            // 收弓 → 断开滑触头（主线程写缓存 = 发消息；后台构建不再建模受电弓支路）
            if (level != null && !level.isClientSide)
                PantographTapCache.set(worldPosition, null);
            return;
        }

        // M2（2026-08-22 "CEE 全自管电路"）：服务端主线程计算滑触头所在接触网边 + 参数 t
        // → 写 PantographTapCache（主线程同步 = 发消息）；后台 buildContextFromGraph 读缓存把
        // 接触网段动态拆成两半 + 受电弓 0.01Ω 电阻桥接底座↔触点，接入自管电网求解。
        if (level != null && !level.isClientSide)
            handleOnServerCee();
    }

    /**
     * 服务端（主线程）滑触头定位（2026-08-22 "CEE 全自管电路"）：
     *   1) 找【最近的接触网边】（holder↔holder 自管边，无下垂）；水平投影在边上。
     *   2) 三分搜索升弓高度 extension，使滑触板 y 贴合接触网最近点 y（自动对准，
     *      静态放置也能触网——CEE 客户端对齐逻辑移植）。targetExtensionState 随之。
     *   3) 用贴合后的滑触板位置算触点的参数 t → 写 {@link PantographTapCache}（仅消息同步）。
     * 收弓/无接触网 → 清缓存、升到最大（无网时好看）/收（extended=false 已在 tick 处理）。
     */
    private void handleOnServerCee() {
        try {
            // 2026-08-23 统一委托 PantographTapLogic（识别放宽：任何自管导线段
            // 都参与升弓贴合；取电 tap 仅猫天线；含扫描诊断日志）
            PantographTapLogic.serverHandle(
                    level, worldPosition, getBlockState(), extended,
                    v -> targetExtensionState = v);
        } catch (Throwable ignored) {
        }
    }

    /** 三分搜索升弓高度 extension∈[0,2.7]，使滑触板 y 最近接触网。
     *  ⚠ 2026-08-22 对齐 CEE：CEE 的贴合搜索域为 [0, 2.7]（renderer 角度
     *  -75+e*30 / -90+e*27 均按此域设计）；此前我们只用 [0,0.85]/[0,1.7]
     *  → 高接触网时"够不到"、角度与 CEE 不一致。 */
    private float fitExtensionToWire(double contactY) {
        float hi = 2.7f;
        float lo = 0;
        for (int i = 0; i < 20; i++) {
            float m1 = lo + (hi - lo) / 3f;
            float m2 = hi - (hi - lo) / 3f;
            if (Math.abs(getConnectorPos(m1).y - contactY) < Math.abs(getConnectorPos(m2).y - contactY))
                hi = m2;
            else
                lo = m1;
        }
        return (lo + hi) / 2f;
    }

    /**
     * 滑触板（受电端）在世界坐标的位置。基于升弓角度的纯几何计算。
     */
    public Vec3 getConnectorPos() {
        return getConnectorPos(currentExtensionState);
    }

    public Vec3 getConnectorPos(float extensionState) {
        Direction.Axis axis = getBlockState().getValue(FACING).getAxis();
        if (getBlockState().getValue(PantographBlock.DOUBLE)) {
            float lowerArmRadians = (-90 + extensionState * 27) * Mth.DEG_TO_RAD;
            double armHingePosY = Mth.cos(lowerArmRadians) * 1.5 + 0.5;
            double armHingePosX = Mth.sin(lowerArmRadians) * 1.5;

            float b = (float) (-0.4f - Math.abs(armHingePosX));
            float a = Mth.sqrt(-b * b + 2f * 2f);

            double pantographX = getBlockState().getValue(FACING).getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 1;

            Vec3 connectorPlatePos = new Vec3(axis == Direction.Axis.X ? pantographX : 0.5f, 0.1875 + a + armHingePosY, axis == Direction.Axis.Z ? pantographX : 0.5f);
            connectorPlatePos = connectorPlatePos.add(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
            return connectorPlatePos;
        }

        float lowerArmRadians = (-75 + extensionState * 30) * Mth.DEG_TO_RAD;
        double armHingePosY = Mth.cos(lowerArmRadians) * 1.875 + 0.5;
        double armHingePosX = Mth.sin(lowerArmRadians) * 1.875;

        float upperArmRadians = (89 - extensionState * 50) * Mth.DEG_TO_RAD;
        double pantographX = getBlockState().getValue(FACING).getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0.25 : 0.75;
        Vec3 connectorPlatePos = new Vec3(axis == Direction.Axis.X ? pantographX : 0.5, armHingePosY,
                        armHingePosX + (axis == Direction.Axis.Z ? pantographX : 0.5))
                .add(0, Mth.cos(upperArmRadians) * 1.9, Mth.sin(upperArmRadians) * 1.9);
        connectorPlatePos = connectorPlatePos.add(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
        return connectorPlatePos;
    }

    /** 线段上距 checker 最近的点参数化位置 t∈[0,1]（纯数学，供滑触头定位） */
    public static float closestPointOnWire(Vec3 start, Vec3 end, Vec3 checker) {
        double t = 0;
        Vec3 ab = end.subtract(start);
        Vec3 ap = checker.subtract(start);
        double denom = ab.lengthSqr();
        if (denom != 0)
            t = ap.dot(ab) / denom;
        if (t < -0.01)
            return 0;
        if (t > 1.01)
            return 1;
        return (float) t;
    }

    /**
     * 判断受电弓滑触头是否在导线/接触网“触达范围”内，返回最近接触点或 null。
     * 沿 FACING 的正交容差随高度扩大（CEE 语义）。
     */
    public Vec3 checkCatenary(Vec3 start, Vec3 end, Vec3 pantographPos, float closestPointOnWire, float halfPantoReach) {
        Vec3 closest = start.lerp(end, closestPointOnWire);
        Vec3 distance = pantographPos.subtract(closest);
        if (distance.y > 0.8)
            return null;
        distance = distance.yRot((float) ((getBlockState().getValue(FACING).toYRot() + 90) * Math.PI / 180));
        float xTol = (float) ((-distance.y + halfPantoReach) * 0.2f + 0.125f);
        if (Math.abs(distance.z) < 1.5 && Math.abs(distance.x) < xTol && Math.abs(distance.y) < halfPantoReach)
            return closest;
        return null;
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        color = DyeColor.byName(tag.getString("Color"), DyeColor.WHITE);
        targetExtensionState = tag.getFloat("TargetExtensionState");
        currentExtensionState = tag.getFloat("ExtensionState");
        prevExtensionState = tag.getFloat("PrevExtensionState");
        extended = tag.getBoolean("Extended");
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("Color", color.getSerializedName());
        tag.putFloat("TargetExtensionState", targetExtensionState);
        tag.putFloat("ExtensionState", currentExtensionState);
        tag.putFloat("PrevExtensionState", prevExtensionState);
        tag.putBoolean("Extended", extended);
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return super.createRenderBoundingBox().inflate(3);
    }
}
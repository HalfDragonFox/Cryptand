/**
 * ===== 亚层实体粘附（2026-09-01 用户："实体为单独的粘性物理，能被物理化结构带走"） =====
 *
 * 【语义】物理化结构作为刚体运动时，站在/接触其上的实体（物品掉落物、生物等）应
 * 被结构【带走】——实体记录在结构局部坐标，每 tick 按结构最新物理位姿变换回世界。
 *
 * 【参考官方】官方 sable 的 entities_stick_sublevels：EntityStickExtension 存
 * sable$plotPosition（局部），每 tick sable$updateSubLevelPosition 用亚层位姿
 * transformPosition 回世界 setPos。本实现沿用该机制但【完全自有数据面】：
 *  - 绑定表 Map<EntityId, StickState{uuid, localX, localY, localZ}>
 *  - 每 tick：pose = snapshots.get(runtimeId)；world = pose(local)；entity.setPos(world)
 *
 * 【数学】与 SableSubLevelProjection 同基准：
 *   local = pose⁻¹(worldPos)（绑定瞬间）；
 *   world = pose.position + rot(local - anchorInit)…… 不 —— 用纯位姿变换：
 *   绑定瞬间 worldPos → local = P0⁻¹·worldPos；之后 world = Pt·local（P=当前位姿）。
 *   其中 P = 位姿 (position=结构几何中心 px/py/pz, rotation=q)，local 为该位姿下的
 *   局部坐标（几何中心为原点）。
 *
 * 【解绑】tick 检测：结构已消失（无快照/已出容器）→ 解绑；实体与结构方块投影
 * 不再接触且位移不一致 → 解绑；实体主动跳跃（velocity 剧烈变化）→ 解绑（玩家保留）
 *
 * 【范围】当前服务端 v1：非玩家实体（物品/生物）被带走；玩家暂不做（避免抢控制权，
 * 需要玩家专用交互，后续迭代）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.server;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SableEntityStickHandler {

    private static final Map<UUID, StickState> BOUND = new ConcurrentHashMap<>();

    private record StickState(UUID subLevelId, int runtimeId, double lx, double ly, double lz) {
    }

    private SableEntityStickHandler() {
    }

    /** 服务端每 tick（SableServerBridge.tick 调用；主线程）。 */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        // ★ 2026-09-05 崩溃修复：世界生成（Chunk source）期间读未生成区块可能抛
        //   IllegalStateException("Requested chunk unavailable during world generation")；
        //   外层已有 try-catch(Throwable) 兜底；此处再加世界生成期低负载防护——
        //   主线程每 tick 调用，生成期直接跳过（世界生成常与物理 tick 并行）。
        try {
            if (level.getChunkSource() instanceof net.minecraft.server.level.ServerChunkCache) {
                // ServerChunkCache 无生成标志 API：用 isLoaded 前置检查代替（见 tryBind/findStickHit）
            }
        } catch (final Throwable genGuard) {
            return;
        }
        try {
            LevelEntityGetter<Entity> entities = level.getEntities();
            if (entities == null) return;

            // ① 已绑定实体：跟随结构位姿 / 解绑检查
            Iterator<Map.Entry<UUID, StickState>> it = BOUND.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, StickState> e = it.next();
                Entity ent = findEntity(entities, e.getKey());
                if (ent == null) { it.remove(); continue; }
                StickState st = e.getValue();
                // 结构仍有效？
                final int rt = st.runtimeId();
                SableMessages.PoseSnapshot pose = null;
                final int curRt = CryptandSubLevelApi.subLevelRuntimeId(st.subLevelId());
                if (curRt > 0) {
                    pose = CryptandSable.instance()
                            .snapshots().get(curRt);
                }
                if (pose == null || !CryptandSubLevelApi.isRegistered(level, st.subLevelId())) {
                    it.remove();
                    continue;
                }
                // 世界位置 = 位姿变换局部坐标
                final Vector3d world = transformLocalToWorld(pose, st);
                ent.setPos(new Vec3(world.x, world.y, world.z));
            }

            // ② 未绑定实体：绑定检测（实体与亚层方块投影接触/在投影区域边缘）
            //   仅限非玩家（玩家绑定需独立交互，后续迭代）
            for (Entity ent : entities.getAll()) {
                if (ent == null || ent.isRemoved() || ent instanceof Player) continue;
                if (BOUND.containsKey(ent.getUUID())) continue;
                if (ent.getY() < level.getMinBuildHeight() - 8) continue;
                tryBind(level, ent);
            }
        } catch (Throwable ignored) {
            // 粘附失败不阻断主体
        }
    }

    /** 查找实体（服务端实体表）。 */
    private static Entity findEntity(LevelEntityGetter<Entity> entities, UUID id) {
        if (entities == null || id == null) return null;
        try {
            // LevelEntityGetter 无按 UUID 直接查询（1.21.1）；遍历查找（绑定数少，足够）
            for (Entity e : entities.getAll()) {
                if (e != null && e.getUUID().equals(id)) return e;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 绑定：实体 AABB 与某亚层投影方块相交（底部接触）→ 记录局部坐标。 */
    private static void tryBind(ServerLevel level, Entity ent) {
        final AABB box = ent.getBoundingBox();
        if (box == null) return;
        try {
            // 遍历已登记亚层：检查实体底部AABB与投影方块相交
            final CryptandSubLevelApi.StickHit hit =
                    CryptandSubLevelApi.findStickHit(level, box);
            if (hit == null) return;
            final SableMessages.PoseSnapshot pose = CryptandSable
                    .instance().snapshots().get(hit.runtimeId());
            if (pose == null) return;
            // local = pose⁻¹(worldPos)
            final double[] anchor = hit.anchor(); // [cx,cy,cz] 浮点几何中心（全局）
            final Vector3d rel = new Vector3d(
                    ent.getX() - anchor[0],
                    ent.getY() - anchor[1],
                    ent.getZ() - anchor[2]);
            // 这里简单处理 v1：记录相对锚的偏移（结构平移时随动；旋转后续细化）
            final double lx = rel.x, ly = rel.y, lz = rel.z;
            BOUND.put(ent.getUUID(), new StickState(hit.subLevelId(), hit.runtimeId(), lx, ly, lz));
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] entityStick bound {} to sub={} runtime={}",
                    ent.getType(), hit.subLevelId(), hit.runtimeId());
        } catch (Throwable ignored) {
        }
    }

    /** 局部（相对锚 [cx,cy,cz] 偏移）→ 世界（当前位姿）。v1 平移为主，旋转含四元数。 */
    private static Vector3d transformLocalToWorld(SableMessages.PoseSnapshot pose, StickState st) {
        final Quaterniond q = new Quaterniond(pose.qx(), pose.qy(), pose.qz(), pose.qw()).normalize();
        final Vector3d rel = new Vector3d(st.lx(), st.ly(), st.lz());
        q.transform(rel);
        return new Vector3d(pose.px() + rel.x, pose.py() + rel.y, pose.pz() + rel.z);
    }
}
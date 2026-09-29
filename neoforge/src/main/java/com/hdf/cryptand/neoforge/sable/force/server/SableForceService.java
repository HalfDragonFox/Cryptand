/**
 * ===== 力显示服务（服务端，永远被动，2026-09-14） =====
 *
 * <p>服务端只提供【数据采集核心】，且【仅接收】：一切采集都由客户端请求驱动，
 * 服务端不主动广播、不主动采集（tick 只做过期会话清理）。
 *
 * <p>会话模型（一个玩家 = 一个会话，参考万用表 ServerMeasurementSystem 的请求驱动架构）：
 * <ul>
 *   <li>请求自带玩家 UUID → 与连接玩家比对（防伪/防串会话）</li>
 *   <li>【每会话每秒请求上限】{@code sableForceDisplayMaxRequestsPerSecond}（默认 1，
 *       与客户端 1s/次匹配）→ 超限【静默丢弃】，不回包不报错</li>
 *   <li>能力探测（probe）单独限流 1s/次，不计入数据请求窗口</li>
 *   <li>返回范围：以【玩家为中心 + 配置距离】的球内结构（大结构取位姿点/质心较近者）</li>
 *   <li>订阅：对"有人要看"的结构打开官方逐点力记录；会话 8s 无请求 → 自动解订阅并关记录</li>
 * </ul>
 *
 * <p>多玩家：各自独立会话/独立限流/共享结构时订阅引用计数正确。
 */
package com.hdf.cryptand.neoforge.sable.force.server;

import com.hdf.cryptand.neoforge.sable.force.api.CryptandForceDisplay;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSample;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSource;
import com.hdf.cryptand.neoforge.sable.force.impl.ForceContextImpl;
import com.hdf.cryptand.neoforge.sable.force.impl.ForceReflect;
import com.hdf.cryptand.neoforge.sable.force.net.SableForceRequestPayload;
import com.hdf.cryptand.neoforge.sable.force.net.SableForceResponsePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SableForceService {

    private SableForceService() {
    }

    /** 会话（一个玩家一个）。 */
    private static final class Session {
        /** 当前 1 秒窗口起点 + 计数。 */
        long windowStartMs = 0L;
        int windowCount = 0;
        /** 最近一次任意请求（含 probe）的时间 → 会话 TTL。 */
        long lastRequestMs = 0L;
        /** 最近一次能力探测（单独限流）。 */
        long lastProbeMs = 0L;
        /** 本会话订阅的结构 key（过期时精确解订阅）。 */
        final Set<Integer> subscribed = ConcurrentHashMap.newKeySet();
    }

    /** 玩家 UUID → 会话。 */
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    /** 结构 key → 订阅者集合（引用计数；空 → 关闭该结构的逐点记录）。 */
    private static final Map<Integer, Set<UUID>> SUBSCRIBERS = new ConcurrentHashMap<>();
    /** 已开启逐点追踪的结构 key（避免重复反射调用）。 */
    private static final Set<Integer> TRACKING = ConcurrentHashMap.newKeySet();

    /** 会话 TTL（ms）：超过则解除订阅并关闭逐点记录。 */
    private static final long SESSION_TTL_MS = 8000L;
    /** 能力探测最小间隔（ms）。 */
    private static final long PROBE_MIN_INTERVAL_MS = 1000L;

    private static long serverTick = 0L;

    /**
     * 服务端 tick（游戏总线 {@code ServerTickEvent.Post}）：只做过期会话清理与订阅解绑，
     * 【不做任何采集】——采集只发生在收到客户端请求时（服务器永远被动）。
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(final net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        tick();
    }

    // ===== 请求入口（主线程，被动响应） =====

    public static void onRequest(final ServerPlayer player, final SableForceRequestPayload req) {
        // 服务端懒加载：确保内置力源/样式已注册（幂等）
        com.hdf.cryptand.neoforge.sable.force.impl.ForceDisplayBootstrap.init();

        // ① 总开关：未开启 → 只回能力标记（零采集）
        if (!isServerEnabled()) {
            PacketDistributor.sendToPlayer(player, SableForceResponsePayload.unsupported());
            return;
        }

        // ② 会话校验：请求自带 UUID 必须与连接玩家一致（防伪）
        if (req.playerId() != null && !req.playerId().equals(player.getUUID())) {
            return;
        }

        final long now = System.currentTimeMillis();
        final Session session = SESSIONS.computeIfAbsent(player.getUUID(), k -> new Session());
        session.lastRequestMs = now;

        // ③ 能力探测：单独限流（1s 一次），不计入数据请求窗口
        if (req.probe()) {
            if (now - session.lastProbeMs < PROBE_MIN_INTERVAL_MS) return;
            session.lastProbeMs = now;
            PacketDistributor.sendToPlayer(player,
                    new SableForceResponsePayload(true, req.detailed(), List.of()));
            return;
        }

        // ④ 【每会话每秒请求上限】：超限静默丢弃
        final int maxRps = Math.max(1, maxRequestsPerSecond());
        if (now - session.windowStartMs >= 1000L) {
            session.windowStartMs = now;
            session.windowCount = 0;
        }
        if (session.windowCount >= maxRps) {
            return;
        }
        session.windowCount++;

        // ⑤ 采集（被动：只在此刻发生）
        final boolean detailed = req.detailed() && isDetailedAllowed();
        final int maxDistance = req.maxDistance() > 0 ? req.maxDistance() : defaultMaxDistance();
        final List<SableForceResponsePayload.StructureForces> out = new ArrayList<>();

        if (player.level() instanceof ServerLevel level) {
            collectNear(level, player, session, detailed, maxDistance, out,
                    maxStructures(), maxSamplesPerStructure());
        }

        PacketDistributor.sendToPlayer(player,
                new SableForceResponsePayload(true, detailed, out));
    }

    /** 每游戏 tick：清理过期会话 + 解订阅（唯一在 tick 里做的事）。 */
    public static void tick() {
        serverTick++;
        final long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> {
            final Session s = entry.getValue();
            if (now - s.lastRequestMs <= SESSION_TTL_MS) return false;
            unsubscribeAll(entry.getKey(), s);
            return true;
        });
    }

    /** 玩家退出：立刻解除其全部订阅。 */
    public static void onPlayerLeave(final UUID uuid) {
        final Session s = SESSIONS.remove(uuid);
        if (s != null) unsubscribeAll(uuid, s);
    }

    // ===== 采集 =====

    private static void collectNear(final ServerLevel level, final ServerPlayer player,
                                    final Session session,
                                    final boolean detailed, final int maxDistance,
                                    final List<SableForceResponsePayload.StructureForces> out,
                                    final int maxStructures, final int maxSamples) {
        final Collection<?> subLevels = allSubLevels(level);
        if (subLevels.isEmpty()) return;

        final double px = player.getX(), py = player.getY(), pz = player.getZ();
        final double maxSq = (double) maxDistance * maxDistance;
        int structures = 0;

        for (final Object sub : subLevels) {
            if (structures >= maxStructures) break;

            final Object pose = ForceReflect.logicalPose(sub);
            final Vector3dc comLocal = ForceReflect.centerOfMass(sub);
            if (comLocal == null) continue;

            // 范围判定：【玩家中心 + 配置距离】；大结构取"质心 / 位姿点"较近者，避免长条结构被误剔除
            final double[] comWorld = ForceReflect.toWorld(pose, comLocal.x(), comLocal.y(), comLocal.z());
            double bestSq = distSq(comWorld[0], comWorld[1], comWorld[2], px, py, pz);
            final double[] posePos = ForceReflect.posePosition(pose);
            if (posePos != null) {
                bestSq = Math.min(bestSq, distSq(posePos[0], posePos[1], posePos[2], px, py, pz));
            }
            if (bestSq > maxSq) continue;

            final int key = ForceReflect.structureKey(sub);
            subscribe(key, player.getUUID(), session, sub);

            final ForceContextImpl ctx = new ForceContextImpl(sub, detailed, true, maxSamples);
            ctx.setGravity(ForceReflect.gravity(level));

            for (final ForceSource source : CryptandForceDisplay.sources()) {
                if (!source.enabled()) continue;
                try {
                    source.collect(ctx);
                } catch (final Throwable ignored) {
                    // 单个力源失败不影响其它力源/其它结构
                }
            }

            final List<ForceSample> samples = ctx.samples();

            // 质心球（框架级样本；力为 0 → 客户端只画球不画箭头）
            samples.add(new ForceSample(CryptandForceDisplay.CENTER_OF_MASS,
                    ForceSample.Kind.RESULTANT,
                    comWorld[0], comWorld[1], comWorld[2], 0.0, 0.0, 0.0));

            if (samples.isEmpty()) continue;

            final List<SableForceResponsePayload.Sample> encoded = new ArrayList<>(samples.size());
            for (final ForceSample s : samples) {
                encoded.add(new SableForceResponsePayload.Sample(
                        s.id().toString(), (byte) s.kind().ordinal(),
                        s.cx(), s.cy(), s.cz(), s.fx(), s.fy(), s.fz()));
            }
            out.add(new SableForceResponsePayload.StructureForces(key, encoded));
            structures++;

            if (com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_FORCE_DISPLAY_DEBUG.get()) {
                final StringBuilder sb = new StringBuilder();
                for (final ForceSample s : samples) {
                    sb.append(' ').append(s.id().getPath())
                            .append('[').append(String.format("%.1f", s.magnitude())).append(']');
                }
                com.hdf.cryptand.neoforge.cryptandsable.CryptandSable.class.getName(); // 保持类加载顺序无关
                System.out.println("[ForceDbg] structure=" + key + " samples=" + samples.size()
                        + " mass=" + String.format("%.1f", ForceReflect.mass(sub))
                        + " com=" + (comLocal == null ? "null" : "ok")
                        + " groups=" + (ForceReflect.queuedForceGroups(sub) == null ? "null" : "ok")
                        + " ->" + sb);
            }
        }
    }

    private static double distSq(final double ax, final double ay, final double az,
                                 final double bx, final double by, final double bz) {
        final double dx = ax - bx, dy = ay - by, dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    // ===== 订阅 / 逐点记录开关（引用计数） =====

    private static void subscribe(final int key, final UUID player, final Session session,
                                  final Object subLevel) {
        SUBSCRIBERS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(player);
        session.subscribed.add(key);
        if (TRACKING.add(key)) {
            ForceReflect.setTrackIndividual(subLevel, true);
        }
    }

    private static void unsubscribeAll(final UUID player, final Session session) {
        for (final Integer key : session.subscribed) {
            final Set<UUID> subs = SUBSCRIBERS.get(key);
            if (subs == null) continue;
            subs.remove(player);
            if (subs.isEmpty()) {
                SUBSCRIBERS.remove(key);
                closeTracking(key);
            }
        }
        session.subscribed.clear();
    }

    private static void closeTracking(final int key) {
        if (TRACKING.remove(key)) {
            forEachSubLevel(sub -> {
                if (ForceReflect.structureKey(sub) == key) {
                    ForceReflect.setTrackIndividual(sub, false);
                }
            });
        }
    }

    // ===== 反射：SubLevelContainer =====

    private static volatile boolean containerInit = false;
    private static Method mGetContainer;

    private static Collection<?> allSubLevels(final ServerLevel level) {
        ensureContainer();
        if (mGetContainer == null) return List.of();
        try {
            final Object container = mGetContainer.invoke(null, level);
            if (container == null) return List.of();
            final Object all = container.getClass().getMethod("getAllSubLevels").invoke(container);
            return all instanceof Collection<?> c ? c : List.of();
        } catch (final Throwable t) {
            return List.of();
        }
    }

    private static void forEachSubLevel(final java.util.function.Consumer<Object> consumer) {
        final net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (final ServerLevel level : server.getAllLevels()) {
            for (final Object sub : allSubLevels(level)) {
                consumer.accept(sub);
            }
        }
    }

    private static synchronized void ensureContainer() {
        if (containerInit) return;
        containerInit = true;
        try {
            final Class<?> c = Class.forName("dev.ryanhcode.sable.sublevel.SubLevelContainer");
            mGetContainer = c.getMethod("getContainer", net.minecraft.world.level.Level.class);
        } catch (final Throwable t) {
            mGetContainer = null;
        }
    }

    // ===== 配置读取（ConfigSable） =====

    private static boolean isServerEnabled() {
        return com.hdf.cryptand.neoforge.sable.config.ConfigSable.ENABLE_SABLE_FORCE_DISPLAY.get();
    }

    private static boolean isDetailedAllowed() {
        return com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_FORCE_DISPLAY_ALLOW_DETAILED.get();
    }

    private static int defaultMaxDistance() {
        return com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_FORCE_DISPLAY_MAX_DISTANCE.get();
    }

    private static int maxStructures() {
        return com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_FORCE_DISPLAY_MAX_STRUCTURES.get();
    }

    private static int maxSamplesPerStructure() {
        return com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_FORCE_DISPLAY_MAX_POINTS.get();
    }

    private static int maxRequestsPerSecond() {
        return com.hdf.cryptand.neoforge.sable.config.ConfigSable
                .SABLE_FORCE_DISPLAY_MAX_REQUESTS_PER_SECOND.get();
    }
}

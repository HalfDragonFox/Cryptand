/**
 * ===== 外设会话 · 服务端（被动端，2026-09-13）=====
 *
 * 用户定稿的职责划分：
 * <ul>
 *   <li><b>客户端 = 主动端</b>：外设计算全在客户端异步完成，按固定频率上行（默认 20Hz，可配）；</li>
 *   <li><b>服务端 = 被动端</b>：只做三件事 —— ① 维护会话窗口；② <b>限流</b>
 *       （每玩家每秒接收上限可配，超出<b>直接丢弃</b>，不排队、不反压）；
 *       ③ 回一份该方块所在结构的 <b>sable 运动数据</b>（客户端做力反馈的权威来源）。</li>
 * </ul>
 * 结构运动直接用 {@code PeripheralHelmMotion.worldCenter} 做位置差分得到
 * （该类零 Sable 编译期依赖，双端可用），采样在<b>收到上行包时</b>触发，
 * 因此不需要额外的每 tick 遍历。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmBlockEntity;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmMotion;
import com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralSessionServer {

    /**
     * 接收计数：key = {@code 玩家UUID@方块坐标} → [本秒已收包数, 当前秒号]
     * （超上限直接丢弃 ⇒ "客户端发太快就丢消息"）。
     * <p>⚠ 2026-09-14 修正为<b>每方块</b>独立计数，见 {@link #isOverLimit}。
     */
    private static final Map<String, int[]> RECV = new ConcurrentHashMap<>();

    /** 每方块的结构运动采样缓存（上次位置/速度，用于差分）。 */
    private static final Map<Long, Sample> MOTION = new ConcurrentHashMap<>();

    /** 结构运动采样：上一次的世界中心与速度（blocks/tick）。 */
    private record Sample(Vec3 pos, Vec3 vel, long at) {
    }

    private PeripheralSessionServer() {
    }

    /** 上行包入口（服务端线程）。 */
    public static void onUpstream(ServerPlayer player, PeripheralSessionPayload payload) {
        try {
            // ① 限流：每【方块】每秒最多 recvHz 个包，超出直接丢弃（用户定稿）
            if (isOverLimit(player, payload.pos())) {
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            BlockPos pos = payload.pos();

            // ② 应用数据（空包只保活，不改状态）
            if (payload.kind() != PeripheralSessionPayload.KIND_PING) {
                if (level.getBlockEntity(pos) instanceof PeripheralHelmBlockEntity helm) {
                    if (helm.owner() == null || helm.owner().equals(player.getUUID())) {
                        helm.setClientAngle(payload.value());
                    }
                } else if (level.getBlockEntity(pos) instanceof PeripheralLeverBlockEntity lever) {
                    if (lever.owner() == null || lever.owner().equals(player.getUUID())) {
                        // 拉杆：档位只在变化时才写（setSignalFromDevice 内含变化检测，不会刷音效）
                        lever.setSignalFromDevice(Math.round(payload.value()));
                    }
                } else if (level.getBlockEntity(pos)
                        instanceof com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionBlockEntity transmission) {
                    if (transmission.owner() == null || transmission.owner().equals(player.getUUID())) {
                        // 外设模拟传动器：曲线算出的输出比例（0..1）→ 传动比
                        transmission.setRatioFromDevice(payload.value());
                    }
                } else if (level.getBlockEntity(pos)
                        instanceof com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyBlockEntity transmissionKey) {
                    if (transmissionKey.owner() == null || transmissionKey.owner().equals(player.getUUID())) {
                        // 外设模拟传动器（按键）：滑块进度（0..1，同时也是输出百分比）→ 传动比
                        transmissionKey.setRatioFromDevice(payload.value());
                    }
                } else if (level.getBlockEntity(pos)
                        instanceof com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBlockEntity railing) {
                    if (railing.owner() == null || railing.owner().equals(player.getUUID())) {
                        // 外设栏杆（按钮）：按键映射表算出的信号
                        railing.setSignalFromDevice(Math.round(payload.value()));
                    }
                }
            }

            // ③ 回 sable 数据（结构运动）
            Sample sample = sampleMotion(level, pos);
            if (sample != null) {
                Vec3 vel = sample.vel();
                Vec3 accel = Vec3.ZERO;
                Sample prev = MOTION.get(pos.asLong());
                if (prev != null) {
                    accel = vel.subtract(prev.vel());
                }
                float speed = (float) vel.length() * 20f;
                float accelMps2 = (float) accel.length() * PeripheralHelmMotion.BLOCKS_PER_TICK2_TO_MPS2;
                PacketDistributor.sendToPlayer(player, new PeripheralSablePayload(
                        pos, speed, accelMps2,
                        (float) accel.x * PeripheralHelmMotion.BLOCKS_PER_TICK2_TO_MPS2,
                        (float) accel.y * PeripheralHelmMotion.BLOCKS_PER_TICK2_TO_MPS2,
                        (float) accel.z * PeripheralHelmMotion.BLOCKS_PER_TICK2_TO_MPS2,
                        (float) vel.x * 20f, (float) vel.y * 20f, (float) vel.z * 20f,
                        (int) level.getGameTime()));
            }
        } catch (Throwable ignored) {
            // 会话是尽力而为的通道：任何异常都不该影响游戏主循环
        }
    }

    /**
     * 限流：<b>每个外设方块</b>每秒接收上限（{@code aeronautics.toml#peripheralServerRecvHz}）。
     *
     * <p>⚠ 2026-09-14 修正（用户实测"红石反应最长要 1~2 秒"的根因）：
     * 旧实现按<b>每玩家</b>总额计数，而客户端上行本来就是"<b>每方块</b> recvHz"、
     * 每个方块各自独立计时。3 个外设方块就是 60 包/秒，却全被算进同一个 20 的配额里 ——
     * 结果是每秒<b>前 1/3 秒</b>就把配额用光，<b>剩下 2/3 秒的包全部被丢弃</b>；
     * 红石变化若落在那段时间里，就必须等到下一整秒才可能被接受。
     *
     * <p>现在按 {@code 玩家UUID@方块坐标} 独立计数：每个方块各自享有完整的 recvHz 配额，
     * 与客户端"每方块独立计时上行"的语义完全对齐（方块再多也不会互相挤占）。
     */
    private static boolean isOverLimit(ServerPlayer player, BlockPos pos) {
        int hz = ConfigAero.peripheralServerRecvHz();
        long second = player.level().getGameTime() / 20L;
        String key = player.getUUID() + "@" + pos.asLong();
        int[] slot = RECV.computeIfAbsent(key, k -> new int[] { 0, (int) second });
        if (slot[1] != (int) second) {
            slot[1] = (int) second;
            slot[0] = 0;
        }
        slot[0]++;
        return slot[0] > hz;
    }

    /** 采样该方块所在结构的运动（位置差分；首帧只记录、返回 null）。 */
    private static Sample sampleMotion(ServerLevel level, BlockPos pos) {
        Vec3 now = PeripheralHelmMotion.worldCenter(level, pos);
        long key = pos.asLong();
        Sample prev = MOTION.get(key);
        long at = level.getGameTime();
        if (prev == null || at <= prev.at()) {
            MOTION.put(key, new Sample(now, Vec3.ZERO, at));
            return null;
        }
        // ⚠ 按【实际经过的 tick 数】归一化：上行速率是自适应的（稳态 2Hz，甚至 0 = 只在变化时发），
        //   两个包之间可能隔了很多 tick。不除的话这段位移会被当成"1 tick 的位移"，
        //   算出来的速度会虚高几十倍（力反馈会跟着爆掉）。
        long dtTicks = Math.max(1L, at - prev.at());
        Vec3 vel = now.subtract(prev.pos()).scale(1.0 / dtTicks);
        MOTION.put(key, new Sample(now, vel, at));
        return new Sample(now, vel, at);
    }

    /** 会话清理（方块被破坏 / 玩家退出 / 世界卸载时调用）。 */
    public static void forget(BlockPos pos) {
        if (pos != null) {
            MOTION.remove(pos.asLong());
            String suffix = "@" + pos.asLong();
            RECV.keySet().removeIf(k -> k.endsWith(suffix));
        }
    }

    /** 玩家退出：清限流计数（key 形如 {@code uuid@posLong}）。 */
    public static void forgetPlayer(UUID player) {
        if (player != null) {
            String prefix = player + "@";
            RECV.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }
}

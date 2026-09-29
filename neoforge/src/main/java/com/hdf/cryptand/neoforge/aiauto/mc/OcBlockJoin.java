package com.hdf.cryptand.neoforge.aiauto.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== 放置 OC 方块后的"入网"收尾（2026-09-27）=====
 *
 * <p><b>现场</b>（上一项实测，交接文档 §遗留 3）：用 {@code place_block} 直写世界放出来的 OC 机箱
 * {@code machine.node.network == null} ⇒ OC 的 {@code Machine.start()} **静默**返回 false（开不了机）。
 * 玩家/界面放置的机箱没有这个问题。</p>
 *
 * <h3>根因</h3>
 * <p>OC 的方块实体不是"放下去就入网"的：它的 {@code Environment} trait 在 <b>{@code clearRemoved()}</b>
 * 里调 {@code EventHandler.scheduleServer(this)}，把 {@code Network.joinOrCreateNetwork(be)} 排到
 * <b>下一个服务端 tick 开头</b>执行（OC 源码 {@code common/blockentity/traits/Environment.scala}）。
 * 而"原版 setBlock / 直写世界"这条路**不会走** {@code clearRemoved()}（那是区块加载/注册那条路）
 * ⇒ 从来没有人替它 join：节点在，网络是 null。</p>
 *
 * <p>OC 自己的 {@code /oc_spawnComputer} 命令同样吃这个亏，所以它放完机箱后**显式**调了
 * {@code api.Network.joinOrCreateNetwork(...)}（{@code server/command/SpawnComputerCommand.scala}）。
 * 我们走**同一个入口的延迟版**：{@code EventHandler.scheduleServer(be)} —— 与玩家放置那条路完全一致
 * （下一个 tick 开头 join）。这不是补丁，而是"补上放置路径本来就该做的那一步"。</p>
 *
 * <h3>为什么用反射而不是直接 import</h3>
 * <p>OC 对本模组是 <b>软依赖</b>（{@code neoforge/build.gradle} 里 {@code compileOnly + runtimeOnly}，
 * {@code OpenComputersEntry} 只按 modId 探测）。aiauto 在 OC 缺席时也必须能加载，所以这里只按
 * <b>类名/方法名</b>取；参数是一个 OC 的方块实体 ⇒ 真正调用到它时 OC 必然在场
 * （与 {@code AiQueryTools} 对 OC 的既有做法一致）。</p>
 */
public final class OcBlockJoin {

    private OcBlockJoin() {
    }

    /** ⚠ 用 log4j（而不是 System.out）：只有 log4j 会进 run/logs/latest.log */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/aiauto");

    /** OC 的环境接口：方块实体实现它就说明"这个方块要入网" */
    private static final String ENV_INTERFACE = "li.cil.oc.api.network.Environment";

    /** OC 的入网入口：{@code object EventHandler} 上的静态转发方法（javap 实证存在该重载） */
    private static final String EVENT_HANDLER = "li.cil.oc.common.EventHandler";

    private static volatile java.lang.reflect.Method scheduleServer;
    private static volatile boolean lookupFailed;
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 直写世界放完方块后调用：是 OC 的方块实体就把它排进"下一个 tick 入网"队列。
     *
     * <p>只对**真的带方块实体**的方块做事（读世界里的真实方块态，不是调用方传进来的期望态）——
     * 绝大多数放置（石头、机器外壳）在这里就返回了，零额外开销。</p>
     */
    public static void afterPlace(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.getBlockState(pos).hasBlockEntity()) {
            return;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !isOcEnvironment(be.getClass())) {
            return;
        }
        final java.lang.reflect.Method m = scheduleServerMethod();
        if (m == null) {
            return;                     // 原因已由 scheduleServerMethod 记过一次日志
        }
        try {
            m.invoke(null, be);
            // 这一行是"谁替它入的网"的唯一证据（无人化验证时按它 + oc_machine_state 的网络字段判）
            LOG.info("[aiauto] 放置的 OC 方块实体已排入入网队列（EventHandler.scheduleServer）：{} @ {}",
                    be.getClass().getSimpleName(), pos.toShortString());
        } catch (Throwable t) {
            warnOnce("调用 EventHandler.scheduleServer 失败", t);
        }
    }

    /**
     * 这个类（含父类与接口继承链）是不是 OC 的 {@code api.network.Environment}。
     *
     * <p>不用 {@code instanceof}：那要求本类**加载时就解析** OC 的接口，OC 缺席时 aiauto 直接
     * {@code NoClassDefFoundError}。这里只看**名字**，探测的是一个真的 OC 方块实体的类型
     * （它存在 ⇒ OC 在场），所以不会误判。</p>
     */
    private static boolean isOcEnvironment(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (final Class<?> itf : c.getInterfaces()) {
                if (implementsInterface(itf, ENV_INTERFACE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean implementsInterface(Class<?> itf, String name) {
        if (itf == null) {
            return false;
        }
        if (name.equals(itf.getName())) {
            return true;
        }
        for (final Class<?> parent : itf.getInterfaces()) {
            if (implementsInterface(parent, name)) {
                return true;
            }
        }
        return false;
    }

    /** 取 {@code EventHandler.scheduleServer(BlockEntity)}；取不到 ⇒ null 并只记一次日志 */
    private static java.lang.reflect.Method scheduleServerMethod() {
        java.lang.reflect.Method m = scheduleServer;
        if (m != null || lookupFailed) {
            return m;
        }
        try {
            m = Class.forName(EVENT_HANDLER).getMethod("scheduleServer", BlockEntity.class);
            scheduleServer = m;
            return m;
        } catch (Throwable t) {
            // 软依赖探测：OC 缺席/版本不匹配都走这里（明确记日志，不静默）
            lookupFailed = true;
            warnOnce("找不到 " + EVENT_HANDLER + ".scheduleServer(BlockEntity) ⇒ "
                    + "place_block 放的 OC 方块不会自动入网（OC 版本不匹配？）", t);
            return null;
        }
    }

    private static void warnOnce(String what, Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            LOG.warn("[aiauto] {}（后续同类失败不再刷日志）", what, t);
        }
    }
}

/**
 * ===== Sable 亚层移除观察者（2026-08-23 用户：取消物理化后网络丢失） =====
 *
 * 场景：物理化（装配）时端点 key 已迁到亚层 plot 坐标（SableAssemblyMoveMixin
 * / 参考 CEE SubLevelAssemblyHelperMixin）。取消物理化（亚层移除/destroy）时：
 *   - 亚层方块被销毁 → 端点 plot 坐标处无 BE/方块 → 渲染网格消失；
 *   - 确认器（NetworkDestructionDetector.confirmAndDestroy）按 plot 坐标最终
 *     删除端点 → 网络永久丢失。
 *
 * 方案：反射注册 Sable {@code SubLevelContainer.addObserver(SubLevelObserver)}
 * （零编译期依赖；未装 sable 安全跳过）。onSubLevelRemoved（服务端，主线程，
 * 亚层移除流程中、confirm 多数重试完成前触发）：
 *   遍历自管图端点，凡属于【该亚层】的（getContaining(level,pos)==subLevel）
 *   → 用 CeePoseUtil.projectOut（logicalPose 投影）换算【世界原位置】→
 *   WireNetworkManager.remapBlockPos(plot→world)。
 * 之后：
 *   - 若取消物理化把方块放回世界 → 事件 onBePlaced addDevice（幂等）+ 渲染
 *     resolveTerminal 世界坐标 → 网格恢复；
 *   - 若纯销毁 → 端点 key 已回世界坐标，确认器按 plot 查不到 → 不删（悬空，
 *     方块重建后自动恢复）；渲染 fallback 服务端世界位置 → 网格仍可见。
 *
 * 注册时机：WireNetworkManager.onWorldLoad（主世界加载）+
 * SableAssemblyMoveMixin 首次物理化兜底。线程：主线程。
 */
package com.hdf.cryptand.neoforge.cee;

import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireKeyUtil;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class SableSubLevelObserver {

    /** 是否已注册（进程级一次；世界加载后 sable 容器就绪才注册成功） */
    private static volatile boolean registered;

    private SableSubLevelObserver() {
    }

    /** 确保在 level 的 SubLevelContainer 上注册观察者（幂等；主线程调用） */
    public static void ensure(ServerLevel level) {
        if (level == null || registered) return;
        try {
            Class<?> containerCls = Class.forName(
                    "dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> observerCls = Class.forName(
                    "dev.ryanhcode.sable.api.sublevel.SubLevelObserver");
            Method getContainer = containerCls.getMethod("getContainer", Level.class);
            Object container = getContainer.invoke(null, level);
            if (container == null) return; // sable 容器未就绪（世界加载早期）→ 静默跳过
            Method addObserver = containerCls.getMethod("addObserver", observerCls);
            Object observer = Proxy.newProxyInstance(observerCls.getClassLoader(),
                    new Class<?>[]{observerCls}, (proxy, method, args) -> {
                        try {
                            if ("onSubLevelRemoved".equals(method.getName())
                                    && args != null && args.length >= 1 && args[0] != null) {
                                onSubLevelRemoved(level, args[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                        return null;
                    });
            addObserver.invoke(container, observer);
            registered = true;
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[Sable] sublevel observer registered");
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Sable] observer register failed", t);
        }
    }

    /** 亚层移除（主线程）：该亚层内端点回迁世界坐标（投影后） */
    private static void onSubLevelRemoved(ServerLevel level, Object subLevel) {
        try {
            var mgr = WireNetworkManager.get();
            int n = 0;
            for (WirePoint p : mgr.pointList()) {
                if (p.key == null || p.key.isEmpty()) continue;
                char c = p.key.charAt(0);
                if (c != 'B' && c != 'J') continue;
                BlockPos pos = WireKeyUtil.posOf(p.key);
                if (pos == null) continue;
                Object sub = CeePoseUtil.getContaining(level, pos);
                if (sub == null || sub != subLevel) continue;
                Vec3 world = CeePoseUtil.projectOut(level,
                        Vec3.atCenterOf(pos));
                if (world == null) continue;
                BlockPos np = BlockPos.containing(world.x, world.y, world.z);
                if (mgr.remapBlockPos(level, pos, np)) n++;
            }
            if (n > 0) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[Sable] sublevel removed: remapped {} block-positions", n);
            }
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Sable] sublevel removed remap failed", t);
        }
    }
}

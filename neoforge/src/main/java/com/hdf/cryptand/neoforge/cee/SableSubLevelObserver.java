/**
 * ===== Sable 2026-08-23  =====
 *
 * ?key ?plot SableAssemblyMoveMixin
 * / ?CEE SubLevelAssemblyHelperMixin?destroy?
 *   - ?? plot  BE/ ??
 *   - NetworkDestructionDetector.confirmAndDestroy plot ?
 *      ??
 *
 * ?Sable {@code SubLevelContainer.addObserver(SubLevelObserver)}
 *  sable onSubLevelRemoved?
 * confirm ?
 *   getContaining(level,pos)==subLevel?
 *   ??CeePoseUtil.projectOutlogicalPose 
 *   WireNetworkManager.remapBlockPos(plotworld)?
 * ?
 *   - ?? onBePlaced addDevice+ 
 *     resolveTerminal  ??
 *   - ?? key ?plot ??
 *     ?fallback ???
 *
 * WireNetworkManager.onWorldLoad?
 * SableAssemblyMoveMixin ?
 */
package com.hdf.cryptand.neoforge.cee;

import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cee.CeePoseUtil;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Proxy;

/** ★ 2026-09-07 由 com.hdf.cryptand.neoforge.sable 移入 cee 子包：CEE×电网的 sable 联动观察者归 cee；sable 子包只保留 sable 本体相关。 */
public final class SableSubLevelObserver {

    /** ?sable  */
    private static volatile boolean registered;

    private SableSubLevelObserver() {
    }

    /** ?level ?SubLevelContainer ?*/
    public static void ensure(ServerLevel level) {
        if (level == null || registered) return;
        // ?026-08-30 enableSableSupport=false ??
        try {
            if (!com.hdf.cryptand.neoforge.core.module.SubpackageRegistry.isEnabled("sable")) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            // ★ 2026-09-06 修复：原反射遍历（getMethods）会解析官方 API 全部公开方法签名，
            //   其中客户端签名类（ClientLevel）在 DEDICATED_SERVER 上加载 → dist 崩溃每 tick
            //   刷屏。改直接类型调用（官方 compileOnly 提供签名；未装官方时调用点
            //   NoClassDefFoundError → catch 兜底，与反射版等价）。
            SubLevelContainer container =
                    SubLevelContainer.getContainer(level);
            if (container == null) return; // sable 未接管该 level
            SubLevelObserver observer =
                    (SubLevelObserver) Proxy.newProxyInstance(
                            SubLevelObserver.class.getClassLoader(),
                            new Class<?>[]{SubLevelObserver.class},
                            (proxy, method, args) -> {
                                try {
                                    if ("onSubLevelRemoved".equals(method.getName())
                                            && args != null && args.length >= 1 && args[0] != null) {
                                        onSubLevelRemoved(level, args[0]);
                                    }
                                } catch (Throwable ignored) {
                                }
                                return null;
                            });
            container.addObserver(observer);
            registered = true;
            // ★ 2026-09-06 成功注册静默（关闭/正常形态都不输出 [Sable] 行）
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[Sable] observer register failed", t);
        }
    }

    /** ?*/
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
                CryptandNeoForge.WAF_LOGGER.debug(
                        "[Sable] sublevel removed: remapped {} block-positions", n);
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[Sable] sublevel removed remap failed", t);
        }
    }
}

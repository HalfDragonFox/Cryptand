package com.hdf.cryptand.neoforge.cryptandsable_compat;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandBounds3i;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandLevelPlot;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevel;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 亚层定位工具（cryptandsable_compat —— 官方 sable 转接层）。
 *
 * <p>负责任务：把第三方 mod（simulated / aeronautics / offroad）对
 * {@code Sable.HELPER.getContaining(...)} 的调用，在 mixin 接管层转换为
 * 对【核心自有容器】的直接查询——绝不走进官方 sable（官方 jar 为纯类库、不初始化、
 * 官方容器不被挂载）。
 *
 * <p>命中来源：{@link CryptandSubLevelContainer#getSubLevelMap()} 登记的
 * 真实亚层（由 {@code CryptandSubLevelApi.physicalize} 落库），按
 * {@link CryptandLevelPlot#contains(double, double)} 命中。核心物理体由
 * CryptandSable worker 异步托管，本查询仅做轻量定位（无 I/O、无阻塞）。
 */
public final class CryptandSubLevelLocator {

    private static final long LOG_THROTTLE_MS = 5000L;
    private static long lastLog = 0L;

    private CryptandSubLevelLocator() {
    }

    /** 节流日志（主线程热路径避免刷屏；★ 2026-09-06 降 debug——默认静默，排查时开 debug）。 */
    private static void throttledLog(final String msg) {
        final long now = System.currentTimeMillis();
        if (now - lastLog >= LOG_THROTTLE_MS) {
            lastLog = now;
            CryptandNeoForge.WAF_LOGGER.debug("[CryptandSable] LOCATOR {}", msg);
        }
    }

    /** 在世界坐标查核心容器登记的真实亚层（核心自有类型）；无 → null。
     *  <p>命中两档：① 亚层【精确结构包围盒】localBounds 含该点（结构本体/结构内视角）；
     *  ② 无精确命中时退回 plot.contains（大范围，覆盖装配器这类"紧邻结构外侧"的判定点）。
     *  两档各自取体积最小（最精确）者，避免同 chunk 共享 plot 的多亚层交错。 */
    public static @Nullable CryptandSubLevel containingOwn(final Level level, final double x, final double z) {
        if (!(level instanceof ServerLevel)) {
            return null;
        }
        try {
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
            final int size = container.getSubLevelMap().size();
            CryptandSubLevel exact = null;      // 档①：localBounds 精确命中
            double exactVol = Double.MAX_VALUE;
            CryptandSubLevel broad = null;      // 档②：plot.contains 命中
            double broadVol = Double.MAX_VALUE;
            for (final CryptandSubLevel sub : container.getSubLevelMap().values()) {
                final CryptandLevelPlot plot = sub.getPlot();
                if (plot == null) continue;
                final CryptandBounds3i box = plot.getBoundingBox();
                if (box != null && !box.isEmpty()
                        && box.minX() <= x && x <= box.maxX()
                        && box.minZ() <= z && z <= box.maxZ()) {
                    final double vol = Math.max(1, ((long) box.maxX() - box.minX() + 1))
                            * Math.max(1, ((long) box.maxZ() - box.minZ() + 1));
                    if (vol < exactVol) {
                        exactVol = vol;
                        exact = sub;
                    }
                }
                if (plot.contains(x, z)) {
                    final double vol = Math.max(1, 1L << plot.getLogSize());
                    if (vol < broadVol) {
                        broadVol = vol;
                        broad = sub;
                    }
                }
            }
            final CryptandSubLevel best = exact != null ? exact : broad;
            if (best != null) {
                throttledLog("HIT x=" + x + " z=" + z + " size=" + size + " sub=" + best.getUniqueId()
                        + (exact != null ? " [exact]" : " [broad]"));
            } else if (size > 0) {
                throttledLog("MISS x=" + x + " z=" + z + " size=" + size);
            }
            return best;
        } catch (final Throwable t) {
            // 定位失败不阻断（核心 worker 仍可兜底）
        }
        return null;
    }

    /** BlockEntity 快捷定位（核心自有类型）。 */
    public static @Nullable CryptandSubLevel containingBlockEntityOwn(final BlockEntity be) {
        if (be == null) {
            return null;
        }
        final Level level = be.getLevel();
        if (level == null) {
            return null;
        }
        final BlockPos p = be.getBlockPos();
        return containingOwn(level, p.getX(), p.getZ());
    }

    /**
     * 仅精确命中（2026-08-31 用户"专属API天然独立分隔"）：只按【结构精确包围盒】
     * localBounds 判定（不含 plot 广域兜底），供装配器相关 mixin 使用——
     * 避免"别处装配器因 plot 覆盖命中同一亚层"的跨结构串扰。返回核心自有类型。
     */
    public static @Nullable CryptandSubLevel containingExactOwn(final BlockEntity be) {
        if (be == null) {
            return null;
        }
        final Level level = be.getLevel();
        if (!(level instanceof ServerLevel) || level == null) {
            return null;
        }
        final BlockPos p = be.getBlockPos();
        final double x = p.getX(), z = p.getZ();
        try {
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
            CryptandSubLevel best = null;
            double bestVol = Double.MAX_VALUE;
            for (final CryptandSubLevel sub : container.getSubLevelMap().values()) {
                final CryptandLevelPlot plot = sub.getPlot();
                if (plot == null) continue;
                final CryptandBounds3i box = plot.getBoundingBox();
                if (box == null || box.isEmpty()) {
                    continue;
                }
                if (box.minX() <= x && x <= box.maxX() && box.minZ() <= z && z <= box.maxZ()) {
                    final double vol = Math.max(1, (long) (box.maxX() - box.minX() + 1))
                            * Math.max(1, (long) (box.maxZ() - box.minZ() + 1));
                    if (vol < bestVol) {
                        bestVol = vol;
                        best = sub;
                    }
                }
            }
            return best;
        } catch (final Throwable ignored) {
        }
        return null;
    }
}
package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.opencomputers.OpenComputersEntry;
import com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlock;
import com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlockEntity;
import com.hdf.cryptand.soc.board.SandboxInspect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * ===== 高级分析器：采集门面（**不引用 OC 任何类型**，2026-09-27）=====
 *
 * <p>为什么要有这一层薄门面（而不是让面板/工具直接调采集器）：</p>
 * <ul>
 *   <li><b>软依赖纪律</b>：{@code soc} 子包在 OC 缺席时必须能正常加载 —— 真正的采集器
 *       （{@link AdvancedAnalyzerPanelProbe}）引用了 {@code li.cil.oc.*}，所以只有
 *       {@link OpenComputersEntry#ocLoaded()} 为真时才去触碰它（类加载是惰性的：
 *       OC 不在场时下面那些调用点根本不会执行到）；</li>
 *   <li><b>唯一入口</b>：面板与无人化 MCP 工具 {@code soc_inspect} 都从这里拿快照 ⇒
 *       两者看到的必然是同一份数据（{@link SandboxInspect} 是唯一的排版来源）。</li>
 * </ul>
 */
public final class AdvancedAnalyzerPanelData {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/soc");

    private AdvancedAnalyzerPanelData() {
    }

    /**
     * 从分析器方块出发重扫目标机（先 6 邻居，再 {@value AdvancedAnalyzerBlock#SCAN_RADIUS} 格内最近）。
     *
     * @return 目标机坐标；没找到 = {@code null}（**明确清空**，不沿用上次）
     */
    @Nullable
    public static BlockPos resolveTarget(Level level, BlockPos origin) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || origin == null) {
            return null;
        }
        if (!OpenComputersEntry.ocLoaded()) {
            return null;
        }
        try {
            return AdvancedAnalyzerPanelProbe.findTarget(server, origin, AdvancedAnalyzerBlock.SCAN_RADIUS);
        } catch (Throwable t) {
            LOG.warn("[分析器] 目标机扫描失败（{}）", t.toString());
            return null;
        }
    }

    /**
     * 目标的一句话结论（面板标题栏 + 方块实体里的 {@code note}）—— 人读的文本，
     * 数据本身在 {@link SandboxInspect}，这里只描述"看的是谁"。
     */
    public static String describeTarget(Level level, @Nullable BlockPos target) {
        if (!OpenComputersEntry.ocLoaded()) {
            return "OpenComputers 未加载（沙箱数据不可用）";
        }
        if (target == null) {
            return "没找到 OC 机器（把分析器放在机箱旁 " + AdvancedAnalyzerBlock.SCAN_RADIUS + " 格内）";
        }
        if (level == null) {
            return "目标 " + target.toShortString();
        }
        if (!level.isLoaded(target)) {
            // ⚠ 区块没加载时连方块状态都不该去读（读到的是假的空气、还可能把区块拽起来算一遍）
            //   —— 这里必须**明说"未加载"**：否则面板会拿着上一次的旧数字当成这台机器的现状。
            return "目标 " + target.toShortString() + "（所在区块未加载：沙箱数据不可用，区块回来自动恢复）";
        }
        final boolean loaded = level.getBlockEntity(target) != null;
        return "目标 " + level.getBlockState(target).getBlock()
                + " @ " + target.getX() + "," + target.getY() + "," + target.getZ()
                + (loaded ? "" : "（方块实体未加载）");
    }

    /**
     * 无人化工具 {@code soc_inspect} 的目标解析：给机器坐标就直接分析它；给分析器方块坐标
     * 就跟随它记住的目标（没有就当场重扫一次）。
     */
    @Nullable
    public static BlockPos inspectTarget(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AdvancedAnalyzerBlockEntity analyzer) {
            final BlockPos remembered = analyzer.target();
            return remembered != null ? remembered : resolveTarget(level, pos);
        }
        return pos;
    }

    /**
     * 每秒自动刷新时"这一秒该看谁"的判定（**面板侧的自愈入口**）。
     *
     * <p>与右键那条路（{@link #resolveTarget} 全量重扫）的区别：这里**默认沿用上次的目标**，
     * 只在目标确实没用了的时候才重扫 —— 分析器方块没动，邻居表也不会凭空多出机器，
     * 每秒全量重扫一遍是白花钱。三种状态各有各的处理：</p>
     *
     * <ul>
     *   <li><b>目标还在（是机器）</b> ⇒ 原样返回（最常见的一条，没有任何额外开销）；</li>
     *   <li><b>目标所在区块没加载</b> ⇒ 原样返回，**不重扫**。重扫这时候只会命中"另一台还在
     *       加载中的机器"，于是面板悄悄换了分析对象 —— 那比显示"未加载"糟糕得多
     *       （用户会以为自己看的是 A，其实数据来自 B）。区块回来数据自然恢复；</li>
     *   <li><b>目标方块没了 / 不再是机器</b>（被拆、被换成别的方块、机器没了 CPU、句柄失效）
     *       ⇒ 重扫邻居表 {@link #resolveTarget}：旁边还有别的机器就改看它，一台都没有就返回
     *       {@code null}（面板如实显示"没找到 OC 机器"，而不是继续显示上一台的死数据）。</li>
     * </ul>
     *
     * <p>另外记一句日志：目标**换了**才是值得知道的事（"查不到"与"换了对象"混在一起时最难查），
     * 而每秒都在正常刷新这件事一律不写日志（否则日志会被刷屏）。</p>
     */
    @Nullable
    public static BlockPos tickTarget(Level level, BlockPos origin, @Nullable BlockPos remembered) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || origin == null) {
            return remembered;
        }
        if (!OpenComputersEntry.ocLoaded()) {
            return remembered;
        }
        if (remembered == null) {
            // 之前没目标（旁边还没机器）⇒ 每秒扫一眼：用户放上机箱后不用再右键一次
            return resolveTarget(level, origin);
        }
        if (!server.isLoaded(remembered)) {
            return remembered;
        }
        try {
            if (AdvancedAnalyzerPanelProbe.isMachine(server, remembered)) {
                return remembered;
            }
        } catch (Throwable t) {
            // 校验本身抛异常（OC 侧句柄失效等）：保持原目标，下一轮再说，绝不因此崩溃/清空
            LOG.warn("[分析器] 目标机校验失败（{} @ {}）", t.toString(), remembered.toShortString());
            return remembered;
        }
        final BlockPos next = resolveTarget(level, origin);
        LOG.info("[分析器] 目标 @ {} 已失效（方块没了/不再是机器），重扫 ⇒ {}",
                remembered.toShortString(), next == null ? "没找到" : next.toShortString());
        return next;
    }

    /**
     * 采集快照（面板与 {@code soc_inspect} 共用的**唯一**入口）。
     *
     * <p>任何"采集不了"的情况（坐标不是机器 / OC 未加载 / 采集抛异常）都返回
     * {@link SandboxInspect#empty()} —— 七个分区都在、值全是占位符，配合一句日志，
     * 既不会静默也不会编造。</p>
     */
    public static SandboxInspect.Snapshot snapshot(Level level, @Nullable BlockPos target) {
        if (target == null || !(level instanceof net.minecraft.server.level.ServerLevel server)) {
            return SandboxInspect.empty();
        }
        if (!OpenComputersEntry.ocLoaded()) {
            return SandboxInspect.empty();
        }
        try {
            return AdvancedAnalyzerPanelProbe.snapshot(server, target);
        } catch (Throwable t) {
            LOG.warn("[分析器] 沙箱快照采集失败（{} @ {}）", t.toString(), target.toShortString(), t);
            return SandboxInspect.empty();
        }
    }
}

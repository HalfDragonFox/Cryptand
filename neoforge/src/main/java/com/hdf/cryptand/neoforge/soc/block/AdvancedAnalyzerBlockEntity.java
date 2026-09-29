package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.soc.ui.AdvancedAnalyzerPanelData;
import com.hdf.cryptand.soc.board.SandboxInspect;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * ===== 高级分析器方块实体（2026-09-27；2026-09-27 加"每秒刷新"）=====
 *
 * <p>职责只有一个：**把沙箱快照搬到客户端能读的地方**。</p>
 *
 * <h3>为什么必须经方块实体</h3>
 * <p>LDLib2 的 BlockUI 面板在**两侧都会各建一棵 UI 树**（{@code ModularUIContainerMenu} 的构造器
 * 两侧都跑，且 {@code layout(...)} 在服务端是空操作 ⇒ 真正渲染的是客户端那棵树），而沙箱状态
 * 只存在于服务端（OC 的节点/沙箱线程都在服务端）。所以面板要显示真实数据，快照就必须
 * **随方块实体同步到客户端**：服务端采集 → 按 {@link SandboxInspect.SECTIONS} 切成每个分区一段
 * 文本 → 存进本 BE 并 {@code sendBlockUpdated} 送出去；客户端那份数据一到，面板的每帧比对
 * （{@code UIEvents.TICK}）就把 Label 的文本换掉。</p>
 *
 * <h3>刷新语义（用户要求：每秒更新一次）</h3>
 * <ol>
 *   <li><b>每秒一次（20 tick）</b>：{@link #tick()} 自己数 tick，每 {@value #REFRESH_TICKS} tick 采一次。
 *       为什么不是每 tick：一次快照要读 guest 内存（邮箱在途调用、消息缓存区占用）、要读每块盘的
 *       分区表 —— 那是给人看的读数，20 tick 的粒度足够让数字"在动"，每 tick 采只是白烧主线程。</li>
 *   <li><b>只在内容变化时才发包</b>：七个分区是**全量文本**，一秒一发等于每秒推几 KB 的字符串；
 *       所以采完之后先跟手上这份逐字比（{@link SandboxInspect#sameSections}），全同就直接
 *       丢掉、连包都不发、日志一个字都不写。</li>
 *   <li><b>只在有人看着时刷</b>：没有玩家的面板指着这台分析器时，客户端根本没有那份 UI 树可更新，
 *       采集纯属白干（世界里有几台分析器就每秒乘几份）。判据不是"玩家在附近"这种猜测，而是
 *       "服务端此刻真有一个指向本方块位置的 LDLib2 容器菜单打开着"（{@link #watched(Level)}）。</li>
 *   <li><b>右键 = 强制重扫 + 强制同步</b>：用户主动打开面板时目标可能换了（机器被搬走/新建），
 *       所以右键走 {@link #collect(Level, BlockPos)}（重扫目标 + 无条件同步一次），不受上面两条限制。</li>
 *   <li><b>目标会自愈</b>：目标机被拆、停机、区块卸载都不是"崩"，而是下一次刷新要处理的状态
 *       —— 目标方块没了就重扫（可能换到旁边另一台，也可能明确变成"没找到"），区块没加载就**记住**
 *       原目标并让标题栏讲清楚（见 {@code AdvancedAnalyzerPanelData.tickTarget}）。</li>
 * </ol>
 *
 * <p>另外它记着"上一次锁定的分析目标"：面板每次打开都会重建，目标坐标存在方块里，
 * 面板才能稳定显示"这一台分析器正对着哪台机器"。目标解析本身需要 OC 的类型
 * （{@code MachineHost}），而 {@code soc} 子包**不许引用 OC 的任何类型**（OC 缺席时本子包必须
 * 照常加载）—— 所以解析动作留在 {@code soc.ui} 的门面里。</p>
 */
public class AdvancedAnalyzerBlockEntity extends BlockEntity {

    /**
     * 自动刷新周期（tick）。20 tick = 1 秒 = 用户要求的"每秒更新一次"。
     *
     * <p>为什么不每 tick：见类注释 §3「刷新语义」第 1 条 —— 一次采集要碰 guest 内存与盘元数据。</p>
     */
    public static final int REFRESH_TICKS = 20;

    /** 上次锁定的目标机坐标；{@code null} = 还没找到 */
    @Nullable
    private BlockPos target;

    /** 上次解析的一句话结论（面板标题栏用；纯展示，不参与任何逻辑） */
    private String note = "";

    /**
     * 七个分区各一段文本（下标 = {@link SandboxInspect#SECTIONS} 的下标）。
     *
     * <p>存**排版好的文本**而不是结构化数值：排版唯一来源是 common 的 {@link SandboxInspect}
     * —— 到了客户端就只剩"显示"这一件事，面板里再算一遍数值必然与 {@code soc_inspect} 对不上账。</p>
     */
    private final String[] sections = new String[SandboxInspect.SECTIONS.size()];

    /**
     * 采集时刻（世界时间；面板显示"快照于 …"）。
     *
     * <p>语义 = **客户端正看着的那份数据是什么时候采的**（即最近一次真的发出去的那份），
     * 不是"最近一次采集"。内容没变就不发包也不改这个值，标题栏上的时刻才与屏幕上的数字对得上。</p>
     */
    private long collectedAt = -1L;

    /** 自动刷新的 tick 计数（瞬态：不需要存档，重启世界后重新数一遍即可） */
    private int refreshTimer;

    public AdvancedAnalyzerBlockEntity(BlockPos pos, BlockState state) {
        super(com.hdf.cryptand.neoforge.soc.content.SocContent.ADVANCED_ANALYZER_BE.get(), pos, state);
        java.util.Arrays.fill(sections, "");
    }

    @Nullable
    public BlockPos target() {
        return target;
    }

    public String note() {
        return note;
    }

    public long collectedAt() {
        return collectedAt;
    }

    /** 某个分区的文本块（没采集过 = 空串，面板显示"等待服务端…"） */
    public String section(int index) {
        return index >= 0 && index < sections.length ? sections[index] : "";
    }

    /** 是否已经采集过一次（面板用它区分"还没来"与"真的是空的"） */
    public boolean collected() {
        return collectedAt >= 0L;
    }

    // ==================== 每秒刷新（唯一的自动触发点） ====================

    /**
     * 服务端每 tick 调用一次（由 {@code AdvancedAnalyzerBlock.getTicker} 挂上；客户端不挂）。
     *
     * <p>这就是全工程**唯一**的自动采样入口：数够 {@value #REFRESH_TICKS} tick（= 1 秒）才往前走，
     * 而且只在有人看着这台方块时才真的采（见 {@link #watched(Level)}）。写在这里而不是
     * 挂在别的什么全局 tick 事件上，是为了"这台方块自己的节拍归它自己管" —— 不会因为别的
     * 子系统改周期而跟着变。</p>
     */
    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (++refreshTimer < REFRESH_TICKS) {
            return;
        }
        // 先归零再判有没有人看：没人的这一秒也算走完了，玩家一打开面板最多等 1 秒就有新数据
        refreshTimer = 0;
        if (!watched(level)) {
            return;
        }
        refresh();
    }

    /**
     * 此刻是否真有玩家的面板指着本方块。
     *
     * <p>判据用**服务端真实打开的容器菜单**（LDLib2 的 BlockUI 一定走
     * {@code ModularUIContainerMenu} + {@code BlockUIHolder}，见 {@code BlockUIMenuType.openUI}），
     * 不是"玩家在附近"这类猜测：猜测会让站在旁边聊天的人也每秒触发采集，反而更贵；
     * 也不是自己维护的"打开/关闭"标志位 —— 那种标志位在掉线、被传送、原版强制关屏时
     * 都可能漏掉一次关闭通知，于是永远刷下去（这类泄漏只能靠重启世界消掉）。菜单引用是
     * **每 tick 现查的既成事实**，玩家一关面板下一个 tick 就自然不刷了。</p>
     */
    private boolean watched(Level level) {
        for (final Player player : level.players()) {
            if (player.containerMenu instanceof ModularUIContainerMenu menu
                    && menu.uiHolder instanceof BlockUIMenuType.BlockUIHolder holder
                    && worldPosition.equals(holder.pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自动刷新一次：**沿用上次的目标**（并让它自愈），只有内容真的变了才同步到客户端。
     *
     * <p>与右键那条路的区别就是"目标从哪来"和"要不要强制发包"：这里不重扫邻居表
     * （分析器没动，邻居表也不会凭空多出机器），只在目标**没了**的时候才重扫。</p>
     */
    private void refresh() {
        collect(level, AdvancedAnalyzerPanelData.tickTarget(level, worldPosition, target), false);
    }

    /**
     * 采集一次快照并同步到客户端（**只在服务端调用**）。右键走这条：重扫过目标，
     * 并且**无条件同步一次**（用户刚打开面板，客户端必须拿到当前这份）。
     *
     * @param level  服务端世界
     * @param target 目标机坐标；{@code null} = 没找到（照样采集，七个分区全占位符）
     */
    public void collect(Level level, @Nullable BlockPos target) {
        collect(level, target, true);
    }

    /**
     * 采集 + 按需同步。
     *
     * @param force {@code true} = 无论内容有没有变都发（用户主动打开面板）；
     *              {@code false} = 每秒自动刷新，内容逐字相同就不发（网络代价：七个分区的全量文本）
     */
    private void collect(Level level, @Nullable BlockPos target, boolean force) {
        if (level == null || level.isClientSide()) {
            return;
        }
        final BlockPos resolved = target == null ? null : target.immutable();
        final String freshNote = AdvancedAnalyzerPanelData.describeTarget(level, resolved);
        final SandboxInspect.Snapshot snapshot = AdvancedAnalyzerPanelData.snapshot(level, resolved);
        final String[] fresh = SandboxInspect.sectionTexts(snapshot);

        // "变化才发"的唯一比较点：七段文本逐字比 + 标题那句话 + 目标是不是换了。
        // 为什么把 note/target 也算进来：目标从 A 机换到 B 机、或区块卸载导致"查不到"，
        // 数字可能一模一样，但标题栏必须跟着改（否则就是"静默错显"）。
        final boolean changed = force
                || !freshNote.equals(note)
                || !Objects.equals(resolved, this.target)
                || !SandboxInspect.sameSections(sections, fresh);
        if (!changed) {
            return;
        }

        this.target = resolved;
        this.note = freshNote;
        System.arraycopy(fresh, 0, sections, 0, sections.length);
        this.collectedAt = level.getGameTime();
        setChanged();
        syncToClient();
    }

    /** 记录/清空目标（{@code target == null} = 明确"没找到"，不是"保持上次"） */
    public void setTarget(@Nullable BlockPos target, String note) {
        this.target = target == null ? null : target.immutable();
        this.note = note == null ? "" : note;
        setChanged();
    }

    /** 把这份方块实体数据推给客户端（{@code getUpdatePacket()} 不重写的话原版默认返回 null，什么都不会发） */
    private void syncToClient() {
        if (level == null || level.isClientSide()) {
            return;
        }
        final BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 3);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * ⚠ 不存 {@code refreshTimer}：它是"距上次刷新过了几 tick"的瞬态计数，不是世界数据
     * （存档、同步都用不到它；存进 NBT 反而会让读档后的第一次刷新时机变得不可预测）。
     */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (target != null) {
            tag.putLong("Target", target.asLong());
        }
        tag.putString("Note", note);
        tag.putLong("CollectedAt", collectedAt);
        for (int i = 0; i < sections.length; i++) {
            tag.putString("S" + i, sections[i] == null ? "" : sections[i]);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        target = tag.contains("Target") ? BlockPos.of(tag.getLong("Target")) : null;
        note = tag.getString("Note");
        collectedAt = tag.contains("CollectedAt") ? tag.getLong("CollectedAt") : -1L;
        for (int i = 0; i < sections.length; i++) {
            sections[i] = tag.getString("S" + i);
        }
    }
}

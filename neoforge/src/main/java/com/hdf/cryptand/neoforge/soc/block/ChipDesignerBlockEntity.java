package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.soc.content.BlueprintItem;
import com.hdf.cryptand.neoforge.soc.content.SocContent;
import com.hdf.cryptand.soc.board.ChipConfig;
import com.hdf.cryptand.soc.board.ChipDesignLayout;
import com.hdf.cryptand.soc.link.PortKind;
import com.hdf.cryptand.soc.factory.ChipBlueprint;
import com.hdf.cryptand.soc.factory.ChipDraft;
import com.hdf.cryptand.soc.factory.ChipFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 芯片蓝图设计机方块实体（2026-09-29）=====
 *
 * <p>用户定案：「设置一个芯片蓝图设计机的方块……蓝图被载入后所有相关变更要保存到此蓝图物品的信息中」、
 * 「操作句柄是抽象，用于给不同客户」「停止制作后需要让工厂销毁」。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>槽 0 = 蓝图物品（数据组件里带着设计内容）；</li>
 *   <li>服务端持有一个<b>在制句柄</b> {@link ChipDraft}（来自 common 的 {@link ChipFactory}）：
 *       打开面板时申请（空白蓝图 = 新建；已有内容的蓝图 = {@code beginFrom} 导入），
 *       按「停止制作」或方块被拆掉时 {@code destroy}（工厂登记表随之清空）；</li>
 *   <li>把句柄的当前状态做成 {@link #snapshot()} 同步给客户端 —— 面板两棵树读的是同一份快照，
 *       绝不在客户端另算一套（前端只是壳）。</li>
 * </ul>
 */
public class ChipDesignerBlockEntity extends BlockEntity {

    /** 蓝图槽 */
    public static final int SLOT_BLUEPRINT = 0;

    private final ItemStackHandler handler = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            refreshSnapshot();
            sync();
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };

    /** 在制句柄（服务端；不落盘 —— 重新打开面板会重新申请/导入） */
    private transient ChipDraft draft;

    /** 谁在制（玩家 uuid；诊断用） */
    private String designer = "";

    /**
     * 设计态（EDA 画布数据，2026-09-29 用户定案）：芯片通用点 + 拖入的模块 + 连线。
     *
     * <p>规则在 common 的 {@link ChipDesignLayout}（纯 Java、有离线闸门）：
     * <b>一条连线消耗 1 个资源点数</b>，超了就拒绝并给中文原因（面板打到画布左下角）。</p>
     */
    private ChipDesignLayout.State design = ChipDesignLayout.empty(0);

    /** 面板快照（同步给客户端；两棵树读它） */
    private CompoundTag snapshot = new CompoundTag();

    public ChipDesignerBlockEntity(BlockPos pos, BlockState state) {
        super(SocContent.CHIP_DESIGNER_BE.get(), pos, state);
    }

    public ItemStackHandler handler() {
        return handler;
    }

    public ItemStack blueprintStack() {
        return handler.getStackInSlot(SLOT_BLUEPRINT);
    }

    public ChipDraft draft() {
        return draft;
    }

    public boolean isDesigning() {
        return draft != null;
    }

    public String designer() {
        return designer;
    }

    // ==================== 句柄生命周期 ====================

    /**
     * 打开面板时申请句柄：空白/无蓝图 ⇒ 新建（默认 CPU 示例）；已有内容的蓝图 ⇒ 申请 + 导入内容
     * （用户：「如果需要再次编辑蓝图只需要申请一个句柄然后导入操作即可」）。
     */
    public void openDraft(ServerPlayer player) {
        if (draft != null) {
            return;   // 已经在制（同一个面板重开不重新申请）
        }
        final ItemStack stack = blueprintStack();
        final ChipBlueprint blueprint = stack.isEmpty() ? ChipBlueprint.blank() : BlueprintItem.blueprint(stack);
        draft = blueprint.complete()
                ? ChipFactory.beginFrom(blueprint.config(), player.getStringUUID())
                : ChipFactory.begin(com.hdf.cryptand.soc.board.ChipType.CPU, player.getStringUUID());
        designer = player.getStringUUID();
        refreshSnapshot();
        sync();
    }

    /** ★ 停止制作：工厂销毁句柄（幂等） */
    public void stopDraft() {
        if (draft != null) {
            ChipFactory.destroy(draft);
            draft = null;
        }
        designer = "";
        refreshSnapshot();
        sync();
    }

    /** 换类型：销毁旧句柄并按新类型重新申请（类型是申请时定下的，不许中途改） */
    public void beginType(com.hdf.cryptand.soc.board.ChipType type, ServerPlayer player) {
        stopDraft();
        draft = ChipFactory.begin(type, player.getStringUUID());
        designer = player.getStringUUID();
        afterAction();
    }

    /** 面板动作统一收尾：重算快照 + 通知客户端 */
    public void afterAction() {
        refreshSnapshot();
        setChanged();
        sync();
    }

    /** 把句柄的产出写进蓝图物品（槽空则新建一张空白蓝图） */
    public boolean writeBlueprint() {
        if (draft == null) {
            return false;
        }
        final ChipConfig config = draft.build();
        final ItemStack current = blueprintStack();
        final ChipBlueprint base = current.isEmpty() ? ChipBlueprint.blank() : BlueprintItem.blueprint(current);
        final ChipBlueprint done = base.withConfig(config);
        if (current.isEmpty()) {
            handler.setStackInSlot(SLOT_BLUEPRINT, BlueprintItem.create(done));
        } else {
            BlueprintItem.write(current, done);
            handler.setStackInSlot(SLOT_BLUEPRINT, current);
        }
        setChanged();
        refreshSnapshot();
        sync();
        return true;
    }

    // ==================== 快照（面板唯一数据源）====================

    public CompoundTag snapshot() {
        return snapshot;
    }

    /** 重算快照：句柄状态 + 组件表 + 预算 + 校验问题 + 蓝图状态 */
    public void refreshSnapshot() {
        final CompoundTag tag = new CompoundTag();
        final ItemStack stack = blueprintStack();
        final ChipBlueprint blueprint = stack.isEmpty() ? ChipBlueprint.blank() : BlueprintItem.blueprint(stack);
        tag.putBoolean("HasBlueprint", !stack.isEmpty());
        tag.putBoolean("BlueprintComplete", blueprint.complete());
        tag.putString("BlueprintName", blueprint.displayName());
        tag.putBoolean("Designing", draft != null);
        tag.putString("Designer", designer);
        if (draft != null) {
            // 预算跟着句柄走（族/档位决定资源点数）：已用线数可能暂时超预算，由设计动作自己拦
            design = new ChipDesignLayout.State(draft.slotBudget(), design.points(), design.modules(), design.wires());
            tag.putString("Type", draft.type().id());
            if (draft.family() != null) {
                tag.putString("Family", draft.family().name());
            }
            if (draft.isa() != null) {
                tag.putString("Isa", draft.isa().id());
            }
            tag.putInt("Mhz", draft.mhz());
            tag.putInt("Budget", draft.slotBudget());
            tag.putInt("Used", draft.slotsUsed());
            tag.put("Table", strings(draft.moduleTable()));
            tag.put("Modules", strings(draft.modules()));
            tag.put("Problems", strings(draft.problems()));
            tag.put("Fsync", strings(draft.familyOptions().stream().map(Enum::name).toList()));
            tag.put("Isas", strings(draft.isaOptions().stream().map(com.hdf.cryptand.soc.board.SocIsa::id).toList()));
        }
        tag.put("Design", designTag());
        tag.putString("DesignSummary", ChipDesignLayout.summary(design));
        snapshot = tag;
    }

    // ==================== 设计态（EDA 画布）====================

    public ChipDesignLayout.State design() {
        return design;
    }

    /** 设计动作统一收尾：成功就落状态，然后重算快照 + 通知客户端；失败把中文原因原样返回。 */
    private ChipDesignLayout.Result applyDesign(ChipDesignLayout.Result r) {
        if (r.ok()) {
            design = r.next();
        }
        afterAction();
        return r;
    }

    /** 在芯片核心方框上加一个通用点（白点）。 */
    public ChipDesignLayout.Result designAddPoint() {
        return applyDesign(ChipDesignLayout.addPoint(design));
    }

    public ChipDesignLayout.Result designRemovePoint(String pointId) {
        return applyDesign(ChipDesignLayout.removePoint(design, pointId));
    }

    /** 从左侧列表把模块放进画布（端口固定：按模块名映射到一种接口点）。 */
    public ChipDesignLayout.Result designAddModule(String moduleId) {
        final int n = design.modules().size();
        final ChipDesignLayout.Module m = new ChipDesignLayout.Module(moduleId, moduleId,
                List.of(portIdOf(moduleId)), 60 + (n % 3) * 90, 60 + (n / 3) * 90);
        return applyDesign(ChipDesignLayout.addModule(design, m));
    }

    public ChipDesignLayout.Result designRemoveModule(String moduleId) {
        return applyDesign(ChipDesignLayout.removeModule(design, moduleId));
    }

    public ChipDesignLayout.Result designMoveModule(String moduleId, int x, int y) {
        return applyDesign(ChipDesignLayout.moveModule(design, moduleId, x, y));
    }

    public ChipDesignLayout.Result designConnect(String moduleId, String port, String pointId) {
        return applyDesign(ChipDesignLayout.connect(design, moduleId, port, pointId));
    }

    public ChipDesignLayout.Result designDisconnect(String moduleId, String port) {
        return applyDesign(ChipDesignLayout.disconnect(design, moduleId, port));
    }

    /** 模块的固定接口点 id：按模块名认端口（uart0 → UART），认不出按总线（RV）。 */
    private static String portIdOf(String moduleName) {
        final PortKind byName = PortKind.byId(moduleName == null ? "" : moduleName);
        return (byName == null ? PortKind.RV : byName).portId(1);
    }

    /** 设计态 → NBT（随快照同步给客户端；也随蓝图物品落盘）。 */
    private CompoundTag designTag() {
        final CompoundTag t = new CompoundTag();
        t.putInt("Budget", design.budget());
        final ListTag pts = new ListTag();
        for (final ChipDesignLayout.ChipPoint p : design.points()) {
            pts.add(StringTag.valueOf(p.id()));
        }
        t.put("Points", pts);
        final ListTag mods = new ListTag();
        for (final ChipDesignLayout.Module m : design.modules()) {
            final CompoundTag mt = new CompoundTag();
            mt.putString("Id", m.id());
            mt.putString("Label", m.label());
            mt.putInt("X", m.x());
            mt.putInt("Y", m.y());
            mt.put("Ports", strings(m.ports()));
            mods.add(mt);
        }
        t.put("Modules", mods);
        final ListTag wires = new ListTag();
        for (final ChipDesignLayout.Wire w : design.wires()) {
            final CompoundTag wt = new CompoundTag();
            wt.putString("M", w.moduleId());
            wt.putString("P", w.port());
            wt.putString("G", w.chipPoint());
            wires.add(wt);
        }
        t.put("Wires", wires);
        return t;
    }

    /** NBT → 设计态（客户端读快照用）。预算以快照为准。 */
    public static ChipDesignLayout.State readDesign(CompoundTag t, int fallbackBudget) {
        if (t == null || t.isEmpty()) {
            return ChipDesignLayout.empty(fallbackBudget);
        }
        final List<ChipDesignLayout.ChipPoint> points = new ArrayList<>();
        final ListTag pts = t.getList("Points", 8);
        for (int i = 0; i < pts.size(); i++) {
            points.add(new ChipDesignLayout.ChipPoint(pts.getString(i)));
        }
        final List<ChipDesignLayout.Module> modules = new ArrayList<>();
        final ListTag mods = t.getList("Modules", 10);
        for (int i = 0; i < mods.size(); i++) {
            final CompoundTag mt = mods.getCompound(i);
            modules.add(new ChipDesignLayout.Module(mt.getString("Id"), mt.getString("Label"),
                    stringsOf(mt.getList("Ports", 8)), mt.getInt("X"), mt.getInt("Y")));
        }
        final List<ChipDesignLayout.Wire> wires = new ArrayList<>();
        final ListTag ws = t.getList("Wires", 10);
        for (int i = 0; i < ws.size(); i++) {
            final CompoundTag wt = ws.getCompound(i);
            wires.add(new ChipDesignLayout.Wire(wt.getString("M"), wt.getString("P"), wt.getString("G")));
        }
        return new ChipDesignLayout.State(t.getInt("Budget"), points, modules, wires);
    }

    private static List<String> stringsOf(ListTag list) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }

    private static ListTag strings(List<String> values) {
        final ListTag list = new ListTag();
        for (final String v : values) {
            list.add(StringTag.valueOf(v));
        }
        return list;
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ==================== 持久化与同步 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Slots", handler.serializeNBT(registries));
        tag.put("Snapshot", snapshot);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Slots")) {
            handler.deserializeNBT(registries, tag.getCompound("Slots"));
        }
        if (tag.contains("Snapshot")) {
            snapshot = tag.getCompound("Snapshot");
        }
    }

    /** 给客户端：区块包（进世界时）与更新包（变化时）都带上快照 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        tag.put("Slots", handler.serializeNBT(registries));
        tag.put("Snapshot", snapshot);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 方块被拆掉：在制句柄必须被工厂销毁（不然工厂登记表会泄漏） */
    @Override
    public void setRemoved() {
        stopDraft();
        super.setRemoved();
    }
}

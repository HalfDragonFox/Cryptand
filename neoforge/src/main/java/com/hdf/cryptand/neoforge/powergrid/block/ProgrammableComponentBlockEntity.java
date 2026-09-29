/**
 * ===== 可编程元件方块实体 =====
 *
 * 通用元件库容器：NBT 保存库条目名（LibraryName）+ 参数（Params），
 * buildCircuit 按库条目端口数创建端子（Cryptand 侧在 PhasorNetworkBuilder
 * 按库定义展开真实元件）。右键/配置棒打开元件库选择界面。
 */

package com.hdf.cryptand.neoforge.powergrid.block;

import com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit;
import com.hdf.cryptand.neoforge.core.library.ComponentLibrary;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;

import java.util.Map;

public class ProgrammableComponentBlockEntity extends ElectricBlockEntity {

    /** 方块最多端子数（4 面 CONNECTOR） */
    public static final int MAX_TERMINALS = 4;

    private String libraryName = "";
    /** 固化电路（选择模型时 resolve 写入；构建不依赖库） */
    private ResolvedCircuit circuit = null;
    /** 端子数缓存（buildCircuit 用） */
    private int portCount = 2;

    public ProgrammableComponentBlockEntity(BlockPos pos, BlockState state) {
        super(CreativeSources.PROGRAMMABLE_COMPONENT_BE.get(), pos, state);
    }

    public String getLibraryName() {
        return libraryName;
    }

    /** 固化电路（构建展开用；无则 null）。 */
    public ResolvedCircuit getResolvedCircuit() {
        return circuit;
    }

    /**
     * 选择模型：从元件库解析该条目为【固化电路】（参数求值 + 嵌套内联）
     * 并写入方块——之后构建不再依赖库文件。
     */
    public void setLibraryAndResolve(String name, Map<String, Double> params) {
        ResolvedCircuit circ = name == null || name.isEmpty()
                ? null : ComponentLibrary.get().resolve(name, params);
        if (circ == null || circ.elements.isEmpty()) {
            this.libraryName = name == null ? "" : name;
            this.circuit = null;
            this.portCount = 2;
        } else {
            this.libraryName = name;
            this.circuit = circ;
            this.portCount = Math.min(Math.max(circ.pins.size(), 1), MAX_TERMINALS);
        }
        setChanged();
        markDirtyNetwork();
    }

    /** 清空（重置为未配置）。 */
    public void clearLibrary() {
        this.libraryName = "";
        this.circuit = null;
        this.portCount = 2;
        setChanged();
        markDirtyNetwork();
    }

    public int getPortCount() {
        return portCount;
    }

    private void markDirtyNetwork() {
        try {
            CryptandTopologyManager.get().markTopologyChanged();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        int n = Math.min(Math.max(portCount, 1), MAX_TERMINALS);
        builder.setTerminalCount(n);
        // 只创建端子节点，不建模内部元件——真实元件由 Cryptand 侧按固化电路展开
        for (int i = 0; i < n; i++) {
            builder.terminalNode(i);
        }
    }

    // ========== NBT（Create SmartBlockEntity read/write 体系） ==========

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.libraryName = tag.getString("LibraryName");
        this.circuit = readCircuit(tag);
        if (circuit != null) {
            this.portCount = Math.min(Math.max(circuit.pins.size(), 1), MAX_TERMINALS);
        } else {
            this.portCount = 2;
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("LibraryName", libraryName);
        writeCircuit(tag, circuit);
    }

    /** 固化电路 → NBT（Elements 列表 + PortCount）。 */
    private static void writeCircuit(CompoundTag tag, ResolvedCircuit circ) {
        if (circ == null) {
            tag.putInt("PortCount", 2);
            tag.put("Elements", new ListTag());
            return;
        }
        tag.putInt("PortCount", circ.pins.size());
        ListTag list = new ListTag();
        for (ResolvedCircuit.Element el : circ.elements) {
            CompoundTag e = new CompoundTag();
            e.putString("T", el.type);
            ListTag ns = new ListTag();
            for (String n : el.nodes) {
                ns.add(net.minecraft.nbt.StringTag.valueOf(n));
            }
            e.put("N", ns);
            if (el.valueText != null) e.putString("V", el.valueText);
            ListTag ex = new ListTag();
            for (String x : el.extra) {
                ex.add(net.minecraft.nbt.StringTag.valueOf(x));
            }
            e.put("E", ex);
            list.add(e);
        }
        tag.put("Elements", list);
    }

    /** NBT → 固化电路。 */
    private static ResolvedCircuit readCircuit(CompoundTag tag) {
        ListTag list = tag.getList("Elements", Tag.TAG_COMPOUND);
        if (list.isEmpty()) return null;
        ResolvedCircuit circ = new ResolvedCircuit();
        int ports = tag.getInt("PortCount");
        for (int i = 0; i < ports; i++) {
            circ.pins.add("p" + i);
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            String type = e.getString("T");
            java.util.List<String> nodes = new java.util.ArrayList<>();
            ListTag ns = e.getList("N", Tag.TAG_STRING);
            for (int j = 0; j < ns.size(); j++) {
                nodes.add(ns.getString(j));
            }
            String valueText = e.contains("V") ? e.getString("V") : null;
            java.util.List<String> extra = new java.util.ArrayList<>();
            ListTag ex = e.getList("E", Tag.TAG_STRING);
            for (int j = 0; j < ex.size(); j++) {
                extra.add(ex.getString(j));
            }
            circ.elements.add(new ResolvedCircuit.Element(type, nodes, valueText, extra));
        }
        return circ;
    }
}

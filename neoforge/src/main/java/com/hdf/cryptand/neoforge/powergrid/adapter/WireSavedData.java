package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 导线网络【原版世界存档】（2026-08-14 用户要求：先不用 SQLite，参考原版
 * 保存方式——SavedData 随世界存档自动落盘，无自建数据库/驱动/加载器）。
 *
 * 存储：每世界（主维度）data/cryptand_wires.dat，CompoundTag 存导线列表。
 * 生命周期：
 *   - {@link #get}：进世界时从存档加载（触发 addEdge 恢复）
 *   - {@link #save}：世界保存时自动调用（NeoForge SavedData 机制），从
 *     WireNetworkManager 读边写入 NBT。
 *   - 兜底：图被误清空（退回主菜单时 edgeCount=0）→ 用
 *     {@link WireNetworkManager#lastSnapshot()}（最近一次非空快照），保证不丢。
 */
public class WireSavedData extends SavedData {

    public static final String DATA_NAME = "cryptand_wires";

    private final WireNetworkManager mgr;

    private WireSavedData(WireNetworkManager mgr) {
        this.mgr = mgr;
    }

    /** 获取（并触发加载）当前世界导线存档 */
    public static WireSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        () -> new WireSavedData(WireNetworkManager.get()),
                        (tag, prov) -> load(tag, prov, WireNetworkManager.get())),
                DATA_NAME);
    }

    private static WireSavedData load(CompoundTag tag, HolderLookup.Provider provider,
                                      WireNetworkManager mgr) {
        WireSavedData data = new WireSavedData(mgr);
        ListTag wires = tag.getList("wires", Tag.TAG_COMPOUND);
        for (int i = 0; i < wires.size(); i++) {
            try {
                CompoundTag e = wires.getCompound(i);
                String ak = e.getString("a");
                String bk = e.getString("b");
                if (ak.isEmpty() || bk.isEmpty() || ak.equals(bk)) continue;
                WirePoint a = new WirePoint(ak);
                WirePoint b = new WirePoint(bk);
                double r = e.getDouble("r");
                String therm = e.contains("therm") ? e.getString("therm") : null;
                double len = e.getDouble("len");
                String rendererId = e.contains("ren") ? e.getString("ren") : null;
                int colorOverride = e.contains("col") ? e.getInt("col") : 0;
                boolean self = e.getBoolean("self");
                String item = e.contains("item") ? e.getString("item") : null;
                mgr.addEdge(new WireEdge(a, b, r, therm, len, rendererId, colorOverride, self, item));
            } catch (Throwable ignored) {
            }
        }
        // 2026-08-20 设备点持久化（治本替代 ChunkLoad 反射遍历补丁）：放下即建网
        // 的孤立设备端子（无导线连接）随存档保存/恢复——世界重载后设备点不丢，
        // 设备照常建模（电机等 IElectricEntity 不再依赖 ChunkLoad/反推补丁）。
        ListTag devices = tag.getList("devices", Tag.TAG_STRING);
        java.util.List<String> dk = new java.util.ArrayList<>(devices.size());
        for (int i = 0; i < devices.size(); i++) {
            try {
                dk.add(devices.getString(i));
            } catch (Throwable ignored) {
            }
        }
        mgr.restoreDevicePoints(dk);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        java.util.List<WireEdge> edges = mgr.edgeList();
        // 兜底：图被误清空（退回主菜单时 edgeCount=0）→ 用最近一次非空快照
        if (edges.isEmpty()) edges = mgr.lastSnapshot();
        ListTag wires = new ListTag();
        for (WireEdge e : edges) {
            CompoundTag et = new CompoundTag();
            et.putString("a", e.a.key);
            et.putString("b", e.b.key);
            et.putDouble("r", e.resistance);
            if (e.temperatureKey != null) et.putString("therm", e.temperatureKey);
            et.putDouble("len", e.length);
            // 2026-08-14 渲染参数引用化：只存渲染器 id + 染色覆盖（不存 sag/color）
            if (e.rendererId != null) et.putString("ren", e.rendererId);
            if (e.colorOverride != 0) et.putInt("col", e.colorOverride);
            et.putBoolean("self", e.selfPlaced);
            if (e.itemId != null) et.putString("item", e.itemId);
            wires.add(et);
        }
        tag.put("wires", wires);
        // 2026-08-20 设备点持久化：孤立设备端子（无导线连接）——与导线一起
        // 落盘，世界重载后 restoreDevicePoints 恢复（治本替代 ChunkLoad 补丁）
        java.util.List<String> devKeys = mgr.devicePointKeys();
        if (!devKeys.isEmpty()) {
            ListTag devices = new ListTag();
            for (String k : devKeys) devices.add(net.minecraft.nbt.StringTag.valueOf(k));
            tag.put("devices", devices);
        }
        return tag;
    }
}

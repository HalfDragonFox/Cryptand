package com.hdf.cryptand.neoforge.powergrid.network.wire;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
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
        // ===== 2026-09-15 修复"重进世界后电路无功率 / 调参不更新"=====
        // 旧存档的 devices 只含"无导线连接的孤立端子"（有导线连接的端子当时被
        // 认为可由 edges 恢复而省略掉）—— 但 edges 恢复不了【同一设备的多个端子
        // 同属一个网络】这一信息。于是重载后同一设备的端子会被拆到不同的导线
        // 分量里（例如 A#1-B#0 一个分量、A#0-B#1 另一个分量）⇒ 构建器的设备端子
        // 簇扩展拿不到成对端子 ⇒ 设备元件无法组装 ⇒ 无功率，且没有 ParamSource
        // ⇒ 调参永不刷新。
        //
        // 这里用【当前图】的全部设备端子再收敛一次：devicePointKeys() 现在返回
        // 图上全部 B 点 → 按方块分组 addDevice → 把被导线分量拆散的同一设备端子
        // 重新并回一个网络（addDevice 内部 merge，幂等）。
        // ⚠ 不依赖存档里的 devices 内容，所以对【已存在的旧存档】同样生效。
        mgr.restoreDevicePoints(mgr.devicePointKeys());
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

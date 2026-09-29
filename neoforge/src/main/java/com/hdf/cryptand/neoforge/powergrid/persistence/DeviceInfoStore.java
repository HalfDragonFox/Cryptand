package com.hdf.cryptand.neoforge.powergrid.persistence;

import com.hdf.cryptand.circuitsimulation.compute.NetworkStructureCodec;
import com.hdf.cryptand.circuitsimulation.db.NetlistDatabase;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.DeviceInfoRecord;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备信息存储（2026-09-15 用户："BE 侧走 Sqlite 保存即可，接管 NBT 保存，保存组装器
 * 等信息"）。
 * <p>
 * ===== 它解决什么问题 =====
 * <p>
 * Cryptand 自管的设备运行时信息（电机转速/应力/EMF、绕组内部节点 id、各种可调状态）
 * 此前只活在【静态内存】里 —— 退出世界就没了，重进世界电机从 0 重爬。
 * 把它们塞进方块的 NBT 也不行：那会把存档格式和原版 BE 的序列化耦合死，
 * 而这些东西本来就不属于"原版方块配置"。
 * <p>
 * ===== 分工 =====
 * <ul>
 *   <li><b>谁提供</b>：设备自己的【组装器】（{@code Assembler.persistInfo}）——
 *       它建了这个设备，也就最清楚重建需要什么；绑定（{@link DeviceBinding}）只是通道。</li>
 *   <li><b>存什么</b>：KV（K = String，V = 通用变量，递归可嵌套），
 *       经 {@link NetworkStructureCodec#writeKv} 编成二进制。</li>
 *   <li><b>存哪儿</b>：SQLite 的 {@code device_info} 表（与网络结构缓存同库同生命周期）。</li>
 * </ul>
 * <p>
 * ===== 时序（与网络缓存完全一致的约定：游戏期间零 SQLite 交互） =====
 * <pre>
 *   游戏期间   ：信息从组装器现取现用（本类的内存表只是世界保存那一刻的快照）
 *   世界保存   ：collect() → 内存表 → saveAsync()/saveSync() 批量落库
 *   世界加载   ：loadAll() 一次性读回 → 组装器恢复时按 pos 取用
 * </pre>
 */
public final class DeviceInfoStore {

    /** pos → KV（当前世界的设备信息快照；世界保存时整体换掉） */
    private static final Map<BlockPos, Map<String, Object>> INFO =
            new ConcurrentHashMap<>();

    /** 当前维度 id（落库主键的一部分；世界加载/切换时更新） */
    private static volatile String DIM = "";

    private DeviceInfoStore() {
    }

    /** 维度 id（诊断/落库用） */
    public static String dim() {
        return DIM;
    }

    /**
     * 采集：遍历【绑定注册表】逐个问它要 KV（绑定再转给对应设备的组装器）。
     * <p>
     * 只采集真正有信息的设备：组装器不关心持久化（返回 null/空）就跳过 ——
     * 表里不为"没什么可存"的设备留空行。
     *
     * @return 采集到的设备数
     */
    public static int collect(String dim) {
        DIM = dim == null ? "" : dim;
        Map<BlockPos, Map<String, Object>> fresh = new ConcurrentHashMap<>();
        int[] n = {0};
        try {
            DeviceBinding.forEach(b -> {
                try {
                    Map<String, Object> kv = b.persistInfo();
                    if (kv == null || kv.isEmpty()) return;
                    fresh.put(b.pos(), kv);
                    n[0]++;
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        // 整体替换 = 快照语义：已消失设备的陈旧条目自动清理
        INFO.clear();
        INFO.putAll(fresh);
        return n[0];
    }

    /** 编码为落库记录（世界保存用；纯数据，可在写线程执行） */
    public static List<DeviceInfoRecord> toRecords() {
        List<DeviceInfoRecord> out = new ArrayList<>(INFO.size());
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, Map<String, Object>> en : INFO.entrySet()) {
            try {
                BlockPos p = en.getKey();
                Map<String, Object> kv = en.getValue();
                if (p == null || kv == null || kv.isEmpty()) continue;
                byte[] bytes = encode(kv);
                if (bytes == null) continue;
                out.add(new DeviceInfoRecord(DIM, p.getX(), p.getY(), p.getZ(),
                        null, bytes, now));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 世界保存（异步；不阻塞主线程） */
    public static void saveAsync(NetlistDatabase db) {
        if (db == null || db.isClosed()) return;
        try {
            db.saveDeviceInfosAsync(toRecords());
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[DevInfo] saveAsync failed", t);
        }
    }

    /** 退出世界（同步；立即落盘） */
    public static void saveSync(NetlistDatabase db) {
        if (db == null || db.isClosed()) return;
        try {
            db.saveDeviceInfosSync(toRecords());
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[DevInfo] saved {} device rows (sync)", INFO.size());
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[DevInfo] saveSync failed", t);
        }
    }

    /** 世界加载：一次性读回内存（组装器恢复时按 pos 取用） */
    public static int loadAll(NetlistDatabase db, String dim) {
        DIM = dim == null ? "" : dim;
        INFO.clear();
        if (db == null || db.isClosed()) return 0;
        int n = 0;
        try {
            for (DeviceInfoRecord r : db.loadAllDeviceInfos()) {
                if (r == null) continue;
                // 只取本维度的（同库可能含多维度行）
                if (DIM != null && !DIM.isEmpty() && r.dim() != null
                        && !DIM.equals(r.dim())) {
                    continue;
                }
                Map<String, Object> kv = decode(r.info());
                if (kv == null || kv.isEmpty()) continue;
                INFO.put(new BlockPos(r.x(), r.y(), r.z()), kv);
                n++;
            }
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[DevInfo] loadAll failed", t);
        }
        return n;
    }

    /** 取某设备保存的信息（无 → null）；组装器恢复路径用 */
    public static Map<String, Object> infoOf(BlockPos pos) {
        return pos == null ? null : INFO.get(pos);
    }

    /** 条目数（诊断） */
    public static int size() {
        return INFO.size();
    }

    /** 世界卸载：清内存（信息已落库） */
    public static void clear() {
        INFO.clear();
    }

    // ==================== KV 二进制 ====================

    private static byte[] encode(Map<String, Object> kv) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bos);
            NetworkStructureCodec.writeKv(o, kv);
            o.flush();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, Object> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return Collections.emptyMap();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            return NetworkStructureCodec.readKv(in);
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }
}
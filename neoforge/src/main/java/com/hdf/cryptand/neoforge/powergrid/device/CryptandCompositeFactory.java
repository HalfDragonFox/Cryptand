package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.circuitsimulation.compute.NetworkStructureCodec;
import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.persistence.DeviceInfoStore;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用复合元件恢复工厂（2026-09-15 用户："进入存档直接从 sqlite 恢复"）。
 * <p>
 * ===== 它在链路里的位置 =====
 * <pre>
 *   SQLite(device_info / network_cache)
 *        │  NetworkStructureCodec.decode
 *        ▼
 *   byte 5 通用条目 (compositeKey + 类名 + 展开元件 + KV)
 *        │  CompositeFactory.create(...)   ← 本类
 *        ▼
 *   按 compositeKey 解析出 pos → 找该设备的组装器
 *        │  Assembler.restoreFromInfo(pos, key, cls, expanded, KV)
 *        ▼
 *   组装器用展开元件 + KV 重建真实模型（电机/变压器/……）
 *        │  Assembler.bindAllPos(pos, ce, nets)
 *        ▼
 *   重新绑定（DeviceBinding 按坐标注册，引擎侧不再持任何 BE 引用）
 * </pre>
 * <p>
 * ===== 为什么放在 device 包 =====
 * 它要调 {@code Assembler.bindAllPos}（包内可见的 pos-only 绑定入口）；
 * common 侧不可能自己 new 出 MC 相关模型，所以恢复必须由这一层接管。
 * <p>
 * ⚠ 引擎线程执行：全程只读 {@link DeviceParamCache} / {@link DeviceInfoStore}（纯数据），
 * 绝不碰 Level/BE。
 */
public final class CryptandCompositeFactory
        implements NetworkStructureCodec.CompositeFactory {

    private static final CryptandCompositeFactory INSTANCE = new CryptandCompositeFactory();

    private CryptandCompositeFactory() {
    }

    /** 注册（模组初始化时调一次；幂等） */
    public static void register() {
        try {
            NetworkStructureCodec.setCompositeFactory(INSTANCE);
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[CompFactory] 通用复合元件恢复工厂已注册");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public CompositeElement create(Network net, String compositeKey, String className,
                                   Element[] expanded, Map<String, Object> info) {
        try {
            BlockPos pos = posOf(compositeKey);
            if (pos == null) return null;
            Assembler asm = assemblerFor(pos);
            if (asm == null) return null;
            // KV 合并：结构快照里带的那份 vs 设备信息表里那份（后者是"退出存档那一刻"，
            //  更新）→ 设备信息表优先覆盖。
            Map<String, Object> kv = new LinkedHashMap<>();
            if (info != null) kv.putAll(info);
            try {
                Map<String, Object> fromDb = DeviceInfoStore.infoOf(pos);
                if (fromDb != null && !fromDb.isEmpty()) kv.putAll(fromDb);
            } catch (Throwable ignored) {
            }
            CompositeElement ce = asm.restoreFromInfo(pos, compositeKey, className,
                    expanded, kv);
            if (ce == null) return null;
            // 身份 + 绑定：compositeKey 就是身份锚点（类型码 + 坐标）；绑定按坐标注册
            try {
                if (ce.compositeKey() == null) ce.setCompositeKey(compositeKey);
            } catch (Throwable ignored) {
            }
            try {
                Assembler.bindAllPos(pos, ce, null);  // nets=null：恢复期无原版网络集合
            } catch (Throwable ignored) {
            }
            return ce;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 pos 找设备的组装器（走主线程同步下来的参数缓存，不碰 BE） */
    private static Assembler assemblerFor(BlockPos pos) {
        try {
            DeviceParamCache.Entry de = DeviceParamCache.get(pos);
            if (de == null || de.deviceClass == null) return null;
            return Assemblers.getByClass(de.deviceClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 从 compositeKey 解析坐标。
     * <p>
     * 格式 = 类型码 + {@code BlockPos.toString()}，例如
     * {@code "MBlockPos{x=1, y=2, z=3}"} / {@code "WBlockPos{...}"}。
     * 用户明确："保存的话不需要另存三个 int 坐标" —— compositeKey 已经携带坐标，
     * 所以这里从它解析，不再在记录里冗余存一份。
     * <p>
     * 解析刻意写得宽容（找 x=/y=/z= 后的数字，允许负号与空格），任一处缺失即返回 null。
     */
    public static BlockPos posOf(String compositeKey) {
        if (compositeKey == null) return null;
        try {
            int ix = compositeKey.indexOf("x=");
            int iy = compositeKey.indexOf("y=");
            int iz = compositeKey.indexOf("z=");
            if (ix < 0 || iy < 0 || iz < 0) return null;
            int x = readInt(compositeKey, ix + 2);
            int y = readInt(compositeKey, iy + 2);
            int z = readInt(compositeKey, iz + 2);
            return new BlockPos(x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读一个十进制整数（允许前导空格与负号；非法 → Integer.MIN_VALUE） */
    private static int readInt(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == start) return Integer.MIN_VALUE;
        return Integer.parseInt(s.substring(start, i));
    }
}
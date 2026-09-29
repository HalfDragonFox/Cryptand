/**
 * ===== 变压器参数侧表 =====
 *
 * 每个变压器方块位置 → 自定义参数模型（魔法数字/铁心/导线规格）。
 * 无条目 → addTransformerElement 用默认魔法数字（行为不变）。
 * 用静态 ConcurrentHashMap（与 TransformerBlockEntityMixin 惯例一致，
 * mixin 注入字段可能被 Architectury Transformer 破坏）。
 */

package com.hdf.cryptand.neoforge.powergrid.state;

import net.minecraft.core.BlockPos;

import java.util.Map;

public final class TransformerParamStore {

    private TransformerParamStore() {
    }

    /** 指定方块的变压器参数（无则 null → 用默认魔法数字）。 */
    public static TransformerParameters get(BlockPos pos) {
        return DeviceParameterStore.get(pos, TransformerParameters.class);
    }

    /** 写入参数（模型套用后调用；可先调 {@link TransformerParameters#applyModel()}）。 */
    public static void put(BlockPos pos, TransformerParameters p) {
        DeviceParameterStore.put(pos, p);
    }

    public static void remove(BlockPos pos) {
        DeviceParameterStore.remove(pos);
    }

    /** 服务器停止/世界卸载清理。 */
    public static void clear() {
        DeviceParameterStore.clear();
    }

    public static Map<BlockPos, TransformerParameters> all() {
        java.util.Map<BlockPos, TransformerParameters> out = new java.util.HashMap<>();
        for (Map.Entry<BlockPos, DeviceParameters> e : DeviceParameterStore.all().entrySet()) {
            if (e.getValue() instanceof TransformerParameters tp) {
                out.put(e.getKey(), tp);
            }
        }
        return out;
    }
}

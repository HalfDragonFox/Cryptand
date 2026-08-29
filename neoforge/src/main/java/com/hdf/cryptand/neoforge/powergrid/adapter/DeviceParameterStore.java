/**
 * ===== 设备参数侧表（通用） =====
 *
 * 每个方块位置 → 设备参数模型（DeviceParameters 子类）。
 * 无条目 → 建模用默认魔法数字（行为不变）。
 * 用静态 ConcurrentHashMap（与 TransformerBlockEntityMixin 惯例一致）。
 * 受配置 ENABLE_DEVICE_PARAMETER_MODELS 控制（关闭时建模忽略侧表）。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DeviceParameterStore {

    private static final Map<BlockPos, DeviceParameters> PARAMS = new ConcurrentHashMap<>();

    private DeviceParameterStore() {
    }

    /** 指定方块的参数（无则 null → 用默认魔法数字）。 */
    public static DeviceParameters get(BlockPos pos) {
        return pos == null ? null : PARAMS.get(pos);
    }

    /** 指定方块 + 类型限定（类型不匹配返回 null）。 */
    public static <T extends DeviceParameters> T get(BlockPos pos, Class<T> cls) {
        DeviceParameters p = get(pos);
        return p != null && cls.isInstance(p) ? cls.cast(p) : null;
    }

    /** 写入参数（模型套用后调用；可先调 {@link DeviceParameters#applyModel()}）。 */
    public static void put(BlockPos pos, DeviceParameters p) {
        if (pos != null && p != null) {
            PARAMS.put(pos, p);
        }
    }

    public static void remove(BlockPos pos) {
        if (pos != null) PARAMS.remove(pos);
    }

    /** 服务器停止/世界卸载清理。 */
    public static void clear() {
        PARAMS.clear();
    }

    public static Map<BlockPos, DeviceParameters> all() {
        return PARAMS;
    }
}

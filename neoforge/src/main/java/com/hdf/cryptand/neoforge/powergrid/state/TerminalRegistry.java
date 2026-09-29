/**
 * ===== 端子注册表（2026-08-13 用户架构：完全接管端子） =====
 *
 * 全局单例：方块端子（pos#term）→ 引擎端子测试点（{@link TerminalElement}）的
 * 【稳定映射】。核心价值：
 *
 *   - 【稳定】：key = 位置（pos#term），不随引擎网络重建 / PowerGrid 节点实例
 *     替换而变化。即使网络分裂、ctx 缓存命中/失效、原版节点对象被替换，只要
 *     方块还在，注册表里的端子映射就始终有效。
 *   - 【接管】：引擎侧端子由 Cryptand 无条件创建并注册（见
 *     {@code PhasorNetworkBuilder} 4.5 段）——不依赖原版 buildCircuit 时序 /
 *     getTerminal 是否返回节点。原版端子节点仅作为【电压写回目标】
 *     （PowerGrid network.setValue），不再是 Cryptand 拓扑依据。
 *   - 【消费统一】：写回（DEVICE_TERMINAL_V）、失效（invalidate）、诊断、原理图
 *     导出全部走注册表，杜绝各处各自遍历 ctx / 反查 nodeToEngine 的散乱逻辑。
 *
 * 线程安全：ConcurrentHashMap，构建线程写、消费线程（渲染/主线程）读。
 *
 * 生命周期：
 *   - 构建时（4.5 段）put：新建 TerminalElement 覆盖旧对象（engineNode 随
 *     ctx 网络更新，旧对象被 GC）。
 *   - 设备移除（ElectricBlockEntityRemoveMixin → setRemoved）→ unregister(pos)：
 *     清掉该方块所有端子，防止残留旧映射。
 *   - 失效（EngineMeasurements.invalidateTerminals）→ 遍历注册表 invalidate 端子测试点。
 */
package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public final class TerminalRegistry {

    private TerminalRegistry() {
    }

    /** pos#term → TerminalElement（引擎测试点；每次构建覆盖，engineNode 随 ctx 更新） */
    private static final ConcurrentHashMap<String, TerminalElement> TERMINALS =
            new ConcurrentHashMap<>();

    /** key：设备方块位置 + 端子索引（"B" + pos + "#" + term，与引擎 deviceKey 一致） */
    public static String key(BlockPos pos, int term) {
        return "B" + pos + "#" + term;
    }

    /** 注册（覆盖旧对象）：构建时 4.5 段调用。 */
    public static void register(BlockPos pos, int term, TerminalElement t) {
        if (pos == null || t == null) return;
        TERMINALS.put(key(pos, term), t);
    }

    /** 按位置 + 端子索引取端子测试点；未注册返回 null */
    public static TerminalElement get(BlockPos pos, int term) {
        if (pos == null) return null;
        return TERMINALS.get(key(pos, term));
    }

    /** 移除某方块的全部端子（设备拆除/区块卸载清理） */
    public static void unregister(BlockPos pos) {
        if (pos == null) return;
        String prefix = "B" + pos + "#";
        TERMINALS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 位置迁移（2026-08-23 物理化）：该方块端子映射 key 从旧坐标搬到新坐标，
     *  保持 TerminalElement 引用（引擎测试点稳定，不丢绑定）。 */
    public static void move(BlockPos oldPos, BlockPos newPos) {
        if (oldPos == null || newPos == null || oldPos.equals(newPos)) return;
        String oldPrefix = "B" + oldPos + "#";
        String newPrefix = "B" + newPos + "#";
        for (var en : TERMINALS.entrySet()) {
            String k = en.getKey();
            if (k == null || !k.startsWith(oldPrefix)) continue;
            String nk = newPrefix + k.substring(oldPrefix.length());
            TERMINALS.put(nk, en.getValue());
            TERMINALS.remove(k);
        }
    }

    /** 全部端子（遍历失效/诊断/原理图导出） */
    public static Collection<TerminalElement> all() {
        return TERMINALS.values();
    }

    /** 失效全部端子测试点（稳定清零/世界卸载联动）：消费端读 null → 不动作。 */
    public static void invalidateAll() {
        for (TerminalElement t : TERMINALS.values()) {
            try {
                t.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 当前注册端子总数（诊断） */
    public static int size() {
        return TERMINALS.size();
    }

    /** 清空全部（世界卸载/重载） */
    public static void clear() {
        TERMINALS.clear();
    }
}

/**
 * ===== 原理图导出桥（2026-08-22 用户需求） =====
 *
 * 用户要求："通过发送导出请求（带网络）来直接导出虚拟电路而不是检测
 * 实际 BE 模型"。本桥是 MC 侧（neoforge）唯一的导出入口：
 *   - 构造【导出请求】（SchematicExportRequest = CommOp.EXPORT_SCHEMATIC
 *     相同语义：带网络（worldName + networkKey））；
 *   - 交给【核心导出门面】SchematicExporters（与 CommRequestHandler 处理
 *     EXPORT_SCHEMATIC 用的完全同一个门面）——直接读 NetworkWorld 的
 *     虚拟电路数据（CachedNetwork：WirePoint/WireEdge/DeviceInfo）导出。
 *
 * 铁律：本桥【不接收 Level / 不检测任何 BlockEntity】；导出在核心完成。
 * 未来可平滑切换到经 CommClient（TCP/UDP/进程内）远程发送请求——门面不变。
 */

package com.hdf.cryptand.neoforge.core.export;

import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.circuitsimulation.export.SchematicExporters;
import com.hdf.cryptand.circuitsimulation.export.SchematicExportRequest;
import com.hdf.cryptand.circuitsimulation.export.SchematicExportResult;
import com.hdf.cryptand.circuitsimulation.export.SchematicFormat;
import com.hdf.cryptand.neoforge.powergrid.adapter.MainThreadInteractionManager;

public final class SchematicExportBridge {

    private SchematicExportBridge() {
    }

    /**
     * 发送导出请求（带网络）→ 核心直接导出虚拟电路。
     *
     * @param networkKey 目标网络 key（null = 导出该世界所有网络）
     * @param format     导出格式（null = 默认 CUSTOM_EDA）
     */
    public static SchematicExportResult export(String networkKey, SchematicFormat format) {
        NetworkWorld w = MainThreadInteractionManager.get().world();
        if (w == null) return null;
        SchematicFormat f = (format == null) ? SchematicFormat.CUSTOM_EDA : format;
        SchematicExportRequest req = SchematicExportRequest.of(w.name(), networkKey).withFormat(f);
        return SchematicExporters.export(w, req);
    }

    /** 导出全部网络（默认自定义 EDA） */
    public static SchematicExportResult exportAll() {
        return export(null, SchematicFormat.CUSTOM_EDA);
    }

    /** 导出指定网络（默认自定义 EDA） */
    public static SchematicExportResult exportNetwork(String networkKey) {
        return export(networkKey, SchematicFormat.CUSTOM_EDA);
    }
}
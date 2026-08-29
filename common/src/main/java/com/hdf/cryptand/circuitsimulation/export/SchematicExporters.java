package com.hdf.cryptand.circuitsimulation.export;

import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 原理图导出服务（门面，2026-08-22 核心导出扩展：统一出口 + 扩展注册表）。
 * <p>
 * 通信层（CommRequestHandler 的 EXPORT_SCHEMATIC）与本地调用都经
 * {@link #export(NetworkWorld, SchematicExportRequest)} 路由到对应格式的
 * 导出器；未注册/未支持的格式 → 返回"暂不支持，请用默认自定义 EDA"失败结果。
 * <p>
 * 默认注册 {@link CustomEdaExporter}（自定义 EDA，test 前端格式）——按下
 * 用户要求"默认自定义 eda，并且暂时支持这个"。
 */
public final class SchematicExporters {

    private static final List<SchematicExporter> EXPORTERS = new CopyOnWriteArrayList<>();

    static {
        register(new CustomEdaExporter());
    }

    private SchematicExporters() {
    }

    /** 注册扩展格式导出器（可插拔） */
    public static void register(SchematicExporter exporter) {
        if (exporter != null && !EXPORTERS.contains(exporter)) EXPORTERS.add(exporter);
    }

    /** 查询指定格式的导出器（未注册 null） */
    public static SchematicExporter exporterOf(SchematicFormat format) {
        if (format == null) return null;
        for (SchematicExporter e : EXPORTERS) if (e.format() == format) return e;
        return null;
    }

    /**
     * 导出原理图（统一出口）。世界为空 → 失败；格式未支持 → 返回
     * "暂不支持，请用默认自定义 EDA(custom-eda)" 失败结果。
     */
    public static SchematicExportResult export(NetworkWorld world, SchematicExportRequest req) {
        SchematicFormat format = (req == null)
                ? SchematicFormat.CUSTOM_EDA : req.format();
        if (world == null) {
            return SchematicExportResult.fail(format,
                    "world is null（无法导出，无虚拟电路数据）");
        }
        SchematicExporter exporter = exporterOf(format);
        if (exporter == null) {
            return SchematicExportResult.fail(format,
                    "暂不支持的导出格式 '" + format.id() + "'（" + format.displayName()
                            + "）；当前支持：" + SchematicFormat.CUSTOM_EDA.id()
                            + "（" + SchematicFormat.CUSTOM_EDA.displayName() + "）");
        }
        try {
            return exporter.export(world, req);
        } catch (Throwable t) {
            return SchematicExportResult.fail(format, String.valueOf(t));
        }
    }
}
/**
 * ===== 电路图导出服务（core/export 分类包，2026-08-22 用户要求类分类） =====
 *
 * 导出触发 → 走【核心导出请求】：
 *   - 不再接收 Level（旧版接 ServerLevel 并遍历自管图 + DeviceParamCache）；
 *   - 改为经 {@link SchematicExportBridge} 发送导出请求（带网络）→ 核心
 *     直接导出虚拟电路（不检测任何实际 BE 模型）；
 *   - 分配器工作线程异步执行（不阻塞 MC 主线程），完成后回主线程通知玩家。
 *
 * 默认保存路径：<gamedir>/cryptand/circuits/（客户端 = .minecraft/…，
 * 开发环境 = neoforge/run/cryptand/circuits）。EDA 打开路径默认同一目录。
 *
 * 触发：命令 /cryptand export（全部）/ /cryptand export here|<x y z>（指定网络）。
 */

package com.hdf.cryptand.neoforge.core.export;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.circuitsimulation.export.SchematicExportResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

public final class CircuitExportService {

    /** 文件名时间戳：20260822_153000 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private CircuitExportService() {
    }

    // ==================== 目录 ====================

    /** 电路图目录：<gamedir>/cryptand/circuits/（EDA 打开也默认此目录，自动创建） */
    public static Path circuitsDir() {
        Path dir = FMLPaths.GAMEDIR.get()
                .resolve("cryptand").resolve("circuits");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    /** 列出电路图目录中的 .json 文件（EDA 打开列表用） */
    public static java.util.List<Path> listCircuits() {
        java.util.List<Path> out = new java.util.ArrayList<>();
        Path dir = circuitsDir();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    // ==================== 异步导出（分配器；核心请求导出） ====================

    /** 异步导出【全部网络】（核心请求；分配器工作线程执行；完成后主线程通知） */
    public static void exportAllAsync(ServerPlayer player) {
        ThreadDispatchers.submitGeneric(() -> {
            try {
                SchematicExportResult r = SchematicExportBridge.exportAll();
                if (!r.ok) {
                    notify(player, "电路图导出失败：" + r.error);
                    return;
                }
                int nComp = r.componentCount;
                Path out = save(circuitsDir(),
                        "circuit_all_" + stamp() + ".json", r.content);
                notify(player, "电路图已异步导出（EDA 打开目录）：" + out
                        + "（元件 " + nComp + " 个）");
            } catch (Throwable t) {
                notify(player, "电路图导出失败：" + t);
            }
        });
    }

    /** 异步导出【指定方块所在网络】（核心请求；无网络 → 提示；完成后主线程通知） */
    public static void exportAtAsync(BlockPos target, ServerPlayer player) {
        ThreadDispatchers.submitGeneric(() -> {
            try {
                String netKey = netKeyOf(target);
                if (netKey == null) {
                    notify(player, "该位置不在任何电气网络中: "
                            + (target == null ? "null" : target.toShortString()));
                    return;
                }
                SchematicExportResult r = SchematicExportBridge.exportNetwork(netKey);
                if (!r.ok) {
                    notify(player, "电路图导出失败：" + r.error);
                    return;
                }
                int nComp = r.componentCount;
                String name = (target == null)
                        ? "circuit_here_" + stamp() + ".json"
                        : "circuit_" + target.getX() + "_" + target.getY()
                                + "_" + target.getZ() + "_" + stamp() + ".json";
                Path out = save(circuitsDir(), name, r.content);
                notify(player, "电路图已异步导出（EDA 打开目录）：" + out
                        + "（元件 " + nComp + " 个）");
            } catch (Throwable t) {
                notify(player, "电路图导出失败：" + t);
            }
        });
    }

    /** 定位方块所在网络 key（虚拟电路 byPoint 查询；不在任何网络 → null） */
    public static String netKeyOf(BlockPos pos) {
        if (pos == null) return null;
        var world = com.hdf.cryptand.neoforge.powergrid.adapter
                .MainThreadInteractionManager.get().world();
        if (world == null) return null;
        for (int t = 0; t < 8; t++) {
            var net = world.networkOf("B" + pos + "#" + t);
            if (net != null) return String.valueOf(net.key());
        }
        return null;
    }

    // ==================== 内部 ====================

    /** 回主线程通知玩家（工作线程调用安全；玩家已断开时静默忽略） */
    private static void notify(ServerPlayer player, String msg) {
        if (player == null || player.connection == null) return;
        player.server.execute(() -> {
            try {
                player.sendSystemMessage(Component.literal(msg));
            } catch (Throwable ignored) {
            }
        });
    }

    private static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    /** 粗略统计元件数（JSON 中 "type" 出现次数；失败 0） */
    private static int countComponents(String json) {
        if (json == null) return 0;
        return json.contains("\"components\"")
                ? json.split("\"type\"").length - 1 : 0;
    }

    private static Path save(Path dir, String name, String json) throws IOException {
        Path out = dir.resolve(name);
        Files.write(out, json.getBytes(StandardCharsets.UTF_8));
        return out;
    }
}
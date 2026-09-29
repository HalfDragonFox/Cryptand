package com.hdf.cryptand.neoforge.soc.compile;

import com.hdf.cryptand.toolchain.CCompileResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * ===== SoC 编译界面信息输出（2026-09-15）=====
 *
 * <p>用户要求：<b>编译前先在界面打印使用的相关工具信息</b>，缺工具时提示缺少什么，
 * 编译后给出日志与诊断。当前以聊天栏/日志为载体；芯片方块与 LDLib2 面板接入后，
 * 同一批文本直接喂给面板控件（本类即数据源）。</p>
 */
public final class SocCompileUi {

    private SocCompileUi() {
    }

    /** 编译前：打印"将使用哪些工具"（含查找轨迹摘要与编译方案） */
    public static void printToolInfo(Player player) {
        if (player == null) {
            return;
        }
        for (String line : ClientToolchainService.displayLines()) {
            player.displayClientMessage(Component.literal(line), false);
        }
    }

    /** 编译前：打印编译方案（本地 → 服务端 → 缺工具） */
    public static void printPlan(Player player) {
        if (player == null) {
            return;
        }
        for (String line : ClientToolchainService.router().planLines()) {
            player.displayClientMessage(Component.literal(line), false);
        }
    }

    /** 编译后：结果 + 诊断列表 + 产物大小 */
    public static void printResult(Player player, CCompileResult result) {
        if (player == null || result == null) {
            return;
        }
        if (result.ok()) {
            player.displayClientMessage(Component.literal(
                    "[SoC] 编译成功：" + result.binarySize() + " 字节固件，耗时 " + result.elapsedMs() + " ms"), false);
            player.displayClientMessage(Component.literal("[SoC] 工具链：" + result.toolchain()), false);
        } else {
            player.displayClientMessage(Component.literal("[SoC] 编译失败：" + result.error()), false);
        }
        final List<CCompileResult.Diagnostic> diagnostics = result.diagnostics();
        for (int i = 0; i < Math.min(diagnostics.size(), 20); i++) {
            player.displayClientMessage(Component.literal("  " + diagnostics.get(i)), false);
        }
        if (diagnostics.size() > 20) {
            player.displayClientMessage(Component.literal("  …（其余 " + (diagnostics.size() - 20) + " 条见日志）"), false);
        }
    }
}

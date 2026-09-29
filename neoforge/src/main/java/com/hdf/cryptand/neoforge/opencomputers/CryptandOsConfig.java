/**
 * ===== Cryptand OS 系统配置（宿主侧，2026-09-17）=====
 *
 * <p>用户要求：<b>"系统上电可以通过读取配置文件来修改，这样进入系统后只需要修改文件重启就行了"</b>。</p>
 *
 * <h3>机制（零设备、零 ROM 开销）</h3>
 * <ol>
 *   <li>玩家改 {@code config/cryptand/cryptand-os.cfg}（普通 key=value 文本，改完不用重启游戏）；</li>
 *   <li>机器上电时（OC 的 power cycle 或重放卡）本类把文件内容注入
 *       <b>guest RAM 顶端保留的 4KB 配置块</b>（链接脚本 {@code _config_block}，栈顶已让出这段）；</li>
 *   <li>固件用 {@code hal_config_get/int/bool} 直接读内存 ⇒ 新配置立即生效。</li>
 * </ol>
 *
 * <p>文件不存在时自动写一份带注释的默认配置，玩家照着改即可。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CryptandOsConfig {

    /** 配置文件路径（相对游戏运行目录） */
    public static final String FILE = "config/cryptand/cryptand-os.cfg";

    /** 配置块魔数（必须与固件 hal.h 的 HAL_CONFIG_MAGIC 一致） */
    public static final String MAGIC = "CFG1";

    /** 配置块保留字节数（必须与链接脚本 CONFIG_RESERVE 一致） */
    public static final int RESERVE_BYTES = 4096;

    /**
     * 配置块地址 —— **必须与固件链接脚本的布局一致，不能按"机箱实际内存"算**。
     *
     * <p>踩过的坑：一开始用 {@code RAM_BASE + 实际内存 - 4K} 注入，而固件读的是
     * {@code RAM_BASE + 链接脚本RAM(128K) - 4K}；机箱插 256KB 内存条时两者差 128KB，
     * 固件于是报 {@code config block (absent)} 并退回默认值（实测）。</p>
     */
    public static final long CONFIG_BLOCK_ADDR = 0x2000_0000L + 128L * 1024L - RESERVE_BYTES;

    private static final String DEFAULT_TEXT = """
            CFG1
            # ============================================================
            # Cryptand OS 系统配置（宿主侧）
            #   改完这个文件后：在游戏里把机器 **关机再开机**（power cycle）即生效，
            #   不需要重启游戏、不需要重编译固件。
            #   格式：key=value，# 开头是注释。缺的键用固件内默认值。
            # ============================================================

            # --- 通用 ---
            system.banner=Cryptand OS 0.1
            console.tick_log=1

            # --- 系统①（仅 FreeRTOS）---
            heartbeat.ms=1000

            # --- 系统②（FreeRTOS + LVGL + shell）---
            shell.prompt=Cryptand>
            lvgl.refr_ms=50
            panel.interval_ms=250

            # --- 系统③（Cryptand UI OS）---
            desktop.clock_ms=500
            desktop.title=Cryptand UI OS
            """;

    private CryptandOsConfig() {
    }

    /**
     * 取配置块内容（每次调用都重新读文件 ⇒ "改文件 + 重启机器"立即生效）。
     *
     * @return 以 {@link #MAGIC} 开头、以 {@code \0} 结尾的字节块（长度 ≤ {@link #RESERVE_BYTES}）
     */
    public static byte[] block() {
        String text = readOrCreate();
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);

        // 保证魔数在块首（玩家误删也能兜住）
        if (!text.startsWith(MAGIC)) {
            final byte[] withMagic = (MAGIC + "\n" + text).getBytes(StandardCharsets.UTF_8);
            raw = withMagic;
        }
        final int max = RESERVE_BYTES - 1;              // 末尾留一个 '\0'
        final byte[] out = new byte[Math.min(raw.length, max) + 1];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, max));
        out[out.length - 1] = 0;
        return out;
    }

    /** 读文件；不存在则写默认值并返回它 */
    private static String readOrCreate() {
        final Path path = Path.of(FILE);
        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            Files.writeString(path, DEFAULT_TEXT, StandardCharsets.UTF_8);
            return DEFAULT_TEXT;
        } catch (Throwable t) {
            // 读不到就用默认文本（不影响开机）
            return DEFAULT_TEXT;
        }
    }
}

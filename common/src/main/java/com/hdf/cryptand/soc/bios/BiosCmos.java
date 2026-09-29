package com.hdf.cryptand.soc.bios;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.FsMode;

/**
 * ===== CMOS：BIOS 配置在**启动盘**上的落点（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（第 35 轮）：BIOS 配置 = CMOS，持久化在**每台机器**上（不是全局 toml）。
 * 第 52 轮动手时撞到一个卡点：宿主侧的 {@code CryptandOcArchitecture} 拿不到方块坐标
 * （它只有 OC 的 machine），所以"按机器存"的 key 一时没有可靠来源。</p>
 *
 * <p>于是先落这一版：<b>CMOS 跟着启动盘走</b> —— 盘上 {@code /boot/cmos.cfg}，
 * 内容是 {@link BiosConfig#toText()}。这不是权宜之计，现实里就是这么干的：
 * EFI 的 {@code BOOTX64.CSV}、GRUB 的 {@code grub.cfg} 都是"启动介质自带引导配置"。
 * 等真机日志确认 host 能给出方块坐标，再决定是否加一层"按机器存"（per-machine CMOS）。</p>
 *
 * <p>⚠ 容错是这一层的核心：{@link BiosConfig#fromText} 永不抛，损坏的 CMOS 只会退回默认值 ——
 * <b>配置坏掉不该让机器起不来</b>（真机 CMOS 电池没电就是这个表现：时间丢了，机器照开）。</p>
 */
public final class BiosCmos {

    /** CMOS 在盘上的路径（与 {@code /boot/system.bin}、{@code /boot/loader.bin} 同一目录） */
    public static final String CMOS_PATH = "/boot/cmos.cfg";

    private BiosCmos() {
    }

    /**
     * 读盘上的 CMOS。
     *
     * @return {@code null} = **这块盘上没有 CMOS**（调用方应当去找下一块盘 / 用默认值）；
     *         有文件时返回解析结果（文件损坏 ⇒ 默认值，不抛）
     */
    public static BiosConfig read(CryptandFileSystem fs) {
        if (fs == null || !fs.exists(CMOS_PATH)) {
            return null;
        }
        final int h = fs.open(CMOS_PATH, FsMode.READ);
        final byte[] buf = new byte[(int) fs.getHandle(h).length()];
        final int n = fs.getHandle(h).read(buf);
        fs.getHandle(h).close();
        final String text = new String(buf, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
        return BiosConfig.fromText(text);
    }

    /**
     * 把 CMOS 写进盘（覆盖），并**回读校验**。
     *
     * <p>回读的理由与其他写盘工具一致：写完不回读，错误要等到下次开机才暴露，
     * 而那时人已经不记得自己改了什么。</p>
     */
    public static void write(CryptandFileSystem fs, BiosConfig config) {
        if (fs == null || config == null) {
            throw new IllegalArgumentException("fs and config must not be null");
        }
        if (!fs.exists("/boot")) {
            fs.makeDirectories("/boot");
        }
        final byte[] data = config.toText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final int h = fs.open(CMOS_PATH, FsMode.WRITE);
        fs.getHandle(h).write(data);
        fs.getHandle(h).close();
        final BiosConfig back = read(fs);
        if (back == null || !back.equals(config)) {
            throw new IllegalStateException("CMOS verify failed at " + CMOS_PATH
                    + ": wrote " + data.length + " bytes, read back " + (back == null ? "nothing" : back.toText()));
        }
    }
}

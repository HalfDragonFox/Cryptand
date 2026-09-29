package com.hdf.cryptand.neoforge.soc;

import com.hdf.cryptand.toolchain.CCompileRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ===== SoC 内置资源（2026-09-15）=====
 *
 * <p>随模组分发的开发素材（{@code assets/cryptand/soc/}）：链接脚本模板与示例固件。
 * 玩家可直接取用（编译时作为附加文件上传，或在本地开新工程时复制）。</p>
 */
public final class SocResources {

    private static final String BASE = "/assets/cryptand/soc/";

    /**
     * 镜像文件（与 {@code excode/firmware/build-all.ps1} 的产物同名）。
     *
     * <p>启动模型（用户 2026-09-17 定稿，**与官方 OC 一致**）：</p>
     * <pre>
     *   EEPROM（cryptand:cbios）  = Cryptand Boot  —— BIOS / bootloader，上电就跑
     *   硬盘（cryptand:hdd1/2/3） = 系统盘         —— Boot 逐块扫描，找到有效系统就跳过去
     * </pre>
     * <p>所以 EEPROM 只有 Boot 一个镜像；三个系统镜像分别对应三块硬盘槽的默认映射
     * （可用配置文件覆盖）。</p>
     */
    public static final String FIRMWARE_BOOT = "cryptand-boot.bin";         // EEPROM：BIOS / bootloader
    public static final String FIRMWARE_SYSTEM1 = "cryptand-os.bin";        // 系统盘 slot 1：Cryptand OS
    public static final String FIRMWARE_SYSTEM2 = "cryptand-ui-os.bin";     // 系统盘 slot 2：Cryptand UI OS
    public static final String FIRMWARE_SYSTEM3 = "cryptand-ext.bin";       // 系统盘 slot 3：扩展（预留，无内置镜像）

    /** 系统盘槽位 → 镜像文件名（0 起） */
    public static String systemImageName(int diskSlot) {
        return switch (diskSlot) {
            case 1 -> FIRMWARE_SYSTEM2;
            case 2 -> FIRMWARE_SYSTEM3;
            default -> FIRMWARE_SYSTEM1;
        };
    }

    /** 系统盘槽位 → 人类可读的系统名（诊断/日志） */
    public static String systemName(int diskSlot) {
        return switch (diskSlot) {
            case 1 -> "Cryptand UI OS";
            case 2 -> "Ext";
            default -> "Cryptand OS";
        };
    }

    private SocResources() {
    }

    /** 读取内置文本资源（缺失返回空串） */
    public static String read(String name) {
        try (InputStream in = SocResources.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 链接脚本模板（内存布局须与 SocBoard 装配一致） */
    public static String linkerScript() {
        return read("cryptand.ld");
    }

    /** 示例固件（读写寄存器区/定时器/UART/PWM/ADC） */
    public static String blinky() {
        return read("blinky.c");
    }

    /**
     * 读某个镜像文件（缺失返回空数组）。
     *
     * @param name 见 {@link #FIRMWARE_BOOT} / {@link #systemImageName(int)}
     */
    public static byte[] firmwareByName(String name) {
        try (InputStream in = SocResources.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                return new byte[0];
            }
            return in.readAllBytes();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** Cryptand Boot（EEPROM）镜像 */
    public static byte[] bootImage() {
        return firmwareByName(FIRMWARE_BOOT);
    }

    /** 第 {@code diskSlot} 块系统盘的镜像 */
    public static byte[] systemImage(int diskSlot) {
        return firmwareByName(systemImageName(diskSlot));
    }

    /** 由内置示例构造编译请求（/cryptand soc compile example 用） */
    public static CCompileRequest exampleRequest() {
        return new CCompileRequest("blinky", blinky())
                .march("rv32im")
                .mabi("ilp32")
                .file("cryptand.ld", linkerScript())
                .linkerScript("cryptand.ld")
                .timeoutMs(30_000);
    }
}

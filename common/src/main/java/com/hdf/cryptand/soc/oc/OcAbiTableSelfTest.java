package com.hdf.cryptand.soc.oc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ===== 方法声明表的闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>这张表是"组件方法的名字 / 方向 / 线程 / 预算"的**唯一来源**（用户 2026-09-18 定案：
 * 不再留第二处判断）。表出错的表现极其分散 —— 某个方法名拼错就变成"宿主说 unknown method"、
 * 方向写反就变成"结果写不回 guest"、覆盖漏一个就是"某个操作神秘失败" ——
 * 所以它必须有自己的闸门，而且闸门要断言到**每一条**，不是抽查。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runOcAbiTableTest}</p>
 */
public final class OcAbiTableSelfTest {

    private static int passed;
    private static int failed;

    private OcAbiTableSelfTest() {
    }

    public static void main(String[] args) {
        final List<OcAbi.MethodSpec> table = OcAbi.methods();
        expect(!table.isEmpty(), "方法表不应为空");

        // ① 键唯一：同名组件下方法号不能重复（重复会让 spec() 静默取到先来的那条）
        final Set<String> keys = new HashSet<>();
        for (final OcAbi.MethodSpec m : table) {
            final String key = m.component() + "#" + m.methodId();
            expect(keys.add(key), "方法表键重复: " + key);
            expect(m.name() != null, "方法名不应为 null（" + key + "）");
        }

        // ② 名字（表驱动）：抽查每个组件的首尾，确保名字真的来自表而不是残留 switch
        expect("set".equals(OcAbi.gpuMethodName(OcAbi.GPU_METHOD_SET)), "gpu 0 应为 set");
        expect("blit".equals(OcAbi.gpuMethodName(OcAbi.GPU_METHOD_BLIT)), "gpu 5 应为 blit");
        expect(OcAbi.gpuMethodName(99).isEmpty(), "未知 gpu 方法号应为空串");
        expect("open".equals(OcAbi.fsMethodName(OcAbi.FS_OPEN)), "fs 16 应为 open");
        expect("read".equals(OcAbi.fsMethodName(OcAbi.FS_READ)), "fs 17 应为 read");
        expect("list".equals(OcAbi.fsMethodName(OcAbi.FS_LIST)), "fs 21 应为 list");
        expect("makeDirectory".equals(OcAbi.fsMethodName(OcAbi.FS_MKDIR)), "fs 24 应为 makeDirectory");
        expect(OcAbi.fsMethodName(OcAbi.FS_STAT).isEmpty(), "FS_STAT 是本 ABI 便捷方法，OC 侧无同名");
        // 引导服务只允许是"BIOS 读盘服务"（= INT 13h）。这条断言钉死 2026-09-27 定案：
        // 宿主**不**提供"替 guest 取系统"这种服务 —— 名字一旦回到 loadSystem 这类语义就失败。
        expect("readFile".equals(OcAbi.spec("boot", OcAbi.BOOT_METHOD_READ_FILE).name()),
                "boot #0 应为 readFile（BIOS 读盘服务 = INT 13h）");
        for (final OcAbi.MethodSpec m : table) {
            expect(!"boot".equals(m.component()) || !m.name().toLowerCase(java.util.Locale.ROOT).contains("loadsystem"),
                    "引导服务里不允许再出现 loadSystem 式的\"宿主替 guest 取系统\"入口");
        }

        // ③ 方向（表驱动）
        expect(OcAbi.isOutbound("filesystem", OcAbi.FS_READ), "fs read 是出方向");
        expect(OcAbi.isOutbound("filesystem", OcAbi.FS_LIST), "fs list 是出方向");
        expect(!OcAbi.isOutbound("filesystem", OcAbi.FS_OPEN), "fs open 是入方向");
        expect(!OcAbi.isOutbound("filesystem", OcAbi.FS_WRITE), "fs write 是入方向");
        expect(OcAbi.isFsOutbound(OcAbi.FS_READ), "isFsOutbound(read) 应为真");
        expect(!OcAbi.isFsOutbound(OcAbi.FS_SIZE), "isFsOutbound(size) 应为假");
        for (int i = OcAbi.PE_METHOD_TARGETS; i <= OcAbi.PE_METHOD_LAST; i++) {
            expect(OcAbi.isOutbound(OcAbi.PE_COMPONENT_NAME, i), "pe 方法 " + i + " 应全部是出方向（回文本）");
        }
        expect(OcAbi.isOutbound(OcAbi.DISK_COMPONENT_NAME, OcAbi.DISK_METHOD_BLOCK_READ), "disk 块读是出方向");
        expect(!OcAbi.isOutbound(OcAbi.DISK_COMPONENT_NAME, OcAbi.DISK_METHOD_BLOCK_WRITE), "disk 块写是入方向");
        expect(!OcAbi.isOutbound(OcAbi.GPU_COMPONENT_NAME, OcAbi.FS_READ),
                "gpu 组件不该套用文件系统的方向表（旧实现对任意组件套 FS 表 ⇒ 会误判成出方向）");
        expect(!OcAbi.isOutbound("nope", 0), "未知组件不应被判为出方向");

        // ④ 覆盖度：每段的每个方法号都必须编目（漏一条就是"某个操作神秘失败"）
        cover(OcAbi.GPU_COMPONENT_NAME, OcAbi.GPU_METHOD_SET, OcAbi.GPU_METHOD_BLIT);
        cover("filesystem", OcAbi.FS_METHOD_FIRST, OcAbi.FS_METHOD_LAST);
        cover(OcAbi.PE_COMPONENT_NAME, OcAbi.PE_METHOD_TARGETS, OcAbi.PE_METHOD_LAST);
        cover(OcAbi.DISK_COMPONENT_NAME, OcAbi.DISK_METHOD_INFO, OcAbi.DISK_METHOD_LAST);
        cover("boot", OcAbi.BOOT_METHOD_READ_FILE, OcAbi.BOOT_METHOD_READ_FILE);

        // ⑤ 线程编目自洽：字符绘制可在工作线程直调（组件桥现状），其余碰 managed 状态的走主线程
        for (int i = OcAbi.GPU_METHOD_SET; i <= OcAbi.GPU_METHOD_BLIT; i++) {
            expect(!OcAbi.spec(OcAbi.GPU_COMPONENT_NAME, i).mainThread(), "gpu 方法 " + i + " 目前是工作线程直调");
        }
        for (int i = OcAbi.FS_METHOD_FIRST; i <= OcAbi.FS_METHOD_LAST; i++) {
            expect(OcAbi.spec("filesystem", i).mainThread(), "filesystem 方法 " + i + " 应在主线程兑现");
        }

        System.out.println("[oc-abi] " + passed + "/" + (passed + failed)
                + (failed == 0 ? " —— 全部通过" : " —— 有失败"));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void cover(String component, int first, int last) {
        for (int i = first; i <= last; i++) {
            expect(OcAbi.spec(component, i) != null, "方法表漏了 " + component + " #" + i);
        }
    }

    private static void expect(boolean ok, String message) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("[oc-abi] FAIL " + message);
        }
    }
}

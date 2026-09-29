package com.hdf.cryptand.soc.fs;

import java.util.UUID;

/**
 * ===== 盘地址沙盒自测（纯 Java 零 MC）=====
 *
 * <p>钉住三件事：① 合法地址照旧（老存档兼容）；② 非法地址被**拒绝**而不是被安全化
 * （`a/b` 与 `a_b` 安全化后同目录 ⇒ 两块盘串内容）；③ 生成格式与历史一致。</p>
 */
public final class DiskAddressSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== 盘地址自测（common，无 MC）===");

        // ---- 1. 合法：老存档的 UUID 必须继续合法，且安全化恒等 ----
        final String legacy = UUID.randomUUID().toString();
        check("UUID 地址合法（老存档兼容）", DiskAddress.isValid(legacy));
        check("安全化对 UUID 恒等（老存档盘目录不换名）",
                legacy.equals(DiskAddress.safeSegment(legacy, "unbound")));
        check("常见地址合法", DiskAddress.isValid("disk-1") && DiskAddress.isValid("A.b_c-9"));
        check("前后空白被接受（规范化后合法）", DiskAddress.isValid("  disk-1  "));
        check("require 会去掉首尾空白", "disk-1".equals(DiskAddress.require("  disk-1  ")));

        // ---- 2. 非法：必须拒绝 ----
        check("空地址非法", !DiskAddress.isValid("") && !DiskAddress.isValid("   "));
        check("null 非法", !DiskAddress.isValid(null));
        check("含路径分隔符非法（a/b 与 a_b 会撞同一个目录）",
                !DiskAddress.isValid("a/b") && !DiskAddress.isValid("a\\b"));
        check("含 .. 非法（目录穿越）",
                !DiskAddress.isValid("..") && !DiskAddress.isValid("../evil") && !DiskAddress.isValid("a..b"));
        check("单点 . 非法", !DiskAddress.isValid("."));
        check("含空格非法", !DiskAddress.isValid("a b"));
        check("含冒号/竖线非法（Windows 保留字符）",
                !DiskAddress.isValid("a:b") && !DiskAddress.isValid("a|b"));
        check("超长非法", !DiskAddress.isValid("x".repeat(DiskAddress.MAX_LENGTH + 1)));
        check("恰好 MAX_LENGTH 合法", DiskAddress.isValid("x".repeat(DiskAddress.MAX_LENGTH)));

        // ---- 3. require 明确报错（带错误码，不静默） ----
        boolean rejected = false;
        try {
            DiskAddress.require("../evil");
        } catch (FsException e) {
            rejected = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS;
        }
        check("非法地址 ⇒ FsException(ERR_BAD_ARGS)（带码，不静默）", rejected);

        // ---- 4. 生成 ----
        final String a = DiskAddress.generate();
        final String b = DiskAddress.generate();
        check("生成的地址是合法 UUID 形态",
                a.length() == 36 && a.chars().filter(c -> c == '-').count() == 4 && DiskAddress.isValid(a));
        check("两次生成不重复", !a.equals(b));

        // ---- 5. 安全化（历史兼容路径）只做"落进安全目录"，不承担校验 ----
        check("安全化：斜杠换下划线", "a_b".equals(DiskAddress.safeSegment("a/b", "unbound")));
        check("安全化：.. 换下划线", !DiskAddress.safeSegment("../evil", "unbound").contains(".."));
        check("安全化：空值回落", "unbound".equals(DiskAddress.safeSegment("", "unbound"))
                && "unbound".equals(DiskAddress.safeSegment(null, "unbound")));

        System.out.println("[ADDR] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

package com.hdf.cryptand.soc.fs;

import java.util.UUID;

/**
 * ===== 盘地址（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-18）："分配地址可以增加一条指令来实现强制分配地址" —— 地址进日志、
 * 进盘目录、进断言，整条链路因此变成可复现的。</p>
 *
 * <p>⚠ 为什么规则放 common 而不是物品类里：地址<b>同时</b>是三种东西 ——
 * ① 物品上的一个数据组件；② 宿主上的目录名（{@code <gameDir>/cryptand/disks/<world>/<address>/}）；
 * ③ 日志/命令/测试里的标识符。生成与校验只有一份，才不会出现
 * "物品上存下来了、目录上却撞了另一个盘"。</p>
 *
 * <p>⚠ <b>兼容红线</b>：老存档里的地址是 UUID 字符串，必须继续合法；并且
 * {@link #safeSegment} 对合法地址必须**恒等**返回 —— 否则老存档的盘目录会换名，
 * 用户看到的是"盘突然空了"。</p>
 */
public final class DiskAddress {

    /** 地址长度上限（既是目录名也是日志标识符，够 UUID 用且留余量） */
    public static final int MAX_LENGTH = 64;

    private DiskAddress() {
    }

    /** 生成新地址：UUID 字符串（与历史格式一致 ⇒ 老代码、老存档、老测试的语义都不变） */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * 地址是否合法：非空、不超长、只含 {@code [A-Za-z0-9._-]}、且不含 {@code ".."}。
     *
     * <p>非法地址**必须被拒绝**而不是被安全化：`a/b` 与 `a_b` 安全化后是同一个目录，
     * 两块盘会共享同一份内容 —— 这种 bug 表现成"存档串了"，极难查。</p>
     */
    public static boolean isValid(String address) {
        if (address == null) {
            return false;
        }
        final String a = address.trim();
        if (a.isEmpty() || a.length() > MAX_LENGTH || ".".equals(a) || a.contains("..")) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            final char c = a.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 校验并规范化（去首尾空白）；不合法 ⇒ {@code FsException(ERR_BAD_ARGS)}，错误信息带上原值 */
    public static String require(String address) {
        final String a = address == null ? "" : address.trim();
        if (!isValid(a)) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS,
                    "invalid disk address: '" + address + "' (allowed: 1.." + MAX_LENGTH
                            + " chars of [A-Za-z0-9._-], no '..')");
        }
        return a;
    }

    /**
     * 目录名安全化（**历史兼容**用，不用于新地址 —— 新地址走 {@link #require}）。
     *
     * <p>⚠ 合法地址恒等返回（老存档盘目录不换名）；只把非法字符换成下划线，
     * 让"老存档里可能存在的怪地址"仍能落到一个安全目录，而不是让整个挂载失败。
     * 行为与 {@code DiskMount.sanitizeSegment} 逐字一致（那是它原来的实现）。</p>
     */
    public static String safeSegment(String raw, String fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        final String s = raw.replace('\\', '_').replace('/', '_').replace("..", "_");
        return s.isEmpty() ? fallback : s;
    }
}

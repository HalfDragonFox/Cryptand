package com.hdf.cryptand.soc.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ===== 一块盘 = 宿主上一个真实文件夹（2026-09-18）=====
 *
 * <p>目录布局（照 {@code cryptand-fs-design.md §3.1}）：</p>
 * <pre>
 *   &lt;gameDir&gt;/cryptand/disks/&lt;worldId&gt;/&lt;address&gt;/   ← 这块盘看到的 "/"
 *   &lt;gameDir&gt;/cryptand/disk-src/&lt;folder&gt;/              ← 文件加载器 GUI/MCP 的只读源
 * </pre>
 *
 * <p>为什么带 {@code worldId} 一层：盘是**物品**（可跨世界带着走），而宿主文件夹是全局的。
 * 不带这层的话"同一块盘在两个世界插拔"会互相串内容 —— 这一层现在加便宜，事后迁移很贵。</p>
 *
 * <p>{@code address} 用 OC 同构的 UUID 字符串（天然唯一、可直接当目录名）；
 * <b>label 不能当目录名</b>（会重名、可能含非法字符）。</p>
 *
 * <h3>路径解析（本类唯一的"安全边界"，比 OC 更严）</h3>
 * <ol>
 *   <li>消解 {@code .} / {@code ..} / 重复斜杠 / 尾斜杠（等价 OC 的 {@code simplifyPath}）；</li>
 *   <li>消解后仍越出根（{@code /a/../../b}）⇒ {@code ERR_INVALID_PATH}（照 OC 组件层 {@code :332}）；</li>
 *   <li>{@code "/"}/{@code "."}/{@code ""} ⇒ 空串（根目录在实现里用空串表示，照 OC {@code :333}）；</li>
 *   <li>逐段校验非法字符 {@code \ : * ? " < > |}（照 {@code server/fs/FileSystem.scala:58-67}）；</li>
 *   <li><b>jail + 符号链接检查</b>：结果必须仍在根下，且路径上任何一段都不是符号链接。</li>
 * </ol>
 * <p>第 5 条是 Cryptand 必须比 OC 严的地方：OC 的文件系统是虚拟的，越界最坏是虚拟空间里的错；
 * 我们的盘背后是**真实文件系统** —— 一个指到盘外的 junction/符号链接就等于把宿主的文件暴露给固件。</p>
 */
public final class DiskMount {

    /** 路径里不允许出现的字符（与 OC 的非法字符集一致） */
    private static final String ILLEGAL = "\\:*?\"<>|";

    private final Path root;
    private final String address;

    /**
     * @param root    盘根（真实目录；不存在时由本构造器创建）
     * @param address 盘地址（UUID 字符串，用于日志/登记）
     */
    public DiskMount(Path root, String address) {
        this.root = root.toAbsolutePath().normalize();
        this.address = address == null || address.isEmpty() ? "unbound" : address;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_INVALID_PATH,
                    "cannot create disk root " + this.root + ": " + e.getMessage());
        }
    }

    /** 盘的挂载目录：{@code <gameDir>/cryptand/disks/<worldId>/<address>/} */
    public static Path rootDir(Path gameDir, String worldId, String address) {
        return gameDir.resolve("cryptand").resolve("disks")
                .resolve(sanitizeSegment(worldId, "world"))
                .resolve(sanitizeSegment(address, "unbound"));
    }

    /**
     * 按目录名安全化（worldId / address 只允许出现在路径段里，绝不允许含分隔符）。
     *
     * <p>规则本体在 common 的 {@link DiskAddress#safeSegment}（与"地址是否合法"同一份实现，
     * 见那个类的兼容红线：合法地址必须恒等返回）。</p>
     */
    private static String sanitizeSegment(String raw, String fallback) {
        return DiskAddress.safeSegment(raw, fallback);
    }

    public Path root() {
        return root;
    }

    public String address() {
        return address;
    }

    /**
     * guest 路径 → 宿主机真实路径（含 jail 与符号链接检查）。
     *
     * @throws FsException {@code ERR_INVALID_PATH}
     */
    public Path resolve(String guestPath) {
        final String clean = normalizeGuestPath(guestPath);
        if (clean.isEmpty()) {
            return root;
        }
        final Path tail = root.resolve(clean).normalize();
        if (!tail.startsWith(root)) {
            throw FsException.invalidPath(guestPath + " (escapes disk root)");
        }
        // 符号链接检查：逐段（含中间目录 —— 中间目录是链接同样能指到盘外）
        Path cur = root;
        for (final String seg : clean.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            cur = cur.resolve(seg);
            if (Files.isSymbolicLink(cur)) {
                throw FsException.invalidPath(guestPath + " (symbolic link in path: " + seg + ")");
            }
        }
        return tail;
    }

    /** 真实路径 → guest 路径（{@code list} 用；根 → 空串） */
    public String toGuestPath(Path real) {
        final Path rel = root.relativize(real.toAbsolutePath().normalize());
        final String s = rel.toString().replace('\\', '/');
        return s.equals(".") ? "" : s;
    }

    /**
     * 消解 guest 路径：{@code /a/./b//} → {@code a/b}；根 → 空串。
     *
     * @throws FsException {@code ERR_INVALID_PATH}：越出根（消解后仍剩 {@code ..}）、
     *                     空段（除首尾斜杠）、含非法字符
     */
    public static String normalizeGuestPath(String path) {
        if (path == null) {
            throw FsException.invalidPath("null");
        }
        if (path.isEmpty() || path.equals("/") || path.equals(".")) {
            return "";
        }
        final List<String> out = new ArrayList<>();
        boolean escaped = false;
        for (final String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (out.isEmpty()) {
                    escaped = true;      // 消解后仍以 .. 开头 ⇒ 非法（照 OC）
                } else {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            checkSegment(seg, path);
            out.add(seg);
        }
        if (escaped) {
            throw FsException.invalidPath(path);
        }
        return String.join("/", out);
    }

    private static void checkSegment(String seg, String whole) {
        for (int i = 0; i < seg.length(); i++) {
            final char c = seg.charAt(i);
            if (c < 0x20 || ILLEGAL.indexOf(c) >= 0) {
                throw FsException.invalidPath(whole + " (illegal character '" + c + "')");
            }
        }
    }
}

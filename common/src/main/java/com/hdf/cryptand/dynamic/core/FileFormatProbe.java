package com.hdf.cryptand.dynamic.core;

import com.hdf.cryptand.dynamic.api.FormatProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ===== 文件格式探测（性能关键路径）=====
 *
 * <p>只读 zip/jar 的<b>中央目录</b>，不读内容；条目名缓存一次，之后查询零 IO。
 * 必须 close 句柄（Windows 上未释放的 jar 句柄会导致文件删不掉/换不掉）。</p>
 */
public final class FileFormatProbe implements FormatProbe {

    /** 条目名缓存上限：超大 jar 不必把几万条名字全留在内存里（探测只需要 META-INF/少量命中）。 */
    private static final int MAX_CACHED_ENTRIES = 4096;

    private final Path file;
    private final String fileName;
    private final long size;
    private List<String> entries;
    private boolean probed;
    private boolean truncated;

    private FileFormatProbe(Path file) {
        this.file = file;
        final Path n = file.getFileName();
        this.fileName = n == null ? file.toString() : n.toString();
        long len = 0L;
        try {
            len = Files.size(file);
        } catch (IOException ignored) {
            // 取不到就当 0（探测失败不影响分派，后续加载会报错）
        }
        this.size = len;
    }

    public static FileFormatProbe of(Path file) {
        return new FileFormatProbe(file);
    }

    @Override
    public String fileName() {
        return fileName;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean zip() {
        return !entries(Integer.MAX_VALUE).isEmpty();
    }

    @Override
    public List<String> entries(int max) {
        ensureProbed();
        if (entries.isEmpty() || max >= entries.size()) {
            return entries;
        }
        return entries.subList(0, Math.max(0, max));
    }

    private void ensureProbed() {
        if (probed) {
            return;
        }
        probed = true;
        final List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            final Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                if (names.size() >= MAX_CACHED_ENTRIES) {
                    truncated = true;   // 内存优先：截断并标记，不再继续堆名字
                    break;
                }
                names.add(it.nextElement().getName());
            }
        } catch (IOException ex) {
            entries = Collections.emptyList();
            return;
        }
        entries = Collections.unmodifiableList(names);
    }

    /** 条目名是否已因上限被截断（超大 jar）。 */
    public boolean truncated() {
        ensureProbed();
        return truncated;
    }

    /** 是否含某条目（前缀匹配，用于 META-INF 目录判定）。 */
    public boolean hasEntryPrefix(String prefix) {
        for (final String e : entries(Integer.MAX_VALUE)) {
            if (e.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

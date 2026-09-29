package com.hdf.cryptand.dynamic.core;

import com.hdf.cryptand.dynamic.api.CoreHost;
import com.hdf.cryptand.dynamic.api.DynamicCoreSpi;
import com.hdf.cryptand.dynamic.api.DynamicPlugin;
import com.hdf.cryptand.dynamic.api.ExecutorPlan;
import com.hdf.cryptand.dynamic.api.FormatProbe;
import com.hdf.cryptand.dynamic.api.FrameworkContext;
import com.hdf.cryptand.dynamic.api.ResourceSource;
import com.hdf.cryptand.dynamic.api.Source;
import com.hdf.cryptand.dynamic.api.Stage;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * ===== 动态框架离线契约测试（纯 Java 零 MC）=====
 *
 * <p>运行：{@code ./gradlew :common:runDynamicFrameworkTest}</p>
 *
 * <p>做法：用 {@link JavaCompiler} <b>现场编译并打包真实的插件 jar</b>（含
 * {@code META-INF/services} 与 {@code plugin.properties}），再交给框架加载 ——
 * 因此类加载隔离、热重载、回滚、卸载句柄释放都是<b>真的</b>被验证，不是打桩。</p>
 */
public final class DynamicFrameworkSelfTest {

    private static final List<String> FAILS = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) throws Exception {
        basicLoad();
        attachAndRelease();
        dispatchBySuffix();
        multiCoreHitKeepsFirst();
        noCoreSkips();
        incrementalScanIsStable();
        mtimeChangeReloads();
        deleteUnloads();
        badJarIsolated();
        apiMismatchRejectedBeforeClassLoad();
        missingDependencyRejected();
        dependencyPresentAccepted();
        dependencyTopologyOrder();
        dependencyCycleRejected();
        dependencyFailurePropagates();
        cascadeUnloadOnDependencyGone();
        cascadeReloadOnDependencyReload();
        attachThrowRollsBack();
        reloadSameIdReplacesInPlace();
        reloadCoreScoped();
        reloadAllWorks();
        atomicPublishOnFailure();
        concurrentScanIdempotent();
        entryObservability();
        shutdownUnloadsAllAndCallsCore();
        tickAndResourceReloadForwarded();
        jarHandleReleasedAfterUnload();
        memoryNoResidueAfterUnload();
        memoryStampsDoNotAccumulate();
        memoryProbeCappedOnHugeJar();
        summary();
    }

    // ===================== 用例 =====================

    private static void basicLoad() throws Exception {
        final Path dir = tmp("basic");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:one", 1, "");
        final Harness h = harness(dir, jar, core);
        final ScanReport r = h.fw.scan();
        check("basicLoad: added=1", r.added() == 1);
        check("basicLoad: 已发布 id", h.fw.ids().contains("t:one"));
        h.close();
    }

    private static void attachAndRelease() throws Exception {
        final Path dir = tmp("attach");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:two", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        check("attach: 核心收到 attach", core.events.contains("attach:t:two"));
        h.fw.shutdown();
        check("attach: 释放动作被调用", core.events.contains("release:t:two"));
        check("attach: detach 被调用", core.events.contains("detach:t:two"));
    }

    private static void dispatchBySuffix() throws Exception {
        final Path dir = tmp("dispatch");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:three", 1, "");
        final Harness h = harness(dir, jar, core);
        // 用一个不吃 .jar 的核心 + 一个吃 .ogg 的核心
        final TestCore other = new TestCore("sound", Set.of(".ogg"));
        final Path soundJar = dir.resolve("noise.jar");  // .jar 但只有 sound 核心在场
        Files.copy(jar, soundJar);
        final DynamicFramework fw = DynamicFramework.builder()
                .root(dir).core(other).source(new DirSource(dir))
                .scheduler(new VirtualThreadScheduler()).build();
        final ScanReport r = fw.scan();
        check("dispatch: 后缀不匹配则分派失败但不计失败项", r.failed() == 0 && r.added() == 0);
        fw.shutdown();
        h.close();
    }

    private static void multiCoreHitKeepsFirst() throws Exception {
        final Path dir = tmp("multi");
        final TestCore first = new TestCore("ui", Set.of(".jar"));
        final TestCore second = new TestCore("asset", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:four", 1, "");
        final Harness h = harness(dir, jar, first, second);
        final ScanReport r = h.fw.scan();
        check("multiCore: 取注册顺序第一个", r.added() == 1 && first.events.contains("attach:t:four")
                && !second.events.contains("attach:t:four"));
        check("multiCore: 有命中告警", r.messages().stream().anyMatch(m -> m.contains("多核心命中")));
        h.close();
    }

    private static void noCoreSkips() throws Exception {
        final Path dir = tmp("nocore");
        final TestCore core = new TestCore("ui", Set.of(".ogg"));
        final Path jar = makeJar(dir, "t:five", 1, "");
        final Harness h = harness(dir, jar, core);
        final ScanReport r = h.fw.scan();
        check("noCore: 跳过且不失败", r.added() == 0 && r.failed() == 0);
        check("noCore: 有跳过日志", r.messages().stream().anyMatch(m -> m.contains("无核心可处理")));
        h.close();
    }

    private static void incrementalScanIsStable() throws Exception {
        final Path dir = tmp("incr");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:six", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        final ScanReport second = h.fw.scan();
        check("incremental: 第二次无变化", second.added() == 0 && second.reloaded() == 0 && second.removed() == 0);
        check("incremental: attach 只发生一次", core.events.stream().filter(e -> e.equals("attach:t:six")).count() == 1);
        h.close();
    }

    private static void mtimeChangeReloads() throws Exception {
        final Path dir = tmp("mtime");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:seven", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        final ScanReport r = h.fw.scan();
        check("mtime: 变更触发重载", r.reloaded() == 1 && r.added() == 0);
        check("mtime: 重载后仍只有一份", h.fw.ids().stream().filter("t:seven"::equals).count() == 1);
        h.close();
    }

    private static void deleteUnloads() throws Exception {
        final Path dir = tmp("delete");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:eight", 1, "");
        final DirSource src = new DirSource(dir);
        final DynamicFramework fw = DynamicFramework.builder()
                .root(dir).core(core).source(src).scheduler(new VirtualThreadScheduler()).build();
        fw.scan();
        src.empty = true;                       // 来源不再报告它
        final ScanReport r = fw.scan();
        check("delete: removed=1", r.removed() == 1);
        check("delete: 视图中不再有它", !fw.ids().contains("t:eight"));
        check("delete: detach 被调用", core.events.contains("detach:t:eight"));
        fw.shutdown();
        boolean deleted;
        try {
            Files.delete(jar);
            deleted = true;
        } catch (Exception ex) {
            deleted = false;
        }
        check("delete: 卸载后 jar 可删（Windows 句柄已释放）", deleted);
    }

    private static void badJarIsolated() throws Exception {
        final Path dir = tmp("badjar");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path good = makeJar(dir, "t:nine", 1, "");
        final Path bad = dir.resolve("bad.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(bad))) {
            jos.putNextEntry(new JarEntry("readme.txt"));
            jos.write("not a plugin".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        final Harness h = harness(dir, good, core);
        final ScanReport r = h.fw.scan();
        check("badJar: 好包仍加载", h.fw.ids().contains("t:nine"));
        check("badJar: 坏包计入失败但不影响整体", r.failed() == 1 && r.messages().stream().anyMatch(m -> m.contains("加载失败")));
        h.close();
    }

    private static void apiMismatchRejectedBeforeClassLoad() throws Exception {
        final Path dir = tmp("api");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:ten", 99, "");   // 声明 apiVersion=99
        final Harness h = harness(dir, jar, core);
        final ScanReport r = h.fw.scan();
        check("api: 被拒绝", r.failed() == 1 && !h.fw.ids().contains("t:ten"));
        check("api: 报错含版本区间", r.messages().stream().anyMatch(m -> m.contains("API 版本不兼容")));
        check("api: 未加载任何类（静态块未执行）", System.getProperty("dyntest.loaded.t:ten") == null);
        h.close();
    }

    private static void missingDependencyRejected() throws Exception {
        final Path dir = tmp("depmiss");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:eleven", 1, "", "t:absent");
        final Harness h = harness(dir, jar, core);
        final ScanReport r = h.fw.scan();
        check("dep: 缺失依赖被拒绝", r.failed() == 1 && !h.fw.ids().contains("t:eleven"));
        check("dep: 报错含依赖名", r.messages().stream().anyMatch(m -> m.contains("依赖缺失")));
        h.close();
    }

    private static void dependencyPresentAccepted() throws Exception {
        final Path dir = tmp("depok");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path base = makeJar(dir, "t:base", 1, "");
        final Path dep = makeJar(dir, "t:dep", 1, "", "t:base");
        final Harness h = harness(dir, base, core);
        final ScanReport r = h.fw.scan();
        check("dep: 有依赖时可加载", h.fw.ids().contains("t:base") && h.fw.ids().contains("t:dep"));
        check("dep: 无失败项", r.failed() == 0);
        h.close();
    }

    private static void dependencyTopologyOrder() throws Exception {
        final Path dir = tmp("topo");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path dep = makeJar(dir, "t:dep1", 1, "");
        final Path main = makeJar(dir, "t:main1", 1, "", "t:dep1");
        final Harness h = harness(dir, main, core);
        h.fw.scan();
        check("topo: 两条都加载", h.fw.ids().contains("t:dep1") && h.fw.ids().contains("t:main1"));
        final int depIdx = core.events.indexOf("attach:t:dep1");
        final int mainIdx = core.events.indexOf("attach:t:main1");
        check("topo: 被依赖者先装配", depIdx >= 0 && mainIdx > depIdx);
        h.close();
    }

    private static void dependencyCycleRejected() throws Exception {
        final Path dir = tmp("cycle");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path a = makeJar(dir, "t:cycA", 1, "", "t:cycB");
        final Path b = makeJar(dir, "t:cycB", 1, "", "t:cycA");
        final Harness h = harness(dir, a, core);
        final ScanReport r = h.fw.scan();
        check("cycle: 环上都不加载", !h.fw.ids().contains("t:cycA") && !h.fw.ids().contains("t:cycB"));
        check("cycle: 报错含循环依赖", r.messages().stream().anyMatch(m -> m.contains("循环依赖")));
        h.close();
    }

    private static void dependencyFailurePropagates() throws Exception {
        final Path dir = tmp("propagate");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path f1 = makeJar(dir, "t:f1", 1, "", "t:nope");
        final Path f2 = makeJar(dir, "t:f2", 1, "", "t:f1");
        final Harness h = harness(dir, f1, core);
        final ScanReport r = h.fw.scan();
        check("propagate: 直接依赖缺失 ⇒ 不加载", !h.fw.ids().contains("t:f1"));
        check("propagate: 传递禁用 ⇒ 依赖它的也不加载", !h.fw.ids().contains("t:f2"));
        check("propagate: 报错含依赖失败", r.messages().stream().anyMatch(m -> m.contains("依赖失败")));
        h.close();
    }

    private static void cascadeUnloadOnDependencyGone() throws Exception {
        final Path dir = tmp("cascadeun");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path dep = makeJar(dir, "t:cb", 1, "");
        final Path main = makeJar(dir, "t:ca", 1, "", "t:cb");
        final DirSource src = new DirSource(dir);
        final DynamicFramework fw = DynamicFramework.builder()
                .root(dir).core(core).source(src).scheduler(new VirtualThreadScheduler()).build();
        fw.scan();
        check("cascade: 初始两条都在", fw.ids().contains("t:ca") && fw.ids().contains("t:cb"));
        core.events.clear();
        src.hide = "t_cb.jar";                       // 被依赖者消失
        final ScanReport r = fw.scan();
        check("cascade: 被依赖者已卸载", !fw.ids().contains("t:cb"));
        check("cascade: 依赖它的级联卸载", !fw.ids().contains("t:ca"));
        final int caIdx = core.events.indexOf("detach:t:ca");
        final int cbIdx = core.events.indexOf("detach:t:cb");
        check("cascade: 依赖者先退役（逆拓扑）", caIdx >= 0 && cbIdx > caIdx);
        check("cascade: 计数不重复", r.removed() >= 2);
        fw.shutdown();
    }

    private static void cascadeReloadOnDependencyReload() throws Exception {
        final Path dir = tmp("cascaderl");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path dep = makeJar(dir, "t:rb", 1, "");
        final Path main = makeJar(dir, "t:ra", 1, "", "t:rb");
        final Harness h = harness(dir, main, core);
        h.fw.scan();
        final ReloadReport rr = h.fw.reload("t:rb");
        check("cascade reload: 被依赖者重载", rr.ids().contains("t:rb"));
        check("cascade reload: 依赖者一起重载", rr.ids().contains("t:ra"));
        check("cascade reload: 视图仍两条", h.fw.ids().size() == 2);
        h.close();
    }

    private static void attachThrowRollsBack() throws Exception {
        final Path dir = tmp("rollback");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        core.failAttach = true;
        final Path jar = makeJar(dir, "t:twelve", 1, "");
        final Harness h = harness(dir, jar, core);
        final ScanReport r = h.fw.scan();
        check("rollback: 失败且不发布", r.failed() == 1 && !h.fw.ids().contains("t:twelve"));
        check("rollback: own 的资源被释放", core.events.contains("release:t:twelve"));
        // 恢复核心后可再次加载（loader 已释放，jar 可删）
        core.failAttach = false;
        Files.delete(jar);
        final ScanReport r2 = h.fw.scan();
        check("rollback: 删除 jar 不报错（句柄已释放）", r2.failed() == 0);
        h.close();
    }

    private static void reloadSameIdReplacesInPlace() throws Exception {
        final Path dir = tmp("reload");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:thirteen", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        core.events.clear();
        final ReloadReport rr = h.fw.reload("t:thirteen");
        check("reload: 成功", rr.ok() && rr.ids().contains("t:thirteen"));
        final int attachIdx = core.events.indexOf("attach:t:thirteen");
        final int detachIdx = core.events.indexOf("detach:t:thirteen");
        check("reload: 先装后卸（新实例先接管，旧实例后退役）", attachIdx >= 0 && detachIdx > attachIdx);
        check("reload: 视图仍只有一份", h.fw.ids().stream().filter("t:thirteen"::equals).count() == 1);
        h.close();
    }

    private static void reloadCoreScoped() throws Exception {
        final Path dir = tmp("reloadcore");
        final TestCore ui = new TestCore("ui", Set.of(".jar"));
        final TestCore asset = new TestCore("asset", Set.of(".asset"));
        final Path a = makeJar(dir, "t:r1", 1, "");
        final Path b = makeJarNamed(dir, "t:r2", 1, "", null, ".asset");
        final DynamicFramework fw = DynamicFramework.builder()
                .root(dir).core(ui).core(asset).source(new DirSource(dir))
                .scheduler(new VirtualThreadScheduler()).build();
        fw.scan();
        asset.events.clear();
        ui.events.clear();
        final ReloadReport rr = fw.reloadCore("asset");
        check("reloadCore: 只重载该核心的条目", rr.ids().contains("t:r2") && !rr.ids().contains("t:r1"));
        check("reloadCore: 另一核心未被触碰", ui.events.isEmpty());
        fw.shutdown();
    }

    private static void reloadAllWorks() throws Exception {
        final Path dir = tmp("reloadall");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path a = makeJar(dir, "t:r3", 1, "");
        final Path b = makeJar(dir, "t:r4", 1, "");
        final Harness h = harness(dir, a, core);
        h.fw.scan();
        final ReloadReport rr = h.fw.reloadAll();
        check("reloadAll: 两条都重载", rr.ok() && rr.ids().size() == 2);
        h.close();
    }

    private static void atomicPublishOnFailure() throws Exception {
        final Path dir = tmp("atomic");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path good = makeJar(dir, "t:ok", 1, "");
        final Path badApi = makeJar(dir, "t:bad", 99, "");
        final Harness h = harness(dir, good, core);
        h.fw.scan();
        check("atomic: 失败项不在视图", !h.fw.ids().contains("t:bad"));
        check("atomic: 其余照常发布", h.fw.ids().contains("t:ok"));
        check("atomic: 视图条目数一致", h.fw.entries().size() == h.fw.ids().size());
        h.close();
    }

    private static void concurrentScanIdempotent() throws Exception {
        final Path dir = tmp("concurrent");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:conc", 1, "");
        final Harness h = harness(dir, jar, core);
        final Thread t1 = new Thread(h.fw::scan);
        final Thread t2 = new Thread(h.fw::scan);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        check("concurrent: 并发 scan 后只有一份", h.fw.ids().stream().filter("t:conc"::equals).count() == 1);
        check("concurrent: attach 计数 ≥1 且不重复发布", core.events.stream().filter("attach:t:conc"::equals).count() >= 1);
        h.close();
    }

    private static void entryObservability() throws Exception {
        final Path dir = tmp("observ");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:obs", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        final Entry e = h.fw.entries().get(0);
        check("entry: id", "t:obs".equals(e.id()));
        check("entry: coreId", "ui".equals(e.coreId()));
        check("entry: sourceKind=dir", "dir".equals(e.sourceKind()));
        check("entry: stage=ATTACH", Stage.ATTACH.name().equals(e.stage()));
        check("entry: apiVersion", e.apiVersion() == 1);
        h.close();
    }

    private static void shutdownUnloadsAllAndCallsCore() throws Exception {
        final Path dir = tmp("shutdown");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path a = makeJar(dir, "t:s1", 1, "");
        final Path b = makeJar(dir, "t:s2", 1, "");
        final Harness h = harness(dir, a, core);
        h.fw.scan();
        h.fw.shutdown();
        check("shutdown: 全部卸载", h.fw.ids().isEmpty());
        check("shutdown: 核心 onShutdown 被调用", core.shutdownCalled);
        check("shutdown: 两条 detach", core.events.contains("detach:t:s1") && core.events.contains("detach:t:s2"));
    }

    private static void tickAndResourceReloadForwarded() throws Exception {
        final Path dir = tmp("fwd");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:fwd", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        h.fw.tick();
        h.fw.tick();
        h.fw.onResourceReload();
        check("forward: tick 转发 2 次", core.ticks == 2);
        check("forward: 资源重载转发 1 次", core.reloads == 1);
        h.close();
    }

    private static void jarHandleReleasedAfterUnload() throws Exception {
        final Path dir = tmp("handle");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:handle", 1, "");
        final Harness h = harness(dir, jar, core);
        h.fw.scan();
        h.fw.shutdown();
        boolean deleted;
        try {
            Files.delete(jar);
            deleted = true;
        } catch (Exception ex) {
            deleted = false;
        }
        check("handle: 卸载后 jar 可删除（类加载器句柄已释放）", deleted);
    }

    private static void memoryNoResidueAfterUnload() throws Exception {
        final Path dir = tmp("mem");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path a = makeJar(dir, "t:m1", 1, "");
        final Path b = makeJar(dir, "t:m2", 1, "");
        final Harness h = harness(dir, a, core);
        h.fw.scan();
        check("mem: 加载后 2 个存活装载器", h.fw.stats().liveLoaders() == 2);
        check("mem: 加载后 2 条发布", h.fw.stats().entries() == 2);
        h.fw.shutdown();
        check("mem: 关停后发布视图清空", h.fw.stats().entries() == 0);
        check("mem: 关停后无存活装载器", h.fw.stats().liveLoaders() == 0);
        check("mem: 关停后指纹表清空", h.fw.stats().stamps() == 0);
    }

    private static void memoryStampsDoNotAccumulate() throws Exception {
        final Path dir = tmp("memstamp");
        final TestCore core = new TestCore("ui", Set.of(".jar"));
        final Path jar = makeJar(dir, "t:s1", 1, "");
        final DirSource src = new DirSource(dir);
        final DynamicFramework fw = DynamicFramework.builder()
                .root(dir).core(core).source(src).scheduler(new VirtualThreadScheduler()).build();
        fw.scan();
        check("mem: 加载后 1 条指纹", fw.stats().stamps() == 1);
        src.hide = "t_s1.jar";
        fw.scan();
        check("mem: 来源消失后指纹被清掉（不单调增长）", fw.stats().stamps() == 0);
        fw.shutdown();
    }

    private static void memoryProbeCappedOnHugeJar() throws Exception {
        final Path dir = tmp("probe");
        final Path big = dir.resolve("big.jar");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(big))) {
            zos.putNextEntry(new JarEntry("META-INF/cryptand-dynamic/plugin.properties"));
            zos.write("id=t:big\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            final byte[] empty = new byte[0];
            for (int i = 0; i < 6000; i++) {
                zos.putNextEntry(new JarEntry("pad/" + i + ".txt"));
                zos.write(empty);
                zos.closeEntry();
            }
        }
        final FileFormatProbe probe = FileFormatProbe.of(big);
        check("mem: 超大 jar 条目缓存被截断", probe.entries(Integer.MAX_VALUE).size() <= 4096);
        check("mem: 截断后仍能判定 META-INF 前缀", probe.hasEntryPrefix("META-INF/"));
        check("mem: 截断标记可见", probe.truncated());
    }

    // ===================== 测试设施 =====================

    private record Harness(DynamicFramework fw, Path dir) {
        void close() {
            try {
                fw.shutdown();
            } catch (Throwable ignored) {
                // 清理尽力而为
            }
        }
    }

    private static Harness harness(Path dir, Path firstJar, TestCore... cores) {
        final DynamicFramework.Builder b = DynamicFramework.builder()
                .root(dir).source(new DirSource(dir)).scheduler(new VirtualThreadScheduler());
        for (final TestCore c : cores) {
            b.core(c);
        }
        return new Harness(b.build(), dir);
    }

    /** 目录来源：扫描目录下所有 .jar。 */
    private static final class DirSource implements ResourceSource {
        private final Path dir;
        /** 置为 true 后不再报告任何文件（模拟"来源消失"；Windows 上无法真删已加载的 jar —— 见 DynamicFramework 类注释）。 */
        volatile boolean empty;
        /** 隐藏指定文件名（模拟"某个包消失"，用于级联卸载）。 */
        volatile String hide;

        DirSource(Path dir) {
            this.dir = dir;
        }

        @Override
        public String id() {
            return "dir:" + dir.getFileName();
        }

        @Override
        public List<Source> discover(FrameworkContext ctx) {
            final List<Source> out = new ArrayList<>();
            if (empty) {
                return out;
            }
            try (var s = Files.list(dir)) {
                for (final Path p : s.sorted().toList()) {
                    // 来源只负责"发现"，不按扩展名过滤 —— 能不能处理由核心的 canHandle 决定
                    final String name = p.getFileName().toString();
                    if (hide != null && hide.equals(name)) {
                        continue;
                    }
                    if (Files.isRegularFile(p)) {
                        out.add(Source.dir(p, id()));
                    }
                }
            } catch (Exception ignored) {
                // 空目录
            }
            return out;
        }
    }

    /** 可编程的测试核心。 */
    private static final class TestCore implements DynamicCoreSpi<DynamicPlugin> {
        private final String id;
        private final Set<String> suffixes;
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean failAttach;
        boolean shutdownCalled;
        int ticks;
        int reloads;

        TestCore(String id, Set<String> suffixes) {
            this.id = id;
            this.suffixes = suffixes;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> suffixes() {
            return suffixes;
        }

        @Override
        public ExecutorPlan plan() {
            return new ExecutorPlan(EnumSet.of(Stage.DISCOVER, Stage.PROBE, Stage.EXTRACT), false, true);
        }

        @Override
        public void attach(DynamicPlugin plugin, CoreHost host) {
            if (failAttach) {
                host.own("r:" + plugin.id(), () -> events.add("release:" + plugin.id()));
                throw new IllegalStateException("测试注入失败");
            }
            host.own("r:" + plugin.id(), () -> events.add("release:" + plugin.id()));
            events.add("attach:" + plugin.id());
        }

        @Override
        public void detach(DynamicPlugin plugin, CoreHost host) {
            events.add("detach:" + plugin.id());
        }

        @Override
        public Class<DynamicPlugin> pluginType() {
            return DynamicPlugin.class;
        }

        @Override
        public void onShutdown() {
            shutdownCalled = true;
        }

        @Override
        public void onClientTick() {
            ticks++;
        }

        @Override
        public void onResourceReload() {
            reloads++;
        }
    }

    // ===================== jar 生成 =====================

    private static Path makeJar(Path dir, String id, int apiVersion, String body) throws Exception {
        return makeJarNamed(dir, id, apiVersion, body, null, ".jar");
    }

    private static Path makeJar(Path dir, String id, int apiVersion, String body, String depends) throws Exception {
        return makeJarNamed(dir, id, apiVersion, body, depends, ".jar");
    }

    private static Path makeJarNamed(Path dir, String id, int apiVersion, String body, String depends,
                                     String ext) throws Exception {
        final String pkg = "dyntest.g" + Math.abs(id.hashCode());
        final String cls = pkg + ".P";
        final String src = "package " + pkg + ";\n"
                + "public final class P implements com.hdf.cryptand.dynamic.api.DynamicPlugin {\n"
                + "  static { System.setProperty(\"dyntest.loaded." + id + "\", \"1\"); }\n"
                + "  public String id() { return \"" + id + "\"; }\n"
                + "  public int apiVersion() { return " + apiVersion + "; }\n"
                + "  public void unload() { " + body + " }\n"
                + "}\n";
        final Path srcDir = dir.resolve("src").resolve(pkg.replace('.', '/'));
        Files.createDirectories(srcDir);
        final Path javaFile = srcDir.resolve("P.java");
        Files.writeString(javaFile, src, StandardCharsets.UTF_8);
        final Path outDir = dir.resolve("classes");
        Files.createDirectories(outDir);
        final JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        final int rc = jc.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", outDir.toString(), javaFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("测试插件编译失败: " + id);
        }
        final Path jar = dir.resolve(id.replace(':', '_') + ext);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            final String clsPath = pkg.replace('.', '/');
            addEntry(jos, clsPath + "/P.class", Files.readAllBytes(outDir.resolve(clsPath).resolve("P.class")));
            addEntry(jos, "META-INF/services/com.hdf.cryptand.dynamic.api.DynamicPlugin",
                    (cls + "\n").getBytes(StandardCharsets.UTF_8));
            final StringBuilder props = new StringBuilder();
            props.append("id=").append(id).append('\n');
            props.append("version=1.0.0").append('\n');
            props.append("apiVersion=").append(apiVersion).append('\n');
            props.append("mainClass=").append(cls).append('\n');
            if (depends != null) {
                props.append("depends=").append(depends).append('\n');
            }
            addEntry(jos, "META-INF/cryptand-dynamic/plugin.properties",
                    props.toString().getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void addEntry(JarOutputStream jos, String name, byte[] data) throws Exception {
        jos.putNextEntry(new JarEntry(name));
        jos.write(data);
        jos.closeEntry();
    }

    private static Path tmp(String tag) throws Exception {
        final Path p = Files.createTempDirectory("dynfw-" + tag + "-");
        return p;
    }

    // ===================== 断言 =====================

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            FAILS.add(name);
            System.out.println("[FAIL] " + name);
        }
    }

    private static void summary() {
        int total = passed + FAILS.size();
        System.out.println();
        System.out.println("===== DynamicFrameworkSelfTest " + passed + "/" + total + " PASS =====");
        for (final String f : FAILS) {
            System.out.println("  FAILED: " + f);
        }
        if (!FAILS.isEmpty()) {
            System.exit(1);
        }
    }
}

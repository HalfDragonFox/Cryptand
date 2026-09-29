package com.hdf.cryptand.dynamic.core;

import com.hdf.cryptand.dynamic.api.CoreHost;
import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.DynamicCoreSpi;
import com.hdf.cryptand.dynamic.api.DynamicPlugin;
import com.hdf.cryptand.dynamic.api.DynamicScheduler;
import com.hdf.cryptand.dynamic.api.ExecutorPlan;
import com.hdf.cryptand.dynamic.api.FormatProbe;
// FormatProbe 仅用于分派判定（判定后即弃）
import com.hdf.cryptand.dynamic.api.FrameworkContext;
import com.hdf.cryptand.dynamic.api.ResourceSource;
import com.hdf.cryptand.dynamic.api.Source;
import com.hdf.cryptand.dynamic.api.Stage;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * ===== 动态资源框架（基础核心：只提供框架与接口，2026-09-29）=====
 *
 * <p>用户定案：基础动态核心提供通用动态加载框架 + 接口；MC 相关的具体装配（UI/asset/声音…）
 * 由**具体核心**实现；其他 mod 必须通过 {@code com.hdf.cryptand.dynamic.api} 接入。</p>
 *
 * <p>并发模型（有源码依据）：DISCOVER/PROBE/EXTRACT 并行（虚拟线程）；LOAD 默认串行
 * （插件类 {@code <clinit>} 可能触碰 MC 全局）；ATTACH 默认主线程；生命周期提交走单车道。
 * 取消只靠 epoch（项目 {@code submitGenericTimed} 语义：超时后任务仍在执行、不可取消）。</p>
 *
 * <p>一致性：只有成功 attach 的条目进入发布视图（copy-on-write），失败一律回滚到前一状态，
 * 单包失败不影响其余（失败隔离）。</p>
 *
 * <p><b>⚠ Windows 文件句柄约束（2026-09-29 实测）</b>：`URLClassLoader` 加载类后会持有 jar 的
 * `JarFile` 句柄，<b>已加载的 jar 无法被外部删除或替换</b>（会报"另一个程序正在使用此文件"）。
 * 因此正确流程是 <b>先 {@link #reload(String)}/{@link #shutdown()}（释放句柄）→ 再替换文件 → 再加载</b>；
 * 框架的"来源消失 ⇒ 卸载"只作为兜底（在句柄未持有的平台上直接生效）。</p>
 */
public final class DynamicFramework {

    private final Path root;
    private final List<DynamicCoreSpi<?>> cores;
    private final List<ResourceSource> sources;
    private final DynamicScheduler scheduler;
    private final Consumer<String> logger;
    private final boolean enforceApi;

    private final ReentrantLock lane = new ReentrantLock();
    /** 已发布条目（id → 状态）。只在 lane 内修改。 */
    private final Map<String, PluginState> live = new LinkedHashMap<>();
    /** 增量扫描指纹（文件 → mtime）。 */
    private final Map<Path, Long> stamps = new LinkedHashMap<>();
    /** 原子发布视图（读方无锁）。 */
    private volatile List<Entry> view = List.of();
    /** 当前所处阶段（MC 适配器在 setup 后提升；离线默认 ANY）。 */
    private volatile com.hdf.cryptand.dynamic.api.LoadWindow phase;

    private DynamicFramework(Builder b) {
        this.root = b.root;
        this.cores = List.copyOf(b.cores);
        this.sources = List.copyOf(b.sources);
        this.scheduler = b.scheduler != null ? b.scheduler : new VirtualThreadScheduler();
        this.logger = b.logger != null ? b.logger : msg -> { };
        this.enforceApi = b.enforceApi;
        this.phase = b.phase;
    }

    /** 提升/设置当前阶段（MC 适配器在客户端 setup 后调用 CLIENT_SETUP）。 */
    public void setPhase(com.hdf.cryptand.dynamic.api.LoadWindow phase) {
        if (phase != null) {
            this.phase = phase;
        }
    }

    public com.hdf.cryptand.dynamic.api.LoadWindow phase() {
        return phase;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ===== 门面 =====

    /** 扫描全部来源：新增即加载、消失即卸载、内容变更即重载。 */
    public ScanReport scan() {
        final long t0 = System.nanoTime();
        if (scheduler instanceof VirtualThreadScheduler vts) {
            vts.nextEpoch();
        }
        lane.lock();
        try {
            final List<String> msgs = new ArrayList<>();

            // ① DISCOVER（来源发现；来源实现须线程安全，这里顺序调用以保持可预测顺序）
            final List<Source> found = new ArrayList<>();
            for (final ResourceSource src : sources) {
                try {
                    found.addAll(src.discover(context()));
                } catch (Throwable t) {
                    msgs.add("来源 " + src.id() + " 发现失败：" + t);
                }
            }

            // ② 分派（格式探测只读 zip 中央目录）
            final Map<Path, Match> matches = new LinkedHashMap<>();
            for (final Source src : found) {
                if (src.file() == null || !Files.exists(src.file())) {
                    continue;
                }
                final FormatProbe probe = FileFormatProbe.of(src.file());
                final DynamicCoreSpi<?> core = pick(src, probe, msgs);
                if (core == null) {
                    msgs.add("无核心可处理，跳过 " + src);
                    continue;
                }
                matches.put(key(src.file()), new Match(src, core));
            }

            // ③ 找出新增/变更（指纹未变则完全不动 —— 性能关键）
            final List<Path> dirty = new ArrayList<>();
            for (final Map.Entry<Path, Match> e : matches.entrySet()) {
                final long mtime = mtimeOf(e.getKey());
                final Long old = stamps.get(e.getKey());
                if (old != null && old == mtime) {
                    continue;
                }
                dirty.add(e.getKey());
            }

            // ④ PROBE/EXTRACT 并行（元数据读取 = IO；用户定案：虚拟线程）
            final Map<Path, CompletableFuture<Prep>> preps = new LinkedHashMap<>();
            for (final Path k : dirty) {
                final Match m = matches.get(k);
                final ExecutorPlan plan = m.core.plan();
                if (plan.parallelFor(Stage.PROBE)) {
                    preps.put(k, scheduler.background(Stage.PROBE, () -> prep(m)));
                } else {
                    preps.put(k, CompletableFuture.completedFuture(prep(m)));
                }
            }

            // ⑤ 依赖解析（**框架职责**：缺失 / 循环 / 传递禁用 + 拓扑排序）
            //    具体核心不需要关心依赖，只做与 MC 的对接。
            int added = 0;
            int reloaded = 0;
            int failed = 0;
            final List<Candidate> candidates = new ArrayList<>();
            for (final Path k : dirty) {
                final Match m = matches.get(k);
                try {
                    candidates.add(new Candidate(k, m, preps.get(k).join()));
                } catch (Throwable t) {
                    failed++;
                    msgs.add("探测失败 " + m.source + "：" + rootCause(t));
                }
            }
            final List<Candidate> ordered = orderByDependencies(candidates, msgs);
            failed += candidates.size() - ordered.size();

            // ⑥ LOAD + ATTACH（拓扑序：被依赖者先；默认串行）
            for (final Candidate c : ordered) {
                final boolean isReload = stamps.containsKey(c.path);
                try {
                    loadAndAttach(c.match, c.prep, msgs);
                    stamps.put(c.path, mtimeOf(c.path));
                    if (isReload) {
                        reloaded++;
                    } else {
                        added++;
                    }
                } catch (Throwable t) {
                    failed++;
                    msgs.add("加载失败 " + c.match.source + "：" + rootCause(t));
                }
            }

            // ⑦ 消失的来源 ⇒ 级联卸载（依赖它的先走，否则它们的类会指向已关闭的 loader）
            int removed = 0;
            final List<String> gone = new ArrayList<>();
            for (final PluginState st : live.values()) {
                if (st.adhoc) {
                    continue;   // 临时加载的包不由"来源消失"触发卸载（它本就不在任何来源里）
                }
                if (!matches.containsKey(st.jar)) {
                    gone.add(st.id);
                }
            }
            for (final String id : gone) {
                for (final String x : dependentsFirst(id)) {
                    if (live.containsKey(x)) {
                        retire(x, msgs);
                        removed++;
                    }
                }
                msgs.add("卸载 " + id + "（来源消失/级联）");
            }
            // 内存：清掉已消失路径的指纹，避免长期运行后指纹表单调增长
            stamps.keySet().removeIf(p -> !matches.containsKey(p));

            publish();
            for (final String msg : msgs) {
                logger.accept(msg);
            }
            return new ScanReport(added, removed, reloaded, failed, List.copyOf(msgs),
                    (System.nanoTime() - t0) / 1_000_000L);
        } finally {
            lane.unlock();
        }
    }

    /** 单 id 热重载（就地接管由具体核心决定）。 */
    public ReloadReport reload(String id) {
        return reloadMatching(List.of(id));
    }

    /** 按核心热重载。 */
    public ReloadReport reloadCore(String coreId) {
        final List<String> ids = new ArrayList<>();
        for (final PluginState st : live.values()) {
            if (st.core.id().equals(coreId)) {
                ids.add(st.id);
            }
        }
        return reloadMatching(ids);
    }

    /** 全量热重载。 */
    public ReloadReport reloadAll() {
        final List<String> ids = new ArrayList<>(live.keySet());
        return reloadMatching(ids);
    }

    private ReloadReport reloadMatching(List<String> ids) {
        final long t0 = System.nanoTime();
        lane.lock();
        try {
            final List<String> ok = new ArrayList<>();
            final List<String> failed = new ArrayList<>();
            // 级联：重载被依赖者时，依赖它的插件一起重建（逆"依赖者优先"序 = 被依赖者先）
            final java.util.LinkedHashSet<String> expanded = new java.util.LinkedHashSet<>();
            for (final String id : ids) {
                final List<String> closure = dependentsFirst(id);
                for (int i = closure.size() - 1; i >= 0; i--) {
                    expanded.add(closure.get(i));
                }
            }
            for (final String id : expanded) {
                final PluginState st = live.get(id);
                if (st == null) {
                    failed.add(id + ": 未加载");
                    continue;
                }
                try {
                    final Match m = new Match(st.source, st.core);
                    final Prep prep = prep(m);
                    loadAndAttach(m, prep, new ArrayList<>(), st.adhoc);
                    stamps.put(st.jar, mtimeOf(st.jar));
                    ok.add(id);
                } catch (Throwable t) {
                    failed.add(id + ": " + rootCause(t));
                }
            }
            publish();
            return new ReloadReport(failed.isEmpty(), List.copyOf(ok), List.copyOf(failed),
                    (System.nanoTime() - t0) / 1_000_000L);
        } finally {
            lane.unlock();
        }
    }

    /**
     * 临时加载任意 jar（<b>调试用</b>；宿主必须先校验配置开关）。
     *
     * <p>与来源加载的区别：它不来自任何 {@code ResourceSource}，因此 {@code scan()} 的
     * "来源消失即卸载"不会波及它（标 {@code adhoc}）；要卸载请显式 {@link #unload(String)}。</p>
     */
    public boolean loadAdHoc(Path jar) {
        lane.lock();
        try {
            if (jar == null || !Files.isRegularFile(jar)) {
                throw new IllegalArgumentException("找不到 jar：" + jar);
            }
            final List<String> msgs = new ArrayList<>();
            final Source src = Source.dir(jar, "adhoc");
            final DynamicCoreSpi<?> core = pick(src, FileFormatProbe.of(jar), msgs);
            if (core == null) {
                throw new IllegalStateException("没有核心能处理这个包（" + jar.getFileName() + "）");
            }
            loadAndAttach(new Match(src, core), prep(new Match(src, core)), msgs, true);
            publish();
            for (final String msg : msgs) {
                logger.accept(msg);
            }
            return true;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("临时加载失败：" + rootCause(ex), ex);
        } finally {
            lane.unlock();
        }
    }

    /** 按 id 卸载（<b>级联</b>：依赖它的先退役）。返回是否真的卸载了东西。 */
    public boolean unload(String id) {
        lane.lock();
        try {
            final List<String> msgs = new ArrayList<>();
            boolean any = false;
            for (final String x : dependentsFirst(id)) {
                if (live.containsKey(x)) {
                    retire(x, msgs);
                    any = true;
                }
            }
            publish();
            for (final String m : msgs) {
                logger.accept(m);
            }
            return any;
        } finally {
            lane.unlock();
        }
    }

    /** 当前已发布条目（无锁读）。 */
    public List<Entry> entries() {
        return view;
    }

    public List<String> ids() {
        final List<String> out = new ArrayList<>();
        for (final Entry e : view) {
            out.add(e.id());
        }
        return out;
    }

    /** 规模/内存观测快照（卸载后应全部归零 —— 用它做残留断言）。 */
    public record Stats(int entries, int liveLoaders, int stamps, int cores, int sources) {
        @Override
        public String toString() {
            return "entries=" + entries + " loaders=" + liveLoaders + " stamps=" + stamps
                    + " cores=" + cores + " sources=" + sources;
        }
    }

    /** 读一次观测快照（与生命周期同锁，避免读到半状态）。 */
    public Stats stats() {
        lane.lock();
        try {
            return new Stats(view.size(), live.size(), stamps.size(), cores.size(), sources.size());
        } finally {
            lane.unlock();
        }
    }

    public FrameworkContext context() {
        return new Ctx();
    }

    /** 关停：卸载全部条目、回调核心 onShutdown、关调度器。 */
    public void shutdown() {
        lane.lock();
        try {
            final List<String> msgs = new ArrayList<>();
            for (final String id : new ArrayList<>(live.keySet())) {
                retire(id, msgs);
            }
            publish();
            stamps.clear();   // 内存：关停不留指纹（否则长期运行的实例会攒下历史路径）
            for (final DynamicCoreSpi<?> c : cores) {
                try {
                    c.onShutdown();
                } catch (Throwable t) {
                    msgs.add("核心 " + c.id() + " onShutdown 失败：" + t);
                }
            }
            for (final String m : msgs) {
                logger.accept(m);
            }
        } finally {
            lane.unlock();
        }
        if (scheduler instanceof VirtualThreadScheduler vts) {
            vts.close();
        }
    }

    /** 客户端逐 tick：转发给核心（离线环境不会被调用）。 */
    public void tick() {
        for (final DynamicCoreSpi<?> c : cores) {
            try {
                c.onClientTick();
            } catch (Throwable ignored) {
                // 单核心 tick 异常不拖垮其余核心
            }
        }
    }

    /** 资源重载联动：转发给核心。 */
    public void onResourceReload() {
        for (final DynamicCoreSpi<?> c : cores) {
            try {
                c.onResourceReload();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    // ===== 内部 =====

    /** 分派结果。<b>刻意不持有 {@link FormatProbe}</b>：探测视图（zip 条目名列表）只在 canHandle 判定时需要，
     *  判定完立即变成垃圾 —— 大 jar 不会被整轮扫描钉在内存里。 */
    private record Match(Source source, DynamicCoreSpi<?> core) {
    }

    private record Prep(PluginMeta meta, String id, int apiVersion) {
    }

    private Prep prep(Match m) {
        final PluginMeta meta = PluginMeta.read(m.source.file());
        String id = meta.id();
        int api = meta.apiVersion();
        if (!meta.present()) {
            // 无元数据：回退到类加载读 id（慢路径，日志可见）
            id = null;
            api = DynamicApi.VERSION;
        }
        return new Prep(meta, id, api);
    }

    private void loadAndAttach(Match m, Prep prep, List<String> msgs) throws Exception {
        loadAndAttach(m, prep, msgs, false);
    }

    private void loadAndAttach(Match m, Prep prep, List<String> msgs, boolean adhoc) throws Exception {
        final Path jar = m.source.file();
        // 阶段校验：抽象贯穿整个 MC 周期，但每个核心有自己的最早可用阶段
        if (!m.core.window().readyAt(phase)) {
            throw new IllegalStateException("核心 " + m.core.id() + " 需要阶段 " + m.core.window()
                    + "，当前处于 " + phase + "（太早加载不安全）");
        }
        final PluginMeta meta = prep.meta();
        if (meta.present() && enforceApi && !DynamicApi.supports(meta.apiVersion())) {
            throw new IllegalStateException(DynamicApi.incompatible(meta.apiVersion()));
        }
        if (meta.present()) {
            for (final String dep : meta.depends()) {
                if (!live.containsKey(dep)) {
                    throw new IllegalStateException("依赖缺失：" + dep + "（先加载它）");
                }
            }
        }

        final URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, DynamicPlugin.class.getClassLoader());
        DynamicPlugin plugin = null;
        CoreHostImpl host = null;
        final String previousStage;
        try {
            plugin = instantiate(loader, m.core, meta);
            final String id = plugin.id();
            final int api = plugin.apiVersion();
            if (enforceApi && !DynamicApi.supports(api)) {
                throw new IllegalStateException(DynamicApi.incompatible(api));
            }
            // 同 id 已存在 ⇒ 由具体核心决定是否就地接管；失败时逆序还原
            final PluginState old = live.get(id);
            host = new CoreHostImpl(scheduler, logger);
            if (old != null) {
                host.takeOver(id, old.plugin);
            }
            attachRaw(m.core, plugin, host);
            // 接管成功后才退役旧实例（先装后卸：保证界面/资源不断档）。
            // 关键：旧条目里**与新条目同名的资源**不能释放 —— 否则会把新装配的东西拆掉
            //（原 UI 加载器的 P2b 缺陷；现在由框架统一兜住，核心不必自己处理）。
            if (old != null) {
                retireState(old, msgs, host.ownedIds());
            }
            final PluginState st = new PluginState(id, m.core, m.source, key(jar), loader, plugin,
                    host, api, Stage.ATTACH.name(), 0L, null, meta.depends(), adhoc);
            live.put(id, st);
        } catch (Throwable t) {
            // 事务回滚：把接管过的还原、关掉新 loader
            if (host != null) {
                host.rollback();
            }
            if (plugin != null) {
                try {
                    plugin.unload();
                } catch (Throwable ignored) {
                    // 清理尽力而为
                }
            }
            try {
                loader.close();
            } catch (IOException ignored) {
                // 同上
            }
            throw t;
        }
    }

    /** 待加载候选（框架内部）。 */
    private record Candidate(Path path, Match match, Prep prep) {
        String id() {
            return prep.id();
        }

        List<String> deps() {
            return prep.meta().present() ? prep.meta().depends() : List.of();
        }
    }

    /**
     * ===== 依赖解析（框架职责）=====
     *
     * <p>规则（与子包加载器一致）：被依赖者先加载（拓扑序）；缺失依赖 ⇒ 该包不加载；
     * 循环依赖 ⇒ <b>整个环上的包都不加载</b>并报错；任一依赖失败 ⇒ 依赖它的也失败（传递禁用）。</p>
     *
     * <p>无 {@code plugin.properties} 的包不参与依赖图（id 要加载后才知道），会排在最前直接加载。</p>
     */
    private List<Candidate> orderByDependencies(List<Candidate> candidates, List<String> msgs) {
        final Map<String, Candidate> byId = new LinkedHashMap<>();
        final List<Candidate> noMeta = new ArrayList<>();
        for (final Candidate c : candidates) {
            if (c.id() == null) {
                noMeta.add(c);
                msgs.add("包缺少 " + DynamicApi.PLUGIN_META + "，不参与依赖解析：" + c.match.source);
            } else {
                byId.put(c.id(), c);
            }
        }
        final Set<String> failed = new HashSet<>();
        final Set<String> cyclic = new HashSet<>();

        // ① 缺失依赖
        for (final Candidate c : byId.values()) {
            for (final String d : c.deps()) {
                if (!byId.containsKey(d) && !live.containsKey(d)) {
                    failed.add(c.id());
                    msgs.add("依赖缺失：" + c.id() + " -> " + d + "（不加载）");
                }
            }
        }
        // ② 环检测 + 拓扑序（DFS 三色；后序即拓扑序）
        final Map<String, Integer> color = new HashMap<>();
        final List<String> stack = new ArrayList<>();
        final List<Candidate> order = new ArrayList<>();
        for (final Candidate c : byId.values()) {
            dfsOrder(c.id(), byId, color, stack, order, cyclic, msgs);
        }
        failed.addAll(cyclic);
        // ③ 传递禁用（迭代至不动点）
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final Candidate c : byId.values()) {
                if (failed.contains(c.id())) {
                    continue;
                }
                for (final String d : c.deps()) {
                    if (failed.contains(d)) {
                        failed.add(c.id());
                        msgs.add("依赖失败：" + c.id() + " -> " + d + "（不加载）");
                        changed = true;
                        break;
                    }
                }
            }
        }
        final List<Candidate> out = new ArrayList<>(noMeta);
        for (final Candidate c : order) {
            if (!failed.contains(c.id())) {
                out.add(c);
            }
        }
        return out;
    }

    private void dfsOrder(String id, Map<String, Candidate> byId, Map<String, Integer> color,
                          List<String> stack, List<Candidate> order, Set<String> cyclic,
                          List<String> msgs) {
        final Integer c = color.get(id);
        if (c != null && c == 2) {
            return;
        }
        if (c != null && c == 1) {
            final List<String> cycle = new ArrayList<>();
            boolean in = false;
            for (final String s : stack) {
                if (s.equals(id)) {
                    in = true;
                }
                if (in) {
                    cycle.add(s);
                }
            }
            cycle.add(id);
            cyclic.addAll(cycle);
            msgs.add("检测到循环依赖（整个环不加载）：" + String.join(" -> ", cycle));
            return;
        }
        color.put(id, 1);
        stack.add(id);
        final Candidate cand = byId.get(id);
        if (cand != null) {
            for (final String d : cand.deps()) {
                if (byId.containsKey(d)) {
                    dfsOrder(d, byId, color, stack, order, cyclic, msgs);
                }
            }
        }
        stack.remove(stack.size() - 1);
        color.put(id, 2);
        final Candidate self = byId.get(id);
        if (self != null) {
            order.add(self);
        }
    }

    /**
     * 依赖者闭包：返回"依赖 id 的插件（含传递）在前、id 自身在后"的顺序 ——
     * 卸载按此顺序（先卸依赖者，再卸被依赖者）；重载取逆序（被依赖者先重建）。
     */
    private List<String> dependentsFirst(String id) {
        final List<String> out = new ArrayList<>();
        collectDependents(id, out, new HashSet<>());
        out.add(id);
        return out;
    }

    private void collectDependents(String id, List<String> out, Set<String> seen) {
        for (final PluginState st : live.values()) {
            if (st.id.equals(id) || seen.contains(st.id)) {
                continue;
            }
            if (st.deps.contains(id)) {
                seen.add(st.id);
                collectDependents(st.id, out, seen);
                out.add(st.id);
            }
        }
    }

    private void retire(String id, List<String> msgs) {
        final PluginState st = live.remove(id);
        if (st != null) {
            retireState(st, msgs);
        }
    }

    private void retireState(PluginState st, List<String> msgs) {
        retireState(st, msgs, Set.of());
    }

    private void retireState(PluginState st, List<String> msgs, Set<String> keepResources) {
        try {
            detachRaw(st.core, st.plugin, st.host);
        } catch (Throwable t) {
            msgs.add("detach 失败 " + st.id + "：" + rootCause(t));
        }
        try {
            st.plugin.unload();
        } catch (Throwable t) {
            msgs.add("unload 失败 " + st.id + "：" + rootCause(t));
        }
        st.host.releaseAll(st.id, msgs, keepResources);
        try {
            st.loader.close();
        } catch (IOException ignored) {
            // 关不掉则由 GC 处理（但 Windows 上文件句柄可能残留 —— 尽力而为）
        }
    }

    private void publish() {
        final List<Entry> list = new ArrayList<>(live.size());
        for (final PluginState st : live.values()) {
            list.add(new Entry(st.id, st.core.id(), st.source.kind(), st.source.origin(),
                    st.stage, st.apiVersion, st.loadMillis, st.error));
        }
        view = List.copyOf(list);
    }

    private DynamicCoreSpi<?> pick(Source src, FormatProbe probe, List<String> msgs) {
        DynamicCoreSpi<?> hit = null;
        for (final DynamicCoreSpi<?> c : cores) {
            if (c.canHandle(src, probe)) {
                if (hit == null) {
                    hit = c;
                } else if (!hit.id().equals(c.id())) {
                    msgs.add("多核心命中 " + src + "：" + hit.id() + " / " + c.id() + "（按注册顺序取前者）");
                }
            }
        }
        return hit;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DynamicPlugin instantiate(URLClassLoader loader, DynamicCoreSpi<?> core, PluginMeta meta) {
        if (meta.mainClass() != null && !meta.mainClass().isBlank()) {
            try {
                final Class<?> cls = Class.forName(meta.mainClass(), true, loader);
                return (DynamicPlugin) cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("主类实例化失败 " + meta.mainClass() + "：" + rootCause(ex), ex);
            }
        }
        final ServiceLoader<? extends DynamicPlugin> sl =
                ServiceLoader.load((Class) core.pluginType(), loader);
        final Iterator<? extends DynamicPlugin> it = sl.iterator();
        if (!it.hasNext()) {
            throw new IllegalStateException("jar 里没有 " + core.pluginType().getName()
                    + " 实现（补 " + DynamicApi.servicePath(core.pluginType())
                    + " 或 " + DynamicApi.PLUGIN_META + " 的 mainClass）");
        }
        return it.next();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void attachRaw(DynamicCoreSpi core, DynamicPlugin plugin, CoreHost host) {
        core.attach(plugin, host);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void detachRaw(DynamicCoreSpi core, DynamicPlugin plugin, CoreHost host) {
        core.detach(plugin, host);
    }

    private static Path key(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private static long mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ex) {
            return -1L;
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    /** 条目内部状态。 */
    private static final class PluginState {
        final String id;
        final DynamicCoreSpi<?> core;
        final Source source;
        final Path jar;
        final URLClassLoader loader;
        final DynamicPlugin plugin;
        final CoreHostImpl host;
        final int apiVersion;
        final String stage;
        final long millis = 0L;
        final long loadMillis;
        final String error;
        /** 依赖表（来自 plugin.properties 的 depends）—— 由框架做拓扑/级联，核心不关心。 */
        final List<String> deps;
        /** true = 临时加载（plugin load，调试用）：不受"来源消失即卸载"影响。 */
        final boolean adhoc;

        PluginState(String id, DynamicCoreSpi<?> core, Source source, Path jar, URLClassLoader loader,
                    DynamicPlugin plugin, CoreHostImpl host, int apiVersion, String stage,
                    long loadMillis, String error, List<String> deps, boolean adhoc) {
            this.id = id;
            this.core = core;
            this.source = source;
            this.jar = jar;
            this.loader = loader;
            this.plugin = plugin;
            this.host = host;
            this.apiVersion = apiVersion;
            this.stage = stage;
            this.loadMillis = loadMillis;
            this.error = error;
            this.deps = deps == null ? List.of() : List.copyOf(deps);
            this.adhoc = adhoc;
        }
    }

    /** 宿主给核心的授权实现：资源登记 + 接管登记 + 越权/失败可见。 */
    private static final class CoreHostImpl implements CoreHost {
        private final Map<String, Runnable> owned = new LinkedHashMap<>();
        private final Map<String, Object> previous = new LinkedHashMap<>();
        private final DynamicScheduler scheduler;
        private final Consumer<String> logger;

        CoreHostImpl(DynamicScheduler scheduler, Consumer<String> logger) {
            this.scheduler = scheduler;
            this.logger = logger;
        }

        @Override
        public void own(String resourceId, Runnable release) {
            if (resourceId != null && release != null) {
                owned.put(resourceId, release);
            }
        }

        @Override
        public <R> R mainThread(Callable<R> task) {
            final CompletableFuture<R> f = new CompletableFuture<>();
            scheduler.mainThread(() -> {
                try {
                    f.complete(task.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
            try {
                return f.get();
            } catch (Exception ex) {
                throw new IllegalStateException("主线程任务失败：" + rootCause(ex), ex);
            }
        }

        @Override
        public void takeOver(String resourceId, Object prev) {
            previous.put(resourceId, prev);
        }

        @Override
        public Object previousOf(String resourceId) {
            return previous.get(resourceId);
        }

        @Override
        public void warn(String msg) {
            logger.accept("[warn] " + msg);
        }

        /** 失败回滚：逆序执行释放动作（尽力而为）。 */
        void rollback() {
            final List<Runnable> actions = new ArrayList<>(owned.values());
            for (int i = actions.size() - 1; i >= 0; i--) {
                try {
                    actions.get(i).run();
                } catch (Throwable ignored) {
                    // 尽力而为
                }
            }
            owned.clear();
            previous.clear();   // 内存：不再钉住被接管前的旧实例
        }

        /** 本条目占用的资源 id（用于接管时判定"哪些不能被旧条目释放"）。 */
        Set<String> ownedIds() {
            return new LinkedHashSet<>(owned.keySet());
        }

        /**
         * 正常退役：执行释放动作。
         *
         * @param keep 已被新条目接管的资源 id —— **不能释放**（否则会拆掉刚装配好的新东西）
         */
        void releaseAll(String id, List<String> msgs, Set<String> keep) {
            final Iterator<Map.Entry<String, Runnable>> it = owned.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, Runnable> e = it.next();
                it.remove();
                if (keep != null && keep.contains(e.getKey())) {
                    continue;   // 已被新条目接管：交由新条目负责
                }
                try {
                    e.getValue().run();
                } catch (Throwable t) {
                    msgs.add("释放资源失败 " + id + "/" + e.getKey() + "：" + rootCause(t));
                }
            }
            owned.clear();
            previous.clear();   // 内存：退役后不残留旧实例引用
        }
    }

    /** 框架上下文实现。 */
    private final class Ctx implements FrameworkContext {
        @Override
        public Path root() {
            return root;
        }

        @Override
        public List<DynamicCoreSpi<?>> cores() {
            return cores;
        }

        @Override
        public void log(String msg) {
            logger.accept(msg);
        }
    }

    /** 构建器：第三方通过它注册核心与来源（唯一入口）。 */
    public static final class Builder {
        private Path root = Path.of(DynamicApi.ROOT_DIR);
        private final List<DynamicCoreSpi<?>> cores = new ArrayList<>();
        private final List<ResourceSource> sources = new ArrayList<>();
        private DynamicScheduler scheduler;
        private Consumer<String> logger;
        private boolean enforceApi = true;
        private com.hdf.cryptand.dynamic.api.LoadWindow phase = com.hdf.cryptand.dynamic.api.LoadWindow.ANY;

        /** 当前阶段（离线/测试默认 ANY；MC 侧在构造期传 MOD_CONSTRUCT、setup 后提升 CLIENT_SETUP）。 */
        public Builder phase(com.hdf.cryptand.dynamic.api.LoadWindow phase) {
            if (phase != null) {
                this.phase = phase;
            }
            return this;
        }

        public Builder root(Path root) {
            if (root != null) {
                this.root = root;
            }
            return this;
        }

        /** 注册具体核心（校验 API 版本 + 触发 onRegistered）。 */
        public Builder core(DynamicCoreSpi<?> core) {
            if (core == null) {
                return this;
            }
            if (!DynamicApi.supports(core.apiVersion())) {
                throw new IllegalArgumentException("核心 " + core.id() + " " + DynamicApi.incompatible(core.apiVersion()));
            }
            for (final DynamicCoreSpi<?> c : cores) {
                if (c.id().equals(core.id())) {
                    throw new IllegalArgumentException("核心 id 冲突：" + core.id());
                }
            }
            cores.add(core);
            return this;
        }

        public Builder source(ResourceSource source) {
            if (source != null) {
                sources.add(source);
            }
            return this;
        }

        public Builder scheduler(DynamicScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder logger(Consumer<String> logger) {
            this.logger = logger;
            return this;
        }

        /** 是否强制 API 版本匹配（默认 true —— 用户定案：第三方必须走我们的 API）。 */
        public Builder enforceApi(boolean enforce) {
            this.enforceApi = enforce;
            return this;
        }

        public DynamicFramework build() {
            final DynamicFramework fw = new DynamicFramework(this);
            for (final DynamicCoreSpi<?> c : fw.cores) {
                try {
                    c.onRegistered(fw.context());
                } catch (Throwable t) {
                    fw.logger.accept("核心 " + c.id() + " onRegistered 失败：" + t);
                }
            }
            return fw;
        }
    }
}

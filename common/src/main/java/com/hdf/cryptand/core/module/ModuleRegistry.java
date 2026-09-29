package com.hdf.cryptand.core.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ===== 通用模块注册表/加载器（common · 2026-08-30 用户定稿） =====
 *
 * <b>mod 内多模块的「类 mod」轻量加载器</b>：注册 → 依赖解析（拓扑排序 + 循环
 * 依赖检测 + 传递禁用）→ 生命周期调度（init/tick/commonSetup/commands）→
 * Mixin 配置收集。与 MC 无关（泛型 &lt;B&gt; 总线 / &lt;L&gt; 世界）。
 *
 * 依赖语义（类 mod 加载）：
 * <ul>
 *   <li>依赖表：{@link ModuleEntry#dependencies()}；</li>
 *   <li>拓扑排序：被依赖者先 init；</li>
 *   <li>传递禁用：任一依赖未启用/失败 → 本模块不加载；</li>
 *   <li><b>循环依赖：整个循环链上的模块全部不加载并报错</b>（{@link #resolveDependencies()}）。</li>
 * </ul>
 */
public final class ModuleRegistry<B, L> {

    /** 模块条目。 */
    public static final class Entry<B, L> {
        public final String id;
        public final String conditionDesc;
        public final BooleanSupplier enabled;
        public final List<String> dependencies;
        public final Consumer<B> init;
        public final Consumer<L> tick;
        public final Runnable commonSetup;
        public final Runnable commands;
        public final List<String> mixinConfigs;

        Entry(String id, String conditionDesc, BooleanSupplier enabled,
              List<String> dependencies, Consumer<B> init, Consumer<L> tick,
              Runnable commonSetup, Runnable commands, List<String> mixinConfigs) {
            this.id = id;
            this.conditionDesc = conditionDesc;
            this.enabled = enabled;
            this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            this.init = init;
            this.tick = tick;
            this.commonSetup = commonSetup;
            this.commands = commands;
            this.mixinConfigs = mixinConfigs == null ? List.of() : List.copyOf(mixinConfigs);
        }

        @Override
        public String toString() {
            return "module[" + id + ", deps=" + dependencies + ", enabled="
                    + (enabled == null || enabled.getAsBoolean()) + "]";
        }
    }

    private final Map<String, Entry<B, L>> modules = new LinkedHashMap<>();
    /** 失败集（循环依赖 / 依赖未启用 / 依赖缺失）——这些模块不加载。 */
    private final Set<String> failed = new HashSet<>();
    /** 拓扑加载序（resolveDependencies 后有效）。 */
    private final List<String> loadOrder = new ArrayList<>();
    /** 报警回调（默认打印到 stderr；平台可替换为日志）。 */
    private volatile Consumer<String> warner = msg -> System.err.println("[ModuleRegistry] " + msg);

    public void setWarner(Consumer<String> warner) {
        if (warner != null) this.warner = warner;
    }

    // ===== 注册 =====

    public synchronized void register(String id, String conditionDesc, BooleanSupplier enabled,
                                      List<String> dependencies,
                                      Consumer<B> init, Consumer<L> tick,
                                      Runnable commonSetup, Runnable commands,
                                      List<String> mixinConfigs) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("module id blank");
        modules.put(id, new Entry<>(id, conditionDesc,
                enabled != null ? enabled : () -> true, dependencies,
                init, tick, commonSetup, commands, mixinConfigs));
        rebuildIdCache(); // ⚡ 缓存 id 数组（每 tick 调度零分配）
    }

    public synchronized void register(ModuleEntry<B, L> entry, Consumer<B> init,
                                      Consumer<L> tick, Runnable commonSetup, Runnable commands) {
        register(idOf(entry), entry.conditionDesc(), entry::enabled, entry.dependencies(),
                init, tick, commonSetup, commands, entry.mixinConfigs());
    }

    /** 由平台传入（模块 id 由平台注解提供）。 */
    private String idOf(ModuleEntry<B, L> entry) {
        return entry.getClass().getSimpleName();
    }

    public synchronized boolean has(String id) {
        return modules.containsKey(id);
    }

    /** ⚡ 性能：id 数组缓存（register 时重建——避免每 tick List.copyOf 分配）。 */
    private volatile String[] idCache = new String[0];

    private void rebuildIdCache() {
        idCache = modules.keySet().toArray(new String[0]);
    }

    public synchronized Collection<String> ids() {
        return List.of(idCache);
    }

    /** ⚡ 性能：enabled 求值缓存（配置读取昂贵——每 tick 多次查询时避免重复读）。
     *  配置热改/重载后调用 {@link #invalidateEnabledCache()}。 */
    private final Map<String, Boolean> enabledCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 失效 enabled 缓存（配置重载后调用）。 */
    public void invalidateEnabledCache() {
        enabledCache.clear();
    }

    public synchronized Entry<B, L> entry(String id) {
        return modules.get(id);
    }

    // ===== 依赖解析（拓扑 + 环检测 + 传递禁用） =====

    /**
     * ⚠ 依赖解析（init 之前必须调用）：<br>
     * ① 缺失依赖 → 本模块失败；② 循环依赖 → 整环失败并报错；
     * ③ 依赖未启用/失败 → 本模块失败（传递闭包）；④ 拓扑排序得加载序。
     */
    public synchronized void resolveDependencies() {
        failed.clear();
        loadOrder.clear();
        // ① 缺失依赖（引用了未注册的模块 id）
        for (Entry<B, L> e : modules.values()) {
            for (String d : e.dependencies) {
                if (!modules.containsKey(d)) {
                    failed.add(e.id);
                    warner.accept("模块 " + e.id + " 依赖缺失: " + d + "（不加载）");
                }
            }
        }
        // ② 环检测（DFS 三色：0=白 1=灰(在栈) 2=黑(完成)）
        final Map<String, Integer> color = new HashMap<>();
        final java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String id : modules.keySet()) {
            dfsCycle(id, color, stack);
        }
        // ③ 传递禁用（依赖未启用/失败 → 本模块失败）——迭代至不动点
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Entry<B, L> e : modules.values()) {
                if (failed.contains(e.id)) continue;
                for (String d : e.dependencies) {
                    Entry<B, L> dep = modules.get(d);
                    if (dep == null) continue; // 已在上方标记
                    boolean depOk = !failed.contains(d) && evalEnabled(dep);
                    if (!depOk) {
                        failed.add(e.id);
                        warner.accept("模块 " + e.id + " 依赖 " + d + " 未启用/失败 → 不加载");
                        changed = true;
                        break;
                    }
                }
            }
        }
        // ④ 拓扑排序（被依赖者优先；失败模块不入序）
        final Set<String> visited = new HashSet<>();
        for (String id : modules.keySet()) {
            topoVisit(id, visited);
        }
        invalidateEnabledCache(); // failed 集已更新 → 缓存失效
    }

    /** DFS 环检测：灰→灰 命中环 → 整环失败并报错。 */
    private void dfsCycle(String id, Map<String, Integer> color, java.util.Deque<String> stack) {
        Integer c = color.get(id);
        if (c != null && c == 2) return;
        if (c != null && c == 1) {
            // 命中环：栈中从该 id 到栈顶即环
            List<String> cycle = new ArrayList<>();
            boolean in = false;
            for (String s : stack) {
                if (s.equals(id)) in = true;
                if (in) cycle.add(s);
            }
            cycle.add(id);
            for (String s : cycle) failed.add(s);
            warner.accept("检测到循环依赖（整个循环链不加载）: " + String.join(" -> ", cycle));
            return;
        }
        color.put(id, 1);
        stack.push(id);
        Entry<B, L> e = modules.get(id);
        if (e != null) {
            for (String d : e.dependencies) {
                if (modules.containsKey(d)) dfsCycle(d, color, stack);
            }
        }
        stack.pop();
        color.put(id, 2);
    }

    private void topoVisit(String id, Set<String> visited) {
        if (visited.contains(id) || failed.contains(id)) return;
        visited.add(id);
        Entry<B, L> e = modules.get(id);
        if (e != null) {
            for (String d : e.dependencies) {
                if (modules.containsKey(d)) topoVisit(d, visited);
            }
        }
        if (!loadOrder.contains(id)) loadOrder.add(id); // 依赖先入序
    }

    private boolean evalEnabled(Entry<B, L> e) {
        try {
            return e.enabled == null || e.enabled.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 查询/调度 =====

    /** 是否加载本模块（已注册 && 未失败 && 启用条件为真）。
     *  ⚡ 性能：结果缓存（配置读取昂贵——调度/mixin 每 tick 高频查询）；
     *  配置重载/依赖解析后调用 {@link #invalidateEnabledCache()}。 */
    public boolean isEnabled(String id) {
        if (id == null) return false;
        Boolean cached = enabledCache.get(id);
        if (cached != null) return cached;
        final boolean v;
        Entry<B, L> e;
        synchronized (this) {
            e = modules.get(id);
            if (e == null || failed.contains(id)) {
                v = false;
            } else {
                v = evalEnabled(e);
            }
        }
        enabledCache.put(id, v);
        return v;
    }

    /** 是否因依赖/循环问题失败。 */
    public synchronized boolean isFailed(String id) {
        return failed.contains(id);
    }

    /** 拓扑加载序（诊断）。 */
    public synchronized List<String> loadOrder() {
        return List.copyOf(loadOrder);
    }

    /** 按拓扑序 init（enabled 且未失败才调用）。 */
    public void initAll(B bus) {
        List<String> order;
        synchronized (this) {
            order = List.copyOf(loadOrder);
        }
        for (String id : order) {
            if (!isEnabled(id)) continue;
            Entry<B, L> e;
            synchronized (this) {
                e = modules.get(id);
            }
            if (e != null && e.init != null) {
                try {
                    e.init.accept(bus);
                } catch (Throwable t) {
                    warner.accept("模块 " + id + " init 失败: " + t);
                }
            }
        }
    }

    public void tickAll(L level) {
        // ⚡ 性能：用缓存的 id 数组（不每 tick List.copyOf）；isEnabled 走缓存
        final String[] ids = idCache;
        for (String id : ids) {
            if (!isEnabled(id)) continue;
            Entry<B, L> e;
            synchronized (this) {
                e = modules.get(id);
            }
            if (e != null && e.tick != null) {
                try {
                    e.tick.accept(level);
                } catch (Throwable t) {
                    warner.accept("模块 " + id + " tick 失败: " + t);
                }
            }
        }
    }

    public void commonSetupAll() {
        for (String id : ids()) {
            if (!isEnabled(id)) continue;
            Entry<B, L> e;
            synchronized (this) {
                e = modules.get(id);
            }
            if (e != null && e.commonSetup != null) {
                try {
                    e.commonSetup.run();
                } catch (Throwable t) {
                    warner.accept("模块 " + id + " commonSetup 失败: " + t);
                }
            }
        }
    }

    public void commandsAll() {
        for (String id : ids()) {
            if (!isEnabled(id)) continue;
            Entry<B, L> e;
            synchronized (this) {
                e = modules.get(id);
            }
            if (e != null && e.commands != null) {
                try {
                    e.commands.run();
                } catch (Throwable t) {
                    warner.accept("模块 " + id + " commands 失败: " + t);
                }
            }
        }
    }

    // ===== Mixin 配置（类 mod：模块声明自己的 mixin 面） =====

    /** 可加载的 Mixin 配置（仅 enabled 且未失败模块声明）。 */
    public List<String> loadableMixinConfigs() {
        List<String> out = new ArrayList<>();
        for (String id : ids()) {
            if (!isEnabled(id)) continue;
            Entry<B, L> e;
            synchronized (this) {
                e = modules.get(id);
            }
            if (e != null) out.addAll(e.mixinConfigs);
        }
        return out;
    }

    /** 全部声明（id → mixin 配置；诊断）。 */
    public synchronized Map<String, List<String>> declaredMixinConfigs() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Entry<B, L> e : modules.values()) {
            if (!e.mixinConfigs.isEmpty()) out.put(e.id, e.mixinConfigs);
        }
        return out;
    }
}

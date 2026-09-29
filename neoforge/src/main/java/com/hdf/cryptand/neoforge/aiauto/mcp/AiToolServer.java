package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hdf.cryptand.mcp.tool.McpTool;
import com.hdf.cryptand.mcp.tool.McpToolHandler;
import com.hdf.cryptand.mcp.tool.McpToolRegistry;
import com.hdf.cryptand.mcp.tool.ToolSchemas;
import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ===== aiauto 工具门面（2026-09-15 建立，2026-09-16 接入真 MCP）=====
 *
 * <p>本类是<b>平台侧的工具注册门面</b>：工具实现只写一遍，注册时<b>同时投递到两条通道</b>——</p>
 * <ol>
 *   <li><b>MCP 通道（标准，主通道）</b>：协议框架在 {@code common} 的
 *       {@code com.hdf.cryptand.mcp}（JSON-RPC 2.0 + initialize/tools/resources +
 *       Streamable HTTP 传输）；本类把工具翻成 MCP 工具，交给
 *       {@link AiMcpServer} 暴露成真正的 MCP server，标准客户端
 *       （Claude Desktop / Cline / Inspector / 自研 MCP 客户端）可直接接入；</li>
 *   <li><b>文件通道（legacy，兜底）</b>：{@code calls/<id>.json → results/<id>.json} 的
 *       文件式 JSON-RPC。<b>它不是 MCP</b>，只是给"只能读写文件、不能发 HTTP"的场景留的后路，
 *       可用 {@code aiauto.toml#fileChannelEnabled} 关闭。</li>
 * </ol>
 *
 * <p>两条通道共用同一份 {@link #TOOLS} 定义、同一个主线程队列（{@link #QUEUE}）
 * 与同一套等待/完成语义 —— AI 无论从哪条通道进来，看到的能力和执行顺序都一致。</p>
 *
 * <h3>文件通道协议（legacy）</h3>
 * <pre>
 * 读   tools.json               工具清单与参数说明（启动时生成，AI 先读它）
 * 写   calls/&lt;id&gt;.json          {"tool":"place_block","args":{...}}
 * 读   results/&lt;id&gt;.json        {"ok":true,"result":{...}} / {"ok":false,"error":"…"}
 * </pre>
 *
 * <p>处理完的调用文件会移到 {@code calls/done/}，结果永久保留；同时写一行 console.log。</p>
 */
public final class AiToolServer {

    /**
     * 一个可调用工具。
     *
     * @param requires    依赖类别：{@code world} = 纯世界操作，<b>不需要玩家</b>；
     *                    {@code player} = 需要玩家存在；{@code ui} = 需要客户端界面/渲染（要看的东西）；
     *                    {@code session} = 会话诊断。写进 tools.json，AI 据此判断能不能用。
     * @param annotations MCP 工具注解（readOnlyHint / destructiveHint …；hint 性质，客户端不得据此做安全决策）
     */
    public record Tool(String name, String description, JsonObject params, String requires,
                       JsonObject annotations, Function<JsonObject, JsonObject> handler) {
    }

    /** 当前注册批次默认的依赖类别（由 {@link #category} 切换） */
    private static String currentRequires = "world";

    /** 当前注册批次是否只读（由 {@link #readOnly} 切换；只影响 MCP 注解提示） */
    private static boolean currentReadOnly = false;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    /** 待执行步骤（批处理与单条共用；按序推进，等待不阻塞主线程） */
    private static final java.util.Deque<JsonObject> QUEUE = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** 正在等待条件的步骤 */
    private static volatile JsonObject waiting;
    private static volatile long waitingSince;

    /**
     * 进行中的调用（完成信号用）。
     *
     * <p>每个步骤入队时会被打上 {@code __call} 标记；每执行完一步递减剩余数，
     * 归零即写 {@code results/&lt;id&gt;.done.json} —— <b>该文件出现就代表这个批处理
     * 全部跑完了（含其中的 wait）</b>，AI 轮询它即可，不必再死等固定秒数。</p>
     */
    private static final Map<String, CallState> CALLS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 已完成的调用（保留最近 {@value #DONE_KEEP} 条）—— {@code plan_status} 据此回答
     * "那次 run_plan 到底跑完了没、错了几步"。
     */
    private static final int DONE_KEEP = 50;
    private static final Map<String, CallState> DONE_CALLS = new java.util.LinkedHashMap<>(32, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CallState> eldest) {
            return size() > DONE_KEEP;
        }
    };

    private static void rememberDone(String id, CallState state) {
        synchronized (DONE_CALLS) {
            DONE_CALLS.put(id, state);
        }
    }

    private static final class CallState {
        final String id;
        final int steps;
        int remaining;
        int errors;
        volatile boolean done;
        volatile long tookMs;
        final long startedAt = System.currentTimeMillis();

        CallState(String id, int steps) {
            this.id = id;
            this.steps = steps;
            this.remaining = steps;
        }
    }

    /** 队列状态（供 queue_status / plan_status 查询） */
    public static JsonObject queueStatus() {
        final JsonObject o = new JsonObject();
        o.addProperty("pending", QUEUE.size());
        final JsonObject w = waiting;
        o.addProperty("waiting", w == null ? "" : w.toString());
        o.addProperty("activeCalls", CALLS.size());
        synchronized (DONE_CALLS) {
            o.addProperty("recentDone", DONE_CALLS.size());
        }
        return o;
    }

    /**
     * 查询一次调用（{@code run_plan} 或文件通道批处理）的进度/结果。
     *
     * <p>这是给 AI 的<b>轮询接口</b>：MCP 的工具调用不能阻塞主线程等待跨帧流程，
     * 所以 {@code run_plan} 立即返回 id，AI 再用本方法确认"是否全部落地"。</p>
     */
    public static JsonObject planStatus(String id) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        final CallState active = CALLS.get(id);
        if (active != null) {
            o.addProperty("done", false);
            o.addProperty("steps", active.steps);
            o.addProperty("remaining", active.remaining);
            o.addProperty("errors", active.errors);
            o.addProperty("elapsedMs", System.currentTimeMillis() - active.startedAt);
            o.addProperty("queuePending", QUEUE.size());
            return o;
        }
        final CallState finished;
        synchronized (DONE_CALLS) {
            finished = DONE_CALLS.get(id);
        }
        if (finished != null) {
            o.addProperty("done", true);
            o.addProperty("ok", finished.errors == 0);
            o.addProperty("steps", finished.steps);
            o.addProperty("errors", finished.errors);
            o.addProperty("tookMs", finished.tookMs);
            return o;
        }
        o.addProperty("done", false);
        o.addProperty("ok", false);
        o.addProperty("error", "未知调用 id（可能已过期，只保留最近 " + DONE_KEEP + " 条）");
        return o;
    }

    /** 推进队列：处理等待 → 执行队首（每帧由 tick 调用） */
    private static void pump() {
        final JsonObject w = waiting;
        if (w != null) {
            if (!satisfied(w)) {
                return;
            }
            log("  [wait] 条件满足 " + w);
            waiting = null;
            finishStep(w, true, "wait");
        }
        final JsonObject step = QUEUE.pollFirst();
        if (step == null) {
            return;
        }
        if (step.has("wait")) {
            waiting = step;
            waitingSince = System.currentTimeMillis();
            return;
        }
        final String tool = step.has("tool") ? step.get("tool").getAsString() : "";
        final JsonObject args = step.has("args") && step.get("args").isJsonObject()
                ? step.getAsJsonObject("args") : new JsonObject();
        final Tool impl = TOOLS.get(tool);
        if (impl == null) {
            log("  [err] 未知工具 " + tool);
            return;
        }
        try {
            final JsonObject result = impl.handler().apply(args);
            log("  [" + tool + "] " + (result == null ? "OK" : result.toString()));
            finishStep(step, succeeded(result), tool);
        } catch (Throwable ex) {
            log("  [err] " + tool + " → " + ex);
            finishStep(step, false, tool);
        }
    }

    /**
     * 判断一步是否成功。
     *
     * <p>按协议约定：工具内部失败 ALSO 走 {@code ok=true}，用 {@code result.error} 或
     * <b>{@code result.message} 以 "ERR" 开头</b>来表达（例如"无玩家""无法直写世界"）。
     * 完成信号若不认这条约定，就会把"整批空跑失败"报成 0 错误 —— 实测踩过。</p>
     */
    private static boolean succeeded(JsonObject result) {
        if (result == null) {
            return true;
        }
        if (result.has("error")) {
            return false;
        }
        if (result.has("message")) {
            return !result.get("message").getAsString().startsWith("ERR");
        }
        return true;
    }

    /**
     * 记一步执行结果；该调用的步骤全部走完时写 {@code results/&lt;id&gt;.done.json}。
     *
     * <p>这是给 AI 的<b>完成信号</b>：出现该文件 = 这次批处理（含 wait）已经全部执行完，
     * 不需要 sleep 猜时间。</p>
     */
    private static void finishStep(JsonObject step, boolean ok, String note) {
        final JsonElement marker = step.get("__call");
        if (marker == null) {
            return;
        }
        final String id = marker.getAsString();
        final CallState state = CALLS.get(id);
        if (state == null) {
            return;
        }
        state.remaining--;
        if (!ok) {
            state.errors++;
        }
        if (state.remaining > 0) {
            return;
        }
        CALLS.remove(id);
        final long took = System.currentTimeMillis() - state.startedAt;
        state.done = true;
        state.tookMs = took;
        rememberDone(id, state);                       // 保留给 plan_status 轮询
        log("  [done] " + id + " → 步数=" + state.steps + " 错误=" + state.errors + " 耗时=" + took + "ms");
        try {
            final JsonObject done = new JsonObject();
            done.addProperty("id", id);
            done.addProperty("ok", state.errors == 0);
            done.addProperty("steps", state.steps);
            done.addProperty("errors", state.errors);
            done.addProperty("tookMs", took);
            done.addProperty("finishedAt", LocalDateTime.now().format(STAMP));
            done.addProperty("signal", "这个文件出现即表示该调用（含 wait）已全部执行完毕");
            Files.createDirectories(resultsDir());
            Files.writeString(resultsDir().resolve(id + ".done.json"),
                    GSON.toJson(done), StandardCharsets.UTF_8);
            log("  [done] 已写 results/" + id + ".done.json（完成信号）");
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto/mcp] 写完成信号失败 {}", id, ex);
        }
    }

    /** 等待条件：{"wait":{"world":true}} / {"wait":{"phase":"WORLD_READY"}} / {"wait":{"ms":500}} / {"wait":{"screen":"X"}} */
    private static boolean satisfied(JsonObject step) {
        final JsonElement we = step.get("wait");
        if (we == null || !we.isJsonObject()) {
            return true;
        }
        final JsonObject w = we.getAsJsonObject();
        final var mc = net.minecraft.client.Minecraft.getInstance();
        try {
            if (w.has("ms")) {
                return System.currentTimeMillis() - waitingSince >= w.get("ms").getAsLong();
            }
            if (w.has("world")) {
                return mc.level != null && mc.player != null;
            }
            if (w.has("phase")) {
                return com.hdf.cryptand.neoforge.aiauto.AiSession.phaseName()
                        .equalsIgnoreCase(w.get("phase").getAsString());
            }
            if (w.has("batches")) {
                // 等 place_batch 的批次全部落地（{\"wait\":{\"batches\":0}}）
                return com.hdf.cryptand.neoforge.aiauto.mc.WorldOps.pendingBatches()
                        <= w.get("batches").getAsInt();
            }
            if (w.has("noscreen")) {
                return mc.screen == null;
            }
            if (w.has("screen")) {
                return mc.screen != null && mc.screen.getClass().getSimpleName()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(w.get("screen").getAsString().toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 写一行日志（原 cmd 的 console.log 保留为纯日志，不再是交互通道） */
    public static void log(String text) {
        try {
            final Path log = AiAutomation.outDir().resolve("console.log");
            Files.createDirectories(AiAutomation.outDir());
            Files.writeString(log,
                    "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] "
                            + text + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(log) ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (Throwable ignored) {
        }
    }
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AiToolServer() {
    }

    // ==================== 注册 ====================

    /** 给一组注册调用打上依赖类别标签：world / player / ui / session */
    public static void category(String requires, Runnable body) {
        final String previous = currentRequires;
        currentRequires = requires;
        try {
            body.run();
        } finally {
            currentRequires = previous;
        }
    }

    /** 给一组注册调用打上"只读"标签（进 MCP 的 {@code readOnlyHint}） */
    public static void readOnly(Runnable body) {
        final boolean previous = currentReadOnly;
        currentReadOnly = true;
        try {
            body.run();
        } finally {
            currentReadOnly = previous;
        }
    }

    public static void register(String name, String description, JsonObject params,
                                Function<JsonObject, JsonObject> handler) {
        register(name, description, params, currentRequires, handler);
    }

    /** 覆盖类别的注册（同一个工具包里混有"不依赖玩家"和"依赖玩家"的工具时用） */
    public static void register(String name, String description, JsonObject params, String requires,
                                Function<JsonObject, JsonObject> handler) {
        register(name, description, params, requires, currentReadOnly, handler);
    }

    /**
     * 全参注册：<b>一次注册，两条通道同时投递</b>（MCP 主通道 + 文件 legacy 通道）。
     *
     * @param readOnly 是否只读（只影响 MCP 注解提示，不做强制）
     */
    public static void register(String name, String description, JsonObject params, String requires,
                                boolean readOnly, Function<JsonObject, JsonObject> handler) {
        final JsonObject annotations = annotationsFor(requires, readOnly);
        TOOLS.put(name, new Tool(name, description, params, requires, annotations, handler));
        publishToMcp(name, description, params, annotations, requires, handler);
    }

    /** 推导 MCP 工具注解（hint）：只读 / 是否可能改世界 */
    private static JsonObject annotationsFor(String requires, boolean readOnly) {
        final boolean destructive = !readOnly
                && ("world".equals(requires) || "player".equals(requires));
        return ToolSchemas.annotations(readOnly, destructive, false, false);
    }

    /**
     * 同步注册到 MCP 注册表（真 MCP 通道）。
     *
     * <p>参数表是 aiauto 的老式形态（{@code {"x":"int/string（支持 ~）"}}），这里用
     * {@link ToolSchemas#fromLegacyPairs} 翻译成标准 JSON Schema —— MCP 客户端要按 schema 校验参数。
     * 工具实现<b>不改</b>：执行线程由 {@code McpServer} 的 executor 决定（主线程 tick）。</p>
     */
    private static void publishToMcp(String name, String description, JsonObject params,
                                     JsonObject annotations, String requires,
                                     Function<JsonObject, JsonObject> handler) {
        try {
            final McpToolRegistry registry = AiMcpServer.registry();
            if (registry == null) {
                return;                                 // MCP 未装配（理论上不会）
            }
            final JsonObject schema = params != null && params.size() > 0
                    ? ToolSchemas.fromLegacyPairs(params)
                    : ToolSchemas.object();
            registry.register(new McpTool(name, null, description, schema, annotations,
                    requires, McpToolHandler.ofJson(handler)));
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto/mcp] 注册 MCP 工具失败：{}", name, ex);
        }
    }

    public static List<String> toolNames() {
        return new ArrayList<>(TOOLS.keySet());
    }

    // ==================== 目录 ====================

    public static Path callsDir() {
        return AiAutomation.outDir().resolve("calls");
    }

    public static Path resultsDir() {
        return AiAutomation.outDir().resolve("results");
    }

    public static Path schemaFile() {
        return AiAutomation.outDir().resolve("tools.json");
    }

    // ==================== 驱动 ====================

    /** 每帧调用：扫描 calls/ 下未处理的调用并执行（文件 legacy 通道；MCP 通道由 AiMcpServer 驱动） */
    public static void tick() {
        if (!AiAutomation.allowed()) {
            return;
        }
        try {
            if (com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto.fileChannelEnabled()) {
                final Path calls = callsDir();
                if (!Files.isDirectory(calls)) {
                    Files.createDirectories(calls);
                    Files.createDirectories(resultsDir());
                    writeSchema();
                } else {
                    final List<Path> pending;
                    try (var s = Files.list(calls)) {
                        pending = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                                .sorted().toList();
                    }
                    for (Path file : pending) {
                        handle(file);
                    }
                }
            }
            // 队列推进与两条通道共用（MCP 的 run_plan 也排在这里）：必须每帧跑
            pump();
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto/mcp] 处理调用失败", ex);
        }
    }

    /**
     * 把一段步骤入队（文件通道批处理 与 MCP 的 {@code run_plan} 共用）。
     *
     * @param id    调用 id（AI 用它轮询 {@link #planStatus}）
     * @param steps 步骤数组：{@code {"tool":…,"args":…}} 或 {@code {"wait":{…}}}
     * @return 摘要（含 queuedBehind / note）
     */
    public static JsonObject enqueue(String id, JsonArray steps) {
        final JsonObject summary = new JsonObject();
        summary.addProperty("ok", true);
        summary.addProperty("id", id);
        summary.addProperty("steps", steps.size());
        if (!QUEUE.isEmpty()) {
            summary.addProperty("queuedBehind", QUEUE.size());
        }
        for (JsonElement step : steps) {
            if (!step.isJsonObject()) {
                continue;
            }
            final JsonObject queued = step.getAsJsonObject();
            queued.addProperty("__call", id);          // 每步都知道自己属于哪个调用
            QUEUE.addLast(queued);
        }
        CALLS.put(id, new CallState(id, steps.size()));
        return summary;
    }

    /**
     * 处理一个调用文件。
     *
     * <p>文件内容两种形态，<b>同一套协议</b>：</p>
     * <pre>
     * 单条工具   {"tool":"place_block","args":{...}}
     * 批处理     [ {"tool":"prep"}, {"wait":{"world":true}}, {"tool":"run_command","args":{...}} ]
     * </pre>
     * <p>批处理按序执行，遇到 {"wait":{...}} 会（不阻塞主线程地）等待条件满足再走下一步，
     * 因此不需要单独的 cmd 语法 —— 编排本身就是数据。</p>
     */
    private static void handle(Path file) {
        final String id = file.getFileName().toString().replace(".json", "");
        final JsonObject response = new JsonObject();
        try {
            final JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            final JsonArray steps = root.isJsonArray() ? root.getAsJsonArray()
                    : new JsonArray();
            if (root.isJsonObject()) {
                steps.add(root.getAsJsonObject());
            }
            response.addProperty("ok", true);
            response.addProperty("steps", steps.size());
            // 批处理：整段入队，按序推进（含等待）—— 与 MCP 的 run_plan 共用同一段逻辑
            final JsonObject summary = enqueue(id, steps);
            if (summary.has("queuedBehind")) {
                response.addProperty("queuedBehind", summary.get("queuedBehind").getAsInt());
            }
            response.add("outcomes", new JsonArray());
            response.addProperty("note", "已入队；用 {\"tool\":\"plan_status\",\"args\":{\"id\":\"" + id
                    + "\"}} 查询进度（done=true 即全部执行完毕）");
        } catch (Throwable ex) {
            response.addProperty("ok", false);
            response.addProperty("error", String.valueOf(ex));
        }
        response.addProperty("id", id);
        response.addProperty("at", LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(resultsDir());
            Files.writeString(resultsDir().resolve(id + ".json"),
                    GSON.toJson(response), StandardCharsets.UTF_8);
            final Path doneDir = callsDir().resolve("done");
            Files.createDirectories(doneDir);
            Files.move(file, doneDir.resolve(file.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
            log("  [call] " + id + " → ok=" + response.get("ok").getAsString());
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto/mcp] 写结果失败 {}", id, ex);
        }
    }

    /** 生成工具清单（AI 读它来发现能力，不必猜） */
    public static void writeSchema() {
        try {
            Files.createDirectories(AiAutomation.outDir());
            final JsonObject root = new JsonObject();
            root.addProperty("protocol", "aiauto-file-jsonrpc/1");
            root.addProperty("howToCall",
                    "写 calls/<id>.json：单条 {\"tool\":\"<名字>\",\"args\":{...}}，"
                            + "或数组 [{...},{...}]（批处理，按序执行，可插 {\"wait\":{...}}）；"
                            + "然后读 results/<同一id>.json（ok/result 或 ok/error）");
            final JsonArray tools = new JsonArray();
            for (Tool t : TOOLS.values()) {
                final JsonObject o = new JsonObject();
                o.addProperty("name", t.name());
                o.addProperty("description", t.description());
                o.addProperty("requires", t.requires());
                o.add("params", t.params());
                tools.add(o);
            }
            root.add("tools", tools);

            // 组合示例：工具是原子的，复杂流程靠批处理组合 —— 这里给出可直接复制的样例
            final JsonObject examples = new JsonObject();
            examples.addProperty("note", "工具都是单一操作；复杂流程写成一个数组调用即可（可插 wait）");
            examples.add("clean_workbench", JsonParser.parseString("""
                    [
                      {"tool":"set_gamemode","args":{"mode":"creative"}},
                      {"tool":"set_time","args":{"value":"day"}},
                      {"tool":"set_weather","args":{"value":"clear"}},
                      {"tool":"kill_entities","args":{"radius":64}},
                      {"tool":"clear_effects"}
                    ]"""));
            examples.add("house", JsonParser.parseString("""
                    [
                      {"tool":"fill_region","args":{"x1":"~1","y1":"~-1","z1":"~1","x2":"~9","y2":"~4","z2":"~7","block":"air"}},
                      {"tool":"fill_region","args":{"x1":"~1","y1":"~-1","z1":"~1","x2":"~9","y2":"~-1","z2":"~7","block":"stone"}},
                      {"tool":"fill_region","args":{"x1":"~1","y1":"~","z1":"~1","x2":"~9","y2":"~3","z2":"~1","block":"oak_planks"}},
                      {"tool":"fill_region","args":{"x1":"~1","y1":"~","z1":"~7","x2":"~9","y2":"~3","z2":"~7","block":"oak_planks"}},
                      {"tool":"fill_region","args":{"x1":"~1","y1":"~","z1":"~1","x2":"~1","y2":"~3","z2":"~7","block":"oak_planks"}},
                      {"tool":"fill_region","args":{"x1":"~9","y1":"~","z1":"~1","x2":"~9","y2":"~3","z2":"~7","block":"oak_planks"}},
                      {"tool":"fill_region","args":{"x1":"~1","y1":"~4","z1":"~1","x2":"~9","y2":"~4","z2":"~7","block":"oak_planks"}},
                      {"tool":"fill_region","args":{"x1":"~5","y1":"~","z1":"~1","x2":"~5","y2":"~1","z2":"~1","block":"air"}}
                    ]"""));
            examples.add("inspect_build", JsonParser.parseString("""
                    [
                      {"tool":"scan_region","args":{"x1":-1035,"y1":-1,"z1":-1453,"x2":-1031,"y2":3,"z2":-1449,"map":true}},
                      {"tool":"scan_region","args":{"x1":"~-8","y1":"~-4","z1":"~-8","x2":"~8","y2":"~8","z2":"~8","filter":"dirt"}}
                    ]"""));
            examples.add("browse_mod", JsonParser.parseString("""
                    [
                      {"tool":"list_mods","args":{"filter":"create"}},
                      {"tool":"list_blocks","args":{"mod":"create","limit":500}},
                      {"tool":"list_blocks","args":{"mod":"create","filter":"cog"}},
                      {"tool":"list_items","args":{"mod":"create","limit":500}}
                    ]"""));
            examples.add("open_ui_and_shot", JsonParser.parseString("""
                    [
                      {"tool":"ui_open","args":{"item":"download"}},
                      {"wait":{"screen":"ModularUIScreen"}},
                      {"tool":"ui_query","args":{"item":"download"}},
                      {"tool":"ui_screenshot","args":{"item":"download"}},
                      {"tool":"ui_close"}
                    ]"""));
            examples.add("verify_model", JsonParser.parseString("""
                    [
                      {"tool":"place_block","args":{"x":8,"y":65,"z":8,"block":"cryptand:ai_display"}},
                      {"tool":"display_set","args":{"x":8,"y":65,"z":8,"item":"cryptand:mcu2_32","scale":2,"spin":30}},
                      {"tool":"look_at","args":{"x":8.5,"y":65.8,"z":8.5}},
                      {"tool":"screenshot","args":{"name":"model_check"}}
                    ]"""));
            root.add("examples", examples);
            Files.writeString(schemaFile(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto/mcp] 写 tools.json 失败", ex);
        }
    }

    // ==================== 参数小工具（给 AiTools 用） ====================

    /** 走玩家命令通道执行原版命令（各分类工具的公共出口） */
    public static boolean command(String command) {
        final var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || command == null || command.isBlank()) {
            return false;
        }
        mc.player.connection.sendCommand(command);
        log("    · " + command);
        return true;
    }

    /**
     * 在**集成服务端**执行命令并回传它的输出（用户 2026-09-26 要求：
     * "run_command 回传命令输出（不能只回'已执行'），否则命令类验证无法诊断"）。
     *
     * <p>为什么必须服务端执行：回显是命令的返回值（{@code sendSuccess/sendFailure}），
     * 替客户端敲命令只能从聊天栏里"看"回显，拿不到结构化文本。这里给一个只收集消息的
     * {@link net.minecraft.commands.CommandSource}，其余（位置/权限/实体）仍取自玩家，
     * 于是 {@code data get} / {@code /cryptand …} 这类诊断命令的输出能原样带回给 AI。</p>
     *
     * @return 命令输出（多行用换行连接）；{@code null} = 没有集成服务端或没有玩家（调用方明确报错）
     */
    public static String commandOutput(String command) {
        final var mc = net.minecraft.client.Minecraft.getInstance();
        final var server = mc.getSingleplayerServer();
        final var player = mc.player;
        if (server == null || player == null || command == null || command.isBlank()) {
            return null;
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        final net.minecraft.commands.CommandSource collector = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                out.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        final net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(player.getUUID());
        final net.minecraft.commands.CommandSourceStack src = (sp == null
                ? server.createCommandSourceStack()
                : sp.createCommandSourceStack()).withSource(collector);
        server.getCommands().performPrefixedCommand(src, command);
        log("    · " + command + "  (server, " + out.size() + " lines)");
        return out.isEmpty() ? "（无输出）" : String.join("\n", out);
    }

    public static String str(JsonObject o, String key, String def) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static int num(JsonObject o, String key, int def) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    public static double dbl(JsonObject o, String key, double def) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsDouble();
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    /** 便捷构造 params schema */
    public static JsonObject schema(String... nameTypePairs) {
        final JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < nameTypePairs.length; i += 2) {
            o.addProperty(nameTypePairs[i], nameTypePairs[i + 1]);
        }
        return o;
    }

    public static JsonObject ok(String message) {
        final JsonObject o = new JsonObject();
        o.addProperty("message", message);
        return o;
    }
}

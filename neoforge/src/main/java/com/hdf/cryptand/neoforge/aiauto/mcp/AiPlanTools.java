package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * ===== 编排工具（MCP 友好的"跨帧流程"接口）=====
 *
 * <p><b>为什么需要它</b>：MCP 的工具调用语义是"调用 → 等结果"。但很多游戏内流程
 * 天然跨帧（等世界加载、等界面出现、等方块批次落地）。若在工具里阻塞等，就阻塞了
 * 客户端主线程；若让客户端自己猜时间 sleep，就会脆弱。</p>
 *
 * <p>所以走<b>提交 + 轮询</b>：</p>
 * <pre>
 * run_plan  { "steps": [ {"tool":"set_time","args":{"value":"day"}},
 *                        {"wait":{"world":true}},
 *                        {"tool":"fill_region","args":{...}} ],
 *             "id": "可选" }          → 立即返回 {id, steps}
 * plan_status { "id": "…" }           → {done:false, remaining:3} / {done:true, ok:true, tookMs:…}
 * </pre>
 *
 * <p>等待条件不占用主线程：队列每帧推进一条，{@code wait} 只做条件检查
 * （支持 {@code world / phase / screen / noscreen / ms / batches}）。</p>
 */
public final class AiPlanTools {

    private AiPlanTools() {
    }

    public static void registerAll() {
        AiToolServer.register("run_plan",
                "提交一组按序执行的步骤并立即返回（不阻塞）：steps 每项是 "
                        + "{\"tool\":\"工具名\",\"args\":{...}} 或等待元素 {\"wait\":{\"world\":true}}；"
                        + "等待条件支持 world（世界就绪）/ phase（阶段名）/ screen（界面类名子串）/ "
                        + "noscreen / ms（毫秒）/ batches（方块批次全部落地）。"
                        + "用 plan_status 轮询 done 判断是否跑完 —— 不要用 sleep 猜时间",
                AiToolServer.schema(
                        "steps", "array（步骤数组，元素为 {tool,args} 或 {wait:{...}}）",
                        "id", "string（可选：自定义调用 id，缺省自动生成）"),
                "session",
                args -> {
                    final JsonElement stepsElement = args.get("steps");
                    if (stepsElement == null || !stepsElement.isJsonArray()) {
                        return AiToolServer.ok("ERR steps 必须是数组");
                    }
                    final JsonArray steps = stepsElement.getAsJsonArray();
                    if (steps.size() == 0) {
                        return AiToolServer.ok("ERR steps 为空");
                    }
                    final String id = AiToolServer.str(args, "id", "");
                    return AiToolServer.enqueue(id.isBlank()
                            ? "plan-" + System.currentTimeMillis()
                            : id, steps);
                });

        AiToolServer.readOnly(() -> AiToolServer.register("plan_status",
                "查询 run_plan / 批处理调用的进度：{done:false, remaining:N} 表示还在跑；"
                        + "{done:true, ok:true, errors:0} 表示全部落地",
                AiToolServer.schema("id", "string（run_plan 返回的 id）"),
                "session",
                args -> AiToolServer.planStatus(AiToolServer.str(args, "id", ""))));
    }
}

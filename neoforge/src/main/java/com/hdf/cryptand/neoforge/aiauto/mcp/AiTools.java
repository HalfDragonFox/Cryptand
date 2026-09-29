package com.hdf.cryptand.neoforge.aiauto.mcp;

/**
 * ===== aiauto 工具总入口（facade）=====
 *
 * <p>工具按类别分散在同包下，本类只负责<b>按类别注册</b>，自身不含任何工具实现 ——
 * 这样每个分类类保持单一职责，新增能力时改对应分类即可，不会长成上帝类。</p>
 *
 * <table>
 *   <tr><td>{@link AiCommandTools}</td><td>原版命令与方块建造（run_command / place_block / fill_region / hollow_box）</td></tr>
 *   <tr><td>{@link AiPlayerTools}</td><td>玩家与世界操作（give / tp / 时间 / 天气 / 模式 / 实体）</td></tr>
 *   <tr><td>{@link AiQueryTools}</td><td>只读查询（state_* / list_mods / list_blocks / list_items / list_registry / list_entities / block_at / scan_region / list_recipes；list_* 支持 modid 过滤）</td></tr>
 *   <tr><td>{@link AiUiTools}</td><td>界面交互（ui_list / ui_query / ui_click / ui_set_text / ui_open / ui_dump / ui_screenshot / ui_debug）</td></tr>
 *   <tr><td>{@link AiDisplayTools}</td><td>模型验证（display_set / look_at / screenshot）</td></tr>
 *   <tr><td>{@link AiSessionTools}</td><td>会话与诊断（status / queue_status / chat / log_tail）</td></tr>
 *   <tr><td>{@link AiPlanTools}</td><td>跨帧编排（run_plan / plan_status：提交步骤数组 + 轮询完成）</td></tr>
 *   <tr><td>{@link AiWorldTools}</td><td>世界与存档（new_world：新建存档并直接载入）</td></tr>
 * </table>
 *
 * <p><b>注册即双通道</b>：这里注册的每个工具都会同时进入
 * <b>真 MCP 服务器</b>（{@code common} 的 {@code com.hdf.cryptand.mcp} 框架，
 * 经 {@link com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer} 暴露）与
 * <b>文件 legacy 通道</b>（calls/ → results/）。工具实现只写一遍。</p>
 *
 * <p><b>原子化原则</b>：一个工具 = 一个操作；复杂流程（干净工作台、建房、开界面截图）
 * 由批处理数组组合得到，示例见 {@code tools.json} 的 {@code examples}。</p>
 */
public final class AiTools {

    private AiTools() {
    }

    /**
     * 注册全部工具并刷新 tools.json。
     *
     * <p><b>按依赖类别分组</b>（写进 tools.json 的 {@code requires} 字段，AI 据此判断能不能用）：</p>
     * <ul>
     *   <li>{@code world} —— 不依赖玩家：直写/直读服务端世界（建造、扫描、方块查询、存档）</li>
     *   <li>{@code player} —— 需要玩家：原版命令、传送、物品、时间天气</li>
     *   <li>{@code ui} —— 需要客户端界面/渲染（"要看的"东西）：LDLib2 界面交互、展示方块、截图</li>
     *   <li>{@code session} —— 会话诊断</li>
     * </ul>
     */
    public static void registerAll() {
        AiToolServer.category("world", () -> {
            AiCommandTools.registerAll();
            AiToolServer.readOnly(AiQueryTools::registerAll);   // 只读查询：MCP 侧标 readOnlyHint
            AiWorldTools.registerAll();
        });
        AiToolServer.category("player", AiPlayerTools::registerAll);
        AiToolServer.category("ui", () -> {
            AiUiTools.registerAll();
            AiDisplayTools.registerAll();
            AiCameraTools.registerAll();
        });
        AiToolServer.category("session", () -> {
            AiSessionTools.registerAll();
            AiPlanTools.registerAll();                          // run_plan / plan_status（跨帧编排）
        });
        // 容器与界面原子工具（2026-09-17）：container_read / container_slot_read / container_set /
        //   container_take / container_clear、screen_open / screen_slots / screen_close。
        // ⚠ 用户要求"每个操作都要独立 mcp 不要合在一起方便扩展" ⇒ 本类内部按**原子粒度**注册，
        //   并自行切换 requires 分类（world / ui），故此处直接调用即可。
        AiContainerTools.registerAll();
        AiToolServer.writeSchema();
    }
}

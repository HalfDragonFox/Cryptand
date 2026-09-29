package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.mc.McBuild;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 工具分类：**模型/贴图验证**（展示方块 + 转视角 + 截图）。 */
public final class AiDisplayTools {

    private AiDisplayTools() {
    }

    public static void registerAll() {
        AiToolServer.register("display_set",
                "在 AI 展示方块（cryptand:ai_display）上摆出物品或方块模型",
                AiToolServer.schema("x", "int/string", "y", "int/string", "z", "int/string",
                        "item", "string（与 block 二选一）", "block", "string（与 item 二选一）",
                        "scale", "double（默认 1）", "spin", "double（每秒角度，0=不转）",
                        "yOffset", "double（显示高度，默认 1）"),
                args -> {
                    final JsonObject o = new JsonObject();
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) {
                        o.addProperty("error", "无世界/玩家");
                        return o;
                    }
                    final var pos = new net.minecraft.core.BlockPos(
                            rel(mc, AiToolServer.str(args, "x", "~"), mc.player.blockPosition().getX()),
                            rel(mc, AiToolServer.str(args, "y", "~"), mc.player.blockPosition().getY()),
                            rel(mc, AiToolServer.str(args, "z", "~"), mc.player.blockPosition().getZ()));
                    if (!(mc.level.getBlockEntity(pos)
                            instanceof com.hdf.cryptand.neoforge.aiauto.display.AiDisplayBlockEntity be)) {
                        o.addProperty("error", "该位置不是 cryptand:ai_display（先用 place_block 放一个）");
                        return o;
                    }
                    ItemStack stack = ItemStack.EMPTY;
                    net.minecraft.world.level.block.state.BlockState st = null;
                    final String itemId = AiToolServer.str(args, "item", "");
                    final String blockId = AiToolServer.str(args, "block", "");
                    if (!itemId.isBlank()) {
                        stack = new ItemStack(BuiltInRegistries.ITEM.get(
                                ResourceLocation.parse(McBuild.normalize(itemId))));
                    } else if (!blockId.isBlank()) {
                        st = BuiltInRegistries.BLOCK.get(
                                ResourceLocation.parse(McBuild.normalize(blockId))).defaultBlockState();
                    }
                    be.setDisplay(stack, st, (float) AiToolServer.dbl(args, "scale", 1.0),
                            (float) AiToolServer.dbl(args, "spin", 45.0),
                            (float) AiToolServer.dbl(args, "yOffset", 1.0));
                    o.addProperty("item", stack.isEmpty() ? "" : stack.toString());
                    o.addProperty("block", st == null ? "" : st.toString());
                    return o;
                });

        AiToolServer.register("look_at", "把玩家视角转向某坐标（截图前用）",
                AiToolServer.schema("x", "double", "y", "double", "z", "double"),
                args -> {
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) {
                        return AiToolServer.ok("ERR 无玩家");
                    }
                    final var eye = mc.player.getEyePosition();
                    final double dx = AiToolServer.dbl(args, "x", 0) - eye.x;
                    final double dy = AiToolServer.dbl(args, "y", 64) - eye.y;
                    final double dz = AiToolServer.dbl(args, "z", 0) - eye.z;
                    mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
                    mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                    return AiToolServer.ok("已转向");
                });

        AiToolServer.register("close_screen",
                "关闭当前界面（截图前用：保证拍到的是世界而不是物品栏/菜单）",
                AiToolServer.schema(),
                args -> {
                    final Minecraft mc = Minecraft.getInstance();
                    if (mc.screen == null) {
                        return AiToolServer.ok("当前本来就没有界面");
                    }
                    final String name = mc.screen.getClass().getSimpleName();
                    mc.setScreen(null);
                    return AiToolServer.ok("已关闭 " + name);
                });

        AiToolServer.register("screenshot", "截当前画面（通用，任意名字）",
                AiToolServer.schema("name", "string（默认 shot）"),
                args -> {
                    final String name = AiToolServer.str(args, "name", "shot");
                    AiAutomation.grabScreenshot("shot", name);
                    return AiToolServer.ok(String.valueOf(AiAutomation.artifact("shot", name, "png")));
                });
    }

    private static int rel(Minecraft mc, String token, int origin) {
        final String t = token == null ? "~" : token.trim();
        if (t.startsWith("~")) {
            final String rest = t.substring(1);
            return origin + (rest.isEmpty() ? 0 : (int) Double.parseDouble(rest));
        }
        return (int) Double.parseDouble(t);
    }
}

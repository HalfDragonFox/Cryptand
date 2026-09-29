/**
 * ===== aiauto · 容器与界面操作工具（2026-09-17）=====
 *
 * <p>用户要求："可以在世界放置一个机箱，然后模拟玩家操作进行…**注意每个操作都要独立 mcp
 * 不要合在一起方便扩展**" —— 因此本类提供的是**原子工具**，一个工具只做一件事：</p>
 *
 * <table border="1">
 *   <tr><th>工具</th><th>requires</th><th>作用</th></tr>
 *   <tr><td>{@code container_read}</td><td>world</td><td>读容器的**全部槽位**（槽号 / 物品 / 数量 / 处理器类型）</td></tr>
 *   <tr><td>{@code container_slot_read}</td><td>world</td><td>读**单个槽位**（细粒度校验用）</td></tr>
 *   <tr><td>{@code container_set}</td><td>world</td><td>把物品**放入指定槽位**（等价玩家手动放入，按容器规则校验）</td></tr>
 *   <tr><td>{@code container_take}</td><td>world</td><td>**取出**指定槽位物品（返回物品与数量）</td></tr>
 *   <tr><td>{@code container_clear}</td><td>world</td><td>清空容器（可指定槽位范围）</td></tr>
 *   <tr><td>{@code screen_open}</td><td>ui</td><td>对指定方块**模拟右键**（打开它的界面，如 OC 机箱）</td></tr>
 *   <tr><td>{@code screen_slots}</td><td>ui</td><td>读**当前打开界面**的所有槽位（原版/模组 GUI 通用）</td></tr>
 *   <tr><td>{@code screen_click}</td><td>ui</td><td>在当前界面第 N 个槽位执行**真实点击**（GUI 级操作；默认 QUICK_MOVE 平移）</td></tr>
 *   <tr><td>{@code screen_close}</td><td>ui</td><td>关闭当前界面</td></tr>
 * </table>
 *
 * <p><b>线程纪律</b>：容器读写一律投递到**服务端线程**执行（{@code server.execute}），
 * MCP 调用线程阻塞等待结果 —— 与 {@code WorldOps} 同构，不阻塞主线程。</p>
 *
 * <p><b>容器类型兼容</b>：先走 NeoForge 的 {@code Capabilities.ItemHandler.BLOCK}；
 * 若方块没有该能力，再回退到原版 {@code Container}（用 {@code InvWrapper} 包一层）。
 * 这样 OC 机箱（自研 Container）与原版箱子都能操作。</p>
 */
package com.hdf.cryptand.neoforge.aiauto.mcp;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class AiContainerTools {

    private AiContainerTools() {
    }

    // ==================== 底层：服务端线程执行 + 容器获取 ====================

    /** 在服务端线程执行并等待结果（MCP 调用线程阻塞，主线程不阻塞） */
    private static <T> T onServer(java.util.function.Function<ServerLevel, T> body, long timeoutMs) {
        final Minecraft mc = Minecraft.getInstance();
        final MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        final ServerLevel level = server.overworld();
        final CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(body.apply(level));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取方块的物品处理器：NeoForge 能力优先，回退原版 Container（含 OC 机箱这类自研容器） */
    private static IItemHandler handlerOf(ServerLevel level, BlockPos pos) {
        try {
            final IItemHandler viaCap = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (viaCap != null) {
                return viaCap;
            }
        } catch (Throwable ignored) {
            // 能力查询失败 → 继续尝试原版容器路径
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return new InvWrapper(container);
        }
        return null;
    }

    /** 解析物品名（支持简名与带命名空间） */
    private static ItemStack parseItem(String name, int count) {
        if (name == null || name.isBlank()) {
            return ItemStack.EMPTY;
        }
        String id = name.trim();
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        final var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return new ItemStack(item, Math.max(1, count));
    }

    private static int intArg(JsonObject args, String key, int def) {
        try {
            return args.has(key) ? args.get(key).getAsInt() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 单个槽位 → 可读文本 */
    private static String slotText(int index, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return index + "=空";
        }
        return index + "=" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + "×" + stack.getCount();
    }

    private static BlockPos posOf(JsonObject args) {
        return com.hdf.cryptand.neoforge.aiauto.mc.WorldOps.resolve(
                AiToolServer.str(args, "x", "~"),
                AiToolServer.str(args, "y", "~"),
                AiToolServer.str(args, "z", "~"));
    }

    // ==================== 注册 ====================

    public static void registerAll() {

        // ---------------- 容器（world：直写服务端世界，不需要玩家）----------------
        AiToolServer.category("world", () -> {

            AiToolServer.register("container_read",
                    "读容器全部槽位（直读服务端世界，不需要玩家）：返回槽号/物品/数量与容器类型",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）"),
                    args -> {
                        final BlockPos pos = posOf(args);
                        final String out = onServer(level -> {
                            final IItemHandler h = handlerOf(level, pos);
                            if (h == null) {
                                return "ERR 该位置不是容器（无 ItemHandler / Container）";
                            }
                            final StringBuilder sb = new StringBuilder();
                            sb.append("容器 ").append(pos.toShortString())
                                    .append(" 槽位=").append(h.getSlots()).append("：");
                            for (int i = 0; i < h.getSlots(); i++) {
                                sb.append(' ').append(slotText(i, h.getStackInSlot(i)));
                            }
                            return sb.toString();
                        }, 5_000);
                        return AiToolServer.ok(out == null ? "ERR 服务端不可用或超时" : out);
                    });

            AiToolServer.register("container_slot_read",
                    "读容器单个槽位（细粒度校验用）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "slot", "int 槽号"),
                    args -> {
                        final BlockPos pos = posOf(args);
                        final int slot = intArg(args, "slot", 0);
                        final String out = onServer(level -> {
                            final IItemHandler h = handlerOf(level, pos);
                            if (h == null) {
                                return "ERR 该位置不是容器";
                            }
                            if (slot < 0 || slot >= h.getSlots()) {
                                return "ERR 槽号越界（0.." + (h.getSlots() - 1) + "）";
                            }
                            return "槽 " + slot + " → " + slotText(slot, h.getStackInSlot(slot));
                        }, 5_000);
                        return AiToolServer.ok(out == null ? "ERR 服务端不可用或超时" : out);
                    });

            AiToolServer.register("container_set",
                    "把物品放入容器指定槽位（走容器自身规则校验，等价玩家手动放入）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "slot", "int 槽号",
                            "item", "string 物品（简名或带命名空间）", "count", "int 数量（默认 1）",
                            "force", "bool 强制放入（绕过槽位规则，测试用）"),
                    args -> {
                        final BlockPos pos = posOf(args);
                        final int slot = intArg(args, "slot", 0);
                        final int count = intArg(args, "count", 1);
                        final ItemStack stack = parseItem(AiToolServer.str(args, "item", ""), count);
                        if (stack.isEmpty()) {
                            return AiToolServer.ok("ERR 物品名为空或无效");
                        }
                        final String out = onServer(level -> {
                            final IItemHandler h = handlerOf(level, pos);
                            if (h == null) {
                                return "ERR 该位置不是容器";
                            }
                            if (slot < 0 || slot >= h.getSlots()) {
                                return "ERR 槽号越界（0.." + (h.getSlots() - 1) + "）";
                            }
                            // ① 强制模式：绕过槽位校验直接写入（用户 2026-09-18 明确允许的测试手段 ——
                            //    "如果你只是测试代码的话可以使用强制加载硬件到指定软盘装载即可"）
                            if (AiToolServer.bool(args, "force", false)) {
                                if (h instanceof net.neoforged.neoforge.items.IItemHandlerModifiable modifiable) {
                                    modifiable.setStackInSlot(slot, stack.copy());
                                    return "已强制放入 " + slotText(slot, h.getStackInSlot(slot))
                                            + "（绕过槽位规则）";
                                }
                                return "ERR 该容器不支持强制写入（不是 IItemHandlerModifiable）";
                            }
                            // ② 正常模式：走容器的接受规则 + 返回未插入的余量
                            final ItemStack rest = h.insertItem(slot, stack.copy(), false);
                            if (rest.getCount() != stack.getCount()) {
                                return "已放入 " + slotText(slot, h.getStackInSlot(slot))
                                        + "（余 " + rest.getCount() + "）";
                            }
                            // ② insertItem 全被拒（OC 机箱的槽位校验会拒绝不匹配的部件）⇒ 明确报错
                            return "ERR 槽 " + slot + " 拒绝了 " + BuiltInRegistries.ITEM.getKey(stack.getItem())
                                    + "（不符合该槽位的接受规则）";
                        }, 5_000);
                        return AiToolServer.ok(out == null ? "ERR 服务端不可用或超时" : out);
                    });

            AiToolServer.register("container_take",
                    "取出容器指定槽位的物品（返回被取出的物品与数量）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "slot", "int 槽号",
                            "count", "int 数量（默认取全部）"),
                    args -> {
                        final BlockPos pos = posOf(args);
                        final int slot = intArg(args, "slot", 0);
                        final int count = intArg(args, "count", -1);
                        final String out = onServer(level -> {
                            final IItemHandler h = handlerOf(level, pos);
                            if (h == null) {
                                return "ERR 该位置不是容器";
                            }
                            if (slot < 0 || slot >= h.getSlots()) {
                                return "ERR 槽号越界（0.." + (h.getSlots() - 1) + "）";
                            }
                            final ItemStack before = h.getStackInSlot(slot);
                            if (before.isEmpty()) {
                                return "ERR 槽 " + slot + " 本来就是空的";
                            }
                            final int want = count <= 0 ? before.getCount() : Math.min(count, before.getCount());
                            final ItemStack got = h.extractItem(slot, want, false);
                            return "取出 " + got.getCount() + "×"
                                    + BuiltInRegistries.ITEM.getKey(got.getItem())
                                    + "（槽 " + slot + " 余 " + h.getStackInSlot(slot).getCount() + "）";
                        }, 5_000);
                        return AiToolServer.ok(out == null ? "ERR 服务端不可用或超时" : out);
                    });

            AiToolServer.register("container_clear",
                    "清空容器（可指定起止槽位；默认全部）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "from", "int 起始槽（默认 0）",
                            "to", "int 结束槽（默认最后）"),
                    args -> {
                        final BlockPos pos = posOf(args);
                        final int from = intArg(args, "from", 0);
                        final int to = intArg(args, "to", -1);
                        final String out = onServer(level -> {
                            final IItemHandler h = handlerOf(level, pos);
                            if (h == null) {
                                return "ERR 该位置不是容器";
                            }
                            final int last = to < 0 ? h.getSlots() - 1 : Math.min(to, h.getSlots() - 1);
                            int n = 0;
                            for (int i = Math.max(0, from); i <= last; i++) {
                                final ItemStack got = h.extractItem(i, Integer.MAX_VALUE, false);
                                if (!got.isEmpty()) {
                                    n++;
                                }
                            }
                            return "已清空 " + n + " 个槽位（" + from + ".." + last + "）";
                        }, 5_000);
                        return AiToolServer.ok(out == null ? "ERR 服务端不可用或超时" : out);
                    });
        });

        // ---------------- 界面（ui：需要客户端渲染/输入）----------------
        AiToolServer.category("ui", () -> {

            AiToolServer.register("screen_open",
                    "对指定方块模拟右键（打开它的界面，例如 OC 机箱/组装机）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）"),
                    args -> {
                        final Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null || mc.level == null) {
                            return AiToolServer.ok("ERR 需要玩家（在客户端世界内）");
                        }
                        final BlockPos pos = posOf(args);
                        final var hit = new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
                        mc.execute(() -> mc.gameMode.useItemOn(mc.player,
                                net.minecraft.world.InteractionHand.MAIN_HAND, hit));
                        return AiToolServer.ok("已对该方块模拟右键 @ " + pos.toShortString() + "（等 1 tick 后再读界面）");
                    });

            AiToolServer.register("screen_slots",
                    "读当前打开界面的全部槽位（原版/模组 GUI 通用：槽号/坐标/物品）",
                    AiToolServer.schema(),
                    args -> {
                        final Minecraft mc = Minecraft.getInstance();
                        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)) {
                            return AiToolServer.ok("ERR 当前没有容器界面（screen="
                                    + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()) + "）");
                        }
                        final StringBuilder sb = new StringBuilder();
                        sb.append("界面 ").append(s.getClass().getSimpleName())
                                .append(" 槽位=").append(s.getMenu().slots.size()).append("：");
                        int i = 0;
                        for (final var slot : s.getMenu().slots) {
                            sb.append(' ').append(i++).append('@').append(slot.x).append(',').append(slot.y)
                                    .append('=').append(slot.getItem().isEmpty() ? "空"
                                            : BuiltInRegistries.ITEM.getKey(slot.getItem().getItem())
                                            + "×" + slot.getItem().getCount());
                        }
                        return AiToolServer.ok(sb.toString());
                    });

            AiToolServer.register("screen_click",
                    "在当前界面的第 N 个槽位上执行一次**真实点击**（GUI 级操作，等价玩家点槽位；"
                            + "默认 QUICK_MOVE 平移——用于把玩家背包里的物品「手动」塞进机器，验证 GUI 级接受规则）",
                    AiToolServer.schema("slot", "int 槽位下标（见 screen_slots 的编号）",
                            "button", "int 鼠标键（可选，默认 0）",
                            "type", "string 点击类型（可选：QUICK_MOVE/PICKUP/SWAP/CLONE/THROW/PICKUP_ALL，默认 QUICK_MOVE）"),
                    args -> {
                        final Minecraft mc = Minecraft.getInstance();
                        if (mc.player == null || mc.gameMode == null) {
                            return AiToolServer.ok("ERR 需要玩家（在客户端世界内）");
                        }
                        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)) {
                            return AiToolServer.ok("ERR 当前没有容器界面（先 screen_open）");
                        }
                        final int slot = intArg(args, "slot", -1);
                        if (slot < 0 || slot >= s.getMenu().slots.size()) {
                            return AiToolServer.ok("ERR 槽位下标越界：slot=" + slot
                                    + "（当前界面共 " + s.getMenu().slots.size() + " 个槽位）");
                        }
                        final int button = intArg(args, "button", 0);
                        final String typeName = AiToolServer.str(args, "type", "QUICK_MOVE").trim().toUpperCase();
                        final net.minecraft.world.inventory.ClickType type;
                        try {
                            type = net.minecraft.world.inventory.ClickType.valueOf(typeName);
                        } catch (Throwable t) {
                            return AiToolServer.ok("ERR 未知点击类型：" + typeName
                                    + "（可用 QUICK_MOVE/PICKUP/SWAP/CLONE/THROW/PICKUP_ALL）");
                        }
                        final int containerId = s.getMenu().containerId;
                        final var before = s.getMenu().slots.get(slot).getItem();
                        mc.execute(() -> mc.gameMode.handleInventoryMouseClick(
                                containerId, slot, button, type, mc.player));
                        return AiToolServer.ok("已对槽位 " + slot + " 执行 " + type
                                + "（点击前该槽=" + (before.isEmpty() ? "空"
                                        : BuiltInRegistries.ITEM.getKey(before.getItem()) + "×" + before.getCount())
                                + "；等 1 tick 后再读）");
                    });

            AiToolServer.register("screen_close",
                    "关闭当前界面（回到游戏）",
                    AiToolServer.schema(),
                    args -> {
                        final Minecraft mc = Minecraft.getInstance();
                        mc.execute(() -> mc.setScreen(null));
                        return AiToolServer.ok("已请求关闭当前界面");
                    });
        });
    }
}

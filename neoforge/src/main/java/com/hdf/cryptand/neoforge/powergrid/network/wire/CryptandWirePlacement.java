/**
 * ===== 自管导线放置/剪线（2026-08-13 一步到位：放置直接写自管图，不创建原版实体） =====
 *
 * 玩家手持导线右键端子 → 直接写 WireGraph 边（不创建 BaseWireEntity 实体、
 * 不进 transmissionLines/原版网络）。放置后世界无原版导线实体，拓扑/渲染
 * 完全由自管图驱动（渲染经 WireGraphSyncPayload 同步到客户端）。
 *
 * 入口（WireItemUseOnMixin 拦截 WireItem.useOn → 本类 handleUseOn）：
 *   - 第一次右键端子 → 记录起点（item NBT "Connection" tag，兼容原版预览）
 *   - 第二次右键端子 → placeWire（写图 + 消耗物品 + 图同步）
 *   - 电源线（Cord）→ 放行原版（不转换）
 *
 * 剪线（WireCutHandler 事件 → removeWiresAt）：
 *   - WireCutter 右键端子方块 → 移除该端子所有自管边 + 返回导线物品
 *   - 自管模式无实体 → 原版"右键导线"剪线不可用，改为端子操作
 *
 * 门控：仅自管模式（PowerGridWireConverter.isEnabled() && 图可用）生效。
 * 线程：服务端主线程（放置/剪线事件）。
 */
package com.hdf.cryptand.neoforge.powergrid.network.wire;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.net.WireBlockedPayload;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import com.hdf.cryptand.neoforge.powergrid.client.CryptandWindingScreen;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import dev.architectury.event.EventResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.collections.ModdedDataComponents;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWire;
import org.patryk3211.powergrid.electricity.wire.WireConnection;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;
import org.patryk3211.powergrid.utility.PlayerUtilities;

import java.util.List;

public final class CryptandWirePlacement {

    private CryptandWirePlacement() {
    }

    /** 放置诊断（手动放置低频操作，不节流——确保每次点击都打印完整分支） */
    private static void dbg(String fmt, Object... args) {
        try {
            CryptandNeoForge.WAF_LOGGER.info("[WireUse] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }

    // ===== MC 1.21.1 ItemStack NBT（CUSTOM_DATA 组件）辅助 =====
    private static CompoundTag nbtOf(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void setNbt(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 自管模式是否接管放置（转换启用）；否则放行原版。
     *  客户端/服务端都接管（客户端记录起点 tag 供预览；服务端执行放置）。 */
    public static boolean selfManaged(Level level) {
        try {
            if (level == null) return false;
            return PowerGridWireConverter.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 拦截 WireItem.useOn 后的自管放置处理（客户端/服务端都调用）。
     * 返回 EventResult：interrupt = 已处理（cancel 原版放置）；pass = 放行。
     */
    /** 服务端放置会话：玩家 UUID → 已记录起点（服务端权威状态，不依赖物品 tag
     *  同步——客户端写 tag 会与服务端收到同一点击产生竞态：tag 同步后服务端
     *  误判第二次点击提前放置）。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, BlockWireEndpoint> PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 匝数配置会话：玩家 UUID → 匝数（CryptandWindingScreen → WindingTurnsPayload
     *  直达服务端；第二次点击完成缠绕时读取即清）。完全不走原版 C2S 包。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer> PENDING_TURNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 客户端进行中的匝数配置起点方块（防止重复开屏；服务端不读） */
    private static volatile BlockPos CLIENT_WINDING_POS = null;
    /** 客户端进行中的匝数配置起点端子索引（场景①记录，场景②开屏用） */
    private static volatile int CLIENT_WINDING_TERMINAL = -1;

    /** 匝数包写入（服务端主线程，WindingTurnsPayload.handle 调用） */
    public static void setPendingTurns(Player player, BlockPos pos, int terminal, int turns) {
        try {
            if (player == null) return;
            java.util.UUID uid = player.getUUID();
            PENDING.putIfAbsent(uid, new BlockWireEndpoint(pos, terminal));
            PENDING_TURNS.put(uid, Math.max(1, turns));
            dbg("pending-turns set pos={} term={} turns={}", pos, terminal, turns);
        } catch (Throwable ignored) {
        }
    }

    public static EventResult handleUseOn(Player player, InteractionHand hand,
                                          BlockPos blockPos, Direction direction) {
        try {
            dbg("enter player={} hand={} pos={}", player != null ? player.getName().getString() : "null",
                    hand, blockPos);
            if (player == null || player.isShiftKeyDown()) {
                // shift+右键：取消进行中的放置（清服务端会话 + 物品 tag）
                if (player != null) {
                    PENDING.remove(player.getUUID());
                    PENDING_TURNS.remove(player.getUUID());
                    clearConnectionTag(player.getMainHandItem());
                }
                dbg("cancel-shift");
                return EventResult.pass();
            }
            if (hand != InteractionHand.MAIN_HAND) {
                dbg("pass-hand");
                return EventResult.pass();
            }
            Level level = player.level();
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty()) {
                dbg("pass-empty");
                return EventResult.pass();
            }
            Item item = stack.getItem();
            if (!IWire.isWire(level, item)) {
                dbg("pass-notwire item={}", item);
                return EventResult.pass();
            }
            if (IWire.isCord(level, item)) {
                dbg("pass-cord");
                return EventResult.pass(); // 电源线保持原版
            }
            // ===== 端子定位（客户端/服务端共用）=====
            var electric = IElectric.getAt(level, blockPos);
            if (electric == null) {
                // 点非 electric 方块 → 取消进行中的放置（清 tag/会话）
                if (player != null) {
                    PENDING.remove(player.getUUID());
                    clearConnectionTag(stack);
                }
                dbg("electric=null-clear");
                return EventResult.interruptFalse();
            }
            var state = level.getBlockState(blockPos);
            var hit = player.pick(PlayerUtilities.getReachDistance(player) + 1.0f, 1.0f, false);
            if (!(hit instanceof BlockHitResult blockHit)) {
                dbg("pass-nohit");
                return EventResult.pass();
            }
            var context = new UseOnContext(player, hand, blockHit);
            int terminal = electric.terminalIndexAt(state,
                    blockHit.getLocation().subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
            dbg("terminal={}", terminal);

            // ⚠ 客户端（2026-08-13 彻底方案）：只拦截（阻止原版客户端状态机写
            //   CONNECTION_DATA），不做放置状态机——客户端 interruptTrue 后
            //   右键包【仍发到服务端】（日志实证），服务端 PENDING 是唯一权威
            //   状态源（无竞态）。
            //   ⚠ 2026-08-15 变压器缠绕【完全自管，对齐原版三次点击】：
            //   ① 第一次点击变压器端子（铜线）→ 记录起点（CLIENT_WINDING_POS +
            //      CLIENT_WINDING_TERMINAL + 物品 CONNECTION_DATA），放行服务端
            //      first-click；CONNECTION_DATA 供原版 WirePreview 显示距离；
            //   ② 第二次长按同方块变压器本体（未命中端子）→ 校验【指定物品=铜线 +
            //      起点在变压器端子上（场景①已保证）】→ 开我们的匝数滚轮屏
            //      （CryptandWindingScreen，长按 3 tick 后 ScreenOpener.open），
            //      松手 saveAndClose 发 WindingTurnsPayload；
            //   ③ 第三次点击同方块另一端子 → 放行服务端 second-click 完成缠绕。
            //   普通导线（所有线）：第一次点端子写 CONNECTION_DATA 起点（原版预览
            //   显示距离），第二次点端子清（服务端 second-click 放线）。
            if (level.isClientSide) {
                // 2x2 变压器 BE 只由主方块(PART 0)持有：一律用方块方法取（1x1/2x2 通用）
                org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity tfC =
                        transformerBe(level, blockPos);
                boolean isTf = tfC != null;
                boolean isCopper = isCopperWire(stack);
                boolean inProgress = sameTransformer(level, CLIENT_WINDING_POS, blockPos);
                boolean hasConn = false;
                try {
                    hasConn = stack.get(ModdedDataComponents.CONNECTION_DATA.get()) != null;
                } catch (Throwable ignored) {
                }

                // 场景③：同方块端子 → 完成缠绕，放行服务端
                if (inProgress && terminal >= 0) {
                    CLIENT_WINDING_POS = null;
                    clearConnectionData(stack);
                    dbg("client-completing");
                    return EventResult.interruptTrue();
                }
                // 场景②：长按同方块变压器本体（未命中端子）→ 开匝数屏
                if (inProgress && terminal < 0 && isCopper && isTf) {
                    try {
                        int primaryTurns = 0;
                        try {
                            if (tfC.hasPrimary()) {
                                primaryTurns = tfC.getPrimary().getTurns();
                            }
                        } catch (Throwable e) {
                            primaryTurns = 0;
                        }
                        int maxTurns = 60;
                        try {
                            net.minecraft.world.level.block.Block blk = state.getBlock();
                            if (blk instanceof org.patryk3211.powergrid.electricity
                                    .transformer.TransformerBlock tb) {
                                maxTurns = tb.getMaxTurns();
                            }
                        } catch (Throwable e) {
                            maxTurns = 60;
                        }
                        // 起点 = 场景①记录的变压器端子（CLIENT_WINDING_POS/TERMINAL）
                        CryptandWindingScreen.beginInteraction(
                                CLIENT_WINDING_POS, CLIENT_WINDING_TERMINAL,
                                hand, primaryTurns, maxTurns);
                        dbg("winding-ui-open");
                        return EventResult.interruptTrue();
                    } catch (Throwable ex) {
                        dbg("winding-ui-ex {}", ex);
                    }
                }
                // 场景①：第一次点击变压器端子（铜线，端子未占用）→ 记录起点
                if (terminal >= 0 && isCopper && isTf && CLIENT_WINDING_POS == null) {
                    try {
                        boolean termUsed;
                        try {
                            termUsed = tfC.isTerminalUsed(terminal);
                        } catch (Throwable e) {
                            termUsed = false;
                        }
                        if (!termUsed) {
                            CLIENT_WINDING_POS = blockPos;
                            CLIENT_WINDING_TERMINAL = terminal;
                            setConnectionData(stack, blockPos, terminal);
                            dbg("client-winding-start");
                            return EventResult.interruptTrue();
                        }
                    } catch (Throwable ex) {
                        dbg("winding-start-ex {}", ex);
                    }
                }
                // 普通导线（非变压器 / 变压器非铜线）：点端子 → 有起点=完成，无起点=记录
                if (terminal >= 0 && (!isTf || !isCopper)) {
                    if (hasConn) {
                        clearConnectionData(stack);
                        if (CLIENT_WINDING_POS != null) CLIENT_WINDING_POS = null;
                        dbg("client-wire-complete");
                    } else {
                        setConnectionData(stack, blockPos, terminal);
                        dbg("client-wire-start");
                    }
                    return EventResult.interruptTrue();
                }
                // 其他（身体/非端子）：清除起点标记
                if (hasConn) clearConnectionData(stack);
                if (CLIENT_WINDING_POS != null) CLIENT_WINDING_POS = null;
                dbg("client-intercept");
                return EventResult.interruptTrue();
            }

            // ===== 服务端 =====
            if (terminal < 0) return EventResult.pass();  // 变压器身体点击：客户端开屏，服务端放行
            if (!electric.accepts(stack)) {
                dbg("accepts=false");
                if (player != null) {
                    player.displayClientMessage(Component.literal("此端子不接受该导线")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                return EventResult.interruptFalse();
            }

            // ===== 服务端会话状态机（PENDING，唯一权威，无竞态）=====
            java.util.UUID uid = player != null ? player.getUUID() : null;
            BlockWireEndpoint pending = uid != null ? PENDING.get(uid) : null;
            if (pending != null) {
                // 第二次点击：完成放置；或【变压器 + 铜线】→ 线圈缠绕（移植原版
                // onWinding——只有铜线能缠绕变压器；起点与目标在同一变压器方块；
                // 匝数 = 手持铜线数，无 GUI）。非铜线/非变压器 → 正常放置。
                PENDING.remove(uid);
                dbg("second-click pending={}", pending);
                // 2x2 变压器：BE 只由主方块持有，用方块方法取（1x1/2x2 通用）；
                // 同结构判断（1x1 同方块=同 BE，2x2 同结构=同一主 BE）
                org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity tf2 =
                        transformerBe(level, blockPos);
                if (isCopperWire(stack)
                        && tf2 != null
                        && sameTransformer(level, pending.getPos(), blockPos)) {
                    // 匝数：优先读匝数包会话（CryptandWindingScreen → WindingTurnsPayload
                    // → setPendingTurns）；无则回退手持铜线数
                    int turns = stack.getCount();
                    Integer pt = uid != null ? PENDING_TURNS.remove(uid) : null;
                    if (pt != null && pt > 0) turns = pt;
                    if (turns <= 0) turns = stack.getCount();
                    boolean ok = completeWinding(level, tf2, pending.getTerminal(),
                            terminal, turns, stack, player);
                    dbg("winding-complete ok={} turns={}", ok, turns);
                    // 成功：结束缠绕状态机（服务端也清 CONNECTION_DATA——物品栈由
                    // 服务端权威，客户端本地修改会被同步覆盖；双端一致才稳定）
                    if (ok) {
                        clearConnectionData(stack);
                        try {
                            CompoundTag tag = nbtOf(stack);
                            if (tag.contains("Turns") || tag.contains("Connection")
                                    || tag.contains("Initiator") || tag.contains("Terminal")) {
                                tag.remove("Turns");
                                tag.remove("Connection");
                                tag.remove("Initiator");
                                tag.remove("Terminal");
                                setNbt(stack, tag);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (player != null) {
                        player.displayClientMessage(Component.literal(ok
                                ? "已缠绕线圈：" + turns + " 匝（端子 "
                                        + pending.getTerminal() + "-" + terminal + "）"
                                : "缠绕失败（端子占用/匝数超限/铜线不足）")
                                .withStyle(ok ? net.minecraft.ChatFormatting.GREEN
                                        : net.minecraft.ChatFormatting.RED), true);
                    }
                    return EventResult.interrupt(ok);
                }
                boolean ok = placeWire(level, stack, player, pending,
                        new BlockWireEndpoint(blockPos, terminal));
                // 放线完成：服务端清 CONNECTION_DATA（物品栈权威，双端一致）
                clearConnectionData(stack);
                dbg("place ok={}", ok);
                return EventResult.interrupt(ok);
            }
            // 第一次点击：记录起点（服务端会话唯一状态源）+ 写 CONNECTION_DATA
            // 起点（物品栈权威：服务端写 → 同步客户端 → 附魔/距离/预绘制稳定）
            PENDING.put(uid, new BlockWireEndpoint(blockPos, terminal));
            setConnectionData(stack, blockPos, terminal);
            dbg("first-click record pos={} term={}", blockPos, terminal);
            if (player != null) {
                player.displayClientMessage(Component.literal("已记录起点，点击下一个端子")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            }
            return EventResult.interruptTrue();
        } catch (Throwable ex) {
            dbg("exception {}", ex);
            return EventResult.pass();
        }
    }

    /** 是否铜导线（原版 ModdedItems.WIRE = 铜线；仅铜线可缠绕变压器） */
    private static boolean isCopperWire(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            return stack.is(org.patryk3211.powergrid.collections.ModdedItems.WIRE.get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取 pos 处变压器 BE（2x2：BE 只由主方块 PART 0 持有，方块方法换算回主方块；
     *  1x1：返回自身）。非变压器返回 null。客户端/服务端通用。 */
    private static org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity
            transformerBe(Level level, BlockPos pos) {
        try {
            if (level == null || pos == null) return null;
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
            net.minecraft.world.level.block.Block blk = st.getBlock();
            if (blk instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlock tb) {
                return tb.getBlockEntity(level, pos, st).orElse(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 两个 pos 是否同一变压器结构（2x2 多部分 / 1x1 同方块）：
     *  同一 TransformerBlockEntity 实例 = 同一结构（2x2 所有部分返回同一主 BE）。 */
    private static boolean sameTransformer(Level level, BlockPos a, BlockPos b) {
        try {
            if (a == null || b == null) return false;
            if (a.equals(b)) return true;
            org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity beA =
                    transformerBe(level, a);
            org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity beB =
                    transformerBe(level, b);
            return beA != null && beA == beB;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 完成缠绕：反射写线圈 + 消耗铜线（服务端）。完全自管：
     *  不走原版 makePrimary/makeSecondary/rebuildCircuit——直接写
     *  primaryCoil/secondaryCoil 字段（tick HEAD 已初始化非 null），
     *  DeviceParamCache 下轮 sync 读到 → 组装器 addTransformerElementFromCache
     *  建模；拓扑重建由 markTopologyChanged 驱动。 */
    private static boolean completeWinding(Level level,
            org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity tf,
            int t1, int t2, int turns, ItemStack stack, Player player) {
        try {
            if (t1 == t2) return false;
            if (tf.isTerminalUsed(t1) || tf.isTerminalUsed(t2)) return false;
            int maxT = maxTurnsOf(tf);
            int existing = tf.hasPrimary() ? tf.getPrimary().getTurns() : 0;
            if (existing + turns > maxT) return false;
            if (!player.isCreative()) {
                // 需 turns 个铜线（手持 stack 或背包）
                if (!PlayerUtilities.hasEnoughItems(player, stack, turns)) return false;
            }
            // 直接写线圈（不走原版 makePrimary/makeSecondary）
            boolean primary = !tf.hasPrimary();
            org.patryk3211.powergrid.electricity.transformer.TransformerCoilParameters coil =
                    primary ? tf.getPrimary() : tf.getSecondary();
            coil.set(t1, t2, turns, stack.getItem());
            // 方块状态 COILS 更新（原版 public 方法，仅更新显示层）
            try {
                tf.updateCoilBlockState();
            } catch (Throwable ignored) {
            }
            // 同步客户端（BE NBT）
            try {
                tf.setChanged();
                tf.getLevel().sendBlockUpdated(tf.getBlockPos(), tf.getBlockState(),
                        tf.getBlockState(), 3);
            } catch (Throwable ignored) {
            }
            // 匝数直达组装器：DeviceParamCache 主线程 sync 读 getPrimary()
            // → TransformerParams → 组装器建模；显式触发拓扑重建
            try {
                CryptandTopologyManager
                        .get().markTopologyChanged();
            } catch (Throwable ignored) {
            }
            if (!player.isCreative()) {
                PlayerUtilities.removeItems(player, stack, turns);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 变压器最大匝数（block.getMaxTurns；small=60/medium=240；兜底 60） */
    private static int maxTurnsOf(org.patryk3211.powergrid.electricity.transformer
            .TransformerBlockEntity tf) {
        try {
            net.minecraft.world.level.block.Block blk = tf.getBlockState().getBlock();
            if (blk instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlock tb) {
                return tb.getMaxTurns();
            }
        } catch (Throwable ignored) {
        }
        return 60;
    }

    /** 写物品 CONNECTION_DATA 组件（WireConnection 起点；原版 WirePreview 显示距离）。
     *  客户端专用：所有导线第一次点端子写，第二次/完成清。服务端逻辑不读此组件。 */
    private static void setConnectionData(ItemStack stack, BlockPos pos, int terminal) {
        try {
            if (stack == null || stack.isEmpty()) return;
            stack.set(ModdedDataComponents.CONNECTION_DATA.get(),
                    WireConnection.of(new BlockWireEndpoint(pos, terminal)));
        } catch (Throwable ignored) {
        }
    }

    /** 清物品 CONNECTION_DATA 组件（WireConnection 起点） */
    private static void clearConnectionData(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return;
            stack.remove(ModdedDataComponents.CONNECTION_DATA.get());
        } catch (Throwable ignored) {
        }
    }

    /** 清除物品 "Connection" tag（CUSTOM_DATA；无则不动）+ CONNECTION_DATA 组件 */
    private static void clearConnectionTag(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return;
            clearConnectionData(stack);
            CompoundTag t = nbtOf(stack);
            if (t.contains("Connection")) {
                t.remove("Connection");
                setNbt(stack, t);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 右键空气清除导线绑定（WireItemUseMixin 拦截 WireItem.use 调用，双端）：
     *  清 CONNECTION_DATA 组件 + 客户端会话 + 服务端会话。仅手持导线且有绑定时清。
     *  客户端清物品组件/状态，服务端清放置会话；消息仅服务端显示。 */
    public static void clearBindingByAir(Player player, InteractionHand hand) {
        try {
            if (player == null) return;
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty() || !IWire.isWire(player.level(), stack.getItem())) return;
            boolean had = false;
            try {
                had |= stack.get(ModdedDataComponents.CONNECTION_DATA.get()) != null;
            } catch (Throwable ignored) {
            }
            had |= CLIENT_WINDING_POS != null;
            had |= PENDING.containsKey(player.getUUID())
                    || PENDING_TURNS.containsKey(player.getUUID());
            if (!had) return;
            clearConnectionData(stack);
            clearConnectionTag(stack);
            CLIENT_WINDING_POS = null;
            CLIENT_WINDING_TERMINAL = -1;
            PENDING.remove(player.getUUID());
            PENDING_TURNS.remove(player.getUUID());
            dbg("clear-by-air");
            if (!player.level().isClientSide) {
                player.displayClientMessage(Component.literal("已清除导线绑定")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清除服务端放置会话（C2S 放置完成/取消时调，防双路径残留） */
    public static void clearPending(Player player) {
        try {
            if (player != null) {
                PENDING.remove(player.getUUID());
                PENDING_TURNS.remove(player.getUUID());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自管放置：两端子 → 写自管图边（不创建实体）。服务端调用。
     * 返回是否成功（放置/消耗）。
     */
    public static boolean placeWire(Level world, ItemStack stack, Player player,
                                    BlockWireEndpoint ep1, BlockWireEndpoint ep2) {
        try {
            if (world == null || world.isClientSide) {
                dbg("place-fail world={} client={}", world, world != null ? world.isClientSide : "?");
                return false;
            }
            if (ep1 == null || ep2 == null) {
                dbg("place-fail ep-null");
                return false;
            }
            if (ep1.getPos().equals(ep2.getPos()) && ep1.getTerminal() == ep2.getTerminal()) {
                dbg("place-fail same-endpoint {}#{} vs {}#{}", ep1.getPos(), ep1.getTerminal(),
                        ep2.getPos(), ep2.getTerminal());
                return false;
            }
            if (!ep1.isValid(world) || !ep2.isValid(world)) {
                dbg("place-fail invalid ep1={} valid={} ep2={} valid={}", ep1, ep1.isValid(world),
                        ep2, ep2.isValid(world));
                return false;
            }
            var entry = WireRegistry.forItem(world, stack.getItem());
            if (entry == null) {
                dbg("place-fail no-entry item={}", stack.getItem());
                return false;
            }
            Vec3 p1 = ep1.getExactPosition(world);
            Vec3 p2 = ep2.getExactPosition(world);
            if (p1 == null || p2 == null) {
                dbg("place-fail pos-null");
                return false;
            }
            double distance = p1.distanceTo(p2);
            dbg("place dist={} itemsPerMeter={} maxLen={}", distance, entry.itemsPerMeter(),
                    entry.maximumLength());
            if (distance <= 0.001) {
                dbg("place-fail dist-zero");
                return false;
            }
            if (distance > entry.maximumLength()) {
                dbg("place-fail too-long");
                if (player != null) {
                    player.displayClientMessage(Component.literal("连接过长（超过最大长度 "
                            + entry.maximumLength() + " 格）")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                return false;
            }
            // 阻挡检测（IE 参考 2026-08-14）：沿二次曲线路径有实体方块 → 拒绝，
            // 并把阻挡方块列表发给该玩家（客户端红色方框选中显示）
            java.util.List<BlockPos> blocked = blockedBlocks(world, ep1, ep2, p1, p2, distance);
            if (!blocked.isEmpty()) {
                dbg("place-fail blocked blocks={}", blocked);
                if (player != null) {
                    player.displayClientMessage(Component.literal("路径被方块阻挡")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                    // 红色方框高亮阻挡方块（S2C，只发该玩家，客户端 3s 过期）
                    try {
                        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                                    new WireBlockedPayload(blocked));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return false;
            }
            int items = Math.max((int) Math.ceil(distance * entry.itemsPerMeter()), 1);
            if (player != null && !PlayerUtilities.hasEnoughItems(player, stack, items)) {
                dbg("place-fail not-enough-items need={}", items);
                player.displayClientMessage(Component.literal("导线物品不足")
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                return false;
            }
            WirePoint a = new WirePoint("B" + ep1.getPos() + "#" + ep1.getTerminal());
            WirePoint b = new WirePoint("B" + ep2.getPos() + "#" + ep2.getTerminal());
            if (a.equals(b)) {
                dbg("place-fail a==b");
                return false;
            }
            var mgr = WireNetworkManager.get();
            // 已存在同端点边 → 幂等跳过（防止重复放置/消耗）
            if (mgr.contains(a) && mgr.contains(b)) {
                for (WireEdge e : mgr.adjacent(a)) {
                    if (e.connects(b)) {
                        dbg("place-fail already-exists {}", e);
                        return false;
                    }
                }
            }
            // 导线类型（注册器：电阻值/悬垂率；未注册 → 原版 entry 参数兜底）
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            SaggingWireType wtype =
                    SaggingWireRegistry.byItemId(itemId);
            double r = wtype != null ? wtype.resistancePerMeter() * distance
                    : entry.resistancePerItem() * items;
            // 2026-08-14 渲染参数引用化：只存渲染器 id（不存 sag/color 副本）；
            // 副手染料染色 → colorOverride 单独存（每边独立，不属于渲染器静态参数）
            String rendererId = wtype != null ? wtype.id()
                    : SaggingWireRegistry.rendererIdOf(itemId);
            int colorOverride = 0;
            if (entry.colorable() && player != null) {
                ItemStack off = player.getOffhandItem();
                if (off != null && off.getItem() instanceof DyeItem dye) {
                    colorOverride = dye.getDyeColor().getTextColor() | 0xFF000000;
                }
            }
            mgr.addEdge(new WireEdge(a, b, r, null, distance, rendererId, colorOverride, true, itemId));
            dbg("place-added {} <-> {} r={} items={}", a, b, r, items);
            // 消耗物品（非创造）
            if (player == null || !player.isCreative()) {
                PlayerUtilities.removeItems(player, stack, items);
            }
            // 图同步到客户端（渲染）——立即同步（跳过节流，防放置后不可见）
            try {
                PowerGridWireConverter.syncGraphToClientsNow();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable ex) {
            dbg("place-exception {}", ex);
            return false;
        }
    }

    /** 阻挡检测（IE raytraceAlongCatenary 参考 2026-08-14）：沿二次曲线路径采样，
     *  收集路径上非空/不可替换方块的【阻挡位置】。忽略端点方块（导线从其拉出）
     *  与电力方块（端子/导线底座等，原版 getIgnored 语义）。返回空列表 = 不阻挡。
     *  服务端调用。 */
    private static java.util.List<BlockPos> blockedBlocks(Level world,
            BlockWireEndpoint ep1, BlockWireEndpoint ep2,
            Vec3 p1, Vec3 p2, double distance) {
        java.util.List<BlockPos> blocked = new java.util.ArrayList<>();
        try {
            java.util.Set<BlockPos> ignore = new java.util.HashSet<>();
            ignore.add(ep1.getPos());
            ignore.add(ep2.getPos());
            float sag = 2.0f; // 与放置/渲染一致的下垂
            double step = 0.5;
            double res = distance * 2;
            float a = (float) ((0.05f / Math.max(distance, 0.01)) * sag);
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            for (double t = step; t < distance; t += step) {
                float f = (float) (t / distance);
                float yOff = a * (f * (float) res) * (f * (float) res - (float) res);
                double x = p1.x + (p2.x - p1.x) * f;
                double y = p1.y + (p2.y - p1.y) * f + yOff;
                double z = p1.z + (p2.z - p1.z) * f;
                BlockPos pos = BlockPos.containing(x, y, z);
                if (ignore.contains(pos) || seen.contains(pos)) continue;
                seen.add(pos);
                net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
                if (state.isAir() || state.canBeReplaced()) continue;
                if (state.getBlock() instanceof IElectric) continue; // 电力方块（端子等）
                blocked.add(pos);
            }
        } catch (Throwable ignored) {
        }
        return blocked;
    }

    /**
     * 剪线：移除指定端子（pos#terminal）的所有自管边 + 返回导线物品。
     * 服务端调用。返回是否移除了任何边。
     */
    public static boolean removeWiresAt(Level level, Player player, BlockPos pos, int terminal) {
        try {
            if (level == null || level.isClientSide) return false;
            var mgr = WireNetworkManager.get();
            if (mgr == null || mgr.nodeCount() == 0) return false;
            WirePoint p = new WirePoint("B" + pos + "#" + terminal);
            if (!mgr.contains(p)) return false;
            List<WireEdge> toRemove = new java.util.ArrayList<>(mgr.adjacent(p));
            if (toRemove.isEmpty()) return false;
            boolean any = false;
            int totalItems = 0;
            String itemId = null;
            for (WireEdge e : toRemove) {
                try {
                    mgr.removeEdge(e.a, e.b);
                    any = true;
                    // 返回物品数 = 边长 * 每米消耗（近似：1 格 1 个物品，取整）
                    int cnt = Math.max((int) Math.ceil(e.length), 1);
                    totalItems += cnt;
                    if (e.itemId != null) itemId = e.itemId;
                } catch (Throwable ignored) {
                }
            }
            if (!any) return false;
            // 物品返回背包
            if (player != null && totalItems > 0 && itemId != null) {
                try {
                    Item item = BuiltInRegistries.ITEM.get(
                            net.minecraft.resources.ResourceLocation.parse(itemId));
                    if (item != null) {
                        for (int left = totalItems; left > 0; left -= 64) {
                            player.getInventory().placeItemBackInInventory(
                                    new ItemStack(item, Math.min(left, 64)));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                PowerGridWireConverter.syncGraphToClientsNow();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 剪线：按两端点（A/B 方块 pos + 端子）精确移除【单条边】+ 返回导线物品。
     * 自管剪线（WireCutPayload）：从 A 端子相邻边中找与 B 端点相连的那条移除。
     */
    public static boolean removeWireByEndpoints(Level level, Player player,
            int ax, int ay, int az, int aTerm, int bx, int by, int bz, int bTerm) {
        try {
            if (level == null || level.isClientSide) return false;
            var mgr = WireNetworkManager.get();
            if (mgr == null || mgr.nodeCount() == 0) return false;
            WirePoint a = new WirePoint("B" + new BlockPos(ax, ay, az) + "#" + aTerm);
            WirePoint b = new WirePoint("B" + new BlockPos(bx, by, bz) + "#" + bTerm);
            if (!mgr.contains(a) || !mgr.contains(b)) return false;
            java.util.Set<WireEdge> edges = mgr.adjacent(a);
            if (edges == null || edges.isEmpty()) return false;
            WireEdge hit = null;
            for (WireEdge e : edges) {
                if ((e.a.equals(a) && e.b.equals(b))
                        || (e.a.equals(b) && e.b.equals(a))) {
                    hit = e;
                    break;
                }
            }
            if (hit == null) return false;
            try {
                mgr.removeEdge(hit.a, hit.b);
            } catch (Throwable ignored) {
                return false;
            }
            int cnt = Math.max((int) Math.ceil(hit.length), 1);
            String itemId = hit.itemId;
            if (player != null && cnt > 0 && itemId != null) {
                try {
                    Item item = BuiltInRegistries.ITEM.get(
                            net.minecraft.resources.ResourceLocation.parse(itemId));
                    if (item != null) {
                        for (int left = cnt; left > 0; left -= 64) {
                            player.getInventory().placeItemBackInInventory(
                                    new ItemStack(item, Math.min(left, 64)));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                PowerGridWireConverter.syncGraphToClientsNow();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

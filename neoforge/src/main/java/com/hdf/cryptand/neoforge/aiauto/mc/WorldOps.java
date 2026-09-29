package com.hdf.cryptand.neoforge.aiauto.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * ===== 世界直写/直读（aiauto 的"不依赖玩家"通道）=====
 *
 * <p>建造、填充、扫描一律直接操作<b>服务端世界</b>（单人游戏的内置服务器），不经过玩家命令通道：
 * 不需要玩家存在、不需要 op/作弊，也不受 /fill 的 32768 上限和命令排队影响。</p>
 *
 * <h3>线程模型</h3>
 * <p>ServerLevel 不是线程安全的，所以读写都经 {@code server.execute(...)} 投递到<b>服务端线程</b>。
 * 写只投递不等待；读要拿回结果，因此是"投递 + 等待（带超时）"。同一队列天然有序 ——
 * "先 fill 再 scan"一定看得见刚写的方块。</p>
 *
 * <h3>什么时候不能直写</h3>
 * <p>多人服务器下 {@code getSingleplayerServer()} 为 null，{@link #available()} 返回 false，
 * 调用方应回退到玩家命令通道（见 AiCommandTools 的 fallback）。</p>
 */
public final class WorldOps {

    /** 读取等待上限（投递到服务端线程后最多等这么久） */
    private static final long QUERY_TIMEOUT_MS = 5000L;

    private WorldOps() {
    }

    /** 当前可直写的服务端世界；非单人游戏返回 null */
    public static ServerLevel level() {
        final Minecraft mc = Minecraft.getInstance();
        final var server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        if (mc.level != null) {
            final ServerLevel scoped = server.getLevel(mc.level.dimension());
            if (scoped != null) {
                return scoped;
            }
        }
        return server.overworld();
    }

    /** 能否直写世界（单人游戏内置服务器） */
    public static boolean available() {
        return level() != null;
    }

    // ==================== 坐标与方块名 ====================

    /** 坐标解析：纯数字 = 绝对坐标；含 ~ = 玩家相对坐标（没有玩家时以 0 为基准） */
    public static BlockPos resolve(String x, String y, String z) {
        final Minecraft mc = Minecraft.getInstance();
        final BlockPos base = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
        return new BlockPos(coord(x, base.getX()), coord(y, base.getY()), coord(z, base.getZ()));
    }

    private static int coord(String token, int origin) {
        final String t = token == null ? "~" : token.trim();
        if (t.startsWith("~")) {
            final String rest = t.substring(1);
            return origin + (rest.isEmpty() ? 0 : (int) Double.parseDouble(rest));
        }
        return (int) Double.parseDouble(t);
    }

    /** 解析方块名（简名 + 可选 blockstate：stone / oak_stairs[facing=north]） */
    public static BlockState parseState(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String text = token.trim();
        String props = "";
        final int bracket = text.indexOf('[');
        if (bracket >= 0 && text.endsWith("]")) {
            props = text.substring(bracket + 1, text.length() - 1);
            text = text.substring(0, bracket);
        }
        final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(text.trim()));
        BlockState state = block.defaultBlockState();
        if (!props.isEmpty()) {
            for (String pair : props.split(",")) {
                final int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                final Property<?> property = block.getStateDefinition()
                        .getProperty(pair.substring(0, eq).trim());
                if (property != null) {
                    state = withProperty(state, property, pair.substring(eq + 1).trim());
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state,
                                                                     Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    // ==================== 写 ====================

    /**
     * 写一个方块（投递到服务端线程，顺序有保证）；返回 false = 无法直写。
     *
     * <p>放完补一次 {@link OcBlockJoin#afterPlace}：OC 的方块实体要**入网**才会工作，
     * 而"直写世界"这条路径不会替它入网（玩家放置走的是区块注册那条路，见该类的说明）。</p>
     */
    public static boolean setBlock(BlockPos pos, BlockState state) {
        final ServerLevel level = level();
        if (level == null || state == null) {
            return false;
        }
        level.getServer().execute(() -> {
            level.setBlock(pos, state, Block.UPDATE_ALL);
            OcBlockJoin.afterPlace(level, pos);
        });
        return true;
    }

    /** 填充长方体（含端点）；返回方块数，-1 = 无法直写 */
    public static int fill(BlockPos a, BlockPos b, BlockState state) {
        final ServerLevel level = level();
        if (level == null || state == null) {
            return -1;
        }
        final int x1 = Math.min(a.getX(), b.getX());
        final int x2 = Math.max(a.getX(), b.getX());
        final int y1 = Math.min(a.getY(), b.getY());
        final int y2 = Math.max(a.getY(), b.getY());
        final int z1 = Math.min(a.getZ(), b.getZ());
        final int z2 = Math.max(a.getZ(), b.getZ());
        level.getServer().execute(() -> {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    for (int x = x1; x <= x2; x++) {
                        final BlockPos pos = new BlockPos(x, y, z);
                        level.setBlock(pos, state, Block.UPDATE_ALL);
                        OcBlockJoin.afterPlace(level, pos);   // OC 方块实体要入网（见 OcBlockJoin）
                    }
                }
            }
        });
        return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    /**
     * 批量写入：一次投递整串方块（{@code place_batch} 的底层）。
     *
     * <p>与逐条 {@code fill} / {@code setblock} 不同，这里<b>所有方块在同一次服务端任务里写完</b>，
     * 所以一次调用可以塞几百上千个方块，不必每个方块占一个 tick。</p>
     *
     * @return 写入的方块数；-1 表示无法直写
     */
    public static int setBatch(java.util.List<BlockPos> positions, java.util.List<BlockState> states) {
        return setBatch(positions, states, DEFAULT_BATCH);
    }

    /** 默认每批方块数（一次写太多会把服务端主线程卡住一帧） */
    public static final int DEFAULT_BATCH = 16384;

    /** 尚未写完的批次数（配合 {@code {"wait":{"batches":0}}} 等它落地） */
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_BATCHES =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int pendingBatches() {
        return PENDING_BATCHES.get();
    }

    /**
     * 批量写入，<b>按 {@code batchSize} 拆成多批、分散到后续 tick</b>。
     *
     * <p>为什么不用 {@code server.execute} 一次写完：那是"当前 tick 内全部执行"，
     * 几万方块会在同一帧里落地，直接卡住服务端主线程。这里改用
     * {@link net.minecraft.server.TickTask}，第 b 批排在 {@code baseTick + b} 执行 ——
     * 服务端每 tick 只多干一批的活，帧时间可控。</p>
     *
     * @param batchSize 每批方块数（&lt;=0 时取 {@link #DEFAULT_BATCH}）
     * @return 写入的方块总数；-1 表示无法直写
     */
    public static int setBatch(java.util.List<BlockPos> positions, java.util.List<BlockState> states,
                               int batchSize) {
        final ServerLevel level = level();
        if (level == null || positions.isEmpty() || positions.size() != states.size()) {
            return -1;
        }
        final var server = level.getServer();
        final int total = positions.size();
        final int size = batchSize > 0 ? batchSize : DEFAULT_BATCH;
        final int batches = (total + size - 1) / size;
        PENDING_BATCHES.addAndGet(batches);
        final int baseTick = server.getTickCount();
        for (int b = 0; b < batches; b++) {
            final int start = b * size;
            final int end = Math.min(total, start + size);
            server.tell(new net.minecraft.server.TickTask(baseTick + b, () -> {
                try {
                    for (int i = start; i < end; i++) {
                        final BlockState state = states.get(i);
                        if (state != null) {
                            final BlockPos pos = positions.get(i);
                            level.setBlock(pos, state, Block.UPDATE_ALL);
                            OcBlockJoin.afterPlace(level, pos);   // OC 方块实体要入网（见 OcBlockJoin）
                        }
                    }
                } finally {
                    PENDING_BATCHES.decrementAndGet();
                }
            }));
        }
        return total;
    }

    // ==================== 读 ====================

    /** 投递到服务端线程执行并取回结果；不可用/超时返回 fallback */
    public static <T> T query(Function<ServerLevel, T> task, T fallback) {
        final ServerLevel level = level();
        if (level == null) {
            return fallback;
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                future.complete(task.apply(level));
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });
        try {
            return future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ex) {
            return fallback;
        }
    }
}

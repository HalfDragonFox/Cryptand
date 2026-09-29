package com.hdf.cryptand.neoforge.pipez;

import com.hdf.cryptand.neoforge.inc.config.ConfigInc;
import com.hdf.cryptand.integratednetwork.NetworkInterface;
import com.hdf.cryptand.integratednetwork.TransferType;
import com.hdf.cryptand.integratednetwork.TransportPayload;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.net.INetworkDevice;
import com.hdf.cryptand.neoforge.net.InterfaceDiff;
import com.hdf.cryptand.neoforge.net.IStructuredDevice;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Pipez 管道设备适配器（2026-08-29 P3 完全接管，ITEM 样板）。
 * <p>
 * 把单个 Pipez 管道 BE（{@link PipeLogicTileEntity}）包装成 {@link INetworkDevice}，
 * 使完全接管成立：INC 主线程收集输入（{@link #collect}）→ 组表 → 核心模拟 →
 * 执行表回主线程，由 {@link #executeExtract}/{@link #executeWrite} 对真实容器直 IO。
 * <ul>
 *   <li>网络能力 = 物品（单网络单能力；流体/能量/气体后续按同模板扩展）；</li>
 *   <li>接口：抽取面 = 输入口（收集源）；其它连接面 = 输出口（接收端）；</li>
 *   <li>IO 走 NeoForge {@link IItemHandler} 能力（{@link Capabilities#ItemHandler}）。</li>
 * </ul>
 */
public final class PipezDeviceAdapter implements INetworkDevice, com.hdf.cryptand.neoforge.net.IStructuredDevice {

    /** 异常日志（2026-08-29 用户：所有 catch 必须打印日志，否则无法判断） */
    private static final org.apache.logging.log4j.Logger LOG =
            CryptandNeoForge.WAF_LOGGER;

    private final ServerLevel level;
    private final PipeLogicTileEntity tile;
    private final Object key;
    private volatile List<NetworkInterface> inputs = List.of();
    private volatile List<NetworkInterface> outputs = List.of();
    /** 管道段结构节点（id 前缀 p:；仅网络结构信息，不参与搬运/交付，2026-08-29） */
    private volatile List<NetworkInterface> structureNodes = List.of();
    /** 上次快照的输入接口 id 集（增量 diff 用；2026-08-29 用户：只发增量） */
    private volatile java.util.Set<Object> prevInIds = java.util.Set.of();
    /** 上次快照的输出接口 id 集（增量 diff 用） */
    private volatile java.util.Set<Object> prevOutIds = java.util.Set.of();
    /** 上次快照的结构节点 id 集（增量 diff 用） */
    private volatile java.util.Set<Object> prevStructIds = java.util.Set.of();
    /** 本次检测到的【增量 diff】（新增/移除 输入/输出/结构节点；主线程每 tick 消费） */
    private volatile InterfaceDiff lastDiff = InterfaceDiff.empty();
    /** 被卸载/失效的接口 id（容器已拆走 → 跳过处理；上报时从图移除） */
    private final java.util.Set<Object> disabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 容器有变动需重新检测上报（交付失败/接口卸载时置位） */
    private volatile boolean needsRescan = false;
    /** 找不到可用输出容器计时（ms 累计；2026-08-29 用户：满箱/无输出时按配置间隔重试+日志） */
    private volatile long noOutputSinceMs = System.currentTimeMillis();
    /** 强制全量重报（指令 /cryptand inc rebuild 用） */
    private volatile boolean forceFullReport = false;
    /** 本管道所在物理簇的全部管道段位置（BFS 结果；2026-08-29 用户：同簇合并网络判断） */
    private volatile java.util.Set<BlockPos> clusterPipes = java.util.Set.of();
    /** 相邻管道段对（图网：p-p 物理边；2026-08-30 用户：普通管道就是一个节点） */
    private volatile java.util.List<BlockPos[]> pipeEdges = java.util.List.of();
    /** 接口 id → 所属管道段 pos（输入/输出锚点；距离计算用） */
    private volatile java.util.Map<Object, BlockPos> ifaceAnchor = java.util.Map.of();

    public PipezDeviceAdapter(ServerLevel level, PipeLogicTileEntity tile) {
        this.level = level;
        this.tile = tile;
        this.key = "pipez:" + tile.getBlockPos();
        refresh();
    }

    /**
     * 重新解析接口（2026-08-29 用户【最终方案】：上报【不走原版】——完全自己
     * 识别连接，自研 BFS 基于 Pipez 原版维护的 blockstate 物理连接属性；当原版
     * （Pipez 的 neighborChanged → blockstate 属性）触发连接后，本方法即识别：
     * <ul>
     *   <li>输入口 = 抽取面（{@code tile.isExtracting(d)}，BlockEntity 实时数据）；</li>
     *   <li>输出口 = BFS 沿 blockstate 物理连接扩散整个管道簇，收集【所有真实面
     *       容器端点】（非管道 AND 有 ItemHandler 的方块，真实面方向）；</li>
     *   <li>管道段 = BFS 途中所有 {@link PipeBlock} 方块位置，作为「管道节点」
     *       （id 前缀 {@code p:}）一并上报——网络结构完整、供 SPLIT_MERGE 判断。</li>
     * </ul>
     * 完全绕开 Pipez 的 {@code getConnections()}/{@code getExtractingConnection()}
     * 缓存（被接管后不刷新、无抽取面时为空、方向镜像）——blockstate 属性由
     * MC 方块更新（neighborChanged/updateShape）自动维护，与 Pipez 自 tick 无关。
     */
    private void refresh() {
        net.minecraft.core.BlockPos p = tile.getBlockPos();
        List<NetworkInterface> in = new ArrayList<>();
        List<NetworkInterface> out = new ArrayList<>();
        List<NetworkInterface> struct = new ArrayList<>();
        java.util.Set<Object> live = new java.util.HashSet<>();
        // 自研 BFS：从本管道段出发，沿 blockstate 物理连接（PipeBlock.EAST 等，
        // 由 MC 方块更新维护）探索【整个管道簇】，收集：
        //   - 所有管道段的【抽取面容器】→ 输入接口（in:<pos>|<face>）；
        //   - 所有容器端点（真实面）→ 输出接口（可交付）；
        //   - 所有管道段位置（p: 前缀）→ 结构节点（仅网络结构，不参与搬运）。
        // 【同簇合并】后本设备代表整簇：接口 = 簇内所有段输入 + 所有容器输出。
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.List<BlockPos[]> edgesList = new ArrayList<>();
        java.util.Map<Object, BlockPos> anchors = new java.util.HashMap<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(p);
        visited.add(p);
        try {
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                BlockState curBs = level.getBlockState(cur);
                if (!(curBs.getBlock() instanceof de.maxhenkel.pipez.blocks.PipeBlock pb)) continue;
                // 管道段 → 结构节点（id 用 p: 前缀与容器区分；无方向/不参与传输）
                String pipeId = "p:" + cur.getX() + "," + cur.getY() + "," + cur.getZ();
                struct.add(new NetworkInterface(pipeId, TransferType.ITEM,
                        false, -1, cur.getX(), cur.getY(), cur.getZ(), 0));
                // 对当前管道段的每个面：blockstate 属性 true = 物理连接
                for (Direction d : Direction.values()) {
                    BooleanProperty prop = pb.getProperty(d);
                    if (prop == null || !curBs.getValue(prop)) continue; // 物理未连接
                    BlockPos target = cur.relative(d);
                    BlockState tBs = level.getBlockState(target);
                    // 管道段 → 图网物理边（p-p）+ 继续 BFS（如果未访问）
                    if (tBs.getBlock() instanceof de.maxhenkel.pipez.blocks.PipeBlock) {
                        if (visited.add(target)) {
                            queue.add(target);
                        }
                        edgesList.add(new BlockPos[]{cur, target}); // 相邻段对
                        continue;
                    }
                    // 容器端点：target 非管道。物理连接即视为【连接存在】（加入
                    // live，供 disabled 解除判断）；有 ItemHandler 时按抽面分流：
                    //  - 该面是抽取面 → 输入接口（源容器）in:<pos>|<face>；
                    //  - 否则 → 输出接口。
                    // ⚠ 2026-08-29 关键修复：必须排除【抽取面容器】——若也作为
                    //   输出接口 → 核心把物品分配回源箱子自环（delivered=0）。
                    //   注意：Pipez 语义是【每个管道段各自设置抽面】；BFS 途中
                    //   每个段按其自身抽面判断（isExtractingAt(cur, d)）。
                    String cid = "c:" + target.getX() + "," + target.getY() + "," + target.getZ()
                            + ":" + d.getName();
                    live.add(cid); // 物理连接存在 → 容器在位（disabled 恢复判定）
                    // ★ 2026-09 P2-5 根因修复：连接判定 = 交付/抽取能力判定，
                    //   统一用【该连接面】的 ItemHandler——BFS 识别的面必然可 IO，
                    //   交付/抽取无需再「任意面乱试」（原 connByCid 死代码 + 兜底删除）。
                    if (itemHandlerAt(target, d) == null) continue;
                    if (isExtractingAt(cur, d)) {
                        // 抽取面容器 → 输入接口（id 含管道段位置，区分各段输入）
                        String inId = "in:" + cur.getX() + "," + cur.getY() + "," + cur.getZ()
                                + ":" + d.getName();
                        if (live.add(inId)) {
                            in.add(new NetworkInterface(inId, TransferType.ITEM,
                                    true, d.get3DDataValue(), cur.getX(), cur.getY(),
                                    cur.getZ(), rateOf(d)));
                            anchors.put(inId, cur); // 输入接口锚点=所属管道段
                        }
                        continue;
                    }
                    String outId = cid;
                    out.add(new NetworkInterface(outId, TransferType.ITEM,
                            false, d.get3DDataValue(), target.getX(), target.getY(),
                            target.getZ(), rateOf(d), filterParam(cur, d)));
                    anchors.put(outId, cur); // 输出接口锚点=所属管道段
                }
            }
        } catch (Throwable t) {
            LOG.warn("[INC] pipez refresh BFS err (result may be incomplete) start={}", p, t);
        }
        // 记录簇内管道段位置（供同簇合并判断：同簇只保留一个设备/网络）
        this.clusterPipes = java.util.Set.copyOf(visited);
        // 记录图网边（p-p 物理边）与接口锚点
        this.pipeEdges = java.util.List.copyOf(edgesList);
        this.ifaceAnchor = java.util.Map.copyOf(anchors);
        // 解除已复原的 disabled 接口
        disabled.removeIf(id -> live.contains(id));
        // ★ 2026-08-29 用户：增量 diff——比较上次快照 vs 本次，产出
        //   「新增了什么 / 删除了什么」，核心据此增删节点并按连通分量自动拆/合。
        List<NetworkInterface> addIn = new ArrayList<>();
        List<Object> rmIn = new ArrayList<>();
        java.util.Set<Object> inIds = new java.util.HashSet<>();
        for (NetworkInterface i : in) { inIds.add(i.id); if (!prevInIds.contains(i.id)) addIn.add(i); }
        for (Object id : prevInIds) if (!inIds.contains(id)) rmIn.add(id);
        List<NetworkInterface> addOut = new ArrayList<>();
        List<Object> rmOut = new ArrayList<>();
        java.util.Set<Object> outIds = new java.util.HashSet<>();
        for (NetworkInterface o : out) { outIds.add(o.id); if (!prevOutIds.contains(o.id)) addOut.add(o); }
        for (Object id : prevOutIds) if (!outIds.contains(id)) rmOut.add(id);
        List<NetworkInterface> addSt = new ArrayList<>();
        List<Object> rmSt = new ArrayList<>();
        java.util.Set<Object> stIds = new java.util.HashSet<>();
        for (NetworkInterface s : struct) { stIds.add(s.id); if (!prevStructIds.contains(s.id)) addSt.add(s); }
        for (Object id : prevStructIds) if (!stIds.contains(id)) rmSt.add(id);
        this.lastDiff = new InterfaceDiff(addIn, rmIn, addOut, rmOut, addSt, rmSt);
        this.prevInIds = java.util.Set.copyOf(inIds);
        this.prevOutIds = java.util.Set.copyOf(outIds);
        this.prevStructIds = java.util.Set.copyOf(stIds);
        this.inputs = List.copyOf(in);
        this.outputs = List.copyOf(out);
        this.structureNodes = List.copyOf(struct);
    }

    /** 本 tick 的接口增量 diff（新增/移除；空 = 无变化） */
    public InterfaceDiff interfaceDiff() {
        return lastDiff;
    }

    /** 标记需要重新扫描（拆除段/邻居变化触发；实际 refresh 统一在下一 tick
     *  {@link #tickReports()} 内执行——P2-6 单一刷新入口，防重入交错） */
    public void markRescan() {
        needsRescan = true;
    }

    /**
     * 强制重新扫描（2026-08-29 用户：连接变化监听/指令强制重建）。
     * <p>
     * ★ 2026-09 P2-6 根因修复：不再【立即】 refresh()——否则邻居事件
     * （PipezNeighborMixin → onConnectionChanged）可在 tick() 遍历设备期间
     * 嵌套触发，与 tickReports() 内的 BFS 交错改写同一实例字段。
     * 改为只置标志：实际 refresh() 统一在下一 tick 的 {@link #tickReports()}
     * （唯一刷新入口）内执行一次，天然去重。
     *
     * @return true = 已触发（下次 tickReports 必 refresh + 全量快照重报）
     */
    public boolean forceRescan() {
        forceFullReport = true; // 全量快照重报（核心幂等）
        needsRescan = true;     // 保证下次 tickReports 返回非空（必上报）
        return true;
    }

    /** 管道段结构节点（离屏图结构信息；不被当作可交付输出） */
    public java.util.List<NetworkInterface> structureNodes() {
        return structureNodes;
    }

    /** 本管道所在物理簇的全部管道段位置（同簇合并判断） */
    public java.util.Set<BlockPos> clusterPipes() {
        return clusterPipes;
    }

    /** 图网边：相邻管道段对（p-p 物理边；2026-08-30 用户：普通管道一个节点） */
    public java.util.List<BlockPos[]> pipeEdges() {
        return pipeEdges;
    }

    /** 接口 id → 所属管道段 pos（输入/输出锚点） */
    public java.util.Map<Object, BlockPos> ifaceAnchor() {
        return ifaceAnchor;
    }

    /** 本管道自身位置 */
    public BlockPos pipePos() {
        return tile.getBlockPos();
    }

    /**
     * 指定管道段【位置+面】是否为抽取面（2026-08-29 关键修复：BFS 途中每个
     * 管道段都有自己的抽面配置，不能只看起点管道 tile）。读取该位置的 BE
     * 的 isExtracting(d)（NeoForge BE 实时数据，不走 Pipez 连接缓存）。
     */
    private boolean isExtractingAt(BlockPos pos, Direction d) {
        try {
            var be = level.getBlockEntity(pos);
            if (be instanceof de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity pt) {
                return pt.isExtracting(d);
            }
        } catch (Throwable t) {
            LOG.warn("[INC] isExtractingAt err pos={} dir={}", pos, d, t);
        }
        return false;
    }

    /**
     * 输出接口过滤参数（2026-08-30 用户：Pipez 解析器用）。
     * 从该管道段 BE 的 Pipez 过滤器（getFilters + getFilterMode）映射为
     * {@link com.hdf.cryptand.integratednetwork.PipezAbstractResolver.PipezFilterParam}：
     * 过滤条目 → 物品注册名 id 列表；filter mode → WHITELIST/BLACKLIST。
     * 无过滤器/读取失败 → ACCEPT_ALL（全接受）。
     */
    private Object filterParam(BlockPos pos, Direction d) {
        try {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity ut)) {
                return com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                        .PipezFilterParam.ACCEPT_ALL;
            }
            de.maxhenkel.pipez.blocks.tileentity.types.PipeType<?, ?>[] pts =
                    ((de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity) be)
                            .getPipeTypes();
            if (pts == null || pts.length == 0) {
                return com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                        .PipezFilterParam.ACCEPT_ALL;
            }
            de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity.FilterMode fm =
                    ut.getFilterMode(d, pts[0]);
            java.util.List<de.maxhenkel.pipez.Filter<?, ?>> fs =
                    ut.getFilters(d, pts[0]);
            boolean blacklist = fm == de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity
                    .FilterMode.BLACKLIST;
            java.util.List<Object> ids = new ArrayList<>();
            if (fs != null) {
                for (de.maxhenkel.pipez.Filter<?, ?> f : fs) {
                    // 管道过滤条目 → 物品注册名（tags 等复杂过滤简化为 item 名）
                    Object tag = f.getTag();
                    if (tag != null) {
                        try {
                            ids.add(tag.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return new com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                    .PipezFilterParam(blacklist
                            ? com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                                    .FilterMode.BLACKLIST
                            : com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                                    .FilterMode.WHITELIST, ids);
        } catch (Throwable t) {
            LOG.warn("[INC] pipez filterParam err pos={} dir={}", pos, d, t);
            return com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                    .PipezFilterParam.ACCEPT_ALL;
        }
    }

    /** 容器键：固定前缀 + 目标容器 pos + 真实连接面（供直 IO 定位真实箱子）。
     *  2026-08-29 修复：Pipez Connection.direction = 目标朝管道（镜像），
     *  此处归一化为管道→容器方向（getOpposite），与 blockstate 探测路径①统一。 */
    private static String containerId(PipeTileEntity.Connection c) {
        Direction face = c.getDirection() != null ? c.getDirection().getOpposite() : null;
        return "c:" + c.getPos().getX() + "," + c.getPos().getY() + "," + c.getPos().getZ()
                + ":" + (face != null ? face.getName() : "none");
    }

    /**
     * 接口/容器变化上报（2026-08-29 用户：直接返回整个网络的连接情况——每次
     * 变化时【全量快照】上报全部接口+容器，核心按 id 幂等增删；再配合
     * {@code DimensionNetworkManager.applyInterfaceDiff} 的增量 diff 维护图）。
     * <p>
     * 触发时机：
     * <ul>
     *   <li>接口/容器集合变化（增量 diff 非空）；</li>
     *   <li>{@link #forceRescan()}（连接变化监听/指令强制重建）；</li>
     *   <li>找不到可用输出容器时按配置间隔（默认 10s）重试（打印日志）。</li>
     * </ul>
     */
    @Override
    public java.util.List<com.hdf.cryptand.integratednetwork.NetworkReport> tickReports() {
        try {
            refresh();
            InterfaceDiff diff = lastDiff;
            boolean retryDue = false;
            // 找不到可用输出容器（无输出或全满/全 disabled）→ 按配置间隔重试
            if (outputs.isEmpty() || !hasUsableOutputNow()) {
                long now = System.currentTimeMillis();
                long interval = ConfigInc.INC_OUTPUT_RETRY_MS.get();
                if (interval > 0 && now - noOutputSinceMs >= interval) {
                    noOutputSinceMs = now;
                    retryDue = true;
                    LOG.info("[INC] pipez output-retry pos={} outputs={} disabled={}",
                            tile.getBlockPos(), outputs.size(), disabled.size());
                }
            } else {
                noOutputSinceMs = System.currentTimeMillis();
            }
            if (diff.isEmpty() && disabled.isEmpty() && !needsRescan
                    && !forceFullReport && !retryDue) return java.util.List.of();
            needsRescan = false;
            forceFullReport = false;
            java.util.List<com.hdf.cryptand.integratednetwork.NetworkReport> reps =
                    new ArrayList<>();
            // ★ 全量快照：被卸载接口 → removeInterface；全部现存接口 → addInterface
            for (Object id : disabled) {
                reps.add(com.hdf.cryptand.integratednetwork.NetworkReport.removeInterface(id));
            }
            for (NetworkInterface i : inputs) {
                if (disabled.contains(i.id)) continue;
                reps.add(com.hdf.cryptand.integratednetwork.NetworkReport.addInterface(i));
            }
            for (NetworkInterface o : outputs) {
                if (disabled.contains(o.id)) continue;
                reps.add(com.hdf.cryptand.integratednetwork.NetworkReport.addInterface(o));
                reps.add(com.hdf.cryptand.integratednetwork.NetworkReport.addContainer(
                        new com.hdf.cryptand.integratednetwork.ContainerInfo(
                                o.id, o.direction, 1, rateOfFromId(o.id), false, null)));
            }
            return reps;
        } catch (Throwable t) {
            LOG.warn("[INC] pipez tickReports err", t);
            return java.util.List.of();
        }
    }

    /** 当前是否有可用（非 disabled、非满）输出接口（供计时重试判断） */
    private boolean hasUsableOutputNow() {
        for (NetworkInterface o : outputs) {
            if (disabled.contains(o.id)) continue;
            try {
                if (!isOutputFull(o.id)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 该接口是否已卸载（容器被拆走 → 跳过处理，2026-08-29 用户） */
    @Override
    public boolean isDisabled(Object interfaceId) {
        return disabled.contains(stripNodePrefix(interfaceId));
    }

    /**
     * 该输出容器是否【全满】（2026-08-29 用户：输出容器都满了则不传输）。
     * 2026-08-30 用户：不要特判——改【纯容量检测】：逐槽看是否为空/未满，
     * 不用"模拟插入"（插入失败无法区分"满"与"拒绝插入"——creative bin 等
     * 拒绝所有模拟插入 → 误判满 → 网络整体拦截）。空槽或未满槽 → 未满。
     */
    @Override
    public boolean isOutputFull(Object containerId) {
        try {
            Object stripped = stripNodePrefix(containerId);
            if (disabled.contains(stripped)) return true;
            ContainerRef ref = parseContainer(stripped);
            if (ref == null) return false;
            // 2026-09 P2-5：判满与识别统一【连接面】handler（BFS 判定该面有
            // handler 才建输出口；此处取不到 = 容器卸载/能力摘除 → 不判满，
            // 交付侧 markUnloaded 处理）
            IItemHandler h = itemHandlerAt(ref.pos(), ref.face());
            if (h == null) return false; // 拿不到 handler → 不判满（交付时跳过）
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack cur = h.getStackInSlot(i);
                if (cur.isEmpty()) return false;                       // 空槽 → 未满
                if (cur.getCount() < cur.getMaxStackSize()) return false; // 未满槽 → 未满
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[INC] pipez isOutputFull err container={}", containerId, t);
            return false;
        }
    }

    /** 该面是否连接（相邻为 Pipez 管道或非空气方块，作为可输出目标） */
    private boolean isConnected(Direction d) {
        net.minecraft.world.level.block.state.BlockState bs =
                level.getBlockState(tile.getBlockPos().relative(d));
        return bs.getBlock() instanceof de.maxhenkel.pipez.blocks.PipeBlock || !bs.isAir();
    }

    private double rateOf(Direction d) {
        return 0; // 速率随升级解析留待解析器增强；0 = 不限
    }

    private double rateOfFromId(Object cid) {
        return 0;
    }

    private Object ifaceId(Direction d) {
        return "in:" + d;
    }

    @Override
    public TransferType networkCapability() {
        return TransferType.ITEM;
    }

    @Override
    public Object networkKey() {
        return key;
    }

    @Override
    public List<NetworkInterface> inputInterfaces() {
        return inputs;
    }

    @Override
    public List<NetworkInterface> outputInterfaces() {
        return outputs;
    }

    @Override
    public double defaultRate() {
        return 1; // 2026-08-29 用户：默认速率 1 物品/tick
    }

    /** 每 tick 传输速率：按 Pipez 升级/容量解析（2026-08-29 用户：默认速率 1 物品/tick） */
    @Override
    public double transferRate() {
        return 1;
    }

    /**
     * 分配规则（2026-08-29 用户：完全接管原版行为）——解析 Pipez 每个抽取面的
     * {@code Distribution} 配置（NEAREST/FURTHEST/ROUND_ROBIN/RANDOM），映射到
     * {@link DistributionRule}；未找到配置回退 NEAREST（原版默认）。
     * 用首个抽取面的 Distribution 作为本设备规则（单能力网络）。
     */
    @Override
    public com.hdf.cryptand.integratednetwork.DistributionRule transferRule() {
        try {
            for (Direction d : Direction.values()) {
                if (!tile.isExtracting(d)) continue;
                de.maxhenkel.pipez.blocks.tileentity.types.PipeType<?, ?>[] pts =
                        tile.getPipeTypes();
                if (pts == null || pts.length == 0) return com.hdf.cryptand.integratednetwork
                        .DistributionRule.NEAREST;
                de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity.Distribution dist =
                        tile.getDistribution(d, pts[0]);
                if (dist == null) return com.hdf.cryptand.integratednetwork.DistributionRule.NEAREST;
                return switch (dist) {
                    case FURTHEST -> com.hdf.cryptand.integratednetwork.DistributionRule.FURTHEST;
                    case ROUND_ROBIN -> com.hdf.cryptand.integratednetwork.DistributionRule.ROUND_ROBIN;
                    case RANDOM -> com.hdf.cryptand.integratednetwork.DistributionRule.RANDOM;
                    default -> com.hdf.cryptand.integratednetwork.DistributionRule.NEAREST;
                };
            }
        } catch (Throwable t) {
            LOG.warn("[INC] pipez transferRule err (fallback NEAREST)", t);
        }
        return com.hdf.cryptand.integratednetwork.DistributionRule.NEAREST;
    }

    // ===== 收集（主线程）：抽取面 → 目标容器列表 → 内容 =====

    @Override
    public boolean collect(Object ifaceId, TransferType type, List<TransportPayload> sink) {
        if (!(ifaceId instanceof String s) || !s.startsWith("in:")) return false;
        BlockPos src = parseInPos(s);
        if (src == null) return false;
        // 2026-08-30 修复：src 是【管道段】位置，in:...:face 的源容器 = src.relative(face)
        Direction face = parseInFace(s);
        BlockPos csrc = face == null ? src : src.relative(face);
        IItemHandler h = face == null ? null : itemHandlerAt(csrc, face);
        if (h == null) return false;
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack st = h.getStackInSlot(i);
            if (!st.isEmpty()) {
                int n = Math.min(64, st.getCount());
                sink.add(new TransportPayload(TransferType.ITEM, n, null,
                        new ContainerRef(csrc, null)));
                return true;
            }
        }
        return false;
    }

    // ===== 预提取（主线程）：从【源容器】真实提取 → 移交网络缓存（2026-08-29 用户方案 B）=====

    @Override
    public java.util.List<Object> pull(Object interfaceId, double maxAmount) {
        if (!(interfaceId instanceof String s) || !s.startsWith("in:")) return List.of();
        // 新格式 in:<x,y,z>:face（BFS 汇总簇内全部段的抽面）
        BlockPos src = parseInPos(s);
        if (src == null) return List.of();
        // 2026-08-30 修复：src 是【管道段】位置，in:...:face 的源容器 = src.relative(face)
        Direction face = parseInFace(s);
        BlockPos csrc = face == null ? src : src.relative(face);
        // 2026-09 P2-5：抽取与识别统一【连接面】handler（BFS 判定该面有 handler
        // 才建输入口；face 恒非 null——接口 id 构造带面）
        IItemHandler h = face == null ? null : itemHandlerAt(csrc, face);
        if (h == null) return List.of();
        int toExtract = (int) Math.max(1, Math.round(maxAmount));
        java.util.List<Object> out = new ArrayList<>();
        for (int i = h.getSlots() - 1; i >= 0 && toExtract > 0; i--) {
            ItemStack st = h.extractItem(i, toExtract, false);
            if (!st.isEmpty()) {
                out.add(st);
                toExtract -= st.getCount();
            }
        }
        return out;
    }

    /** 解析输入接口 id "in:<x,y,z>:<face>" → 源容器位置（不解析 face，任意面取 handler） */
    private static BlockPos parseInPos(String inId) {
        try {
            String body = inId.substring(3); // 去掉 "in:"
            int colon = body.lastIndexOf(':');
            String xyz = colon > 0 ? body.substring(0, colon) : body;
            String[] parts = xyz.split(",");
            if (parts.length != 3) return null;
            return new BlockPos(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析输入接口 id "in:<x,y,z>:<face>" → face（null = 未解析） */
    private static Direction parseInFace(String inId) {
        try {
            int colon = inId.lastIndexOf(':');
            if (colon < 0) return null;
            String f = inId.substring(colon + 1);
            try {
                return Direction.valueOf(f.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 交付（主线程）：从网络缓存取真实物品 → 写目标容器（2026-08-29 用户方案 B）=====

    /**
     * 交付：按真实容器 id（"c:x,y,z:face"）定位目标箱子直接写。
     * 写不进/目标容器被卸载 → 该接口标记 disabled + needsRescan（2026-08-29
     * 用户：传输失败=容器有变动 → 上报最新管道+容器列表），剩余回池不丢。
     */
    @Override
    public java.util.List<Object> deliveryWrite(Object containerId, java.util.List<Object> contents) {
        // ⚠ 2026-08-29 关键修复：执行表 Entry.containerKey 是【节点 id】
        //   "pipez:<pos>|c:x,y,z:face"（manager 用 ifaceNodeId(key,out.id) 组装），
        //   而本类契约是【裸容器 id】"c:x,y,z:face"——先用 deliver 前剥掉 key 前缀，
        //   否则 parseContainer/connByCid 全部落空 → 误判"容器卸载"永久禁用(delivered=0)。
        containerId = stripNodePrefix(containerId);
        if (disabled.contains(containerId)) return new ArrayList<>(contents); // 已卸载跳过
        ContainerRef ref = parseContainer(containerId);
        if (ref == null) {
            markUnloaded(containerId);
            return new ArrayList<>(contents);
        }
        // ★ 2026-09 P2-5 根因修复：交付与识别统一【连接面】handler——BFS 判定该
        //   面有 handler 才建输出口，此处直接按同面取（不再依赖 Pipez Connection
        //   缓存——接管后不刷新、connByCid 从未被填充；也不再任意面乱试）。
        //   取不到 = 容器被卸载/能力被摘除 → markUnloaded + 回池重试。
        IItemHandler h = itemHandlerAt(ref.pos(), ref.face());
        if (h == null) {
            // 找不到容器 handler（卸载/未加载）→ 标记 disabled 从网络移除，
            //   refresh() 的 BFS 若容器恢复（live 含此 id）会自动解除 disabled
            //   并上报重新加入。剩余回池不丢（下次恢复后重新交付）。
            if (!disabled.contains(containerId)) {
                markUnloaded(containerId);
            }
            return new ArrayList<>(contents);
        }
        java.util.List<Object> remaining = new ArrayList<>();
        for (Object o : contents) {
            if (!(o instanceof ItemStack st) || st.isEmpty()) continue;
            ItemStack remain = st.copy();
            // 逐槽插入：插不进去（目标满）的留在 remain，作为剩余回池
            for (int i = 0; i < h.getSlots() && !remain.isEmpty(); i++) {
                remain = h.insertItem(i, remain, false);
            }
            if (!remain.isEmpty()) remaining.add(remain);
        }
        // 目标满（部分插不进）→ 容器状态变化，仍可重试：仅重新上报（不卸负载）
        if (!remaining.isEmpty() && remaining.size() != contents.size()) needsRescan = true;
        return remaining;
    }

    /** 剥掉节点 id 前缀："pipez:<pos>|c:x,y,z:face" → "c:x,y,z:face"（无前缀原样返回） */
    private static Object stripNodePrefix(Object id) {
        if (id instanceof String s) {
            int i = s.indexOf('|');
            if (i >= 0) return s.substring(i + 1);
        }
        return id;
    }

    /** 容器被卸载 → 标记该接口 disabled（跳过处理）并触发重检上报 */
    private void markUnloaded(Object containerId) {
        disabled.add(containerId);
        needsRescan = true;
        CryptandNeoForge.WAF_LOGGER.info(
                "[INC] pipez unload iface {} disabled", containerId);
    }

    /** 容器 id 形如 "c:x,y,z:face" */
    private static ContainerRef parseContainer(Object containerId) {
        if (!(containerId instanceof String s) || !s.startsWith("c:")) return null;
        int comma1 = s.indexOf(',');
        int comma2 = comma1 >= 0 ? s.indexOf(',', comma1 + 1) : -1;
        int colon = comma2 >= 0 ? s.indexOf(':', comma2 + 1) : -1;
        if (comma1 < 0 || comma2 < 0 || colon < 0) return null;
        try {
            int x = Integer.parseInt(s.substring(2, comma1));
            int y = Integer.parseInt(s.substring(comma1 + 1, comma2));
            int z = Integer.parseInt(s.substring(comma2 + 1, colon));
            Direction face = Direction.byName(s.substring(colon + 1));
            if (face == null) return null;
            return new ContainerRef(new net.minecraft.core.BlockPos(x, y, z), face);
        } catch (Throwable t) {
            LOG.warn("[INC] pipez parseContainer err id={}", containerId, t);
            return null;
        }
    }

    // ===== 执行表应用（主线程）：对容器直接输入/输出 =====

    @Override
    public double executeExtract(Object containerId, double amount) {
        // 源容器 = 输入接口 id 中的管道段位置（in:<x,y,z>:face）——2026-08-30 修复：
        //   真实源容器 = 管道段 relative(face)，不是管道段本身
        BlockPos src = parseInPos(String.valueOf(containerId));
        if (src == null) return 0;
        Direction face = parseInFace(String.valueOf(containerId));
        BlockPos csrc = face == null ? src : src.relative(face);
        IItemHandler h = face == null ? null : itemHandlerAt(csrc, face);
        if (h == null) return 0;
        int toExtract = (int) Math.round(amount);
        int got = 0;
        for (int i = h.getSlots() - 1; i >= 0 && toExtract > 0; i--) {
            ItemStack st = h.extractItem(i, toExtract, false);
            int n = st.getCount();
            got += n;
            toExtract -= n;
        }
        return got;
    }

    @Override
    public double executeWrite(Object containerId, double amount) {
        // 2026-08-30 修复：输出容器 id "c:<容器pos>:<face>" 的 pos 才是真实容器；
        //   旧代码用 设备pos.relative(face)（错位到管道内部，delivered 永远 0）。
        ContainerRef ref = parseContainer(containerId);
        if (ref == null) return 0;
        if (tile.isExtracting(ref.face())) return 0; // 抽取面不写
        IItemHandler h = itemHandlerAt(ref.pos(), ref.face());
        if (h == null) return 0;
        int toInsert = (int) Math.round(amount);
        ItemStack template = itemTemplate(ref.pos(), toInsert);
        int inserted = 0;
        for (int i = 0; i < h.getSlots() && toInsert > 0; i++) {
            ItemStack stack = template.copy().split(toInsert);
            ItemStack rest = h.insertItem(i, stack, false);
            int n = stack.getCount() - rest.getCount();
            inserted += n;
            toInsert -= n;
        }
        return inserted;
    }

    private ItemStack itemTemplate(net.minecraft.core.BlockPos at, int count) {
        // 写端使用任意可插物品模板（真实转移在 P3.1 由解析器/过滤器决定具体物品）
        return new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, count);
    }

    private IItemHandler itemHandlerAt(net.minecraft.core.BlockPos pos, Direction face) {
        try {
            return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face);
        } catch (Throwable t) {
            LOG.warn("[INC] pipez itemHandlerAt err pos={} face={}", pos, face, t);
            return null;
        }
    }

    private Direction dirOf(Object containerId) {
        if (!(containerId instanceof String s)) return null;
        int i = s.lastIndexOf(':');
        if (i < 0) return null;
        String name = s.substring(i + 1);
        return Direction.byName(name);
    }

    /** 容器引用（世界内定位真实容器） */
    public record ContainerRef(net.minecraft.core.BlockPos pos, Direction face) {
    }

    /**
     * 连接诊断行（/cryptand inc 端点，2026-08-29）：打印 Pipez blockstate 物理
     * 连接属性 + getConnections() 每条连接的位置/方向/距离/是否有 ItemHandler/
     * 目标方块类型——用于定位交付目标到底是【容器】还是【管道段】（后者不可
     * 直接写入 → delivered=0），以及 east 箱子是否被 Pipez 物理探测到。
     */
    public java.util.List<String> connectionDiag() {
        java.util.List<String> out = new ArrayList<>();
        try {
            net.minecraft.core.BlockPos p = tile.getBlockPos();
            BlockState bs = level.getBlockState(p);
            if (bs.getBlock() instanceof de.maxhenkel.pipez.blocks.PipeBlock pb) {
                StringBuilder sb = new StringBuilder("blockstate conns:");
                for (Direction d : Direction.values()) {
                    BooleanProperty prop = pb.getProperty(d);
                    if (prop != null && bs.getValue(prop)) sb.append(' ').append(d.getName());
                }
                out.add(sb.toString());
            }
            out.add("extracting=" + java.util.Arrays.toString(
                    java.util.Arrays.stream(Direction.values())
                            .filter(d -> tile.isExtracting(d)).map(Direction::getName)
                            .toArray()));
            for (PipeTileEntity.Connection c : tile.getConnections()) {
                Direction d = c.getDirection();
                boolean h = c.getItemHandler() != null;
                String block = "?";
                try {
                    block = String.valueOf(level.getBlockState(c.getPos()).getBlock());
                } catch (Throwable t) {
                    LOG.warn("[INC] pipez connDiag block err pos={}", c.getPos(), t);
                }
                out.add(String.format(
                        "conn pos=%s realFace=%s pipezDir=%s dist=%d hasHandler=%s extract=%s block=%s",
                        c.getPos(), d != null ? d.getOpposite() : "?",
                        d, c.getDistance(), h,
                        (d != null && tile.isExtracting(d.getOpposite())), block));
            }
        } catch (Throwable t) {
            LOG.warn("[INC] pipez connDiag err", t);
            out.add("connDiag err: " + t);
        }
        return out;
    }

    @Override
    public String toString() {
        return "PipezDeviceAdapter{key=" + key + " in=" + inputs.size() + " out=" + outputs.size() + "}";
    }
}
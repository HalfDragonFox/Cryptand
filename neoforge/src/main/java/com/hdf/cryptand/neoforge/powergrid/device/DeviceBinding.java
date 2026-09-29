package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.circuitsimulation.model.ModelLink;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.VirtualDeviceStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实际模型（MC 方块实体）↔ 复合元件 的双向绑定（2026-08-13 用户要求）。
 * <p>
 * 按 BlockPos 注册（跨网络重建稳定）：每轮构建时把【当前 ctx 的复合元件 + 其
 * 所属 PowerGrid 网络】attach 到本绑定，实际模型与复合元件互持引用、互发消息：
 * <pre>
 *   实际模型(BE) ←→ DeviceBinding(pos) ←→ 复合元件(CompositeElement)
 * </pre>
 *   - 【BE 破坏 → 引擎】：mixin（setRemoved）经 {@link #forPos} 找到本绑定 →
 *     {@link #notifyModelDestroyed()} → 用 attach 记录的 ElectricalNetwork
 *     精准 {@code markNetworkChanged(net)}（O(1) 定位，无需遍历世界网络）→
 *     netVer++ → 缓存 miss → 强制重建 → BE 不存在 → 元件自然删除。
 *   - 【元件移除 → BE】：adapter 在 ctx 重建/元件生命周期结束检测到该 pos 元件
 *     不再存在 → {@link #notifyCompositeRemoved()} → 清理温度模型/虚拟快照 +
 *     注销绑定。
 * <p>
 * 引擎不知道 BE 细节；BE 侧不知道引擎 MNA/网络结构——只通过本绑定收发消息。
 */
public final class DeviceBinding implements ModelLink,
        com.hdf.cryptand.engine.Binding {

    /** pos → 绑定（跨网络重建稳定；设备破坏/移除后注销） */
    private static final Map<BlockPos, DeviceBinding> REGISTRY = new ConcurrentHashMap<>();

    /** 坐标（唯一标识；2026-08-15 坐标化：后台构建用 ofPos 只记坐标，不持 BE） */
    private final BlockPos pos;

    /** 实际模型（BE；仅主线程 stillAlive/清理用，可 null = 后台 pos-only 创建） */
    private final BlockEntity be;

    /** 当前复合元件覆盖的 PowerGrid 网络集合（每轮构建 attach 更新；跨变压器
     *  设备覆盖两侧网络，破坏时全部失效）。空集 = 未构建过 → 全局兜底。 */
    private volatile java.util.Set<ElectricalNetwork> networks =
            java.util.Collections.emptySet();

    /** 当前绑定的复合元件（每轮构建 attach 更新；可 null） */
    private volatile CompositeElement composite;

    /** 加载状态（2026-09-11 用户：绑定增加一个 bool）：创建（真实放置）默认 true；
     *  区块加载 → 平台批量置 true（开启）；区块卸载 → 批量置 false（关闭）。
     *  未加载 ≠ 已移除——电路/模型保留不删除（区块卸载时电路仍保留计算）。 */
    private volatile boolean loaded = true;

    private DeviceBinding(BlockPos pos, BlockEntity be) { this.pos = pos; this.be = be; }

    /** 构造/获取绑定；非电气设备（或 null）返回 null（不绑定） */
    public static DeviceBinding of(BlockEntity be) {
        if (be == null || !(be instanceof ElectricBlockEntity)) return null;
        return REGISTRY.computeIfAbsent(be.getBlockPos(), p -> new DeviceBinding(p, be));
    }

    /**
     * 坐标-only 绑定（2026-08-15 完全异步：后台构建用，只记坐标不持 BE）。
     * 交互全部走消息/pos 反查：破坏感知（主线程 mixin）经 {@link #forPos} 反查；
     * 虚拟快照由主线程同步（DeviceParamCache）预存。
     */
    public static DeviceBinding ofPos(BlockPos pos) {
        if (pos == null) return null;
        return REGISTRY.computeIfAbsent(pos, p -> new DeviceBinding(p, null));
    }

    /** 位置迁移（2026-08-23 物理化 HEAD 预提交）：注册表键 旧→新，保留已 attach
     *  的复合元件/网络引用（下次构建 attach 刷新）——物理化后写回/破坏感知仍可
     *  经新 pos 反查到绑定。 */
    public static void move(BlockPos oldPos, BlockPos newPos) {
        if (oldPos == null || newPos == null || oldPos.equals(newPos)) return;
        if (!REGISTRY.containsKey(oldPos)) return;
        REGISTRY.computeIfAbsent(newPos, p -> {
            DeviceBinding old = REGISTRY.remove(oldPos);
            if (old == null) return new DeviceBinding(p, null);
            DeviceBinding nb = new DeviceBinding(p, old.be);
            nb.networks = old.networks;
            nb.composite = old.composite;
            return nb;
        });
    }

    /** 绑定坐标 */
    public BlockPos pos() { return pos; }

    // ===== 引擎绑定接口（common Binding——MC 平台实现；消息处理由平台） =====

    /** 引擎：元件已绑定 → 记录复合元件（本绑定持有的实际模型） */
    @Override
    public void onBound(com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement element) {
        if (element != null) this.composite = element;
    }

    /** 引擎：元件已解除绑定 → 清理复合元件引用 */
    @Override
    public void onUnbound(com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement element) {
        if (this.composite == element) this.composite = null;
    }

    /** 引擎消息（SOLVE_DONE/NETWORK_REBUILT/PARAM_CHANGED/NETWORK_INVALIDATED）：
     *  平台实现处理（MC：更新 BE 状态/护目镜/温度计读数——现有机制
     *  DeviceCurrent/温度同步已覆盖核心；本方法留扩展点，默认空）。 */
    @Override
    public void onMessage(com.hdf.cryptand.engine.EngineMessage message) {
        // 2026-09-15 用户："每次网络计算时不是有消息处理吗，处理时先更新到对应组装器
        //  即可。" —— 绑定只是【通道】，本身不解释消息内容：按 pos 找到该设备的组装器
        //  交出去，由组装器更新自己持有的信息（参数/状态）。
        //  ⚠ 组装器按【位置 + 信息】工作，不认识 BE；这里也绝不把 BE 传下去。
        try {
            DeviceParamCache.Entry de = DeviceParamCache.get(pos);
            if (de == null || de.deviceClass == null) return;
            com.hdf.cryptand.neoforge.powergrid.device.Assembler asm =
                    com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                            .getByClass(de.deviceClass);
            if (asm != null) asm.onMessage(pos, message);
        } catch (Throwable ignored) {
        }
    }

    /** 加载状态（区块加载 → true「开启」/ 区块卸载 → false「关闭」；创建默认 true）。
     *  未加载不删除电路/模型——仅标记状态。 */
    @Override
    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    @Override
    public boolean isLoaded() {
        return this.loaded;
    }

    /**
     * 持久化信息（{@link ModelLink} 接口，2026-09-15 用户："保存时需要组装器记住绑定的
     * 数据的相关信息，可以给绑定接口添加信息接口，用于保存时返回必要信息"）。
     * <p>
     * ⚠ **绑定只是通道**：它自己不知道电机要存什么、变压器要存什么 —— 按 pos 找到该设备
     * 的组装器，把问题转过去（组装器建了这个设备，也就最清楚重建需要什么）。
     * 返回 null 表示该设备无需持久化（纯被动元件）。
     */
    @Override
    public java.util.Map<String, Object> persistInfo() {
        try {
            DeviceParamCache.Entry de = DeviceParamCache.get(pos);
            if (de == null || de.deviceClass == null) return null;
            com.hdf.cryptand.neoforge.powergrid.device.Assembler asm =
                    com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                            .getByClass(de.deviceClass);
            if (asm == null) return null;
            return asm.persistInfo(pos, this.composite);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 遍历全部绑定（世界保存时采集设备信息用；单个失败不影响其余） */
    public static void forEach(java.util.function.Consumer<DeviceBinding> fn) {
        for (DeviceBinding b : REGISTRY.values()) {
            try {
                fn.accept(b);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按 pos 查绑定（破坏感知用；未构建过返回 null） */
    public static DeviceBinding forPos(BlockPos pos) {
        return pos == null ? null : REGISTRY.get(pos);
    }

    /** 每轮构建：把当前复合元件 + 其覆盖的网络集合 attach 到绑定（双向引用刷新） */
    public void attach(java.util.Set<ElectricalNetwork> nets, CompositeElement ce) {
        this.networks = nets == null ? java.util.Collections.emptySet() : nets;
        this.composite = ce;
    }

    public java.util.Set<ElectricalNetwork> networks() { return networks; }
    public CompositeElement assemble() { return composite; }

    // ===== BE → 引擎：实际模型破坏 =====

    /**
     * 实际模型被破坏 → 引擎侧应删除此元件并请求网络重建（2026-08-13 用户架构
     * 点 6：模型破坏 → 向元件所在网络发送销毁信息，附带元件引用帮助处理）。
     * 用 attach 记录的 ElectricalNetwork 集合精准 markNetworkChanged（O(网络数
     * of 本设备)，无需遍历世界网络）→ netVer++ → CACHES miss → 强制重建 →
     * 该方块 BE 不存在 → 元件自然不再建模（等效删除）。无网络记录（未构建过）
     * → 全局兜底。同时：
     *   - 若该元件在销毁队列（过热待销毁）→ 模型已被破坏 → 移出（无需再销毁模型）
     *   - 清理温度/虚拟快照（该位置已无设备）
     */
    @Override
    public void notifyModelDestroyed() {
        CryptandTopologyManager mgr = CryptandTopologyManager.get();
        boolean hit = false;
        for (ElectricalNetwork n : networks) {
            if (n != null) { mgr.markNetworkChanged(n); hit = true; }
        }
        // ⚠ 2026-09-13 用户："不能全世界重建"。networks 为空 = 本元件从未 attach
        //   到任何网络（未参与过求解）⇒ 没有任何网络需要重建；原实现在此调
        //   markTopologyChanged()（全局）已删除。
        try {
            // 附带元件引用帮助处理：销毁队列该 pos 直接移出（模型已破，无需再炸）
            DestructionQueue.modelRemoved(pos);
        } catch (Throwable ignored) {
        }
    }

    // ===== 引擎 → BE：元件被移除 =====

    /**
     * 元件被移除出网络（设备被破坏/网络重建后不再含它）→ 实际模型清理：
     * 温度模型 + 虚拟快照 + 注销绑定（该位置已无设备，避免虚拟残留/重复触发）。
     */
    @Override
    public void notifyCompositeRemoved() {
        try {
            DeviceThermalStore.remove(pos);
            VirtualDeviceStore.remove(pos);
        } catch (Throwable ignored) {
        }
        REGISTRY.remove(pos, this);
    }

    /** 当前绑定是否仍有效（BE 未真移除）：
     *   - 区块【未加载】（虚拟/虚拟设备：BE 暂不在内存，用 VirtualDevice 快照
     *     建模）→ 返回 true（保留，绝不能删——用户要求：虚拟元件不能因检测不到
     *     绑定/BE 就删除）。
     *   - 区块【已加载】但该位置 BE 已不是本绑定 BE（方块被破坏/替换）→ 返回
     *     false（真移除 → 通知复合元件清理注销）。
     *   - 异常保守返回 true（宁可不删，绝不误删虚拟设备）。
     *   2026-08-15：pos-only 绑定（后台创建，be=null）→ 保守 true（由重建/移除
     *   消息自然清理，不在此强删）。 */
    public boolean stillAlive() {
        try {
            if (be == null) return true; // pos-only：保守保留
            Level lv = be.getLevel();
            if (lv == null) return false;
            BlockPos p = be.getBlockPos();
            if (!lv.isLoaded(p)) return true; // 区块未加载 → 虚拟 → 保留
            return lv.getBlockEntity(p) == be; // 已加载：BE 还在 → 保留；消失 → 真移除
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 元件移除统一检测（round 末尾主线程调用，节流）：遍历【已绑定】注册表，
     *  仅当区块已加载且该位置 BE 已消失（真破坏/替换）→ 通知复合元件移除 →
     *  实际模型清理注销。⚠ 虚拟/虚拟元件（未绑定 → 不在注册表；或区块未加载 →
     *  stillAlive=true）一律不删除。 */
    public static void cleanupRemoved(Level level) {
        if (level == null || REGISTRY.isEmpty()) return;
        for (DeviceBinding db : REGISTRY.values()) {
            try {
                if (!db.stillAlive()) {
                    CompositeElement ce = db.composite;
                    if (ce != null) ce.notifyRemoved(); // → notifyCompositeRemoved → 清理注销
                    else REGISTRY.remove(db.be.getBlockPos(), db);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 世界切换/关闭 → 清空全部绑定（防跨世界 pos 串扰） */
    public static void clearAll() { REGISTRY.clear(); }
}

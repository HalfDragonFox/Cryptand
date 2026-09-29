/**
 * ===== 导线渲染管理器（2026-08-13 自管图驱动，CEE 二次曲线） =====
 *
 * 从【客户端自管图缓存】（ClientWireGraphStore，由服务端 WireGraphSyncPayload
 * 每 10 tick 同步）读取导线（端点 + 颜色）→ 生成/更新/移除 Flywheel Effects
 * （CryptandWireEffect → CryptandWireVisual 实例化二次曲线渲染）。
 * <p>
 * 数据源：服务端 PowerGridWireConverter 转换原版导线 → 自管 WireGraph →
 * 广播同步包 → 客户端缓存。渲染【完全由转换后的自管内容驱动】，不依赖
 * 原版导线实体——服务端 removeConvertedWires 已删除实体、MC 同步也移除
 * 客户端实体，实体不再是渲染数据源。
 * <p>
 * 门控：PowerGridWireConverter.isEnabled() 为 false 时不渲染自管导线
 * （原版实体导线保持原版渲染，mixin 也同时放行）。
 * <p>
 * 线程：客户端主线程 tick（ClientSoundTicker.onClientTick 调用）。
 */
package com.hdf.cryptand.neoforge.powergrid.client.wire;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.client.ClientNetworkColorStore;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.*;

public final class WireRenderManager {

    private static final WireRenderManager INSTANCE = new WireRenderManager();

    private boolean active = false;
    /** 当前已注册的效果：边签名 → Effect（按边去重） */
    private final Map<String, CryptandWireEffect> effects = new HashMap<>();
    /** 已处理版本：ClientWireGraphStore.version() 变化才全量扫描（变更驱动，
     *  消除 10 tick 节流 → 放置后 0.5s 才显示 的延迟） */
    private int lastProcessedVer = -1;
    /** 客户端端子位置重解析间隔（tick）：方块旋转/替换/区块加载后导线端点
     *  自动跟随（服务端同步快照冻结不更新，CEE 语义）。 */
    private static final int RESOLVE_INTERVAL = 5;
    private int resolveCounter = 0;
    /** 诊断计数（节流打印） */
    private int diagCounter = 0;

    private WireRenderManager() {
    }

    public static WireRenderManager get() {
        return INSTANCE;
    }

    /** 每客户端 tick 调用（ClientSoundTicker.onClientTick）。 */
    public void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clearAll();
            return;
        }

        boolean enabled = PowerGridWireConverter.isEnabled();
        if (!enabled) {
            if (active) {
                clearAll();
            }
            return;
        }
        if (!active) {
            // 重新激活（世界加载/配置开关 toggle/网络恢复）：强制重扫，
            // 防止 lastProcessedVer 残留旧版本号与当前 version 相等跳过渲染。
            lastProcessedVer = -1;
        }
        active = true;

        // 周期性重解析端子精确位置（客户端自算）：方块旋转/替换/区块加载后
        // 导线端点自动跟随。有位置变化才 version++ → 下方 diff 重建受影响
        // 效果（无变化零开销）。修复“导线渲染位置不在端子上”（服务端同步
        // 快照冻结：同步时区块未加载/端子 placement null → 永久块中心）。
        if (++resolveCounter % RESOLVE_INTERVAL == 0) {
            ClientWireGraphStore.refreshPositions(level);
        }

        // 版本变更驱动：客户端图缓存更新（收到同步包）→ 下一 tick 立即全量
        // 扫描创建效果——放置/剪线延迟仅剩网络 + 1 tick，不再等 10 tick 节流。
        // 平时无变更 → 直接返回（零开销）。
        int ver = ClientWireGraphStore.version();
        if (ver == lastProcessedVer) return;
        lastProcessedVer = ver;

        // 从【客户端自管图缓存】读取（服务端 WireGraphSyncPayload 同步，
        // 不依赖原版导线实体——实体已被服务端删除、客户端同步移除）。
        List<ClientWireGraphStore.ClientWire> wires = ClientWireGraphStore.wires();
        // 诊断（节流）：客户端图缓存导线数 + 已注册效果数，定位渲染断点
        if (++diagCounter % 6 == 0) {
            try {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[WireRender] cachedWires={} effects={}", wires.size(), effects.size());
            } catch (Throwable ignored) {
            }
        }
        if (wires.isEmpty()) {
            // 图空：清掉全部已注册效果（防残留）
            if (!effects.isEmpty()) {
                for (CryptandWireEffect effect : effects.values()) {
                    VisualizationHelper.queueRemove(effect);
                }
                effects.clear();
            }
            return;
        }

        Set<String> present = new HashSet<>();
        for (ClientWireGraphStore.ClientWire wire : wires) {
            String id = edgeId(wire);
            present.add(id);
            CryptandWireEffect existing = effects.get(id);
            CryptandWireEffect effect = createEffect(level, wire);
            if (effect == null) {
                if (existing != null) {
                    VisualizationHelper.queueRemove(existing);
                    effects.remove(id);
                }
                continue;
            }
            if (existing == null) {
                effects.put(id, effect);
                VisualizationHelper.queueAdd(effect);
            } else if (!existing.matches(effect)) {
                VisualizationHelper.queueRemove(existing);
                effects.put(id, effect);
                VisualizationHelper.queueAdd(effect);
            }
        }

        // 移除已不存在的
        effects.keySet().removeIf(id -> {
            if (!present.contains(id)) {
                VisualizationHelper.queueRemove(effects.get(id));
                return true;
            }
            return false;
        });
    }

    /** 世界卸载/关闭时清空全部效果。同时重置客户端图缓存（防止旧世界残留
     *  数据在新世界版本号恰等时跳过渲染）。2026-08-22 追加清空网络颜色缓存
     *  （serviceColors 跨世界残留 → 新世界仍显示旧网络颜色外框）。 */
    public void clearAll() {
        ClientNetworkColorStore.clear(); // 独立渲染器（NetworkColorRenderer），无条件清
        if (effects.isEmpty() && !active) return;
        for (CryptandWireEffect effect : effects.values()) {
            VisualizationHelper.queueRemove(effect);
        }
        effects.clear();
        active = false;
        ClientWireGraphStore.reset();
    }

    /** 边 id（端点【身份】签名：块坐标+端子索引，去重/匹配用）。
     *  ⚠ 不能用解析后的位置（p1/p2）——位置会随客户端重解析/回退变化
     *  （区块加载/未加载、方块旋转），用位置当 key 会导致同一导线被反复
     *  重建。身份（pos#term）稳定不变。 */
    private static String edgeId(ClientWireGraphStore.ClientWire w) {
        return w.ax() + "," + w.ay() + "," + w.az() + "#" + w.aTerm()
                + ">" + w.bx() + "," + w.by() + "," + w.bz() + "#" + w.bTerm();
    }

    private CryptandWireEffect createEffect(ClientLevel level, ClientWireGraphStore.ClientWire wire) {
        try {
            // 下垂系数：图边 sag（放置/转换填 2.0）
            float dip = wire.sag() > 0 ? wire.sag() : 2f;
            // 导线保持原色（选中选框由 WireOutlineRenderer 独立渲染，不改导线本身）
            // 2026-08-23 物理化跟随：效果携带端点【身份】(pos#term)，
            // 视觉每 tick 按身份现算世界位置（渲染层跟随，不再依赖服务端快照）。
            return new CryptandWireEffect(level, wire.p1(), wire.p2(),
                    wire.ax(), wire.ay(), wire.az(), wire.aTerm(),
                    wire.bx(), wire.by(), wire.bz(), wire.bTerm(),
                    dip, wire.color(), wire.texture());
        } catch (Throwable ignored) {
            return null;
        }
    }
}

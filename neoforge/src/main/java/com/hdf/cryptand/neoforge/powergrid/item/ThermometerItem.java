/**
 * ===== 手持温度表 =====
 *
 * 模型使用万用表同款；【只能单线连接】——一次只绑一个目标（新目标覆盖旧目标）：
 *   - 普通右键电气方块 → 自动路由：该处有导线（自管图端点）→ 测【导线段】温度
 *     （WireThermalStore，同段统一温度）；否则测【元件内部】温度
 *   - 潜行右键 → 强制测【元件内部】温度（**只读 Cryptand DeviceThermalStore**）
 *     ⚠ 原版温度机制已【全部禁用】：原版 ThermalBehaviour 的 tick 被取消、
 *       工厂被拦截，其温度恒为环境值、永不推进 ⇒ 读它只会拿到冻结假值。
 *       服务端已移除该回退路径（见 ThermometerRequestPayload：没有自管温度模型的
 *       方块直接按环境温度新建 DeviceThermalStore 条目，绝不读原版那条已停推的路径）。
 *   - 探针线：单点（modeData.Pos = BlockWireEndpoint(pos,0)）→ 万用表同款一根线
 *
 * 读数链路：客户端每 10 tick 发 ThermometerRequestPayload（C2S）→ 服务端直接
 * 查温度存储（不做网络求解）→ ThermometerResponsePayload（S2C）→
 * ThermometerReadoutStore → getText 悬浮显示。
 */

package com.hdf.cryptand.neoforge.powergrid.item;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.client.wire.WireLookStore;
import com.hdf.cryptand.neoforge.powergrid.measurement.CryptandMeterItem;
import com.hdf.cryptand.neoforge.powergrid.measurement.ThermometerReadoutStore;
import com.hdf.cryptand.neoforge.powergrid.net.ThermometerRequestPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;

import java.util.UUID;

public class ThermometerItem extends CryptandMeterItem {

    /** 多行文本标记字符（PlacementOverlayMixin 拆分渲染时剥离） */
    private static final String MARKER = "\uE000";

    /** 请求节流（每玩家，每 10 tick 一次） */
    private static final java.util.Map<UUID, Long> LAST_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** getText 诊断节流（2026-08-20 排查温度表读不到温度） */
    private static volatile long GETTEXT_DBG_LAST;

    /** 目标 NBT 键（与万用表 Pos/Neg/EID 分开，避免原版断连逻辑清空） */
    private static final String KEY_TYPE = "TType";   // 0=元件 1=导线
    private static final String KEY_POS = "TPos";     // long（元件）
    private static final String KEY_EID = "TEID";     // int（导线）

    /* ============================================================================
     * 独立绑定存储（2026-09-13 根治"温度表右键电机显示未连接"）
     *
     * ⚠ 原实现把绑定（TType/TPos）写进原版 MultimeterItem 的 ModeData ——
     *   而原版的 setMode / deleteModeData / inventoryTick 会【整体清空】它，
     *   日志实证：`[ThermoText] getText called md=set hasType=false`
     *   （modeData 还在但已被重建成空标签）⇒ 绑定丢失 ⇒ 显示"未连接"。
     *
     * 现改为写进 ItemStack 的 CUSTOM_DATA 组件里的独立子标签 CryptandThermo，
     * 原版那套逻辑完全碰不到它。modeData 仍照写一份（仅供原版渲染器画探针线）。
     * ========================================================================== */
    private static final String ROOT = "CryptandThermo";

    /** 读独立绑定标签（无 → null） */
    private static CompoundTag readBind(ItemStack stack) {
        try {
            net.minecraft.world.item.component.CustomData cd = stack.get(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (cd == null) return null;
            CompoundTag all = cd.copyTag();
            return all.contains(ROOT) ? all.getCompound(ROOT) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 写/清独立绑定标签（bind=null → 清除） */
    private static void writeBind(ItemStack stack, CompoundTag bind) {
        try {
            net.minecraft.world.item.component.CustomData cd = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY);
            CompoundTag all = cd.copyTag();
            if (bind == null) all.remove(ROOT);
            else all.put(ROOT, bind);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(all));
        } catch (Throwable ignored) {
        }
    }

    /** 绑定一个方块目标（只写独立标签，modeData 由调用方按需补） */
    private static void bindBlock(ItemStack stack, BlockPos pos, int type) {
        CompoundTag bind = new CompoundTag();
        bind.putInt(KEY_TYPE, type);
        bind.putLong(KEY_POS, pos.asLong());
        bind.remove(KEY_EID);
        writeBind(stack, bind);
    }

    /** 绑定一个导线段目标（按端点方块定位） */
    private static void bindWire(ItemStack stack, BlockPos epPos) {
        CompoundTag bind = new CompoundTag();
        bind.putInt(KEY_TYPE, 1);
        bind.putLong(KEY_POS, epPos.asLong());
        bind.remove(KEY_EID);
        writeBind(stack, bind);
    }

    public ThermometerItem(Properties properties) {
        super(properties);
    }

    @Override
    public int maxProbeLines() { return 1; }

    @Override
    public void grabWire(ItemStack stack, WireLookStore.WireHit hit) {
        try {
            // ⚠ 2026-08-18 顺序修复：setMode 会【删除 ModeData】→ 必须在 setMode
            //   之后重写全部字段（TType/TPos 给测量链，X/Y/Z 给原版渲染器）。
            setMode(stack, 1); // 电流模式 → 原版渲染器读 X/Y/Z
            BlockPos pos = new BlockPos(hit.ax(), hit.ay(), hit.az());
            CompoundTag md = org.patryk3211.powergrid.equipment.multimeter.MultimeterItem
                    .getModeData(stack);
            if (md != null) {
                md.putInt(KEY_TYPE, 1);            // 导线段目标
                md.putLong(KEY_POS, pos.asLong()); // TPos
                if (hit.hitPoint() != null) {
                    md.putDouble("X", hit.hitPoint().x);
                    md.putDouble("Y", hit.hitPoint().y);
                    md.putDouble("Z", hit.hitPoint().z);
                    md.putDouble("HitX", hit.hitPoint().x);
                    md.putDouble("HitY", hit.hitPoint().y);
                    md.putDouble("HitZ", hit.hitPoint().z);
                }
                org.patryk3211.powergrid.equipment.multimeter.MultimeterItem
                        .saveModeData(stack, md);
            }
        } catch (Throwable ignored) {
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[Measure] thermo-grabWire pos=({},{},{}) hit=({},{},{}) mdTPos={}",
                hit.ax(), hit.ay(), hit.az(),
                hit.hitPoint() != null ? hit.hitPoint().x : Double.NaN,
                hit.hitPoint() != null ? hit.hitPoint().y : Double.NaN,
                hit.hitPoint() != null ? hit.hitPoint().z : Double.NaN,
                org.patryk3211.powergrid.equipment.multimeter.MultimeterItem
                        .getModeData(stack) != null
                        && org.patryk3211.powergrid.equipment.multimeter.MultimeterItem
                                .getModeData(stack).contains("TPos"));
    }

    // ========== 单线连接（右键绑定一个目标，新目标覆盖旧目标） ==========

    @Override
    public InteractionResult useOn(UseOnContext context) {
        try {
            Player player = context.getPlayer();
            if (player == null) return InteractionResult.PASS;
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            BlockEntity be = level.getBlockEntity(pos);
            ItemStack stack = context.getItemInHand();
            if (stack == null || stack.isEmpty()) return InteractionResult.PASS;

            if (be instanceof ElectricBlockEntity
                    || com.hdf.cryptand.neoforge.powergrid.device.Assemblers.classChainHas(be, "CordJunctionBlockEntity")) { // 2026-09-13 继承链
                // 普通右键 → 自动路由（服务端判断：该处有导线 → 测导线温度；
                // 否则测元件温度）；潜行右键 → 强制测元件内部温度。
                // 单点探针线连到该方块端子（自管导线无实体，探针线画到方块）。
                // ===== 2026-09-13 用户：温度计应该始终右键绑定，shift+右键也是，
                //  除非向空气 shift+右键这样是清除绑定 =====
                // ⇒ 这里【不再区分潜行】：右键方块一律绑定（统一自动路由：该处有
                //   导线且无温度模型 → 测导线段，否则测元件温度）。清除绑定挪到 use()。
                // ① 独立标签 = 真正的绑定（原版逻辑碰不到，不会被清）
                bindBlock(stack, pos, -1); // -1 = 自动路由
                // ② modeData 再写一份，只为让原版渲染器画出探针线（丢了也不影响读数）
                try {
                    CompoundTag md = new CompoundTag();
                    md.put("Pos", new BlockWireEndpoint(pos, 0).serialize());
                    md.remove("Neg");
                    saveModeData(stack, md);
                } catch (Throwable ignored) {
                }
                return InteractionResult.CONSUME;
            }
        } catch (Throwable ignored) {
        }
        return InteractionResult.PASS;
    }

    /**
     * 抓取导线（2026-08-18 自管适配：右键“看向的导线”→ 目标=导线段）。
     * 客户端调用（WireLookPicker 命中 → CryptandWireCutHandler）。
     * 服务端 ThermometerRequestPayload TARGET_WIRE 按端点方块定位导线段
     * （WireNetworkManager.segments → findSegmentKeyAtBlock）。
     * 单线连接：覆盖旧目标。
     */
    public static void grabWire(ItemStack stack, BlockPos pos) {
        try {
            if (stack == null || pos == null) return;
            CompoundTag md = MultimeterItem.getModeData(stack);
            if (md == null) {
                md = new CompoundTag();
                MultimeterItem.saveModeData(stack, md);
            }
            md.putInt(KEY_TYPE, 1);            // 1=导线（modeData 仅给原版渲染器）
            md.putLong(KEY_POS, pos.asLong());
            md.remove(KEY_EID);                // 自管无实体，清残留
            MultimeterItem.saveModeData(stack, md);
            bindWire(stack, pos);              // ① 独立标签 = 真正的绑定
        } catch (Throwable ignored) {
        }
    }

    /** 找接线端子关联的第一根可见导线实体 id（端点落在该接线端子方块上） */
    private static int findJunctionWireEid(Level level, BlockPos pos) {
        try {
            AABB box = new AABB(pos).inflate(3.0);
            for (BaseWireEntity w : level.getEntitiesOfClass(BaseWireEntity.class, box)) {
                if (endpointAt(w.getEndpoint1(), level, pos)
                        || endpointAt(w.getEndpoint2(), level, pos)) {
                    return w.getId();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean endpointAt(IWireEndpoint ep, Level level, BlockPos pos) {
        if (ep instanceof BlockWireEndpoint bep) return bep.getPos().equals(pos);
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                return BlockPos.containing(jep.getExactPosition(level)).equals(pos);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ========== 显示（悬浮文字） ==========

    @Override
    public Component getText(Level level, Player player, ItemStack stack) {
        try {
            // ⚠ 读【独立标签】（原版 ModeData 会被 setMode/inventoryTick 清空 →
            //   日志实证 md=set hasType=false → 误显示"未连接"）
            CompoundTag md = readBind(stack);
            if (md == null || !md.contains(KEY_TYPE)) {
                // 2026-08-20 排查"新世界温度表读不到温度"：节流打印 getText 调用
                // 但无目标 → 确认渲染系统是否调 getText（若长期无此日志 → getText
                // 没被调用 → 请求永不发送 → 服务端无响应）
                long dNow = System.currentTimeMillis();
                if (dNow - GETTEXT_DBG_LAST >= 5000) {
                    GETTEXT_DBG_LAST = dNow;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[ThermoText] getText called md={} hasType={}",
                            md == null ? "null" : "set",
                            md != null && md.contains(KEY_TYPE));
                }
                return hintText();
            }
            int type = md.getInt(KEY_TYPE);
            BlockPos pos = null;
            int eid = -1;
            String key;
            if (type == 0) {
                if (!md.contains(KEY_POS)) return hintText();
                pos = BlockPos.of(md.getLong(KEY_POS));
                key = "T0|" + pos;
            } else if (type == 1) {
                // 导线目标：自管（KEY_POS，无 EID）或旧 eid 兼容——服务端按端点方块
                // 定位导线段（自管）或按实体（旧）。
                if (!md.contains(KEY_POS)) return hintText();
                pos = BlockPos.of(md.getLong(KEY_POS));
                if (md.contains(KEY_EID)) {
                    eid = md.getInt(KEY_EID);
                    key = "T1|" + eid;
                } else {
                    key = "T1|" + pos;
                }
            } else {
                // 自动路由（type=-1）：优先元件，否则该处有导线 → 导线温度，否则元件回退
                if (!md.contains(KEY_POS)) return hintText();
                pos = BlockPos.of(md.getLong(KEY_POS));
                key = "T-1|" + pos;
                eid = -1;
            }

            // 每 10 tick 发送一次请求（客户端；getText 每帧调用，按游戏 tick 节流）
            if (level.isClientSide) {
                long now = level.getGameTime();
                long last = LAST_REQUEST.getOrDefault(player.getUUID(), 0L);
                if (now - last >= 10) {
                    ThermometerRequestPayload.sendToServer(key, type, pos, eid);
                    LAST_REQUEST.put(player.getUUID(), now);
                }
            }

            if (ThermometerReadoutStore.isFailed(key)) {
                return failText();
            }
            ThermometerReadoutStore.Readout ro = ThermometerReadoutStore.read(key);
            if (ro == null) {
                return readingText();
            }
            return tempText(ro.tempC, ro.label);
        } catch (Throwable ignored) {
        }
        return hintText();
    }

    /**
     * 对空气右键（2026-09-13 用户："除非向空气 shift+右键这样是清除绑定"）：
     * 潜行 → 清除绑定；不潜行 → 交回默认。清除时抹掉 Pos/Neg/TType/TPos/TEID，
     * 避免原版断连逻辑残留导致误判。
     */
    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            Level level, Player player, net.minecraft.world.InteractionHand hand) {
        try {
            if (player.isShiftKeyDown()) {
                ItemStack stack = player.getItemInHand(hand);
                writeBind(stack, null); // 清独立标签（真正的绑定）
                try {
                    CompoundTag md = getModeData(stack);
                    if (md != null) {
                        md.remove("Pos");
                        md.remove("Neg");
                        saveModeData(stack, md);
                    }
                } catch (Throwable ignored) {
                }
                return net.minecraft.world.InteractionResultHolder.success(stack);
            }
        } catch (Throwable ignored) {
        }
        return super.use(level, player, hand);
    }

    /** 未连接提示 */
    private Component hintText() {
        return Component.literal(MARKER)
                .append(Component.literal("温度表 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("未连接").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.literal("右键电气方块/导线：绑定并测温度；对空气潜行右键：清除绑定")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 读取中 */
    private Component readingText() {
        return Component.literal(MARKER)
                .append(Component.literal("[...] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("读取中…").withStyle(ChatFormatting.YELLOW));
    }

    /**
     * 读取不到（服务端 valid=false）。
     * 2026-09-13 用户：如果没有温度模型则显示【温度：无】—— 服务端在目标方块没有
     * 温度模型时回发 valid=false（不再新建环境温度条目伪装成 20°C），客户端据此显示。
     */
    private Component failText() {
        return Component.literal(MARKER)
                .append(Component.literal("温度 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("无").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\n"))
                .append(Component.literal("目标没有温度模型").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 温度文本（多行：温度 + 目标；颜色随温度升高 绿→黄→红） */
    private Component tempText(double tempC, String label) {
        ChatFormatting color;
        if (tempC >= 150) color = ChatFormatting.RED;
        else if (tempC >= 60) color = ChatFormatting.GOLD;
        else color = ChatFormatting.GREEN;
        // ⚠ 2026-08-30 字符修复（用户："温度计显示的字符有问题"）：° (U+00B0)
        // MC 默认字体不支持 → 显示 □；改 ASCII " C"。
        Component line1 = Component.literal("温度 ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f C", tempC)).withStyle(color));
        Component line2 = Component.literal(label != null ? label : "")
                .withStyle(ChatFormatting.DARK_GRAY);
        return Component.literal(MARKER)
                .append(line1)
                .append(Component.literal("\n"))
                .append(line2);
    }
}

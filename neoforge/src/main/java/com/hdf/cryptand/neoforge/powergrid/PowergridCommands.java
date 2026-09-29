/**
 * ===== PowerGrid 子包：设备/变压器参数模型 + 拓扑诊断命令（2026-09-07 子包隔离迁移） =====
 *
 * 原 CryptandNeoForge 命令族（transformer/device/graph/convert/export/schematic）
 * 迁入本类并经 {@code CryptandRegistries.registerSubcommand} 挂 /cryptand 根——
 * 删除本子包目录 → 命令整体消失，core 零编译引用。
 * 注册时机：PowergridEntry.commands()（ModConfigEvent 后：config 已加载）。
 */
package com.hdf.cryptand.neoforge.powergrid;

import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParameterStore;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParameters;
import com.hdf.cryptand.neoforge.powergrid.state.MotorParameters;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerParameters;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerParamStore;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** PowerGrid 子包命令（registerSubcommand 挂 /cryptand 根）。 */
public final class PowergridCommands {

    private PowergridCommands() {
    }

    /** 挂载本子包全部 /cryptand 子命令（PowergridEntry.commands 钩子调用）。 */
    public static void register() {
        CryptandRegistries.registerSubcommand("transformer", transformerCommand());
        CryptandRegistries.registerSubcommand("device", deviceCommand());
        // graph/convert/export/schematic（拓扑诊断 + 电路导出）
        PowergridExportCommands.register();
    }

    // ==================== transformer（变压器参数模型） ====================

    /** /cryptand transformer：变压器参数模型命令树（param/info/reset）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            transformerCommand() {
        return net.minecraft.commands.Commands.literal("transformer")
                .requires(s -> s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("param")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .then(net.minecraft.commands.Commands.argument("params",
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .greedyString())
                                        .executes(ctx -> transformerParam(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates
                                                        .BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "params"))))))
                .then(net.minecraft.commands.Commands.literal("info")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> transformerInfo(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(net.minecraft.commands.Commands.literal("reset")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> transformerReset(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));
    }

    /** /cryptand transformer param <pos> <k=v,...>：设置参数并调用基类接口 applyModel。 */
    private static int transformerParam(CommandSourceStack src,
                                        net.minecraft.core.BlockPos pos, String paramsText) {
        net.minecraft.world.level.Level level = src.getLevel();
        if (level == null) return 0;
        if (!(level.getBlockEntity(pos)
                instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity)) {
            src.sendFailure(Component.literal(
                    "该位置不是变压器: " + pos.toShortString()));
            return 0;
        }
        TransformerParameters tp =
                TransformerParamStore.get(pos);
        if (tp == null) tp = TransformerParameters.defaults();
        for (String part : paramsText.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            double v;
            try {
                v = Double.parseDouble(part.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            switch (k) {
                case "maxCoupling": case "coupling":      tp.maxCoupling = v; break;
                case "maxSelfInductance": case "maxL":     tp.maxSelfInductance = v; break;
                case "primaryResistance": case "rCp":      tp.primaryResistance = v; break;
                case "secondaryResistance": case "rCs":    tp.secondaryResistance = v; break;
                case "coreLossMultiplier": case "coreLoss": tp.coreLossMultiplier = (float) v; break;
                case "heatDissipation": case "heat":       tp.heatDissipation = (float) v; break;
                case "heatCapacity": case "heatCap":       tp.heatCapacity = (float) v; break;
                case "ambientK": case "ambient":           tp.ambientK = (float) v; break;
                case "maxTempK": case "maxTemp":           tp.maxTempK = (float) v; break;
                case "coreAl":                              tp.coreAl = (float) v; break;
                case "maxTurns": case "turns":             tp.maxTurns = (int) v; break;
                case "primaryWireRPerMeter": case "wireP": tp.primaryWireRPerMeter = (float) v; break;
                case "secondaryWireRPerMeter": case "wireS": tp.secondaryWireRPerMeter = (float) v; break;
                default:
                    src.sendFailure(Component.literal("未知参数: " + k));
                    return 0;
            }
        }
        // 基类接口：模型套用后更新魔法数字（自感上限/铜阻按铁心+导线换算）
        tp.applyModel();
        TransformerParamStore.put(pos, tp);
        try {
            com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager.get()
                    .markTopologyChanged();
        } catch (Throwable ignored) {
        }
        final TransformerParameters fTp = tp;
        src.sendSuccess(() -> Component.literal(
                "已更新 " + pos.toShortString() + " " + fTp.describe()), true);
        return 1;
    }

    /** /cryptand transformer info <pos>：显示当前参数。 */
    private static int transformerInfo(CommandSourceStack src,
                                       net.minecraft.core.BlockPos pos) {
        TransformerParameters tp =
                TransformerParamStore.get(pos);
        if (tp == null) {
            src.sendSuccess(() -> Component.literal(
                    pos.toShortString() + " 未自定义参数（使用默认魔法数字）"), false);
        } else {
            src.sendSuccess(() -> Component.literal(
                    pos.toShortString() + " " + tp.describe()), false);
        }
        return 1;
    }

    /** /cryptand transformer reset <pos>：清除参数，回默认魔法数字。 */
    private static int transformerReset(CommandSourceStack src,
                                        net.minecraft.core.BlockPos pos) {
        TransformerParamStore.remove(pos);
        try {
            com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager.get()
                    .markTopologyChanged();
        } catch (Throwable ignored) {
        }
        src.sendSuccess(() -> Component.literal(
                pos.toShortString() + " 已重置为默认魔法数字"), true);
        return 1;
    }

    // ==================== device（通用设备参数模型） ====================

    /** /cryptand device：通用设备参数模型命令树（param/info/reset）。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            deviceCommand() {
        return net.minecraft.commands.Commands.literal("device")
                .requires(s -> s.hasPermission(2))
                .then(net.minecraft.commands.Commands.literal("param")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .then(net.minecraft.commands.Commands.argument("params",
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .greedyString())
                                        .executes(ctx -> deviceParam(ctx.getSource(),
                                                net.minecraft.commands.arguments.coordinates
                                                        .BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                com.mojang.brigadier.arguments.StringArgumentType
                                                        .getString(ctx, "params"))))))
                .then(net.minecraft.commands.Commands.literal("info")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> deviceInfo(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                .then(net.minecraft.commands.Commands.literal("reset")
                        .then(net.minecraft.commands.Commands.argument("pos",
                                net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                        .blockPos())
                                .executes(ctx -> deviceReset(ctx.getSource(),
                                        net.minecraft.commands.arguments.coordinates
                                                .BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));
    }

    /** /cryptand device param <pos> <k=v,...>：按设备类型设置参数并调 applyModel。 */
    private static int deviceParam(CommandSourceStack src,
                                   net.minecraft.core.BlockPos pos, String paramsText) {
        net.minecraft.world.level.Level level = src.getLevel();
        if (level == null) return 0;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            src.sendFailure(Component.literal(
                    "该位置没有方块: " + pos.toShortString()));
            return 0;
        }
        DeviceParameters p =
                DeviceParameterStore.get(pos);
        if (p == null) {
            p = createDeviceParams(be);
            if (p == null) {
                src.sendFailure(Component.literal(
                        "该设备不支持参数模型: " + be.getClass().getSimpleName()));
                return 0;
            }
        }
        for (String part : paramsText.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            double v;
            try {
                v = Double.parseDouble(part.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (!setDeviceField(p, k, v)) {
                src.sendFailure(Component.literal("未知参数: " + k));
                return 0;
            }
        }
        p.applyModel(); // 基类接口：模型套用后更新魔法数字
        DeviceParameterStore.put(pos, p);
        try {
            com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager.get()
                    .markTopologyChanged();
        } catch (Throwable ignored) {
        }
        final DeviceParameters fP = p;
        src.sendSuccess(() -> Component.literal(
                "已更新 " + pos.toShortString() + " " + fP.describe()), true);
        return 1;
    }

    /** /cryptand device info <pos>：显示设备参数。 */
    private static int deviceInfo(CommandSourceStack src,
                                  net.minecraft.core.BlockPos pos) {
        DeviceParameters p =
                DeviceParameterStore.get(pos);
        if (p == null) {
            src.sendSuccess(() -> Component.literal(
                    pos.toShortString() + " 未自定义参数（使用默认魔法数字）"), false);
        } else {
            src.sendSuccess(() -> Component.literal(
                    pos.toShortString() + " " + p.describe()), false);
        }
        return 1;
    }

    /** /cryptand device reset <pos>：清除参数，回默认魔法数字。 */
    private static int deviceReset(CommandSourceStack src,
                                   net.minecraft.core.BlockPos pos) {
        DeviceParameterStore.remove(pos);
        try {
            com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager.get()
                    .markTopologyChanged();
        } catch (Throwable ignored) {
        }
        src.sendSuccess(() -> Component.literal(
                pos.toShortString() + " 已重置为默认魔法数字"), true);
        return 1;
    }

    /** 按设备类型创建默认参数模型。 */
    private static DeviceParameters createDeviceParams(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        if (be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
            return TransformerParameters.defaults();
        }
        String cls = com.hdf.cryptand.neoforge.powergrid.device.Assemblers.beKey(be); // 2026-09-13：自有 BE 子类 → 原版名
        if (cls.contains("Generator") || cls.contains("Motor")
                || cls.contains("Electromagnet") || cls.contains("Fan")
                || cls.contains("Commutator") || cls.contains("Winding")) {
            return new MotorParameters();
        }
        return null;
    }

    /** 反射按字段名设置参数（double/int/float）。 */
    private static boolean setDeviceField(DeviceParameters p,
                                          String key, double v) {
        try {
            for (Class<?> c = p.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(key);
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == double.class) f.setDouble(p, v);
                    else if (t == int.class) f.setInt(p, (int) v);
                    else if (t == float.class) f.setFloat(p, (float) v);
                    else return false;
                    return true;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}

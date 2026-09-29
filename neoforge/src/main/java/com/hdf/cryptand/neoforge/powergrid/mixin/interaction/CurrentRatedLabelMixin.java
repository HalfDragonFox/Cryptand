package com.hdf.cryptand.neoforge.powergrid.mixin.interaction;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * ===== 导线/设备 tooltip：「最大电流」→「额定电流」（2026-09-13 用户）=====
 *
 * 原版 tooltip 走 `Current.max(...)` → `Lang.translate("tooltip.current.max")`
 * → 实际 key `powergrid.tooltip.current.max` = 「最大电流」。
 *
 * ⚠ 为什么不能只在 `assets/cryptand/lang/zh_cn.json` 里覆盖该 key：
 *   Minecraft 合并所有资源包的同名 lang —— **后加载的 mod 覆盖先加载的**，
 *   而 mod 加载顺序不由我们决定（实测 powergrid 覆盖了 cryptand 的同名条目，
 *   tooltip 仍显示「最大电流」）。
 * ⇒ 改为在生成处【直接接管】：标签用 cryptand 自己的命名空间 key
 *   `cryptand.tooltip.current.rated`（没有任何 mod 会覆盖它），
 *   数值/单位格式完全复刻原实现（Lang.number + Unit.CURRENT），观感一致。
 */
@Mixin(targets = {
        "org.patryk3211.powergrid.electricity.info.Current"
}, remap = false)
public abstract class CurrentRatedLabelMixin {

    /** 精准拦截无 key 的 max(float, Player, List) 重载（另两个 max 重载不受影响） */
    @Inject(method = "max(FLnet/minecraft/world/entity/player/Player;Ljava/util/List;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$ratedMax(float value, Player player,
                                          List<Component> tooltip, CallbackInfo ci) {
        try {
            // 标签：cryptand 命名空间（不受 mod 间 lang 覆盖顺序影响）
            tooltip.add(Component.translatable("cryptand.tooltip.current.rated")
                    .withStyle(ChatFormatting.GRAY));
            // 数值 + 单位：完全复刻原版 Current.current(...) 的排版
            org.patryk3211.powergrid.utility.Lang.builder()
                    .add(Component.nullToEmpty(" "))
                    .add(org.patryk3211.powergrid.utility.Lang.number(value))
                    .add(Component.nullToEmpty(" "))
                    .add(org.patryk3211.powergrid.utility.Unit.CURRENT.get())
                    .style(ChatFormatting.RED)
                    .addTo(tooltip);
        } catch (Throwable ignored) {
        }
        ci.cancel(); // 已完整产出，不再走原实现
    }
}

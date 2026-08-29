/**
 * ===== 匝数设置屏（完全自管，2026-08-15）=====
 *
 * 复用 Create ValueSettingsScreen（滚轮屏）基础设施，但完全独立于原版
 * PowerGrid 的 TransformerWindingScreen/C2SPacket/WireConnection——
 * 匝数经我们的 WindingTurnsPayload（C2S）发送，服务端存到
 * CryptandWirePlacement 会话 → 第二次点击完成缠绕时反射写 BE 线圈 →
 * DeviceParamCache 预存 → 组装器 addTransformerElementFromCache 建模。
 *
 * 客户端 CLIENT 类：仅在 isClientSide 分支触达（服务端不加载，JVM 懒加载安全）。
 * 交互状态机与原版一致：按住右键 3 tick 后 ScreenOpener.open（保证右键松开
 * 前屏幕弹出，松开即 saveAndClose）。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import net.createmod.catnip.gui.ScreenOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

import java.util.List;

public final class CryptandWindingScreen extends ValueSettingsScreen {

    /** 交互 tick 状态（与原版一致：按住右键 3 tick 后开屏） */
    private static int interactionTicks = -1;
    private static CryptandWindingScreen screen = null;

    private final BlockPos pos;        // 变压器方块
    private final int terminal;        // 起点端子
    private final InteractionHand hand;
    private final int cap;             // 剩余可绕匝数 = 最大匝数 - 已缠绕匝数（对齐原版）

    private CryptandWindingScreen(BlockPos pos, int terminal, InteractionHand hand,
                                  int currentTurns, int maxTurns) {
        super(pos, makeBoard(maxTurns),
                new ValueSettingsBehaviour.ValueSettings(0, 1), // 滚轮从 1 开始（对齐原版）
                setting -> {}, 1000);
        this.pos = pos;
        this.terminal = terminal;
        this.hand = hand;
        // 限制：最多再绕 maxTurns - 已缠绕匝数（至少 1 匝；已满时 UI 显示 1，
        // 完成时 completeWinding 校验 existing+turns>maxT 会拦）
        this.cap = Math.max(1, maxTurns - currentTurns);
    }

    /** 开始交互：3 tick 后开屏（保持右键按住）。返回是否开始成功。 */
    public static boolean beginInteraction(BlockPos pos, int terminal,
                                           InteractionHand hand, int currentTurns, int maxTurns) {
        if (interactionTicks == -1) {
            interactionTicks = 0;
            screen = new CryptandWindingScreen(pos, terminal, hand, currentTurns, maxTurns);
            return true;
        }
        return false;
    }

    /** 每客户端 tick 驱动（ClientSoundTicker.onClientTick 调用）。
     *  与原版行为一致：必须【持续按住右键】3 tick 才开屏（对齐原版
     *  TransformerWindingScreen.clientTick）——玩家长按变压器本体触发；
     *  中途松开 → 取消开屏。开屏后 Create ValueSettingsScreen 自身在
     *  松开鼠标时 saveAndClose（发匝数包）。 */
    public static void clientTick() {
        if (interactionTicks == -1)
            return;
        if (++interactionTicks <= 3) {
            var mc = Minecraft.getInstance();
            if (!mc.options.keyUse.isDown()) {
                interactionTicks = -1;
                return;
            }
            if (interactionTicks == 3) {
                ScreenOpener.open(screen);
            }
        } else {
            interactionTicks = -1;
        }
    }

    private static ValueSettingsBoard makeBoard(int maxTurns) {
        return new ValueSettingsBoard(
                Component.translatable("gui.cryptand.winding.turns"),
                Math.max(1, maxTurns), 10,
                List.of(Component.literal("N")),
                new ValueSettingsFormatter(CryptandWindingScreen::formatSettings));
    }

    private static MutableComponent formatSettings(ValueSettingsBehaviour.ValueSettings settings) {
        return Component.literal(String.valueOf(Math.max(1, Math.abs(settings.value()))));
    }

    /** 滚轮值钳制到剩余可绕匝数（cap），对齐原版 TransformerWindingScreen */
    @Override
    public ValueSettingsBehaviour.ValueSettings getClosestCoordinate(int mouseX, int mouseY) {
        var value = super.getClosestCoordinate(mouseX, mouseY);
        if (value.value() > cap) {
            return new ValueSettingsBehaviour.ValueSettings(value.row(), cap);
        }
        return value;
    }

    // ===== cap 部分材质渲染（对齐原版 TransformerWindingScreen.renderBarCap）=====
    // 原版通过 ValueSettingsScreenMixin 注入 ValueSettingsScreen.renderWindow，对
    // TransformerWindingScreen 实例渲染 cap（不可选）部分的 brass_cover 材质。我们
    // 用 CryptandValueSettingsScreenMixin 对本屏实例做同样渲染（纹理引用原版资源）。

    /** 原版 cap 材质（powergrid:gui/brass_cover，视觉对齐原版） */
    public static final ResourceLocation CAP_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("powergrid", "gui/brass_cover");

    /** 绘制 cap 之后的条（不可选部分，brass_cover 材质）——复制原版逻辑 */
    public void renderBarCap(GuiGraphics graphics, int x, int y, int width, ValueSettingsBoard board) {
        int milestoneCount = board.maxValue() / board.milestoneInterval();
        if (milestoneCount <= 0) return;
        int milestoneWidth = width / milestoneCount;
        int scale = board.maxValue() > 128 ? 1 : 2;
        int capMilestone = cap / board.milestoneInterval();
        int capPos = milestoneWidth * capMilestone + 8 / scale;
        float capRemainder = (cap - capMilestone * board.milestoneInterval())
                / (float) board.milestoneInterval();
        int adjustedCap = (int) (capPos + (milestoneWidth - 7 + 1) * capRemainder);
        x += adjustedCap - 1;
        width -= adjustedCap - 2;
        if (width <= 2) return;
        int sideWidth = Math.min(3, width / 2);
        int centerWidth = width - sideWidth * 2;
        renderCropped(graphics, x, y, sideWidth, 10, 0, 0);
        renderCropped(graphics, x + sideWidth + centerWidth, y,
                Math.min(3, width - sideWidth), 10, 253, 0);
        for (int w = 0; w < centerWidth; w += 249) {
            int segLen = Math.min(249, centerWidth - w);
            renderCropped(graphics, x + w + sideWidth, y, segLen, 10, 3, 0);
        }
    }

    /** 渲染 cap 位置之后的刻度标记（brass_cover 小块）——复制原版逻辑 */
    public void renderBarCapMilestone(GuiGraphics graphics, int x, int y, int milestone, ValueSettingsBoard board) {
        int m = milestone * board.milestoneInterval();
        if (m > cap) {
            graphics.blit(CAP_TEXTURE, x, y + 1, 0, 11, 7, 8);
        }
    }

    /** 绘制带 uv 的纹理块（等价原版 renderCropped；blit 自动处理 256 缩放 uv） */
    public static void renderCropped(GuiGraphics graphics, int x, int y, int width, int height, int u, int v) {
        graphics.blit(CAP_TEXTURE, x, y, u, v, width, height);
    }

    @Override
    protected void saveAndClose(double mouseX, double mouseY) {
        var closest = getClosestCoordinate((int) mouseX, (int) mouseY);
        int turns = Math.max(1, Math.abs(closest.value()));
        if (turns > cap) turns = cap; // 保险：不超剩余可绕匝数
        // 匝数直达服务端会话（不走原版 C2S 包）
        WindingTurnsPayload.send(pos, terminal, turns);
        onClose();
    }
}

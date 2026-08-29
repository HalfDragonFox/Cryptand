/**
 * ===== 交流创造源配置 UI（LDLib2） =====
 *
 * 现代化界面：频率 / 幅值 / 相位 输入行 + 确认按钮。
 * 确认后发送 AcSourceUpdatePayload 到服务端更新 BE。
 * 2026-08-21 取消“频率当前点”（无时域，示波器游标无意义）。
 */

package com.hdf.cryptand.neoforge.core.ui;

import com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlock;
import com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.AcSourceUpdatePayload;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class AcSourceUi {

    private AcSourceUi() {}

    /**
     * 构建交流创造源配置 UI。
     *
     * @param holder LDLib2 方块 UI 持有者
     * @param be     方块实体（可能为 null → 默认值）
     */
    public static ModularUI build(BlockUIHolder holder, AcCreativeSourceBlockEntity be) {
        float freq = be != null ? be.getFrequencyHz() : 50f;
        float amp = be != null ? be.getAmplitude() : 230f;
        float phase = be != null ? be.getPhaseDegrees() : 0f;

        // 电压 / 电流单位由方块类型决定
        boolean voltage = true;
        BlockState bs = holder.blockState;
        if (bs.getBlock() instanceof AcCreativeSourceBlock b) {
            voltage = b.isVoltageSource();
        }
        String ampUnit = voltage ? "V" : "A";

        TextField freqField = CryptandUi.numberField(String.format("%.1f", freq));
        TextField ampField = CryptandUi.numberField(String.format("%.2f", amp));
        TextField phaseField = CryptandUi.numberField(String.format("%.0f", phase));

        BlockPos pos = holder.pos;
        Button confirm = CryptandUi.confirmButton("cryptand.menu.confirm", ev -> {
            try {
                float f = Float.parseFloat(freqField.getValue().trim());
                float a = Float.parseFloat(ampField.getValue().trim());
                float p = Float.parseFloat(phaseField.getValue().trim());
                // 2026-08-22 修复“UI 不立即显示”：确认后先在【客户端本地】镜像
                // 字段（服务端仍权威，稍后 sendBlockUpdated 覆盖——但 UI/方块
                // 立即读到新值，无需重进/等同步）。
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null && mc.level.getBlockEntity(pos)
                        instanceof AcCreativeSourceBlockEntity cbe) {
                    cbe.setFrequencyHz(f);
                    cbe.setAmplitude(a);
                    cbe.setPhaseDegrees(p);
                }
                AcSourceUpdatePayload.sendToServer(pos, f, a, p);
                if (mc.screen != null) mc.screen.onClose();
            } catch (NumberFormatException ignored) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen != null) mc.screen.onClose();
            }
        });

        // 2026-08-21 取消"频率当前点"（示波器游标）：本模组无时域（纯相量求解），
        // 无波形可扫描 → 频率当前点无意义。仅保留频率/幅值/相位配置。
        UIElement root = CryptandUi.panel(300,
                CryptandUi.title("cryptand.menu.ac_source"),
                CryptandUi.infoText(String.format("频率 %.1f Hz · 幅值 %.2f %s · 相位 %.0f°",
                        freq, amp, ampUnit, phase), 0xFF9AA5B1),
                CryptandUi.divider(),
                CryptandUi.inputRow("cryptand.menu.frequency", freqField),
                CryptandUi.inputRow("cryptand.menu.amplitude", ampField),
                CryptandUi.inputRow("cryptand.menu.phase", phaseField),
                CryptandUi.divider(),
                confirm);
        root.layout(l -> l.paddingAll(16));

        return new ModularUI(UI.of(root), holder.player);
    }
}

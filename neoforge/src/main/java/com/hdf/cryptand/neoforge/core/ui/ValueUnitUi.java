/**
 * ===== 元件数值+单位配置 UI（LDLib2，电容 / 电感通用） =====
 *
 * 现代化界面：数值输入框 + 单位下拉选择器（F/mF/µF/nF/pF 或 H/mH/µH）
 * + 确认按钮。确认后发送 ValueUnitUpdatePayload 到服务端更新 BE。
 * 打开时自动选择最合适单位显示当前值。
 */

package com.hdf.cryptand.neoforge.core.ui;

import com.hdf.cryptand.neoforge.powergrid.creative.ConfigurableComponent;
import com.hdf.cryptand.neoforge.powergrid.creative.ValueUnitUpdatePayload;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ValueUnitUi {

    /** 电容单位（F / mF / µF / nF / pF），电感单位（H / mH / µH） */
    private static final String[] CAP_UNITS = {"F", "mF", "µF", "nF", "pF"};
    private static final double[] CAP_MULT = {1, 1e-3, 1e-6, 1e-9, 1e-12};
    private static final String[] IND_UNITS = {"H", "mH", "µH"};
    private static final double[] IND_MULT = {1, 1e-3, 1e-6};

    private ValueUnitUi() {}

    /**
     * 构建电容/电感数值单位配置 UI。
     *
     * @param holder    LDLib2 方块 UI 持有者（pos/player/blockState）
     * @param capacitor true = 电容单位集；false = 电感单位集
     * @param be        方块实体（可能为 null → 显示默认值）
     */
    public static ModularUI build(BlockUIHolder holder, boolean capacitor, ConfigurableComponent be) {
        String[] units = capacitor ? CAP_UNITS : IND_UNITS;
        double[] mult = capacitor ? CAP_MULT : IND_MULT;

        float base = be != null ? be.getValueBase() : 0;
        int unitIndex = be != null ? be.getUnitIndex() : 0;
        unitIndex = Math.max(0, Math.min(units.length - 1, unitIndex));
        if (unitIndex >= units.length) unitIndex = 0;
        if (be != null && be.getUnitIndex() >= 0 && be.getUnitIndex() < units.length) {
            unitIndex = be.getUnitIndex();
        }

        // 当前值（换算到所选单位显示）
        double currentInUnit = base / mult[unitIndex];

        TextField valueField = CryptandUi.numberField(formatValue(currentInUnit));
        Selector<String> unitSelector = new Selector<>();
        AtomicInteger selectedUnit = new AtomicInteger(unitIndex);

        List<String> candidates = Arrays.asList(units);
        unitSelector.setCandidates(candidates);
        unitSelector.setSelected(units[unitIndex]);
        unitSelector.setOnValueChanged(v -> {
            int idx = candidates.indexOf(v);
            if (idx >= 0) selectedUnit.set(idx);
        });
        unitSelector.layout(l -> l.width(70).height(18));

        Label currentLabel = CryptandUi.infoText(
                String.format("当前: %s %s", formatValue(currentInUnit), units[unitIndex]),
                0xFFB8E0FF);

        BlockPos pos = holder.pos;
        Button confirm = CryptandUi.confirmButton("cryptand.menu.confirm", ev -> {
            try {
                double numeric = Double.parseDouble(valueField.getValue().trim());
                float baseValue = (float) (numeric * mult[selectedUnit.get()]);
                ValueUnitUpdatePayload.sendToServer(pos, baseValue, selectedUnit.get());
            } catch (NumberFormatException ignored) {
            }
            // 关闭当前屏幕（确认后退出）
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen != null) mc.screen.onClose();
        });

        UIElement row = new UIElement()
                .layout(l -> l.flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW)
                        .gapAll(6).alignItems(dev.vfyjxf.taffy.style.AlignItems.CENTER));
        row.addChild(valueField.layout(l -> l.flex(1)));
        row.addChild(unitSelector);

        UIElement root = CryptandUi.panel(280,
                CryptandUi.title("cryptand.menu.component_config"),
                currentLabel,
                row,
                CryptandUi.divider(),
                confirm);
        root.layout(l -> l.paddingAll(16));

        return new ModularUI(UI.of(root), holder.player);
    }

    /** 自动选单位：取"换算后 >= 1"的最大单位；全 <1 取最小单位 */
    private static int bestUnit(double baseValue, double[] multipliers) {
        double v = Math.abs(baseValue);
        for (int i = 0; i < multipliers.length; i++) {
            if (v >= multipliers[i]) return i;
        }
        return multipliers.length - 1;
    }

    /** 数值格式化（与旧版一致） */
    private static String formatValue(double v) {
        if (v == 0) return "0";
        if (Math.abs(v) >= 1e6 || (Math.abs(v) > 0 && Math.abs(v) < 1e-3)) {
            return String.format("%.6g", v);
        }
        String s = String.format("%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}

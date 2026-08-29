/**
 * ===== 可编程元件库选择 UI（LDLib2） =====
 *
 * 打开时向服务端请求库条目列表（LibraryListRequestPayload，仅客户端发送），
 * 响应到达后刷新按钮列表（ProgrammableUi.receiveLibraryList）。
 * 点击条目 → 解析参数（k=v,k=v）→ 发送 ProgrammableComponentUpdatePayload
 * 应用到方块并触发网络重建。现代化滚动列表 + 参数输入框。
 */

package com.hdf.cryptand.neoforge.core.ui;

import com.hdf.cryptand.neoforge.powergrid.creative.LibraryListRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.ProgrammableComponentBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.ProgrammableComponentUpdatePayload;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProgrammableUi {

    private static final int BUTTON_H = 18;
    private static final int MAX_VISIBLE = 7;

    /** 服务端返回的库条目列表（静态，响应包写入） */
    private static volatile List<String> libraryList = new ArrayList<>();
    /** 当前打开的列表容器 / 参数输入框（客户端） */
    private static volatile UIElement listContainer;
    private static volatile TextField paramField;
    private static volatile int scrollOffset;

    private ProgrammableUi() {}

    /**
     * 构建可编程元件库选择 UI。
     *
     * @param holder LDLib2 方块 UI 持有者
     * @param be     可编程元件 BE（获取端口数）
     */
    public static ModularUI build(BlockUIHolder holder, ProgrammableComponentBlockEntity be) {
        int portCount = be != null ? be.getPortCount() : 0;

        // 列表容器（竖排，可滚动）
        UIElement container = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.COLUMN).gapAll(2));
        UIElement scrollRow = new UIElement()
                .layout(l -> l.widthPercent(100).flexDirection(FlexDirection.ROW).gapAll(4));
        Button up = new Button().setText("↑");
        up.layout(l -> l.width(24).height(BUTTON_H));
        Button down = new Button().setText("↓");
        down.layout(l -> l.width(24).height(BUTTON_H));
        scrollRow.addChild(up);
        scrollRow.addChild(container.layout(l -> l.flex(1)));

        // 参数输入框
        TextField paramBox = CryptandUi.numberField("");
        paramBox.layout(l -> l.widthPercent(100).height(18));

        // 状态记录（客户端）
        boolean client = holder.player.level().isClientSide;
        if (client) {
            listContainer = container;
            paramField = paramBox;
            scrollOffset = 0;
            rememberPos(holder.pos);
        }
        up.setOnClick(ev -> {
            if (scrollOffset > 0) { scrollOffset--; refreshList(); }
        });
        down.setOnClick(ev -> {
            if (scrollOffset + Math.min(libraryList.size(), MAX_VISIBLE) < libraryList.size()) {
                scrollOffset++; refreshList();
            }
        });

        // 打开时请求库列表（仅客户端）
        if (client) {
            LibraryListRequestPayload.sendToServer();
            refreshList();
        }

        BlockPos pos = holder.pos;
        UIElement root = CryptandUi.panel(280,
                CryptandUi.title("cryptand.menu.library_title"),
                CryptandUi.infoText("端口数: " + portCount, 0xFF9AA5B1),
                scrollRow,
                CryptandUi.subtitle("cryptand.menu.library_params_hint"),
                paramBox,
                CryptandUi.divider());
        root.layout(l -> l.paddingAll(16));

        return new ModularUI(UI.of(root), holder.player);
    }

    /** 服务端响应回调（主线程执行）。 */
    public static void receiveLibraryList(List<String> names) {
        libraryList = new ArrayList<>(names);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(ProgrammableUi::refreshList);
        }
    }

    /** 重建列表按钮（根据 libraryList + scrollOffset） */
    private static void refreshList() {
        UIElement container = listContainer;
        if (container == null) return;
        container.clearAllChildren();
        int visible = Math.min(libraryList.size(), MAX_VISIBLE);
        for (int i = 0; i < visible; i++) {
            int idx = scrollOffset + i;
            if (idx >= libraryList.size()) break;
            String name = libraryList.get(idx);
            Button b = new Button().setText(name);
            b.layout(l -> l.widthPercent(100).height(BUTTON_H));
            b.setOnClick(ev -> onSelect(name));
            container.addChild(b);
        }
        if (libraryList.isEmpty()) {
            container.addChild(CryptandUi.infoText("库为空", 0xFF9AA5B1));
        }
    }

    private static void onSelect(String name) {
        Map<String, Double> params = new HashMap<>();
        String pv = paramField == null ? "" : paramField.getValue();
        for (String part : pv.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                try {
                    params.put(part.substring(0, eq).trim(),
                            Double.parseDouble(part.substring(eq + 1).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // 发送更新：pos 从当前打开的 UI 获取（简化：用最近一次 build 的 pos）
        BlockPos pos = lastPos;
        if (pos != null) {
            ProgrammableComponentUpdatePayload.sendToServer(pos, name, params);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen != null) mc.screen.onClose();
    }

    /** 最近一次 build 的方块坐标（用于发送更新） */
    private static volatile BlockPos lastPos;

    public static void rememberPos(BlockPos pos) {
        lastPos = pos;
    }
}

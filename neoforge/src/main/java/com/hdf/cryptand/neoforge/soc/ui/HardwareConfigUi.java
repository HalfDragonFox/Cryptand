package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.core.ui.CreateUi;
import com.hdf.cryptand.neoforge.soc.content.ConnectorItem;
import com.hdf.cryptand.neoforge.ui.ldlib.DesignDiagramView;
import com.hdf.cryptand.neoforge.soc.content.SocAssembledItem;
import com.hdf.cryptand.neoforge.soc.content.SocSpec;
import com.hdf.cryptand.neoforge.soc.link.SocDeviceInterfaces;
import com.hdf.cryptand.neoforge.soc.link.SocLinkStore;
import com.hdf.cryptand.neoforge.soc.link.SocPairingData;
import com.hdf.cryptand.soc.link.HwCanvasLayout;
import com.hdf.cryptand.soc.link.PortKind;
import com.hdf.cryptand.soc.link.SocLinkTable;
import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType.HeldItemUIHolder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 硬件接线页（连接器右键机箱打开，2026-09-29 用户定案：EDA 画布）=====
 *
 * <p>用户原话：「硬件连接器还是没改掉，取消绑定功能，仅右键机箱打开硬件配置页面」
 * 「怎么硬件连接到具体口需要右键机箱来对外设和内部扩展卡连接到实际虚拟机内的 RV 芯片中，
 * 如果是 lua 架构的 cpu 则直接无效」「是 UI 设计」。</p>
 *
 * <p>所以这一页现在是<b>一块 EDA 视口</b>（{@link DesignDiagramView}）：器件按列摆开
 * （芯片 → 扩展卡 → 屏/外设），边缘是彩色接口点，同色的点之间拉同色线。左键拖点连线、
 * 右键点线断开、拖器件摆位 —— <b>所有改动都发消息给服务端</b>，服务端落
 * {@link SocLinkStore}（世界链路表）后<b>重开面板</b>：客户端不自己算一套状态。</p>
 *
 * <p>{@code lua} 架构 CPU（或机箱里没有 Cryptand 芯片）⇒ 整页无效，如实说明。</p>
 */
public final class HardwareConfigUi {

    /** 画布尺寸（面板 640×480：标题栏 + 画布 + 按钮行） */
    private static final int CANVAS_W = 600;
    private static final int CANVAS_H = 350;

    private HardwareConfigUi() {
    }

    public static ModularUI build(HeldItemUIHolder holder) {
        final UIElement root = SocUiKit.panel(640, 480);
        final ItemStack stack = holder.itemStack;
        final String anchor = ConnectorItem.anchor(stack);
        final String arch = ConnectorItem.cpuArch(stack);
        root.addChildren(SocUiKit.titleBar(SocUiKit.title("⚙ 硬件接线画布"),
                anchor.isEmpty() ? SocUiKit.warn("还没打开过机箱：手持连接器右键一台机箱")
                        : SocUiKit.ok("机箱 " + anchor + " · CPU 架构=" + archText(arch))));

        if (!"cryptand".equals(arch)) {
            root.addChildren(SocUiKit.body(
                    SocUiKit.err("lua".equals(arch)
                            ? "该机箱装的是 Lua 架构 CPU ⇒ 硬件接线无效（没有 RV 芯片可接）"
                            : "该机箱里没有 Cryptand 芯片（RV32）⇒ 没有可接的 RV 芯片"),
                    SocUiKit.dim("把 cryptand:chip_cpu 插进机箱的 CPU 槽，再用连接器右键机箱。")));
            SocUiInspector.remember("hw_config", root);
            return new ModularUI(SocUiKit.createUI(root), holder.player);
        }

        final SocPairingData.Panel panel =
                SocPairingData.read(ConnectorItem.panel(stack), holder.player.level().registryAccess());
        if (panel == null || panel.devices().isEmpty()) {
            root.addChildren(SocUiKit.body(SocUiKit.dim("没有读到硬件：右键机箱重新打开一次。")));
            SocUiInspector.remember("hw_config", root);
            return new ModularUI(SocUiKit.createUI(root), holder.player);
        }

        // ===== 真实 EDA 视口（用户定案：像 AD 那样，器件能拖到视口外、网格随视口移动）=====
        // GraphView 自带网格 + 平移（左键空白/右键拖动）+ 滚轮缩放 + overflow 裁剪；世界坐标内容不被夹住。
        final CanvasContent content = contentOf(panel, ConnectorItem.layout(stack));
        final DesignDiagramView canvas = new DesignDiagramView(CANVAS_W, CANVAS_H);
        final int[] disp = displaySize(CANVAS_W, CANVAS_H);
        canvas.layout(l -> l.width(disp[0]).height(disp[1]));
        final UIElement canvasHost = new UIElement();
        canvasHost.layout(l -> l.width(disp[0]).height(disp[1]));
        canvasHost.addChildren(canvas);
        // 提示贴在 EDA 左下角（用户定案：消息都走左下角提示，不进聊天栏）
        final Label noticeLabel = SocUiKit.text("左键点拖点 = 连线 · 右键点线 = 断开 · 拖动器件 = 摆位 · 空白拖动 = 平移 · 滚轮 = 缩放");
        noticeLabel.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(5).bottom(4));
        noticeLabel.style(s -> s.background(new ColorRectTexture(0x80000000)));
        canvasHost.addChildren(noticeLabel);
        applyContent(canvas, content);
        // 客户端的三种交互 ⇒ 发消息给服务端（服务端落盘后回发 hw_sync 就地刷新）
        canvas.onLink((a, b) -> {
            final CompoundTag tag = new CompoundTag();
            tag.putString("A", a.nodeKey());
            tag.putString("AP", a.portId());
            tag.putString("B", b.nodeKey());
            tag.putString("BP", b.portId());
            canvas.sendMessage("hw_link", tag);
        });
        canvas.onUnlink(w -> {
            final CompoundTag tag = new CompoundTag();
            tag.putString("A", w.aNode());
            tag.putString("AP", w.aPort());
            tag.putString("B", w.bNode());
            tag.putString("BP", w.bPort());
            canvas.sendMessage("hw_unlink", tag);
        });
        canvas.onMoveNode((key, xy) -> {
            final CompoundTag tag = new CompoundTag();
            tag.putString("Layout", mergeLayout(ConnectorItem.layout(holder.itemStack), key, xy[0], xy[1]));
            canvas.sendMessage("hw_move", tag);
        });

        // ===== 服务端动作（客户端 sendMessage ⇒ 这里执行 ⇒ 重开面板刷新）=====
        canvas.onMessage("hw_link", tag -> {
            final ServerPlayer sp = serverPlayer(holder);
            if (sp == null) {
                return;
            }
            final BlockPos a = posOf(tag.getString("A"));
            final BlockPos b = posOf(tag.getString("B"));
            if (a == null || b == null) {
                syncToClient(sp, holder, canvas, "器件坐标坏了，连线取消");
                return;
            }
            final SocLinkTable.Result r = SocLinkStore.link(sp.level(),
                    a, devOf(tag.getString("A")), tag.getString("AP"),
                    b, devOf(tag.getString("B")), tag.getString("BP"));
            syncToClient(sp, holder, canvas, r.ok() ? "已连接：" + r.protocol().id() : r.reason());
        });

        canvas.onMessage("hw_unlink", tag -> {
            final ServerPlayer sp = serverPlayer(holder);
            if (sp == null) {
                return;
            }
            final BlockPos a = posOf(tag.getString("A"));
            final BlockPos b = posOf(tag.getString("B"));
            if (a == null || b == null) {
                return;
            }
            final boolean removed = SocLinkStore.unlink(sp.level(),
                    a, devOf(tag.getString("A")), tag.getString("AP"),
                    b, devOf(tag.getString("B")), tag.getString("BP"));
            syncToClient(sp, holder, canvas, removed ? "已断开" : "这条线已经不在了");
        });

        // 服务端动作后回发最新数据 ⇒ 客户端**就地**重建画面（不关界面、不闪）
        canvas.onMessage("hw_sync", tag -> {
            try {
                final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc == null || mc.level == null) {
                    return;      // 服务端那份不需要重建画面
                }
                final SocPairingData.Panel p = SocPairingData.read(
                        tag.getCompound("Panel"), mc.level.registryAccess());
                if (p == null) {
                    return;
                }
                final CanvasContent c = contentOf(p, tag.getString("Layout"));
                applyContent(canvas, c);
                final String notice = tag.getString("Notice");
                if (!notice.isEmpty()) {
                    SocUiKit.set(noticeLabel, notice, ChatFormatting.YELLOW);   // 操作结果显示在 EDA 左下角
                }
            } catch (Throwable ignored) {
                // 客户端未就绪：下一次刷新再来
            }
        });

        canvas.onMessage("hw_move", tag -> {
            final ServerPlayer sp = serverPlayer(holder);
            if (sp == null) {
                return;
            }
            ConnectorItem.setLayout(holder.itemStack, tag.getString("Layout"));
            // 物品栈的 CUSTOM_DATA 改完要重设一次，客户端那份才会跟着更新（OC 连接器同款做法）
            holder.itemStack.set(DataComponents.CUSTOM_DATA,
                    holder.itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
        });

        final Button refresh = new Button().setText(Component.literal("刷新"));
        refresh.layout(l -> l.width(70).height(16));
        refresh.style(s -> s.background(new ColorRectTexture(0xFF37474F)));
        refresh.setOnServerClick(e -> {
            final ServerPlayer sp = serverPlayer(holder);
            if (sp != null) {
                syncToClient(sp, holder, canvas, null);   // 就地刷新（不关界面、不闪）
            }
        });

        final Button write = new Button().setText(Component.literal("写入机箱芯片"));
        write.layout(l -> l.width(140).height(16));
        write.style(s -> s.background(new ColorRectTexture(0xFF1565C0)));
        write.setOnServerClick(e -> {
            final ServerPlayer sp = serverPlayer(holder);
            if (sp == null) {
                return;
            }
            syncToClient(sp, holder, canvas,
                    writeToChip(sp, ConnectorItem.anchor(holder.itemStack)));
        });

        root.addChildren(SocUiKit.body(canvasHost, SocUiKit.row(refresh, write)));

        // ===== 通道信息子页面（用户定案：右键点通道打开子页面）=====
        // 用 LDLib2 overlay（绝对定位 + 最后 addChild ⇒ 盖在画布与物品图标之上）；
        // 不用 Dialog（见 ui-ldlib2-overlay-and-pitfalls.md：Dialog 会重排父级且关不掉/重建子树）。
        final Label chDevice = SocUiKit.text("");
        final Label chPort = SocUiKit.text("");
        final Label chProto = SocUiKit.text("");
        final Label chLink = SocUiKit.text("");
        final Button chClose = new Button().setText(Component.literal("关闭"));
        chClose.layout(l -> l.width(50).height(16));
        final UIElement chCard = SocUiKit.group("通道信息", chDevice, chPort, chProto, chLink, chClose);
        chCard.layout(l -> l.width(240));
        final UIElement[] chLayer = new UIElement[1];
        chClose.setOnClick(e -> closeChannelCard(root, chLayer));
        canvas.onInspect(dot -> {
            closeChannelCard(root, chLayer);
            if (dot == null) {
                return;
            }
            final PortKind kind = dot.kind();
            SocUiKit.set(chDevice, "设备：" + labelOf(content, dot.nodeKey()), ChatFormatting.GRAY);
            SocUiKit.set(chPort, "端口：" + dot.portId()
                    + (kind == null ? "（未识别）" : "（" + kind.label() + "）"), ChatFormatting.GRAY);
            SocUiKit.set(chProto, "协议：" + (kind == null ? "-" : kind.protocol().id()), ChatFormatting.GRAY);
            SocUiKit.set(chLink, "状态：" + linkTextOf(content, dot), ChatFormatting.GRAY);
            chLayer[0] = CreateUi.overlay(root, chCard);
        });
        SocUiInspector.remember("hw_config", root);
        return new ModularUI(SocUiKit.createUI(root), holder.player);
    }

    /** 把面板数据铺到 EDA 视口上（引脚点由几何层按器件 + 端口现算）。 */
    private static void applyContent(DesignDiagramView view, CanvasContent c) {
        final List<HwCanvasLayout.Dot> dots = new ArrayList<>();
        for (final HwCanvasLayout.Node n : c.nodes()) {
            dots.addAll(HwCanvasLayout.dots(n, c.ports().getOrDefault(n.key(), List.of())));
        }
        view.setContent(c.nodes(), dots, c.wires(), c.icons());
    }

    /** 把某个器件的新位置并进位置编码（其它器件保持不变）。 */
    private static String mergeLayout(String encoded, String key, int x, int y) {
        final HashMap<String, int[]> pos = new HashMap<>(HwCanvasLayout.decodePositions(encoded));
        pos.put(key, new int[]{x, y});
        final List<HwCanvasLayout.Node> tmp = new ArrayList<>();
        for (final java.util.Map.Entry<String, int[]> e : pos.entrySet()) {
            tmp.add(new HwCanvasLayout.Node(e.getKey(), "", null, e.getValue()[0], e.getValue()[1], 1, 1));
        }
        return HwCanvasLayout.encodePositions(tmp);
    }

    private static String labelOf(CanvasContent c, String nodeKey) {
        for (final HwCanvasLayout.Node n : c.nodes()) {
            if (n.key().equals(nodeKey)) {
                return n.label();
            }
        }
        return nodeKey;
    }

    private static String linkTextOf(CanvasContent c, HwCanvasLayout.Dot d) {
        for (final HwCanvasLayout.Wire w : c.wires()) {
            final boolean a = w.aNode().equals(d.nodeKey()) && w.aPort().equals(d.portId());
            final boolean b = w.bNode().equals(d.nodeKey()) && w.bPort().equals(d.portId());
            if (!a && !b) {
                continue;
            }
            final String other = a ? w.bNode() : w.aNode();
            final String otherPort = a ? w.bPort() : w.aPort();
            return "已连接 → " + labelOf(c, other) + ":" + otherPort;
        }
        return "未连接";
    }

    /** 关掉通道信息子页面（overlay 层摘除，反复开关不在树里堆积）。 */
    private static void closeChannelCard(UIElement root, UIElement[] layerRef) {
        if (layerRef[0] != null) {
            CreateUi.closeOverlay(root, layerRef[0]);
            layerRef[0] = null;
        }
    }

    // ==================== 服务端动作 ====================

    /**
     * 把当前接线写进机箱里那颗 Cryptand 芯片的规格（{@code SocSpec.links}）。
     *
     * <p>用户定案：「CPU 信息是权威、所有配置信息在 CPU 里」。接线本身在世界链路表
     * （{@link SocLinkStore}）里，这里把它抄一份到芯片规格上，形如
     * {@code x|y|z|设备:端口=x|y|z|设备:端口}。</p>
     *
     * <p>⚠ 槽位按 {@link Container} 读（OC 机箱不注册 NeoForge 的 item handler capability：
     * 真机实测 capability 恒为 null，这正是以前"接线一直写不进芯片"的原因）。</p>
     */
    private static String writeToChip(ServerPlayer sp, String anchor) {
        final BlockPos pos = anchorPos(anchor);
        if (pos == null) {
            return anchor == null || anchor.isEmpty() ? "还没打开过机箱，无法写入" : "机箱坐标坏了：" + anchor;
        }
        final List<String> encoded = new ArrayList<>();
        for (final SocLinkTable.Link l : SocLinkStore.linksAt(sp.level(), pos)) {
            encoded.add(endpointText(l.a()) + "=" + endpointText(l.b()));
        }
        final BlockEntity be = sp.level().getBlockEntity(pos);
        if (!(be instanceof Container container)) {
            return "机箱没有物品槽（接线仍在世界链路表里）";
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            final ItemStack s = container.getItem(i);
            if (!(s.getItem() instanceof SocAssembledItem)) {
                continue;
            }
            final CompoundTag data = s.get(DataComponents.CUSTOM_DATA) == null
                    ? new CompoundTag() : s.get(DataComponents.CUSTOM_DATA).copyTag();
            final SocSpec spec = SocSpec.fromTag(data);
            if (spec == null) {
                continue;
            }
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(spec.withLinks(encoded).toTag()));
            return "已写入芯片（槽 " + i + "）：" + encoded.size() + " 条接线";
        }
        return "机箱里没找到 Cryptand 芯片（把 chip_cpu 插进 CPU 槽）";
    }

    /** 面板数据 → 画布内容（两端共用；客户端收到 {@code hw_sync} 时按同一函数重建）。 */
    private record CanvasContent(List<HwCanvasLayout.Node> nodes, List<HwCanvasLayout.Wire> wires,
                                 Map<String, List<String>> ports, Map<String, ItemStack> icons) {
    }

    private static CanvasContent contentOf(SocPairingData.Panel panel, String layoutEncoded) {
        final List<HwCanvasLayout.NodeSpec> specs = new ArrayList<>();
        final Map<String, List<String>> portsByNode = new LinkedHashMap<>();
        final Map<String, ItemStack> iconsByNode = new LinkedHashMap<>();
        for (final SocPairingData.Device d : panel.devices()) {
            final String key = nodeKey(d.pos(), d.deviceId());
            final List<String> portIds = new ArrayList<>();
            for (final SocDeviceInterfaces.Port p : d.ports()) {
                portIds.add(p.id());
            }
            specs.add(new HwCanvasLayout.NodeSpec(key, d.label(), kindOf(d.deviceId()), portIds));
            portsByNode.put(key, List.copyOf(portIds));
            if (!d.icon().isEmpty()) {
                iconsByNode.put(key, d.icon());     // 器件图标 = 这台设备对应的物品
            }
        }
        final List<HwCanvasLayout.Node> laid = HwCanvasLayout.applyPositions(
                HwCanvasLayout.autoLayout(specs, CANVAS_W, CANVAS_H), layoutEncoded, CANVAS_W, CANVAS_H);
        final List<HwCanvasLayout.Wire> wires = new ArrayList<>();
        for (final SocPairingData.Link l : panel.links()) {
            wires.add(new HwCanvasLayout.Wire(nodeKey(l.aPos(), l.aDev()), l.aPort(),
                    nodeKey(l.bPos(), l.bDev()), l.bPort(), PortKind.byId(l.aPort())));
        }
        return new CanvasContent(List.copyOf(laid), List.copyOf(wires), portsByNode, iconsByNode);
    }

    /**
     * 服务端动作之后：重新枚举硬件 + 写回物品 NBT，再把权威数据发给客户端<b>就地刷新</b>。
     *
     * <p>用户定案：「刷新最好不要关闭整个 UI 再打开，否则会有闪烁不好」——这里不再
     * {@code closeContainer()/openUI()}，画布自己 setContent 换内容。</p>
     */
    private static void syncToClient(ServerPlayer sp, HeldItemUIHolder holder, DesignDiagramView canvas,
                                     String notice) {
        final String anchor = ConnectorItem.anchor(holder.itemStack);
        final BlockPos pos = anchorPos(anchor);
        final CompoundTag out = new CompoundTag();
        if (pos != null) {
            final SocPairingData.Panel panel = SocPairingData.collect(sp.level(), pos);
            final CompoundTag panelTag = SocPairingData.write(panel, sp.level().registryAccess());
            ConnectorItem.setPanel(holder.itemStack, anchor, panelTag, ConnectorItem.cpuArch(holder.itemStack));
            out.put("Panel", panelTag);
        }
        out.putString("Layout", ConnectorItem.layout(holder.itemStack));
        if (notice != null && !notice.isEmpty()) {
            out.putString("Notice", notice);    // 提示走画布左下角，不再刷聊天栏（用户定案）
        }
        canvas.sendMessage("hw_sync", out);
    }

    private static ServerPlayer serverPlayer(HeldItemUIHolder holder) {
        return holder.player instanceof ServerPlayer sp ? sp : null;
    }

    // ==================== 器件 key 与坐标 ====================

    /** 器件 key：{@code x|y|z|deviceId}（同坐标的芯片与槽位卡靠 deviceId 区分）。 */
    private static String nodeKey(BlockPos pos, String deviceId) {
        return pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|" + deviceId;
    }

    private static BlockPos posOf(String key) {
        if (key == null) {
            return null;
        }
        final String[] p = key.split("\\|");
        if (p.length < 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String devOf(String key) {
        if (key == null) {
            return "";
        }
        final String[] p = key.split("\\|");
        return p.length >= 4 ? p[3] : "";
    }

    private static BlockPos anchorPos(String anchor) {
        if (anchor == null || anchor.isEmpty()) {
            return null;
        }
        final String[] p = anchor.split("\\|");
        if (p.length < 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String endpointText(SocLinkTable.Endpoint e) {
        return e.pos().x() + "|" + e.pos().y() + "|" + e.pos().z() + "|" + e.deviceId() + ":" + e.port();
    }

    /**
     * 器件种类 = **图标**（用户定案"每个外设的名称和图标对应起来"）：一种设备一个图标，
     * 名称来自 {@code SocPairingData.labelOf}（虚拟机 / 图形扩展卡 #n / 真彩屏 / OC 屏 / 键盘…）。
     */
    private static HwCanvasLayout.NodeKind kindOf(String deviceId) {
        final String base = SocDeviceInterfaces.baseId(deviceId);
        return switch (base) {
            case SocDeviceInterfaces.CHIP -> HwCanvasLayout.NodeKind.CHIP;
            case SocDeviceInterfaces.CARD_GPU -> HwCanvasLayout.NodeKind.CARD_GPU;
            case SocDeviceInterfaces.CARD_UART, SocDeviceInterfaces.CARD_PWM,
                 SocDeviceInterfaces.CARD_ADC, SocDeviceInterfaces.CARD_GPIO,
                 SocDeviceInterfaces.BOARD -> HwCanvasLayout.NodeKind.CARD;
            case SocDeviceInterfaces.DISK, SocDeviceInterfaces.FLASH -> HwCanvasLayout.NodeKind.DISK;
            case SocDeviceInterfaces.EEPROM -> HwCanvasLayout.NodeKind.EEPROM;
            case SocDeviceInterfaces.KEYBOARD -> HwCanvasLayout.NodeKind.KEYBOARD;
            case SocDeviceInterfaces.SCREEN -> HwCanvasLayout.NodeKind.SCREEN;
            default -> HwCanvasLayout.NodeKind.PERIPHERAL;
        };
    }

    /**
     * 画布的**显示**尺寸（等比缩放到面板可用区域；像素缓冲仍是 {@link #CANVAS_W}×{@link #CANVAS_H}）。
     *
     * <p>2026-09-29 首帧实测：面板被屏幕逻辑尺寸钳到 415×228（4K 屏 + GUI Scale 3），写死 600×350 的
     * 画布横向溢出、纵向被滚动 ⇒ 一眼看不全。这里等比缩小到装得下：{@code CanvasSurface.drawContents}
     * 器件元素与引脚点都在世界坐标里（见 {@link DesignDiagramView}），拖到视口外也不会被夹住。</p>
     *
     * <p>服务端构建时拿不到窗口尺寸 ⇒ 返回缓冲尺寸（服务端不渲染，尺寸无所谓）。</p>
     */
    private static int[] displaySize(int bufW, int bufH) {
        int guiW = 0;
        int guiH = 0;
        try {
            final var window = net.minecraft.client.Minecraft.getInstance().getWindow();
            guiW = window.getGuiScaledWidth();
            guiH = window.getGuiScaledHeight();
        } catch (Throwable ignored) {
            // 服务端 / 客户端未就绪：按缓冲尺寸
        }
        if (guiW <= 0 || guiH <= 0) {
            return new int[]{bufW, bufH};
        }
        final int bodyW = Math.max(200, guiW - 12 - 12 - 16);          // 面板钳制 - padding - 竖直滚动条
        final int bodyH = Math.max(110, guiH - 12 - 21 - 8 - 16 - 24);  // 标题栏 + 提示行 + 按钮行 + 间距
        final double scale = Math.min(1.0, Math.min((double) bodyW / bufW, (double) bodyH / bufH));
        return new int[]{Math.max(160, (int) (bufW * scale)), Math.max(100, (int) (bufH * scale))};
    }

    private static String archText(String arch) {
        return switch (arch) {
            case "cryptand" -> "Cryptand RV32（可接线）";
            case "lua" -> "Lua（接线无效）";
            case "other" -> "其它（非 RV）";
            default -> "未知";
        };
    }
}

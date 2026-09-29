package com.hdf.cryptand.soc.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ===== 硬件连接画布（EDA 风格）离线闸门（2026-09-29，纯 Java 零 MC）=====
 *
 * <p>跑法：{@code ./gradlew :common:runHwCanvasTest}</p>
 *
 * <p>钉住画布几何：① 分列（芯片最左 → 卡 → 屏/外设）与列内均分、列间距压缩不越界；
 * ② 布点方式（芯片四边引脚、卡左右两侧、外设单边）与按种类上色；③ 点放不下就放大框；
 * ④ 同色才能连；⑤ 折线走线首尾相接；⑥ 命中测试（点/器件/线）；⑦ 拖动夹在画布内；
 * ⑧ 位置编码往返（坏数据不抛、未知 key 忽略）。</p>
 */
public final class HwCanvasLayoutSelfTest {

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) {
        System.out.println("=== Hardware wiring canvas layout self test (EDA-style geometry, no MC) ===");
        kinds();
        autoLayout();
        pinsAndFit();
        dotPlacement();
        hitTests();
        wires();
        dragging();
        panning();
        encoding();
        System.out.println("=== HwCanvas " + passed + "/" + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void section(String s) {
        section = s;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FAIL] (" + section + ") " + what);
        }
    }

    private static List<HwCanvasLayout.NodeSpec> sample() {
        final List<HwCanvasLayout.NodeSpec> specs = new ArrayList<>();
        specs.add(new HwCanvasLayout.NodeSpec("chip", "虚拟机（RV32）", HwCanvasLayout.NodeKind.CHIP,
                List.of("rv", "usb1", "spi1", "uart1", "i2c1")));
        specs.add(new HwCanvasLayout.NodeSpec("card_gpu#1", "图形扩展卡 #1", HwCanvasLayout.NodeKind.CARD_GPU,
                List.of("rv", "dp1", "dp2", "dp3", "dp4")));
        specs.add(new HwCanvasLayout.NodeSpec("screen", "真彩屏", HwCanvasLayout.NodeKind.SCREEN, List.of("dp1")));
        specs.add(new HwCanvasLayout.NodeSpec("keyboard", "键盘", HwCanvasLayout.NodeKind.KEYBOARD,
                List.of("usb1")));
        specs.add(new HwCanvasLayout.NodeSpec("disk", "磁盘", HwCanvasLayout.NodeKind.DISK, List.of("usb1")));
        return specs;
    }

    // ------------------------------------------------------------------ 种类

    private static void kinds() {
        section("器件种类（名称 ↔ 图标）");
        check("每种设备一个图标（10 种，含设计器模块）", HwCanvasLayout.NodeKind.values().length == 10);
        check("芯片四边引脚", HwCanvasLayout.NodeKind.CHIP.pinsAround()
                && !HwCanvasLayout.NodeKind.CARD_GPU.pinsAround()
                && !HwCanvasLayout.NodeKind.SCREEN.pinsAround());
        check("卡两侧布点（输入左 / 输出右）",
                HwCanvasLayout.NodeKind.CARD_GPU.dotsBothSides()
                        && HwCanvasLayout.NodeKind.CARD.dotsBothSides()
                        && !HwCanvasLayout.NodeKind.CHIP.dotsBothSides());
        check("屏/键盘/存储/外设的点在左边",
                HwCanvasLayout.NodeKind.SCREEN.dotsOnLeft()
                        && HwCanvasLayout.NodeKind.KEYBOARD.dotsOnLeft()
                        && HwCanvasLayout.NodeKind.DISK.dotsOnLeft()
                        && HwCanvasLayout.NodeKind.EEPROM.dotsOnLeft()
                        && HwCanvasLayout.NodeKind.PERIPHERAL.dotsOnLeft()
                        && !HwCanvasLayout.NodeKind.CHIP.dotsOnLeft()
                        && !HwCanvasLayout.NodeKind.CARD_GPU.dotsOnLeft());
        check("分列：芯片 < 卡 < 外设 < 机箱",
                HwCanvasLayout.columnOf(HwCanvasLayout.NodeKind.CHIP) == 0
                        && HwCanvasLayout.columnOf(HwCanvasLayout.NodeKind.CARD_GPU) == 1
                        && HwCanvasLayout.columnOf(HwCanvasLayout.NodeKind.CARD) == 1
                        && HwCanvasLayout.columnOf(HwCanvasLayout.NodeKind.DISK) == 2
                        && HwCanvasLayout.columnOf(HwCanvasLayout.NodeKind.KEYBOARD) == 2
                        && HwCanvasLayout.columnOf(HwCanvasLayout.NodeKind.CASE) == 3
                        && HwCanvasLayout.columnOf(null) == 2);
        check("器件框统一尺寸（机箱除外）",
                HwCanvasLayout.baseW(HwCanvasLayout.NodeKind.CHIP) == HwCanvasLayout.NODE_W
                        && HwCanvasLayout.baseH(HwCanvasLayout.NodeKind.CHIP) == HwCanvasLayout.NODE_H
                        && HwCanvasLayout.baseW(HwCanvasLayout.NodeKind.CARD_GPU) == HwCanvasLayout.NODE_W
                        && HwCanvasLayout.baseH(HwCanvasLayout.NodeKind.SCREEN) == HwCanvasLayout.NODE_H
                        && HwCanvasLayout.baseW(HwCanvasLayout.NodeKind.CASE) == HwCanvasLayout.CASE_W);
    }

    // ------------------------------------------------------------------ 摆位

    private static void autoLayout() {
        section("摆位");
        final int W = 560;
        final int H = 360;
        final List<HwCanvasLayout.Node> nodes = HwCanvasLayout.autoLayout(sample(), W, H);
        check("器件数量保持", nodes.size() == 5);
        final Map<String, HwCanvasLayout.Node> byKey = new java.util.HashMap<>();
        for (final HwCanvasLayout.Node n : nodes) {
            byKey.put(n.key(), n);
        }
        final HwCanvasLayout.Node chip = byKey.get("chip");
        final HwCanvasLayout.Node card = byKey.get("card_gpu#1");
        final HwCanvasLayout.Node screen = byKey.get("screen");
        final HwCanvasLayout.Node kb = byKey.get("keyboard");
        final HwCanvasLayout.Node disk = byKey.get("disk");
        check("芯片在最左列", chip.x() < card.x());
        check("卡在芯片右侧", card.x() < screen.x());
        check("屏/键盘/存储同列（都是外设列）",
                kb.x() == screen.x() && disk.x() == screen.x());
        check("同列不重叠（间距 ≥ 框高）",
                Math.abs(kb.y() - screen.y()) >= screen.h() && Math.abs(disk.y() - kb.y()) >= kb.h());
        check("器件不出画布（右）", screen.x() + screen.w() <= W);
        check("器件不出画布（下）",
                Math.max(Math.max(kb.y() + kb.h(), screen.y() + screen.h()), disk.y() + disk.h()) <= H);
        check("器件不出画布（左/上）", chip.x() >= 0 && chip.y() >= 0);
        check("空列表安全", HwCanvasLayout.autoLayout(List.of(), W, H).isEmpty());

        // 卡类合并成一列（CARD_GPU 与 CARD 同列号 ⇒ 同列）
        final List<HwCanvasLayout.Node> mixed = HwCanvasLayout.autoLayout(List.of(
                new HwCanvasLayout.NodeSpec("a", "显卡", HwCanvasLayout.NodeKind.CARD_GPU, List.of("rv")),
                new HwCanvasLayout.NodeSpec("b", "串口卡", HwCanvasLayout.NodeKind.CARD, List.of("rv"))), W, H);
        check("显卡与扩展卡在同一列（同 columnOf 归并）",
                mixed.size() == 2 && mixed.get(0).x() == mixed.get(1).x()
                        && Math.abs(mixed.get(0).y() - mixed.get(1).y()) >= mixed.get(0).h());

        final List<HwCanvasLayout.Node> one = HwCanvasLayout.autoLayout(
                List.of(new HwCanvasLayout.NodeSpec("chip", "芯片", HwCanvasLayout.NodeKind.CHIP, List.of("rv"))), W, H);
        check("单器件竖直居中", one.get(0).y() > 0 && one.get(0).y() < H - one.get(0).h());
    }

    // ------------------------------------------------------------------ 引脚与放大框

    private static void pinsAndFit() {
        section("引脚容量与放大框");
        check("一条边的容量随边长增长", HwCanvasLayout.sideCapacity(48) < HwCanvasLayout.sideCapacity(120));
        check("极小框也至少放得下 1 个点", HwCanvasLayout.sideCapacity(20) == 1
                && HwCanvasLayout.sideCapacity(0) == 1);

        // 芯片：5 个点在 88×88 的四边里放得下 ⇒ 不放大
        final HwCanvasLayout.Node chipBase = new HwCanvasLayout.Node("chip", "芯片",
                HwCanvasLayout.NodeKind.CHIP, 0, 0, 0, 0);
        final HwCanvasLayout.Node chip5 = HwCanvasLayout.fitFor(chipBase, 5);
        check("芯片 5 点用基准框（64×64）",
                chip5.w() == HwCanvasLayout.NODE_W && chip5.h() == HwCanvasLayout.NODE_H);
        // 芯片：40 个点 ⇒ 四边放不下 ⇒ 放大（用户定案"点放不下就放大框"）
        final HwCanvasLayout.Node chip40 = HwCanvasLayout.fitFor(new HwCanvasLayout.Node("chip", "芯片",
                HwCanvasLayout.NodeKind.CHIP, 0, 0, chip5.w(), chip5.h()), 40);
        check("芯片 40 点 ⇒ 框被放大（" + chip40.w() + "×" + chip40.h() + "）",
                chip40.w() > chip5.w() && 4 * HwCanvasLayout.sideCapacity(chip40.w()) >= 40);
        // 单边外设：40 个点 ⇒ 高度必须撑到放得下
        final HwCanvasLayout.Node per40 = HwCanvasLayout.fitFor(new HwCanvasLayout.Node("p", "外设",
                HwCanvasLayout.NodeKind.PERIPHERAL, 0, 0, HwCanvasLayout.NODE_W, HwCanvasLayout.NODE_H), 40);
        check("单边外设 40 点 ⇒ 高度放大到放得下（高 " + per40.h() + "）",
                HwCanvasLayout.sideCapacity(per40.h()) >= 40 && per40.w() == HwCanvasLayout.NODE_W);
        // 卡：两侧容量
        final HwCanvasLayout.Node card0 = new HwCanvasLayout.Node("c", "显卡",
                HwCanvasLayout.NodeKind.CARD_GPU, 0, 0, 0, 0);
        final HwCanvasLayout.Node card9 = HwCanvasLayout.fitFor(card0, 9);
        check("卡 9 点 ⇒ 两侧放得下（高 " + card9.h() + "）",
                2 * HwCanvasLayout.sideCapacity(card9.h()) >= 9);
        check("点数 0 不改变尺寸", HwCanvasLayout.fitFor(chip5, 0).h() == chip5.h());
        final HwCanvasLayout.Node longName = HwCanvasLayout.fitFor(new HwCanvasLayout.Node("g", "图形扩展卡 #1",
                HwCanvasLayout.NodeKind.CARD_GPU, 0, 0, 0, 0), 5);
        check("名称长的器件外框加宽", longName.w() >= HwCanvasLayout.estimatedTextWidth("图形扩展卡 #1")
                + HwCanvasLayout.LABEL_PAD);
        check("名称宽度估算：中文比 ASCII 宽", HwCanvasLayout.estimatedTextWidth("虚拟机")
                > HwCanvasLayout.estimatedTextWidth("abc") && HwCanvasLayout.estimatedTextWidth(null) == 0);
        check("null 安全", HwCanvasLayout.fitFor(null, 3) == null);

        // autoLayout 会应用 fitFor：端口多的器件框更大
        final List<HwCanvasLayout.Node> fitted = HwCanvasLayout.autoLayout(List.of(
                new HwCanvasLayout.NodeSpec("many", "多口外设", HwCanvasLayout.NodeKind.PERIPHERAL,
                        List.of("usb1", "usb2", "usb3", "usb4", "usb5", "usb6", "usb7", "usb8")),
                new HwCanvasLayout.NodeSpec("one", "单口外设", HwCanvasLayout.NodeKind.PERIPHERAL,
                        List.of("usb1"))), 560, 360);
        check("同列按最大框对齐且被放大",
                fitted.get(0).h() == fitted.get(1).h()
                        && fitted.get(0).h() >= HwCanvasLayout.sideCapacity(fitted.get(0).h()) * HwCanvasLayout.PIN_GAP);
    }

    private static void dotPlacement() {
        section("接口点");
        final HwCanvasLayout.Node chip = new HwCanvasLayout.Node("chip", "芯片",
                HwCanvasLayout.NodeKind.CHIP, 10, 20, 64, 64);
        final List<String> chipPorts = List.of("rv", "usb1", "spi1", "uart1", "i2c1");
        final List<HwCanvasLayout.Dot> dots = HwCanvasLayout.dots(chip, chipPorts);
        check("点数 = 端口数", dots.size() == 5);
        check("每个端口恰好一个点",
                dots.stream().map(HwCanvasLayout.Dot::portId).distinct().count() == 5);
        check("芯片的点只落在四条边上",
                dots.stream().allMatch(d -> d.x() == chip.x() || d.x() == chip.x() + chip.w()
                        || d.y() == chip.y() || d.y() == chip.y() + chip.h()));
        final java.util.Set<String> sides = new java.util.HashSet<>();
        for (final HwCanvasLayout.Dot d : dots) {
            if (d.x() == chip.x()) {
                sides.add("L");
            } else if (d.x() == chip.x() + chip.w()) {
                sides.add("R");
            }
            if (d.y() == chip.y()) {
                sides.add("T");
            } else if (d.y() == chip.y() + chip.h()) {
                sides.add("B");
            }
        }
        check("四个方向都用上了（引脚式，不是挤在一边）", sides.size() >= 3);
        check("点在框内范围（不跑到框里）",
                dots.stream().allMatch(d -> d.x() >= chip.x() && d.x() <= chip.x() + chip.w()
                        && d.y() >= chip.y() && d.y() <= chip.y() + chip.h()));
        check("点按 id 上色", dots.stream().anyMatch(d -> d.portId().equals("rv") && d.kind() == PortKind.RV)
                && dots.stream().anyMatch(d -> d.portId().equals("usb1") && d.kind() == PortKind.USB));

        // 卡：rv 在左、dp 在右
        final HwCanvasLayout.Node card = new HwCanvasLayout.Node("card_gpu#1", "显卡",
                HwCanvasLayout.NodeKind.CARD_GPU, 200, 20, 64, 80);
        final List<HwCanvasLayout.Dot> cd = HwCanvasLayout.dots(card, List.of("rv", "dp1", "dp2"));
        final HwCanvasLayout.Dot rv = cd.stream().filter(d -> d.portId().equals("rv")).findFirst().orElseThrow();
        final HwCanvasLayout.Dot dp1 = cd.stream().filter(d -> d.portId().equals("dp1")).findFirst().orElseThrow();
        check("卡的输入（rv）在左边缘", rv.x() == card.x());
        check("卡的输出（dp）在右边缘", dp1.x() == card.x() + card.w());
        check("卡的输出在竖直方向均分", cd.stream().filter(d -> d.kind() == PortKind.DP)
                .map(HwCanvasLayout.Dot::y).distinct().count() == 2);

        final HwCanvasLayout.Node screen = new HwCanvasLayout.Node("screen", "屏",
                HwCanvasLayout.NodeKind.SCREEN, 200, 20, 64, 48);
        final List<HwCanvasLayout.Dot> sd = HwCanvasLayout.dots(screen, List.of("dp1"));
        check("屏的点在左边缘", sd.get(0).x() == screen.x());
        check("屏的点 = DP", sd.get(0).kind() == PortKind.DP);

        final List<HwCanvasLayout.Dot> unknown = HwCanvasLayout.dots(screen, List.of("xx9"));
        check("认不出的端口 = 灰点", unknown.get(0).kind() == null && unknown.get(0).argb() == 0xFF808080);
        check("空端口安全", HwCanvasLayout.dots(chip, List.of()).isEmpty()
                && HwCanvasLayout.dots(null, List.of("rv")).isEmpty());
    }

    // ------------------------------------------------------------------ 命中

    private static void hitTests() {
        section("命中测试");
        final HwCanvasLayout.Node chip = new HwCanvasLayout.Node("chip", "芯片",
                HwCanvasLayout.NodeKind.CHIP, 10, 20, 64, 64);
        final List<HwCanvasLayout.Dot> dots = HwCanvasLayout.dots(chip, List.of("rv", "usb1"));
        final HwCanvasLayout.Dot first = dots.get(0);
        check("点上命中自己", HwCanvasLayout.hitDot(dots, first.x(), first.y(), HwCanvasLayout.DOT_R) == first);
        check("点外不命中", HwCanvasLayout.hitDot(dots, first.x() + 20, first.y(), HwCanvasLayout.DOT_R) == null);
        check("两点之间取最近", HwCanvasLayout.hitDot(dots, first.x() + 5, first.y() + 1, 40) == first);

        check("器件内命中", HwCanvasLayout.hitNode(List.of(chip), 40, 40) == chip);
        check("器件外不命中", HwCanvasLayout.hitNode(List.of(chip), 200, 200) == null);
        check("命中测试容错 null", HwCanvasLayout.hitNode(null, 0, 0) == null
                && HwCanvasLayout.hitDot(null, 0, 0, 4) == null);
    }

    // ------------------------------------------------------------------ 连线

    private static void wires() {
        section("连线");
        check("同色可连（DP↔DP）", HwCanvasLayout.canConnect(PortKind.DP, PortKind.DP));
        check("设计期：通用白点可连任意协议", HwCanvasLayout.canConnectDesign(PortKind.GENERIC, PortKind.UART)
                && HwCanvasLayout.canConnectDesign(PortKind.SPI, PortKind.GENERIC)
                && HwCanvasLayout.canConnectDesign(PortKind.GENERIC, PortKind.GENERIC));
        check("设计期：非通用点仍须同色", HwCanvasLayout.canConnectDesign(PortKind.DP, PortKind.DP)
                && !HwCanvasLayout.canConnectDesign(PortKind.DP, PortKind.USB));
        check("通用点认色：gp1 / gp2 都是 GENERIC", PortKind.byId("gp1") == PortKind.GENERIC
                && PortKind.byId("gp2") == PortKind.GENERIC && PortKind.GENERIC.protocol() == null);
        check("异色不可连（DP↔USB）", !HwCanvasLayout.canConnect(PortKind.DP, PortKind.USB));
        check("未知点不可连", !HwCanvasLayout.canConnect(null, PortKind.DP)
                && !HwCanvasLayout.canConnect(PortKind.DP, null));

        final List<int[]> straight = HwCanvasLayout.route(10, 50, 90, 50);
        check("同 y = 一段直线", straight.size() == 1 && straight.get(0)[0] == 10 && straight.get(0)[2] == 90);
        final List<int[]> zig = HwCanvasLayout.route(10, 50, 90, 90);
        check("异 y = 三段折线", zig.size() == 3);
        check("折线首段从起点出发", zig.get(0)[0] == 10 && zig.get(0)[1] == 50);
        check("折线末段到终点", zig.get(2)[2] == 90 && zig.get(2)[3] == 90);
        check("折线首尾相接 1-2", zig.get(0)[2] == zig.get(1)[0] && zig.get(0)[3] == zig.get(1)[1]);
        check("折线首尾相接 2-3", zig.get(1)[2] == zig.get(2)[0] && zig.get(1)[3] == zig.get(2)[1]);

        final HwCanvasLayout.Dot a = new HwCanvasLayout.Dot("card", "dp1", PortKind.DP, 10, 50);
        final HwCanvasLayout.Dot b = new HwCanvasLayout.Dot("screen", "dp1", PortKind.DP, 90, 90);
        final List<HwCanvasLayout.Wire> wires = List.of(
                new HwCanvasLayout.Wire("card", "dp1", "screen", "dp1", PortKind.DP));
        final java.util.function.BiFunction<String, String, HwCanvasLayout.Dot> dotOf = (nk, p) -> {
            if ("card".equals(nk)) {
                return a;
            }
            if ("screen".equals(nk)) {
                return b;
            }
            return null;
        };
        check("线上（首段中点）命中", HwCanvasLayout.hitWire(wires, dotOf, 30, 50, 2f) != null);
        check("线上（竖段）命中", HwCanvasLayout.hitWire(wires, dotOf, (10 + 90) / 2, 70, 2f) != null);
        check("离线 30px 不命中", HwCanvasLayout.hitWire(wires, dotOf, 30, 20, 2f) == null);
        check("端点缺失不抛", HwCanvasLayout.hitWire(wires, (nk, p) -> null, 30, 50, 2f) == null);
        check("点到线距离：垂足（点在线段上 = 0）",
                Math.abs(HwCanvasLayout.segmentDistance(5, 0, 0, 0, 10, 0)) < 0.01f);
        check("点到线距离：垂距", Math.abs(HwCanvasLayout.segmentDistance(5, 5, 0, 0, 10, 0) - 5f) < 0.01f);
        check("点到线距离：端点裁剪", Math.abs(HwCanvasLayout.segmentDistance(-5, 0, 0, 0, 10, 0) - 5f) < 0.01f);
        check("退化线段（零长）安全", Math.abs(HwCanvasLayout.segmentDistance(3, 4, 0, 0, 0, 0) - 5f) < 0.01f);
    }

    // ------------------------------------------------------------------ 拖动

    private static void dragging() {
        section("拖动");
        final HwCanvasLayout.Node n = new HwCanvasLayout.Node("a", "A", HwCanvasLayout.NodeKind.CARD,
                100, 100, 64, 48);
        final HwCanvasLayout.Node moved = HwCanvasLayout.move(n, 20, -30, 560, 360);
        check("正常拖动 = 位移", moved.x() == 120 && moved.y() == 70);
        check("拖动保留 key/标签/种类",
                moved.key().equals("a") && moved.kind() == HwCanvasLayout.NodeKind.CARD);
        final HwCanvasLayout.Node clamped = HwCanvasLayout.move(n, 9999, 9999, 560, 360);
        check("右/下不出画布", clamped.x() == 560 - 64 && clamped.y() == 360 - 48);
        final HwCanvasLayout.Node clamped2 = HwCanvasLayout.move(n, -9999, -9999, 560, 360);
        check("左/上不出画布", clamped2.x() == 0 && clamped2.y() == 0);
    }

    // ------------------------------------------------------------------ 整体平移（空白处右键拖动）

    private static void panning() {
        section("整体平移布局");
        final List<HwCanvasLayout.Node> nodes = List.of(
                new HwCanvasLayout.Node("a", "A", HwCanvasLayout.NodeKind.CHIP, 40, 40, 64, 64),
                new HwCanvasLayout.Node("b", "B", HwCanvasLayout.NodeKind.CARD_GPU, 200, 60, 64, 48));
        final List<HwCanvasLayout.Node> moved = HwCanvasLayout.translateAll(nodes, 30, -20, 560, 360);
        check("每个器件位移相同（相对位置不变）",
                moved.get(0).x() == 70 && moved.get(0).y() == 20
                        && moved.get(1).x() == 230 && moved.get(1).y() == 40);
        check("尺寸/种类/标签不变", moved.get(0).h() == 64 && moved.get(1).kind() == HwCanvasLayout.NodeKind.CARD_GPU);
        final List<HwCanvasLayout.Node> clamped = HwCanvasLayout.translateAll(nodes, -9999, -9999, 560, 360);
        check("往左上推不会出画布", clamped.get(0).x() == 0 && clamped.get(0).y() == 0);
        final List<HwCanvasLayout.Node> clamped2 = HwCanvasLayout.translateAll(nodes, 9999, 9999, 560, 360);
        check("往右下推不会出画布",
                clamped2.get(1).x() + clamped2.get(1).w() <= 560 && clamped2.get(1).y() + clamped2.get(1).h() <= 360);
        check("原表不变（返回新列表）", nodes.get(0).x() == 40);
        check("空表/null 安全", HwCanvasLayout.translateAll(null, 5, 5, 560, 360).isEmpty()
                && HwCanvasLayout.translateAll(List.of(), 5, 5, 560, 360).isEmpty());
    }

    // ------------------------------------------------------------------ 持久化

    private static void encoding() {
        section("位置编码");
        final List<HwCanvasLayout.Node> nodes = List.of(
                new HwCanvasLayout.Node("chip", "芯片", HwCanvasLayout.NodeKind.CHIP, 12, 34, 64, 64),
                new HwCanvasLayout.Node("card_gpu#1", "显卡", HwCanvasLayout.NodeKind.CARD_GPU, 56, 78, 64, 48));
        final String enc = HwCanvasLayout.encodePositions(nodes);
        final Map<String, int[]> dec = HwCanvasLayout.decodePositions(enc);
        check("编码 ↔ 解码 往返", dec.get("chip")[0] == 12 && dec.get("chip")[1] == 34
                && dec.get("card_gpu#1")[0] == 56 && dec.get("card_gpu#1")[1] == 78);
        check("空编码 = 空表", HwCanvasLayout.decodePositions(null).isEmpty()
                && HwCanvasLayout.decodePositions("  ").isEmpty());
        check("坏条目跳过不抛", HwCanvasLayout.decodePositions("chip=1;bad;x=;y=1,2,3;ok=5,6").size() == 1
                && HwCanvasLayout.decodePositions("chip=1;bad;x=;y=1,2,3;ok=5,6").containsKey("ok"));

        final List<HwCanvasLayout.Node> live = HwCanvasLayout.autoLayout(sample(), 560, 360);
        final HwCanvasLayout.Node chipLive = live.stream().filter(x -> x.key().equals("chip"))
                .findFirst().orElseThrow();
        final List<HwCanvasLayout.Node> applied = HwCanvasLayout.applyPositions(live, "chip=7,9;deleted=1,1", 560, 360);
        check("套用位置：已知 key 用存的值",
                applied.stream().anyMatch(x -> x.key().equals("chip") && x.x() == 7 && x.y() == 9));
        check("套用位置：未知 key 忽略（设备已拆）", applied.size() == live.size());
        check("套用位置：实时标签/种类优先",
                applied.stream().anyMatch(x -> x.key().equals("keyboard")
                        && x.kind() == HwCanvasLayout.NodeKind.KEYBOARD));
        check("套用位置：越界被夹回",
                HwCanvasLayout.applyPositions(live, "chip=99999,99999", 560, 360)
                        .stream().anyMatch(x -> x.key().equals("chip")
                                && x.x() == 560 - chipLive.w() && x.y() == 360 - chipLive.h()));
        check("空 live 安全", HwCanvasLayout.applyPositions(null, "chip=1,1", 560, 360).isEmpty());
    }
}

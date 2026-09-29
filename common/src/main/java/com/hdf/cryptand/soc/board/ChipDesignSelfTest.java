package com.hdf.cryptand.soc.board;

import java.util.List;

/**
 * ===== 芯片蓝图设计器·设计态自测（纯 Java 零 MC，2026-09-29）=====
 *
 * <p>跑法：{@code ./gradlew :common:runChipDesignTest}</p>
 *
 * <p>钉住用户定案的规则：芯片通用点（白点）可加可删、模块拖入去重、模块端口 ↔ 通用点连线、
 * <b>一条线消耗 1 个资源点数</b>、超点数拒绝并给中文原因（面板把它打到画布左下角）、
 * 一个通用点只接一条线、删点/删模块/断线都要把点数退回来。</p>
 */
public final class ChipDesignSelfTest {

    private static int passed;
    private static int failed;
    private static String section = "";

    public static void main(String[] args) {
        System.out.println("=== Chip blueprint design layout self test (no MC) ===");
        basics();
        points();
        modules();
        connectAndBudget();
        teardown();
        budget();
        System.out.println("=== ChipDesign " + passed + "/" + failed + " ===");
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

    private static ChipDesignLayout.Module mod(String id, String label, String... ports) {
        return new ChipDesignLayout.Module(id, label, List.of(ports), 10, 10);
    }

    private static void basics() {
        section("基础");
        final ChipDesignLayout.State s = ChipDesignLayout.empty(4);
        check("空设计：预算 4、已用 0、剩余 4",
                s.budget() == 4 && ChipDesignLayout.used(s) == 0 && ChipDesignLayout.remaining(s) == 4);
        check("摘要文案含资源点数", ChipDesignLayout.summary(s).contains("芯片资源点数 0/4"));
        check("预算负数被夹到 0", ChipDesignLayout.empty(-3).budget() == 0);
        check("查不到的模块/点 = null", ChipDesignLayout.module(s, "x") == null
                && ChipDesignLayout.point(s, "gp1") == null);
    }

    private static void points() {
        section("芯片通用点");
        ChipDesignLayout.State s = ChipDesignLayout.empty(4);
        final ChipDesignLayout.Result r1 = ChipDesignLayout.addPoint(s);
        check("加第一个点 = gp1", r1.ok() && r1.next().points().size() == 1
                && r1.next().points().get(0).id().equals("gp1"));
        final ChipDesignLayout.Result r2 = ChipDesignLayout.addPoint(r1.next());
        check("再加一个 = gp2", r2.ok() && r2.next().points().get(1).id().equals("gp2"));
        final ChipDesignLayout.Result r3 = ChipDesignLayout.removePoint(r2.next(), "gp1");
        check("删点成功且只剩 gp2", r3.ok() && r3.next().points().size() == 1
                && r3.next().points().get(0).id().equals("gp2"));
        final ChipDesignLayout.Result r4 = ChipDesignLayout.addPoint(r3.next());
        check("删掉后重加复用最小空号 gp1",
                r4.ok() && ChipDesignLayout.point(r4.next(), "gp1") != null);
        check("删不存在的点 = 失败并给原因",
                !ChipDesignLayout.removePoint(r4.next(), "gp9").ok()
                        && ChipDesignLayout.removePoint(r4.next(), "gp9").reason().contains("没有这个通用点"));
        check("加成功不改动原状态", s.points().isEmpty());
    }

    private static void modules() {
        section("模块");
        ChipDesignLayout.State s = ChipDesignLayout.empty(4);
        final ChipDesignLayout.Result r1 = ChipDesignLayout.addModule(s, mod("m1", "UART", "usart1"));
        check("放入模块成功", r1.ok() && r1.next().modules().size() == 1);
        final ChipDesignLayout.Result r2 = ChipDesignLayout.addModule(r1.next(), mod("m1", "UART", "usart1"));
        check("同一个模块放两次被拒（并给中文原因）",
                !r2.ok() && r2.reason().contains("已经在画布上"));
        final ChipDesignLayout.Result r3 = ChipDesignLayout.moveModule(r1.next(), "m1", 120, 66);
        check("拖动改位置", r3.ok() && ChipDesignLayout.module(r3.next(), "m1").x() == 120
                && ChipDesignLayout.module(r3.next(), "m1").y() == 66);
        check("拖不存在的模块 = 失败", !ChipDesignLayout.moveModule(r1.next(), "m9", 1, 1).ok());
        check("模块端口固定（构造时拷贝）", ChipDesignLayout.module(r1.next(), "m1").ports().equals(List.of("usart1")));
    }

    private static void connectAndBudget() {
        section("连线与资源点数");
        ChipDesignLayout.State s = ChipDesignLayout.empty(2);
        s = ChipDesignLayout.addPoint(s).next();                     // gp1
        s = ChipDesignLayout.addPoint(s).next();                     // gp2
        s = ChipDesignLayout.addPoint(s).next();                     // gp3
        s = ChipDesignLayout.addModule(s, mod("m1", "UART", "usart1", "usart2", "usart3")).next();

        final ChipDesignLayout.Result c1 = ChipDesignLayout.connect(s, "m1", "usart1", "gp1");
        check("连第一条线成功（1/2）", c1.ok() && ChipDesignLayout.used(c1.next()) == 1
                && "gp1".equals(ChipDesignLayout.pointOf(c1.next(), "m1", "usart1")));
        final ChipDesignLayout.Result c2 = ChipDesignLayout.connect(c1.next(), "m1", "usart2", "gp2");
        check("连第二条线成功（2/2）", c2.ok() && ChipDesignLayout.used(c2.next()) == 2
                && ChipDesignLayout.remaining(c2.next()) == 0);
        final ChipDesignLayout.Result c3 = ChipDesignLayout.connect(c2.next(), "m1", "usart3", "gp3");
        check("超过资源点数 ⇒ 拒绝", !c3.ok() && c3.next().wires().size() == 2);
        check("拒绝原因说清是资源点数（面板左下角就用它）",
                c3.reason().contains("芯片资源点数不够") && c3.reason().contains("2/2"));
        check("被拒后状态不变（调用方不用回滚）", ChipDesignLayout.pointOf(c3.next(), "m1", "usart3") == null);

        final ChipDesignLayout.Result idem = ChipDesignLayout.connect(c2.next(), "m1", "usart1", "gp1");
        final ChipDesignLayout.State withM2 =
                ChipDesignLayout.addModule(c2.next(), mod("m2", "SPI", "spi1")).next();
        final ChipDesignLayout.Result owner = ChipDesignLayout.connect(withM2, "m2", "spi1", "gp1");
        check("通用点被占用 ⇒ 拒绝", !owner.ok() && owner.reason().contains("已经被"));
        check("端口不存在 ⇒ 拒绝", !ChipDesignLayout.connect(s, "m1", "nope", "gp1").ok()
                && ChipDesignLayout.connect(s, "m1", "nope", "gp1").reason().contains("没有端口"));
        check("通用点不存在 ⇒ 拒绝", !ChipDesignLayout.connect(s, "m1", "usart1", "gp9").ok()
                && ChipDesignLayout.connect(s, "m1", "usart1", "gp9").reason().contains("没有通用点"));
        check("通用点不存在 ⇒ 拒绝", !ChipDesignLayout.connect(s, "m1", "usart1", "gp9").ok()
                && ChipDesignLayout.connect(s, "m1", "usart1", "gp9").reason().contains("没有通用点"));
        check("模块不存在 ⇒ 拒绝", !ChipDesignLayout.connect(s, "mX", "usart1", "gp1").ok());
        check("换点：同一端口改连另一个点仍只算一条线", ChipDesignLayout.used(
                ChipDesignLayout.connect(c1.next(), "m1", "usart1", "gp2").next()) == 1);
    }

    private static void teardown() {
        section("拆除");
        ChipDesignLayout.State s = ChipDesignLayout.empty(3);
        s = ChipDesignLayout.addPoint(s).next();
        s = ChipDesignLayout.addModule(s, mod("m1", "UART", "usart1")).next();
        s = ChipDesignLayout.connect(s, "m1", "usart1", "gp1").next();
        check("连线后已用 1", ChipDesignLayout.used(s) == 1);
        final ChipDesignLayout.Result d = ChipDesignLayout.disconnect(s, "m1", "usart1");
        check("断线退回点数", d.ok() && ChipDesignLayout.used(d.next()) == 0);
        check("断不存在的线 ⇒ 失败", !ChipDesignLayout.disconnect(s, "m1", "usart2").ok());
        final ChipDesignLayout.Result rm = ChipDesignLayout.removeModule(s, "m1");
        check("拿走模块连带删线（点数退回）", rm.ok() && rm.next().modules().isEmpty()
                && ChipDesignLayout.used(rm.next()) == 0);
        final ChipDesignLayout.Result rp = ChipDesignLayout.removePoint(s, "gp1");
        check("删通用点连带删线（点数退回）", rp.ok() && rp.next().points().isEmpty()
                && ChipDesignLayout.used(rp.next()) == 0);
    }

    private static void budget() {
        section("改预算");
        ChipDesignLayout.State s = ChipDesignLayout.empty(2);
        s = ChipDesignLayout.addPoint(s).next();
        s = ChipDesignLayout.addModule(s, mod("m1", "UART", "usart1")).next();
        s = ChipDesignLayout.connect(s, "m1", "usart1", "gp1").next();
        check("预算降到 1 仍装得下", ChipDesignLayout.setBudget(s, 1).ok());
        final ChipDesignLayout.Result low = ChipDesignLayout.setBudget(s, 0);
        check("预算降到 0 装不下 ⇒ 拒绝并说明要拆几条",
                !low.ok() && low.reason().contains("先拆掉 1 条"));
        check("提高预算成功", ChipDesignLayout.setBudget(s, 8).ok()
                && ChipDesignLayout.setBudget(s, 8).next().budget() == 8);
    }
}

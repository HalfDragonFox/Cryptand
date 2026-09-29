package com.hdf.cryptand.neoforge.soc.link;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ===== OC 机器反射（软依赖：不许编译期引用 OC 类型）=====
 *
 * <p>⚠ 2026-09-29 修的一个真 bug：以前判定"这个方块实体是不是机器"用的是
 * {@code Class.forName("li.cil.oc.api.machine.Machine").isInstance(be)} —— <b>永远为假</b>：
 * {@code Machine} 是 OC 里那个<b>机器对象</b>（{@code li.cil.oc.server.machine.Machine}），
 * 方块实体（如 {@code li.cil.oc.common.blockentity.Case}）并不实现它 ⇒ 机箱从来认不出来，
 * 连接器右键机箱只会说"这里不是机器"。</p>
 *
 * <p>正确判据（读 OC 源码核实）：机箱/服务器/机架的方块实体走
 * {@code traits.Environment → li.cil.oc.api.network.EnvironmentHost}，并通过
 * {@code li.cil.oc.api.internal.Case → li.cil.oc.api.machine.MachineHost#machine()} 暴露机器对象；
 * 架构在 {@code machine().architecture()}。</p>
 */
public final class OcMachineReflect {

    private static final String ENV_HOST = "li.cil.oc.api.network.EnvironmentHost";
    private static final String MACHINE_HOST = "li.cil.oc.api.machine.MachineHost";

    private OcMachineReflect() {
    }

    /** 是不是一台机器（实现 EnvironmentHost 或 MachineHost 的方块实体） */
    public static boolean isMachineHost(Object be) {
        if (be == null) {
            return false;
        }
        return walksTo(be.getClass(), ENV_HOST, new HashSet<>(), 0)
                || walksTo(be.getClass(), MACHINE_HOST, new HashSet<>(), 0);
    }

    /** 机器对象（{@code machine()}）；取不到返回 null */
    public static Object machine(Object be) {
        if (be == null) {
            return null;
        }
        try {
            return be.getClass().getMethod("machine").invoke(be);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 机箱里那颗 CPU 的架构：{@code cryptand}（我们的 RV 芯片，可接线）/ {@code lua}（原版 Lua，接线无效）/
     * {@code other} / {@code none}（取不到）。
     */
    public static String cpuArch(Object be) {
        final Object machine = machine(be);
        if (machine == null) {
            return "none";
        }
        try {
            final Object arch = machine.getClass().getMethod("architecture").invoke(machine);
            if (arch == null) {
                return "none";
            }
            final String name = arch.getClass().getName();
            if (name.startsWith("com.hdf.cryptand.")) {
                return "cryptand";
            }
            return name.toLowerCase(Locale.ROOT).contains("lua") ? "lua" : "other";
        } catch (Throwable t) {
            return "none";
        }
    }

    private static boolean walksTo(Class<?> c, String target, Set<Class<?>> seen, int depth) {
        if (c == null || depth > 6 || !seen.add(c)) {
            return false;
        }
        if (c.getName().equals(target)) {
            return true;
        }
        for (final Class<?> i : c.getInterfaces()) {
            if (walksTo(i, target, seen, depth + 1)) {
                return true;
            }
        }
        return walksTo(c.getSuperclass(), target, seen, depth + 1);
    }
}

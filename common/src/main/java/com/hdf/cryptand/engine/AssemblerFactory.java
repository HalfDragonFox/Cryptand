package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;

import java.util.List;

/**
 * ===== 引擎组装器工厂（2026-08-30 用户：引擎独立完整电路仿真核心） =====
 *
 * 【每个实例一个的线程安全工厂】：创建预制组装器（灯/加热器等——引擎内置）
 * 或自定义组装器 + 对应绑定内容。引擎可单独拿出来作为软件仿真器使用。
 *
 * 注册语义（用户规范）：
 *   - 每次创建返回的是一个【网络】，被加入【网络列表】——注册为传入列表；
 *   - 只有一个电气设备 → 只需传入此电气设备【绑定类】+【对应组装器】——
 *     天然实现"放下电气设备后自己就是一个网络"；
 *   - 多个电气设备包含导线段 → 一起注册即可——返回网络天然包含此批设备与导线。
 */
public final class AssemblerFactory {

    /** 注册目标（网络上下文注册——用户："注册为传入列表"；每次创建返回网络
     *  （含在 NetworkContext 中）→ 注册上下文——具体实例（SimulationCore）
     *  传入其隔离存储的注册回调（NetworkRegistry.registerCtx）。 */
    private final java.util.function.Consumer<NetworkContext<?>> registrar;

    /** 设备键自增（ctx 组装器/绑定表键——引擎 id） */
    private final java.util.concurrent.atomic.AtomicLong keyCounter =
            new java.util.concurrent.atomic.AtomicLong(0);

    public AssemblerFactory(java.util.List<Network> networks) {
        this(ctx -> networks.add(ctx.network));
    }

    public AssemblerFactory(java.util.function.Consumer<NetworkContext<?>> registrar) {
        this.registrar = registrar == null ? c -> { } : registrar;
    }

    /**
     * 注册单个电气设备（绑定 + 组装器 + 端子数）→ 返回新网络。
     * 天然单设备网络（放下电气设备后自己就是一个网络）。
     *
     * @param binding   绑定器（可 null = 不绑定——引擎跳过）
     * @param assembler 组装器（设备的完整虚拟模型）
     * @param terminals 设备端子数（默认 2）
     */
    public Network registerDevice(Binding binding, Assembler assembler, int terminals) {
        Network net = new Network();
        // ⚠ 2026-08-30 引擎网络上下文（完整求解：图+组装器+绑定——NetworkSolver）
        NetworkContext<Object> ctx = new NetworkContext<>(net, 0);
        GraphBuilder gb = new NetGraphBuilder(net, terminals);
        try {
            assembler.build(gb);           // 组装器构建元件/模型（用端子节点）
        } catch (Throwable ignored) {
        }
        Object key = keyCounter.incrementAndGet();
        ctx.assemblers.put(key, assembler);
        if (binding != null) {
            ctx.bindings.put(key, binding);
            try {
                binding.onBound(compositeOf(net)); // 绑定（虚拟元件 ↔ 实际模型）
            } catch (Throwable ignored) {
            }
        }
        registrar.accept(ctx);             // 注册上下文（含网络——传入列表）
        return net;
    }

    /**
     * 注册一批（设备 + 导线段）→ 返回新网络（天然包含此批设备与导线——连通）。
     * 多个电气设备包含导线段：一起注册即可。
     *
     * @param devices 设备注册（绑定 + 组装器 + 端子数）
     * @param wires   导线段（两端节点索引 + 电阻）
     */
    public Network registerBatch(List<DeviceReg> devices, List<WireReg> wires) {
        Network net = new Network();
        NetworkContext<Object> ctx = new NetworkContext<>(net, 0);
        try {
            // ① 设备：build（端子动态分配——记录端子节点——入 ctx）
            java.util.List<NetGraphBuilder> builders = new java.util.ArrayList<>();
            for (DeviceReg d : devices) {
                if (d == null || d.assembler == null) continue;
                Object key = keyCounter.incrementAndGet();
                NetGraphBuilder gb = new NetGraphBuilder(net, d.terminals);
                d.assembler.build(gb);
                builders.add(gb);
                ctx.assemblers.put(key, d.assembler);
                if (d.binding != null) {
                    ctx.bindings.put(key, d.binding);
                    try {
                        d.binding.onBound(compositeOf(net));
                    } catch (Throwable ignored) {
                    }
                }
            }
            // ② 导线段（连设备端子——【导线组拓扑】）：导线通过计算加入或者
            // 新建导线组（用户：网络从存储导线改成导线组——组 = 导线组装器，
            // 含多段绑定 + 总电阻 + 共享温度模型）。拓扑：与现有组任一段共享
            // 端点 → 加入该组（总电阻 Σ）；否则新建组。
            for (WireReg w : wires) {
                if (w == null || w.assembler == null) continue;
                int na = -1, nb = -1;
                if (w.deviceA >= 0 && w.deviceA < builders.size()) {
                    na = builders.get(w.deviceA).terminalNode(w.termA);
                }
                if (w.deviceB >= 0 && w.deviceB < builders.size()) {
                    nb = builders.get(w.deviceB).terminalNode(w.termB);
                }
                if (na < 0 || nb < 0 || na == nb) continue;
                // 电气连接（基础电阻——图元件）
                net.addElement(new com.hdf.cryptand.circuitsimulation.model.elements.Resistor(
                        na, nb, w.assembler.resistance()));
                // 导线组拓扑：找连通组（共享端点）加入；否则新建
                com.hdf.cryptand.engine.WireGroup target = null;
                for (com.hdf.cryptand.engine.WireGroup g : ctx.wireGroups) {
                    if (g.connects(na, nb)) { target = g; break; }
                }
                if (target == null) {
                    target = new com.hdf.cryptand.engine.WireGroup(
                            w.assembler.ratedCurrent(), w.assembler.thermal());
                    ctx.wireGroups.add(target);
                }
                target.addSegment(na, nb, w.assembler.resistance()); // 总电阻 Σ
            }
        } catch (Throwable ignored) {
        }
        registrar.accept(ctx);             // 注册上下文（含网络——传入列表）
        return net;
    }

    /** 设备注册（绑定 + 组装器 + 端子数） */
    public record DeviceReg(Binding binding, Assembler assembler, int terminals) {}

    /** 导线段注册（连【设备端子】：设备索引 + 端子索引 + 导线组装器——每段一个
     *  组装器（含 R + 温度模型）；连续段可用 {@link WireAssembler#merged} 合并统一计算） */
    public record WireReg(int deviceA, int termA, int deviceB, int termB,
                          com.hdf.cryptand.engine.assemblers.WireAssembler assembler) {}

    /** 从网络取最后一个复合元件（绑定用——简化） */
    private static CompositeElement compositeOf(Network net) {
        java.util.List<CompositeElement> cs = net.composites();
        return cs == null || cs.isEmpty() ? null : cs.get(cs.size() - 1);
    }

    /** GraphBuilder 实现（端子【动态分配】——每设备独立端子节点；避免与设备
     *  内部节点（addNode）冲突——固定索引会撞内部节点） */
    private static final class NetGraphBuilder implements GraphBuilder {
        private final Network net;
        private final int[] terminals; // 端子节点（-1=未分配）
        private final int maxTerms;

        NetGraphBuilder(Network net, int terminals) {
            this.net = net;
            this.maxTerms = Math.max(terminals, 2);
            this.terminals = new int[maxTerms];
            java.util.Arrays.fill(this.terminals, -1);
        }

        @Override
        public void addElement(Element e) {
            if (e != null) net.addElement(e);
        }

        @Override
        public void addModel(CompositeElement m) {
            if (m != null) net.addComposite(m);
        }

        @Override
        public int addNode() {
            return net.addNode().id;
        }

        @Override
        public int terminal(int index) {
            if (index < 0 || index >= maxTerms) return net.addNode().id;
            if (terminals[index] < 0) terminals[index] = net.addNode().id; // 首次分配
            return terminals[index];
        }

        /** 端子节点 id（组装后读——导线连接用；未分配 = -1） */
        int terminalNode(int index) {
            return (index >= 0 && index < maxTerms) ? terminals[index] : -1;
        }
    }
}

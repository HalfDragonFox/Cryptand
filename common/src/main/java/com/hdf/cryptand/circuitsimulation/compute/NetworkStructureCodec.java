package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.circuitsimulation.model.WaveformGroup;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.composite.InductorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络完整结构编解码（2026-08-19 用户需求：缓存网络内所有数据含电路结构，
 * 支持跨区块传输）。比 {@link NetworkSnapshot} 更进一步：
 * <ul>
 *   <li>基础元件（elements，与 NetworkSnapshot 相同编码）</li>
 *   <li>复合元件（composites：WireComposite / ResistorModel / CapacitorModel /
 *       InductorModel——设备模型有外部依赖不在此编码，恢复后由组装器重建）</li>
 *   <li>端子（terminals：deviceKey + terminalIndex + engineNode）</li>
 *   <li>波形组（waveforms：多频叠加）</li>
 *   <li>接地 / 频率 / dt / time</li>
 * </ul>
 * 纯数据（零 MC 依赖），二进制格式（DataStream），Base64 由调用方负责。
 */
public final class NetworkStructureCodec {

    private NetworkStructureCodec() {}

    /** 从 Network 编码完整结构（基础元件 + 复合元件 + 端子 + 波形）。 */
    public static byte[] encode(Network net) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bos);
            o.writeInt(0x43545259); // magic "CTRY"
            o.writeInt(net.nodeCount());
            o.writeInt(net.groundNode);
            o.writeDouble(net.frequency);
            o.writeDouble(net.dt);
            o.writeDouble(net.time);
            // 基础元件
            List<Element> els = net.elements();
            o.writeInt(els.size());
            for (Element e : els) {
                writeElement(o, e);
            }
            // 复合元件（2026-09-15 用户：保存时不再因为"codec 不认识"而丢弃设备）
            //  —— 四种已知类型走原编码；其余走通用条目（byte 5：compositeKey +
            //  类名 + 展开元件 + 绑定持久化信息），恢复时交给 MC 侧工厂重建。
            //  实在无法编码的（展开元件含不支持类型）写 SKIP(6)，只丢那一个元件。
            List<CompositeElement> comps = net.composites();
            o.writeInt(comps.size());
            for (CompositeElement c : comps) {
                writeComposite(o, c);
            }
            // 端子
            List<TerminalElement> terms = net.terminals();
            o.writeInt(terms.size());
            for (TerminalElement t : terms) {
                o.writeUTF(t.deviceKey);
                o.writeInt(t.terminalIndex);
                o.writeInt(t.engineNode);
            }
            // 波形组
            WaveformGroup wg = net.waveforms();
            if (wg == null) {
                o.writeInt(0);
            } else {
                List<WaveformGroup.Component> cs = wg.components();
                o.writeInt(cs.size());
                for (WaveformGroup.Component c : cs) {
                    o.writeDouble(c.frequency);
                    o.writeDouble(c.amplitude);
                    o.writeDouble(c.phaseDeg);
                    o.writeByte(c.type.ordinal());
                }
            }
            o.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("NetworkStructureCodec encode failed", e);
        }
    }

    /** 解码为完整 Network（基础元件 + 复合元件 + 端子 + 波形）。 */
    public static Network decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 0x43545259) throw new IOException("bad magic");
            Network net = new Network();
            int nodeCount = in.readInt();
            net.groundNode = in.readInt();
            net.frequency = in.readDouble();
            net.dt = in.readDouble();
            net.time = in.readDouble();
            for (int i = 0; i < nodeCount; i++) net.addNode();
            int ne = in.readInt();
            for (int i = 0; i < ne; i++) net.addElement(readElement(in));
            int nc = in.readInt();
            for (int i = 0; i < nc; i++) {
                // null = 通用条目没有工厂 / 工厂重建失败 / SKIP 标记 —— 跳过该
                // 元件而不是中断整次解码（其余电路仍然可用）。
                CompositeElement ce = readComposite(in, net);
                if (ce != null) net.addComposite(ce);
            }
            int nt = in.readInt();
            for (int i = 0; i < nt; i++) {
                String key = in.readUTF();
                int ti = in.readInt();
                int en = in.readInt();
                net.addTerminal(new TerminalElement(key, ti, en));
            }
            int nw = in.readInt();
            if (nw > 0) {
                WaveformGroup wg = new WaveformGroup();
                for (int i = 0; i < nw; i++) {
                    double f = in.readDouble();
                    double a = in.readDouble();
                    double p = in.readDouble();
                    WaveformType t = WaveformType.values()[in.readUnsignedByte()];
                    wg.add(new WaveformGroup.Component(f, a, p, t));
                }
                net.setWaveforms(wg);
            }
            return net;
        } catch (IOException e) {
            throw new RuntimeException("NetworkStructureCodec decode failed", e);
        }
    }

    // ==================== 元件编解码 ====================

    /**
     * 通用复合元件恢复工厂（2026-09-15 用户："保存时需要组装器记住绑定的数据的
     * 相关信息，可以给绑定接口添加信息接口，用于保存时返回必要信息。"）。
     * <p>
     * common 侧零 MC 依赖，不可能自己 new 出 ElectroMachineModel 之类的模型；
     * 因此把"重建"这一步交给 MC 侧注册的实现（neoforge = 按 compositeKey 的
     * 类型码 + 坐标找到对应设备的组装器，由组装器用展开元件 + 持久化信息重建，
     * 再 bindAllPos 重新绑定）。
     * <p>
     * 未注册（纯 common 环境 / 测试）→ 通用条目被跳过，其余电路照常恢复。
     */
    public interface CompositeFactory {
        /**
         * @param net          正在恢复的网络（工厂应把新模型建在它的节点上）
         * @param compositeKey 身份锚点（"M"+pos 等：类型码 + 坐标）
         * @param className    原复合模型的类名（诊断/匹配用）
         * @param expanded     展开元件（R/L/C/源，节点 id 已指向 net 的节点）
         * @param info         {@code ModelLink.persistInfo()} 保存的 KV（名称 → 值）
         * @return 重建的复合元件；null = 无法重建（该元件被跳过）
         */
        CompositeElement create(Network net, String compositeKey, String className,
                                Element[] expanded, java.util.Map<String, Object> info);
    }

    private static volatile CompositeFactory FACTORY;

    /** 注册/注销通用复合元件恢复工厂（MC 侧装配阶段调用一次即可） */
    public static void setCompositeFactory(CompositeFactory f) {
        FACTORY = f;
    }

    /* ==================== 持久化 KV 编解码（2026-09-15）====================
     * 用户："保存可以设置 Map 或者其他的类型的数组，然后保存时根据 Map 的名称 +
     * 值做 KV 保存。" —— 按【名称 → 值】逐条写，值带类型标签，读取端按标签还原。
     * 支持的标签：0=double 1=string 2=bool 3=long 5=null。
     * 其余类型（含未知对象）在写入端就折叠成字符串，保证读取端永远能还原出
     * 一个"值"，不会因为对方塞了怪类型而毁掉整条字节流。
     */

    private static final byte KV_DOUBLE = 0;
    private static final byte KV_STRING = 1;
    private static final byte KV_BOOL = 2;
    private static final byte KV_LONG = 3;
    private static final byte KV_NULL = 5;
    // 2026-09-15 用户："数值可以是通用变量，K 为保存字符串，V 为通用变量。"
    //  ⇒ V 不限于标量：容器递归展开，能表达任意嵌套结构。
    private static final byte KV_LIST = 6;  // List / 数组（含基本类型数组）
    private static final byte KV_MAP = 7;   // 嵌套 Map（K 仍是字符串）

    /** 写 KV：先折叠成干净条目（跳过 null key），再写数量 —— 数量必须与实际一致，
     *  否则读取端会错位。 */
    public static void writeKv(DataOutputStream o,
                               java.util.Map<String, Object> kv) throws IOException {
        java.util.List<String> keys = new ArrayList<>();
        java.util.List<Object> vals = new ArrayList<>();
        if (kv != null) {
            for (java.util.Map.Entry<String, Object> en : kv.entrySet()) {
                if (en.getKey() == null) continue;
                keys.add(en.getKey());
                vals.add(en.getValue());
            }
        }
        o.writeInt(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            o.writeUTF(keys.get(i));
            writeValue(o, vals.get(i));
        }
    }

    /** 读 KV（与 {@link #writeKv} 严格对称） */
    public static java.util.Map<String, Object> readKv(DataInputStream in)
            throws IOException {
        int n = in.readInt();
        java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String k = in.readUTF();
            kv.put(k, readValue(in));
        }
        return kv;
    }

    /**
     * 写一个通用值（V）：标量直接写；容器递归展开；其余对象字符串兜底。
     * <p>
     * 用户："数值可以是通用变量，K 为保存字符串，V 为通用变量。"
     * 这里给 V 的通用性画出可往返的边界：
     * <ul>
     *   <li>{@code null} / {@code Boolean} / {@code Double}·{@code Float} /
     *       其它 {@code Number}（long）/ {@code CharSequence}</li>
     *   <li>{@code Map}（K 折叠成字符串，V 递归）</li>
     *   <li>{@code Iterable} 与任意数组（含 {@code double[]} 等基本类型数组，
     *       经 {@link java.lang.reflect.Array} 展开）</li>
     *   <li>其它对象 → {@code String.valueOf} 兜底（能读回字符串，不丢结构）</li>
     * </ul>
     * 兜底的存在保证：**任何**值都不会让整条字节流写坏（与 GENERIC 缓冲同一原则）。
     */
    private static void writeValue(DataOutputStream o, Object v) throws IOException {
        if (v == null) {
            o.writeByte(KV_NULL);
        } else if (v instanceof Boolean b) {
            o.writeByte(KV_BOOL);
            o.writeBoolean(b);
        } else if (v instanceof Double d) {
            o.writeByte(KV_DOUBLE);
            o.writeDouble(d);
        } else if (v instanceof Float f) {
            o.writeByte(KV_DOUBLE);
            o.writeDouble(f);
        } else if (v instanceof Number n) {
            o.writeByte(KV_LONG);
            o.writeLong(n.longValue());
        } else if (v instanceof CharSequence s) {
            o.writeByte(KV_STRING);
            o.writeUTF(s.toString());
        } else if (v instanceof java.util.Map<?, ?> m) {
            java.util.List<Object> ks = new ArrayList<>();
            java.util.List<Object> vs = new ArrayList<>();
            for (java.util.Map.Entry<?, ?> en : m.entrySet()) {
                if (en.getKey() == null) continue;
                ks.add(String.valueOf(en.getKey()));
                vs.add(en.getValue());
            }
            o.writeByte(KV_MAP);
            o.writeInt(ks.size());
            for (int i = 0; i < ks.size(); i++) {
                o.writeUTF((String) ks.get(i));
                writeValue(o, vs.get(i));
            }
        } else if (v instanceof Iterable<?> it) {
            java.util.List<Object> items = new ArrayList<>();
            for (Object x : it) items.add(x);
            o.writeByte(KV_LIST);
            o.writeInt(items.size());
            for (Object x : items) writeValue(o, x);
        } else if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            o.writeByte(KV_LIST);
            o.writeInt(len);
            for (int i = 0; i < len; i++) {
                writeValue(o, java.lang.reflect.Array.get(v, i));
            }
        } else {
            o.writeByte(KV_STRING);
            o.writeUTF(String.valueOf(v));
        }
    }

    /** 读一个通用值（与 {@link #writeValue} 严格对称） */
    private static Object readValue(DataInputStream in) throws IOException {
        byte t = in.readByte();
        switch (t) {
            case KV_DOUBLE: return in.readDouble();
            case KV_STRING: return in.readUTF();
            case KV_BOOL: return in.readBoolean();
            case KV_LONG: return in.readLong();
            case KV_MAP: {
                int n = in.readInt();
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    String k = in.readUTF();
                    m.put(k, readValue(in));
                }
                return m;
            }
            case KV_LIST: {
                int n = in.readInt();
                java.util.List<Object> l = new ArrayList<>(Math.max(n, 0));
                for (int i = 0; i < n; i++) l.add(readValue(in));
                return l;
            }
            case KV_NULL:
            default: return null;
        }
    }

    private static void writeElement(DataOutputStream o, Element e) throws IOException {
        o.writeByte(e.type().ordinal());
        o.writeInt(e.nodeA());
        o.writeInt(e.nodeB());
        double[] p = e.params();
        o.writeInt(p.length);
        for (double v : p) o.writeDouble(v);
    }

    private static Element readElement(DataInputStream in) throws IOException {
        ElementType t = ElementType.values()[in.readUnsignedByte()];
        int a = in.readInt();
        int b = in.readInt();
        int plen = in.readInt();
        double[] p = new double[plen];
        for (int i = 0; i < plen; i++) p[i] = in.readDouble();
        return switch (t) {
            case RESISTOR -> new Resistor(a, b, p[0]);
            case DC_VOLTAGE_SOURCE -> new DcVoltageSource(a, b, p[0], p[1]);
            case CURRENT_SOURCE -> p.length > 1
                    ? new CurrentSource(a, b, p[0], p[1]) : new CurrentSource(a, b, p[0]);
            case CAPACITOR -> new Capacitor(a, b, p[0]);
            case INDUCTOR -> new Inductor(a, b, p[0]);
            case AC_VOLTAGE_SOURCE -> p.length > 2
                    ? new AcVoltageSource(a, b, p[0], p[1], p[2], p[3])
                    : new AcVoltageSource(a, b, p[0], p[1], p[2]);
            case WAVEFORM_SOURCE -> new WaveformSource(a, b, p[0] > 0, WaveformType.SINE,
                    p[1], p[2], p[3], p[4], p[5], p[6]);
            default -> throw new IOException("unsupported element " + t);
        };
    }

    // ==================== 复合元件编解码 ====================

    private static void writeComposite(DataOutputStream o, CompositeElement c) throws IOException {
        if (c instanceof WireComposite wc) {
            o.writeByte(1);
            o.writeInt(wc.a);
            o.writeInt(wc.b);
            o.writeDouble(wc.resistance);
            o.writeUTF(wc.compositeKey() == null ? "" : wc.compositeKey());
        } else if (c instanceof ResistorModel rm) {
            o.writeByte(2);
            o.writeInt(rm.a);
            o.writeInt(rm.b);
            // 电阻值：展开元件第 0 个 Resistor 的 resistance 字段
            double r = 0;
            try {
                Element[] de = rm.decompose();
                if (de.length > 0 && de[0] instanceof Resistor rr) r = rr.resistance;
            } catch (Throwable ignored) { }
            o.writeDouble(r);
            o.writeUTF(rm.compositeKey() == null ? "" : rm.compositeKey());
        } else if (c instanceof CapacitorModel cm) {
            o.writeByte(3);
            o.writeInt(cm.a);
            o.writeInt(cm.b);
            o.writeInt(cm.x);
            o.writeDouble(cm.energy.capacitance);
            // ESR：展开元件中第一个 Resistor 的 resistance
            double esr = 0;
            try {
                for (Element e : cm.decompose()) {
                    if (e instanceof Resistor rr) { esr = rr.resistance; break; }
                }
            } catch (Throwable ignored) { }
            o.writeDouble(esr);
            o.writeUTF(cm.compositeKey() == null ? "" : cm.compositeKey());
        } else if (c instanceof InductorModel im) {
            o.writeByte(4);
            o.writeInt(im.a);
            o.writeInt(im.b);
            o.writeInt(im.x);
            // 电感值：展开元件中最后一个 Inductor 的 inductance
            double l = 0;
            try {
                for (Element e : im.decompose()) {
                    if (e instanceof Inductor ii) { l = ii.inductance; }
                }
            } catch (Throwable ignored) { }
            o.writeDouble(l);
            double dcr = 0;
            try {
                for (Element e : im.decompose()) {
                    if (e instanceof Resistor rr) { dcr = rr.resistance; break; }
                }
            } catch (Throwable ignored) { }
            o.writeDouble(dcr);
            o.writeUTF(im.compositeKey() == null ? "" : im.compositeKey());
        } else {
            // ===== 通用复合元件条目（2026-09-15 用户）=====
            // 用户原话："保存时需要组装器记住绑定的数据的相关信息，可以给绑定接口
            //  添加信息接口，用于保存时返回必要信息。"
            //
            // 不再因为"codec 不认识这个模型类"就丢弃设备（旧行为：encode 直接过滤掉
            //  非 Wire/Resistor/Capacitor/Inductor 的复合元件 ⇒ 电机的电路结构从未
            //  落库 ⇒ 进世界只能重建）。改为存四样东西：
            //   ① compositeKey（"M"+pos 等：类型码 + 坐标，恢复时的身份锚点）
            //   ② 类名（诊断 + 工厂匹配）
            //   ③ 展开元件 decompose()（R/L/C/源，参数完整 ⇒ 电路本身可重建）
            //   ④ 绑定提供的持久化信息（composite.persistInfo()，黑盒字符串，
            //      由组装器通过 ModelLink.persistInfo() 提供）
            // 恢复时由 MC 侧注册的 CompositeFactory 据此重建模型 + 重新绑定。
            //
            // ⚠ 先写临时缓冲、成功才落主流：展开元件里若有 writeElement 不支持的
            //   类型（受控源等），直接在主流上抛异常会把整个字节流写坏 ⇒ 静默损坏比
            //   丢一个元件严重得多。缓冲失败 → 只写一个 SKIP 标记（相当于旧行为）。
            java.io.ByteArrayOutputStream tmp = new java.io.ByteArrayOutputStream();
            DataOutputStream to = new DataOutputStream(tmp);
            boolean ok = true;
            try {
                to.writeUTF(c.compositeKey() == null ? "" : c.compositeKey());
                to.writeUTF(c.getClass().getName());
                Element[] de;
                try { de = c.decompose(); } catch (Throwable t) { de = new Element[0]; }
                if (de == null) de = new Element[0];
                to.writeInt(de.length);
                for (Element e : de) writeElement(to, e);
                // 绑定/组装器提供的持久化 KV（名称 → 值）—— codec 不做格式约定，
                // 只按值类型逐条搬运（2026-09-15 用户："保存可以设置 Map 或者
                // 其他的类型的数组，然后保存时根据 Map 的名称 + 值做 KV 保存"）。
                java.util.Map<String, Object> kv = null;
                try { kv = c.persistInfo(); } catch (Throwable ignored) { }
                writeKv(to, kv);
                to.flush();
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                byte[] body = tmp.toByteArray();
                o.writeByte(5);
                o.writeInt(body.length);
                o.write(body);
            } else {
                o.writeByte(6); // SKIP：本复合元件无法编码（恢复后由组装器按位置重建）
            }
        }
    }

    private static CompositeElement readComposite(DataInputStream in, Network net) throws IOException {
        int kind = in.readUnsignedByte();
        switch (kind) {
            case 1: {
                int a = in.readInt();
                int b = in.readInt();
                double r = in.readDouble();
                String key = in.readUTF();
                return new WireComposite(a, b, r, null, key.isEmpty() ? null : key);
            }
            case 2: {
                int a = in.readInt();
                int b = in.readInt();
                double r = in.readDouble();
                String key = in.readUTF();
                ResistorModel rm = new ResistorModel(a, b, r, null);
                if (!key.isEmpty()) rm.setCompositeKey(key);
                return rm;
            }
            case 3: {
                int a = in.readInt();
                int b = in.readInt();
                int x = in.readInt();
                double c = in.readDouble();
                double esr = in.readDouble();
                String key = in.readUTF();
                CapacitorModel cm = new CapacitorModel(a, b, x, c, esr, null, null);
                if (!key.isEmpty()) cm.setCompositeKey(key);
                return cm;
            }
            case 4: {
                int a = in.readInt();
                int b = in.readInt();
                int x = in.readInt();
                double l = in.readDouble();
                double dcr = in.readDouble();
                String key = in.readUTF();
                InductorModel im = new InductorModel(a, b, x, l, dcr, null, null);
                if (!key.isEmpty()) im.setCompositeKey(key);
                return im;
            }
            case 5: {
                // 通用复合元件（编码侧 byte 5）→ 交 MC 侧注册的工厂重建。
                // common 纯 Java 零 MC 依赖 ⇒ 这里只能拿到【纯数据】；
                // 谁来把它变成 ElectroMachineModel 之类的真实模型，由工厂决定
                // （neoforge 侧 = 对应设备的组装器，它最清楚要恢复什么）。
                int bodyLen = in.readInt();
                byte[] body = new byte[bodyLen];
                in.readFully(body);
                DataInputStream bi = new DataInputStream(
                        new ByteArrayInputStream(body));
                String key = bi.readUTF();
                String cls = bi.readUTF();
                int n = bi.readInt();
                Element[] ex = new Element[n];
                for (int i = 0; i < n; i++) ex[i] = readElement(bi);
                java.util.Map<String, Object> info = readKv(bi);
                CompositeFactory f = FACTORY;
                if (f == null) return null; // 无工厂（纯 common 环境）→ 跳过
                try {
                    return f.create(net, key, cls, ex, info);
                } catch (Throwable t) {
                    return null; // 工厂重建失败 → 跳过该元件，不影响其余解码
                }
            }
            case 6:
                return null; // SKIP：编码侧就放弃了（展开元件含不支持类型）
            default:
                throw new IOException("unsupported composite kind " + kind);
        }
    }
}

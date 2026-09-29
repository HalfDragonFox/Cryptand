package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

/**
 * 通用消息数据体【解析工具】（2026-08-24 用户协议 · 无头，只有数据体）：
 * <p>
 * 【去掉头信息，直接数据体】——元素类型通用（String / int / float / double /
 * boolean / Object[]…），顺序由【固定协议】约定（每个设备家族约定好顺序，如电机
 * 组装器→电机 BE：{@code [方向(+1/-1), 转速(rpm 幅值), 应力(SU)]}）。
 * <p>
 * ⚠ 2026-08-26 用户架构：消息在接口层（{@link ICryptandCircuitBe}）收发
 * 【Object】（任意类型数据体彻底兼容）；本类仅作各设备家族特化解析器的
 * 【解析辅助工具】（{@link #of(Object)} 归一化 + {@link #num(int)} 等取值），
 * 不参与消息语义。具体 BE 子类在 {@link BeBridge#onMessage(Object)} 中：
 * <pre>
 *   BeMessage m = BeMessage.of(msg);   // Object → 归一化（容忍 double[]/Object[]/单值）
 *   double dir = m.num(0);             // 方向
 *   double rpm = m.num(1);             // 转速
 * </pre>
 */
public final class BeMessage {

    /** 通用数据体（Object[]：String/Integer/Float/Double/Boolean…） */
    private final Object[] data;

    public BeMessage(Object... data) {
        this.data = data == null ? new Object[0] : data;
    }

    /**
     * Object 消息 → BeMessage 归一化工具（2026-08-26 通用接口）：
     *   BeMessage → 原样；Object[] / 原始类型数组 → 包装；空 → 空体；其他单值 → 单元素。
     */
    public static BeMessage of(Object raw) {
        if (raw == null) return new BeMessage();
        if (raw instanceof BeMessage bm) return bm;
        if (raw instanceof Object[] arr) return new BeMessage(arr);
        if (raw instanceof double[] da) {
            Object[] oa = new Object[da.length];
            for (int i = 0; i < da.length; i++) oa[i] = da[i];
            return new BeMessage(oa);
        }
        if (raw instanceof float[] fa) {
            Object[] oa = new Object[fa.length];
            for (int i = 0; i < fa.length; i++) oa[i] = fa[i];
            return new BeMessage(oa);
        }
        return new BeMessage(raw);
    }

    public int size() { return data.length; }

    public Object raw(int idx) { return (idx >= 0 && idx < data.length) ? data[idx] : null; }

    /** 数值访问：Number→double；Boolean→1/0；String→parse；其他/越界→NaN。 */
    public double num(int idx) {
        Object v = raw(idx);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Throwable ignored) { }
        }
        return Double.NaN;
    }

    /** 整数访问：{@link #num(int)} 转 int。 */
    public int intVal(int idx) { double n = num(idx); return Double.isNaN(n) ? 0 : (int) n; }

    /** 字符串访问：String 原样；其他 toString（null → ""）。 */
    public String str(int idx) {
        Object v = raw(idx);
        return v == null ? "" : v.toString();
    }

    /** 布尔访问：Boolean 原样；Number→非 0；String→"true"/"1"。 */
    public boolean bool(int idx) {
        Object v = raw(idx);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BeMessage[");
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(data[i]);
        }
        return sb.append(']').toString();
    }
}

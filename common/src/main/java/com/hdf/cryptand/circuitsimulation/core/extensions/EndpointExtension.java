package com.hdf.cryptand.circuitsimulation.core.extensions;

import com.hdf.cryptand.circuitsimulation.core.SimulationCore;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;
import com.hdf.cryptand.circuitsimulation.netop.NetOpRequest;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部接口扩展基类（2026-08-30 用户：核心扩展组件包括外部接口 TCP/UDP/消息等，
 * 并有统一交互接口；扩展有具体实例类用于创建具体实例，可把接口接入核心实例）。
 * <p>
 * 统一交互协议（行协议，UTF-8 文本，每行一条命令；子类负责传输层——TCP 按行、
 * UDP 按包、消息总线按消息）：
 * <pre>
 *   外部 → 核心：
 *     register  &lt;key&gt; &lt;freqHz&gt; &lt;json&gt;       注册网络（json=元件规格，见 NetworkJson）
 *     submit    &lt;key&gt; &lt;SOLVE|REBUILD|SPLIT_MERGE&gt;  提交异步网络操作
 *     solve     &lt;key&gt;                            同步求解（solveNow）
 *     result    &lt;key&gt;                            查询最近结果（solved 行回发）
 *     detach                                    断开（停止本扩展）
 *   核心 → 外部：
 *     solved    &lt;key&gt; &lt;json&gt;                   求解结果推送（result/异步完成后）
 *     event     &lt;key&gt; &lt;json&gt;                   事件（热/状态/销毁等，待扩展）
 *     ready &lt;id&gt;                                接入实例成功
 * </pre>
 * 子类实现传输：{@link #sendLine}（核心→外部）+ 调用 {@link #onLineReceived}
 * （外部→核心）。
 */
public abstract class EndpointExtension implements CoreExtension {

    /** 扩展标识 */
    protected final String id;
    /** 接入的实例（onAttach 设置；null = 未接入） */
    protected volatile SimulationCore core;

    public EndpointExtension(String id) {
        this.id = id == null || id.isBlank() ? "endpoint" : id;
    }

    @Override
    public String id() { return id; }

    @Override
    public void onAttach(SimulationCore core) {
        this.core = core;
    }

    @Override
    public void onDetach(SimulationCore core) {
        this.core = null;
    }

    /** 接入的实例（诊断/扩展用） */
    public SimulationCore attachedCore() { return core; }

    // ==================== 统一交互（外部 → 核心） ====================

    /** 收到一行协议（子类传输层调用；解析并执行核心 API） */
    protected final void onLineReceived(String line) {
        SimulationCore c = core;
        if (c == null || line == null) return;
        line = line.trim();
        if (line.isEmpty()) return;
        try {
            int sp = line.indexOf(' ');
            String cmd = sp < 0 ? line : line.substring(0, sp);
            String rest = sp < 0 ? "" : line.substring(sp + 1).trim();
            switch (cmd) {
                case "register" -> handleRegister(c, rest);
                case "submit" -> handleSubmit(c, rest);
                case "solve" -> handleSolve(c, rest);
                case "result" -> handleResult(c, rest);
                case "detach" -> stop();
                default -> sendLine("err unknown-cmd:" + cmd);
            }
        } catch (Throwable t) {
            sendLine("err " + t);
        }
    }

    /** register &lt;key&gt; &lt;freq&gt; &lt;json&gt;——注册网络（json 空 = 空网络占位） */
    private void handleRegister(SimulationCore c, String rest) {
        String[] parts = rest.split(" ", 3);
        if (parts.length < 1 || parts[0].isEmpty()) {
            sendLine("err register needs key");
            return;
        }
        String key = parts[0];
        double freq = parts.length >= 2 ? parseDouble(parts[1], 0) : 0;
        String json = parts.length >= 3 ? parts[2] : "";
        Network net;
        if (json.isEmpty()) {
            net = new Network();
            net.frequency = freq;
        } else {
            try {
                net = NetworkSpec.parse(json);
                if (net.frequency <= 0) net.frequency = freq;
            } catch (Throwable t) {
                sendLine("err register-spec " + t);
                return;
            }
        }
        c.registerNetwork(key, net);
        sendLine("ok registered:" + key);
    }

    /** submit &lt;key&gt; &lt;OP&gt;——提交异步网络操作 */
    private void handleSubmit(SimulationCore c, String rest) {
        String[] parts = rest.split(" ", 2);
        String key = parts.length > 0 ? parts[0] : "";
        String op = parts.length > 1 ? parts[1].trim().toUpperCase() : "SOLVE";
        if (key.isEmpty()) { sendLine("err submit needs key"); return; }
        try {
            NetOpKind kind = NetOpKind.valueOf(op);
            c.submit(key, new NetOpRequest(kind, null));
            sendLine("ok submitted:" + key + ":" + op);
        } catch (IllegalArgumentException e) {
            sendLine("err unknown-op:" + op);
        }
    }

    /** solve &lt;key&gt;——同步求解并回发 solved */
    private void handleSolve(SimulationCore c, String rest) {
        String key = rest.trim();
        if (key.isEmpty()) { sendLine("err solve needs key"); return; }
        SolveResult r = c.solveNow(key);
        if (r == null) { sendLine("err solve-failed:" + key); return; }
        sendLine("solved " + key + " " + NetworkSpec.result(r));
    }

    /** result &lt;key&gt;——查询结果回发 */
    private void handleResult(SimulationCore c, String rest) {
        String key = rest.trim();
        SolveResult r = c.result(key);
        if (r == null) { sendLine("err no-result:" + key); return; }
        sendLine("solved " + key + " " + NetworkSpec.result(r));
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return def; }
    }

    // ==================== 统一交互（核心 → 外部） ====================

    /** 核心事件推送（子类实现传输层写回；默认空） */
    protected void sendLine(String line) {
        // 子类覆写（TCP 写 socket / UDP 发包 / 消息总线发队列）
    }
}

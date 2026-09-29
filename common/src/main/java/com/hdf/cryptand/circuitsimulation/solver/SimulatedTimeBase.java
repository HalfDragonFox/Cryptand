package com.hdf.cryptand.circuitsimulation.solver;

/**
 * 仿真时间基准（2026-08-30 用户：允许真实时间或仿真时间推进两种方式）。
 * <p>
 * 固定步长推进——每 {@link #advance()} 推进 {@code step} 秒（确定性可复现），
 * 由外部驱动运行标志（{@link #setRunning}）控制是否推进。
 * 用于 EDA 电路仿真 / 测试回放 / 确定性场景（与 {@link SimClock} 真实时间
 * 模式二选一注入物理核心）。
 */
public class SimulatedTimeBase implements TimeBase {

    /** 固定步长（s；构造 clamp ≥ 1ms） */
    private final double step;
    /** 累计仿真时间（s） */
    private volatile double simTime;
    /** 运行标志（外部驱动；false = 时间不推进） */
    private volatile boolean running = true;

    public SimulatedTimeBase(double step) {
        this.step = Math.max(step, 1e-3);
    }

    /** 固定步长（s） */
    public double step() { return step; }

    /** 设置运行标志（false = 暂停推进，advance 返回 0） */
    public void setRunning(boolean running) { this.running = running; }

    @Override
    public double time() { return simTime; }

    @Override
    public double lastDt() { return running ? step : 0; }

    @Override
    public double advance() {
        if (!running) return 0;
        simTime += step;
        return step;
    }

    @Override
    public boolean isAlive() { return running; }

    @Override
    public void reset() { simTime = 0; }

    @Override
    public String toString() {
        return "SimulatedTimeBase{step=" + String.format("%.3f", step)
                + "s t=" + String.format("%.1f", simTime) + "s running=" + running + "}";
    }
}

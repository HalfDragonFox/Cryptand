package com.hdf.cryptand.circuitsimulation.model;

/**
 * 电路节点。id 为网络内稳定索引，电压为求解结果。
 */
public final class Node {
    public final int id;
    /** 求解结果：电压（实数模式为瞬时值；复数模式为相量实部，见 SolveResult） */
    public double voltage;

    public Node(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "Node(" + id + "=" + voltage + "V)";
    }
}

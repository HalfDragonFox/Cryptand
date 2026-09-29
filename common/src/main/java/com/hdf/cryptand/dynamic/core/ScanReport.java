package com.hdf.cryptand.dynamic.core;

import java.util.List;

/** 一次 {@code scan()} 的结果（diff：新增/消失/重载/失败）。 */
public record ScanReport(int added, int removed, int reloaded, int failed,
                         List<String> messages, long millis) {

    public boolean ok() {
        return failed == 0;
    }

    public String summary() {
        return "scan +" + added + " -" + removed + " ~" + reloaded + " fail=" + failed
                + " (" + millis + "ms)";
    }
}

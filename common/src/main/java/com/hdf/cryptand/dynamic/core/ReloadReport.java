package com.hdf.cryptand.dynamic.core;

import java.util.List;

/** 一次 reload 的结果。 */
public record ReloadReport(boolean ok, List<String> ids, List<String> failed, long millis) {

    public String summary() {
        return "reload ok=" + ok + " ids=" + ids + (failed.isEmpty() ? "" : " fail=" + failed)
                + " (" + millis + "ms)";
    }
}

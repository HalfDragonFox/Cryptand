package com.hdf.cryptand.integratednetwork;

/**
 * 容器信息（2026-08-29 集成网络核心改进）：一条「接口→容器信息」里的容器侧。
 * <p>
 * 内存精简：只存必要信息——id（容器键，平台层映射如 BlockPos 编码）/
 * 连接方向 / 距离 / 速率 / 过滤（黑白名单标志 + 紧凑过滤引用数组）。
 * 核心只持有 id 与元数据，绝不触碰真实容器（核心铁律）。
 */
public final class ContainerInfo {

    /** 容器键（平台层映射：如方块位置编码） */
    public final Object id;
    /** 容器位于接口的哪一面（平台层约定，-1 = 无方向） */
    public final int direction;
    /** 距离（格） */
    public final int distance;
    /** 容器接受/供应速率（单位/帧；&lt;=0 = 无限制） */
    public final double rate;
    /** true = 黑名单（过滤表内内容被拒）；false = 白名单（默认全部接受） */
    public final boolean blacklist;
    /** 过滤引用数组（物品/流体/能量键；紧凑数组省内存，勿用 List/包装） */
    public final Object[] filters;

    public ContainerInfo(Object id, int direction, int distance, double rate,
                         boolean blacklist, Object[] filters) {
        this.id = id;
        this.direction = direction;
        this.distance = Math.max(0, distance);
        this.rate = Math.max(0.0, rate);
        this.blacklist = blacklist;
        this.filters = filters == null ? new Object[0] : filters;
    }

    /** 便捷：仅 id（无方向/距离/过滤） */
    public ContainerInfo(Object id) {
        this(id, 0, 0, 0, false, null);
    }

    /** 过滤判定：黑名单=表内拒绝（其余接受）；白名单=仅表内接受（表空=全接受） */
    public boolean accepts(Object ref) {
        if (ref == null) return true;
        if (blacklist) {
            for (Object f : filters) if (f != null && f.equals(ref)) return false;
            return true;
        } else {
            if (filters.length == 0) return true; // 空白名单 = 全部接受
            for (Object f : filters) if (f != null && f.equals(ref)) return true;
            return false;
        }
    }

    @Override
    public String toString() {
        return "ContainerInfo{" + id + " dir=" + direction + " dist=" + distance
                + " rate=" + rate + " " + (blacklist ? "BL" : "WL")
                + " n=" + filters.length + "}";
    }
}
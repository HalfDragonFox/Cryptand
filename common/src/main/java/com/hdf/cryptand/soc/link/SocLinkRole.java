package com.hdf.cryptand.soc.link;

/**
 * ===== 端点在链路里的角色（2026-09-28，纯 Java 零 MC）=====
 *
 * <p>设计原话（用户 2026-09-27 裁定）：<b>组件分「源组件」（外设、CPU —— 单端）与
 * 「中间组件」（扩展卡一类，可双向连接）</b>；例：RV 的 UART 接扩展卡再连到 RV 的 USB 接口。</p>
 *
 * <p>角色只影响**拓扑语义/显示**（源是链路端点，中间件可以串起来），不改变协议协商规则：
 * 协商永远按两端各自的支持接口列表取交集。</p>
 */
public enum SocLinkRole {

    /** 源组件：外设 / CPU（芯片）。链路的一个端点。 */
    SOURCE("source"),
    /** 中间组件：扩展卡 / 桥。可双向，可级联（上限 {@link SocLinkTable#MAX_CASCADE}）。 */
    MIDDLE("middle");

    private final String id;

    SocLinkRole(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isMiddle() {
        return this == MIDDLE;
    }

    public static SocLinkRole byId(String id) {
        if (id == null) {
            return null;
        }
        for (final SocLinkRole r : values()) {
            if (r.id.equalsIgnoreCase(id)) {
                return r;
            }
        }
        return null;
    }
}

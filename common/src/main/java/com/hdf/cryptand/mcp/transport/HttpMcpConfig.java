package com.hdf.cryptand.mcp.transport;

/**
 * HTTP 传输配置（默认<b>只绑本机回环</b>：MCP 能操作游戏世界，不能默认对外）。
 */
public final class HttpMcpConfig {

    /** 监听地址（默认 127.0.0.1；改成 0.0.0.0 必须同时开 {@link #allowRemote}） */
    public String host = "127.0.0.1";

    /** 监听端口（0 = 系统随机分配） */
    public int port = 8765;

    /** Streamable HTTP 端点路径（2025-03-26+ 单一端点） */
    public String path = "/mcp";

    /** 旧版 HTTP+SSE 的流端点（2024-11-05 客户端用；关闭则返回 404） */
    public String ssePath = "/sse";

    /** 旧版 HTTP+SSE 的消息投递端点 */
    public String messagePath = "/message";

    /** 是否开放旧版 HTTP+SSE 端点 */
    public boolean legacySse = true;

    /** 是否允许非回环地址（安全阀；默认 false） */
    public boolean allowRemote = false;

    /** 可选 Bearer 令牌（空 = 不校验；非回环监听时强烈建议设置） */
    public String token = "";

    /** 请求体上限（字节） */
    public int maxBodyBytes = 16 * 1024 * 1024;

    /** POST 等待工具执行完成的上限（毫秒；超出后返回 JSON-RPC 内部错误） */
    public long requestTimeoutMs = 90_000L;

    /** 是否为浏览器客户端开启 CORS 头（MCP Inspector 等） */
    public boolean cors = true;

    /** 是否校验 Origin（MCP 规范 MUST，防 DNS rebinding）；默认开。非浏览器客户端无 Origin 头，放行 */
    public boolean originCheck = true;

    /** 工作线程数提示（0 = 缓存线程池，SSE 长连接多时更合适） */
    public int threads = 0;

    public HttpMcpConfig host(String value) {
        this.host = value;
        return this;
    }

    public HttpMcpConfig port(int value) {
        this.port = value;
        return this;
    }

    public HttpMcpConfig path(String value) {
        this.path = value;
        return this;
    }

    public HttpMcpConfig legacySse(boolean value) {
        this.legacySse = value;
        return this;
    }

    public HttpMcpConfig allowRemote(boolean value) {
        this.allowRemote = value;
        return this;
    }

    public HttpMcpConfig token(String value) {
        this.token = value == null ? "" : value.trim();
        return this;
    }

    public HttpMcpConfig requestTimeoutMs(long value) {
        this.requestTimeoutMs = Math.max(1000L, value);
        return this;
    }

    /** 监听地址是否为回环（安全判定） */
    public boolean loopback() {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

    public String url() {
        return "http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                + ":" + port + path;
    }
}

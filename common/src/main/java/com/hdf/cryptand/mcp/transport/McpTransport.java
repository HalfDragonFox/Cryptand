package com.hdf.cryptand.mcp.transport;

/**
 * MCP 传输（<b>把字节搬进搬出</b>，协议语义全在 {@link com.hdf.cryptand.mcp.McpServer}）。
 *
 * <p>实现：</p>
 * <ul>
 *   <li>{@link HttpMcpTransport} —— Streamable HTTP（2025-03-26+）+ 旧版 HTTP+SSE（2024-11-05）；</li>
 *   <li>{@link StdioTransport} —— 标准输入输出（NDJSON，本地进程直连）。</li>
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /** 传输名（"http" / "stdio"） */
    String name();

    /** 启动（重复启动应无害） */
    void start() throws Exception;

    /** 停止并释放端口/线程 */
    void stop();

    boolean running();

    /** 监听端口（stdio 返回 -1） */
    default int port() {
        return -1;
    }

    /** 人类可读的接入地址（日志/文档用） */
    default String endpoint() {
        return name();
    }

    @Override
    default void close() {
        stop();
    }
}

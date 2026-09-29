/**
 * ===== 被扫描方块缓存（SableDebugScanStore，2026-09-03） =====
 *
 * 服务端 mixin（WorldChunkUploaderHighlightMixin）在 uploadSection 的
 * getCollisionShape 命中点注入，把【被扫描到的实心方块】写到本共享缓存；
 * 客户端渲染器（SableScopeBoxDebugRenderer）读取 → 画黄色小方框。
 *
 * 设计：纯数据（无 MC 客户端依赖），放入 server 包——
 *  - 服务端 mixin 引用 OK（Highlight mixin 本就引用 server 包）
 *  - 客户端渲染器引用 OK（server 包无客户端类依赖，集成服务器同进程共享）
 *  - 不违反"mixin 专属包不可被外部引用"规则（本类不在 debug 包）
 *
 * 上限保护：超过 {@link #MAX_BLOCKS} 时整表清空重来（保证只显示最近一轮扫描）。
 * 集成服务器（单机）下服务端线程写、渲染线程读：ConcurrentHashMap 线程安全。
 * 专用服务器（dedicated）下客户端渲染读不到服务端内存 → 黄框无数据（仅 debug 单机场景支持）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SableDebugScanStore {

    private SableDebugScanStore() {
    }

    /** 上限：超过则不再记录新方块（保留已有结果，不丢）。 */
    public static final int MAX_BLOCKS = 65536;

    /** key = 打包坐标（26 位/轴），value = {x, y, z}。 */
    public static final Map<Long, long[]> BLOCKS = new ConcurrentHashMap<>();

    /** 记录一个被扫描到的实心方块（服务端 mixin 调用，画中线程写入）。 */
    public static void add(int x, int y, int z) {
        final long key = pack(x, y, z);
        if (BLOCKS.size() >= MAX_BLOCKS) {
            return;   // ★ 2026-09-03 超限跳过（不清空，保护已有结果）
        }
        BLOCKS.putIfAbsent(key, new long[]{x, y, z});
    }

    /** 清空（新世界/重装时）。 */
    public static void clearAll() {
        BLOCKS.clear();
    }

    /** 当前缓存数量。 */
    public static int size() {
        return BLOCKS.size();
    }

    /** 打包：26 位/轴 的无符号位域（与 WorldChunkUploader.debugMarkHit 同一格式）。 */
    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF)
                | (((long) y & 0x3FFFFFF) << 26)
                | (((long) z & 0x3FFFFFF) << 52);
    }
}
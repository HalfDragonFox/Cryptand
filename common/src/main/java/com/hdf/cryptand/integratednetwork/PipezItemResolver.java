package com.hdf.cryptand.integratednetwork;

import java.util.List;
import java.util.Map;

/**
 * Pipez 管道物品解析器（2026-08-30 用户：属于 Pipez 的解析器；继承
 * {@link PipezAbstractResolver} 基础实现，额外定义物品过滤参数类）。
 * <p>
 * 注册为 {@code "pipez:item"}，替换内置 {@link GenericPipeResolver}。
 * 语义对齐原版 Pipez {@code canInsert}：
 * <ul>
 *   <li>每个【输出接口参数】= {@link PipezFilterParam}（过滤模式 BLACKLIST/
 *       WHITELIST + 过滤条目）。无参数/无条目 → 全接受；</li>
 *   <li>每个【输入缓冲物品】= {@link PipezBufferedItem}（物品 id + 数量）：
 *       先按输出过滤条目匹配（黑名单=拒绝匹配项；白名单=仅接受匹配项，
 *       白名单空=全接受），再按分配规则（NEAREST/FURTHEST/ROUND_ROBIN/
 *       RANDOM/EQUALIZE）分配到输出；</li>
 *   <li>输入消耗 = 实际分配量（按物品所属输入接口归属）。</li>
 * </ul>
 * 参数类在解析时强制转换（用户：解析器强制转换为对应参数类即可）。
 */
public final class PipezItemResolver extends PipezAbstractResolver {

    public PipezItemResolver() {
        super(TransferType.ITEM);
    }

    @Override
    public String id() {
        return DEFAULT_ID; // "pipez:item"
    }
}

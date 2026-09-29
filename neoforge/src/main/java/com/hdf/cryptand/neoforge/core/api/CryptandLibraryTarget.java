/**
 * ===== CryptandLibraryTarget（core.api · 2026-09-07） =====
 *
 * /cryptand library set 的【目标方块处理器】服务接口——core 命令不感知具体
 * 方块类型：遍历 {@link CryptandServices} 中全部实现，找到 accepts 该方块者
 * 应用网表条目。子包（如 powergrid 的 ProgrammableComponentBlockEntity）与其
 * 他 mod 实现并注册；无实现时 core 提示"该位置没有支持元件库的方块"。
 *
 * 归属：接口在 core（平台 API），实现在子包——core 编译零依赖子包类。
 */
package com.hdf.cryptand.neoforge.core.api;

import com.hdf.cryptand.circuitsimulation.lib.SpiceSubcircuit;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;

/** /cryptand library set 目标方块处理器（core 命令经 CryptandServices 查询）。 */
public interface CryptandLibraryTarget {

    /** 该方块类型是否接受网表条目设置（如可编程元件方块）。 */
    boolean accepts(BlockEntity be);

    /**
     * 把网表条目应用到方块。
     *
     * @param be     目标方块实体（accepts 已通过）
     * @param sub    已解析的网表子电路条目（非 null）
     * @param params k=v 解析后的参数表（可空表）
     * @return 成功描述后缀（core 拼为 "已设置 pos → name，&lt;desc&gt;"）；
     *         实现内自行处理失败并抛异常/返回错误文本的约定：返回以 "!" 开头
     *         视为失败消息（core 原样提示并返回 0）。
     */
    String apply(BlockEntity be, SpiceSubcircuit sub, Map<String, Double> params);
}

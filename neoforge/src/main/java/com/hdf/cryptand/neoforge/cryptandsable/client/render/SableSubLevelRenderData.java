/**
 * ===== 亚层渲染数据（客户端镜像，2026-08-31） =====
 *
 * 由 {@link SableSubLevelRenderPayload} 解码得到；渲染器（SableSubLevelWorldRenderer）
 * 每帧读取本数据绘制亚层方块。
 *
 * 数据内容：
 *  - uuid：亚层 ID
 *  - anchor：锚点（世界坐标，渲染平移基准；位姿快照的位置因此锚点为中心）
 *  - blocks：方块列表（stateId + 相对 anchor 偏移）
 *  - bounds：局部包围盒（世界坐标，方块搬移前）
 *
 * 纯数据（不可变）：渲染线程只读；payload handler 主线程写入。
 */
package com.hdf.cryptand.neoforge.cryptandsable.client.render;

import com.hdf.cryptand.neoforge.cryptandsable.network.SableSubLevelRenderPayload;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SableSubLevelRenderData(
        UUID subLevelId,
        int runtimeId,
        BlockPos anchor,
        int boundMinX, int boundMinY, int boundMinZ,
        int boundMaxX, int boundMaxY, int boundMaxZ,
        List<RenderBlock> blocks
) {

    /** 单个渲染方块：网络状态 id + 相对锚偏移。 */
    public record RenderBlock(int stateId, int dx, int dy, int dz) {
        public BlockPos offset() {
            return new BlockPos(dx, dy, dz);
        }
    }

    /** network payload → 渲染数据。 */
    public static SableSubLevelRenderData from(SableSubLevelRenderPayload p) {
        List<RenderBlock> blocks = new ArrayList<>(p.blockData().size() / 4);
        for (int i = 0; i + 3 < p.blockData().size(); i += 4) {
            blocks.add(new RenderBlock(
                    p.blockData().get(i),       // stateId
                    p.blockData().get(i + 1),   // dx
                    p.blockData().get(i + 2),   // dy
                    p.blockData().get(i + 3))); // dz
        }
        return new SableSubLevelRenderData(
                p.subLevelId(),
                p.runtimeId(),
                new BlockPos(p.anchorX(), p.anchorY(), p.anchorZ()),
                p.boundMinX(), p.boundMinY(), p.boundMinZ(),
                p.boundMaxX(), p.boundMaxY(), p.boundMaxZ(),
                blocks);
    }
}

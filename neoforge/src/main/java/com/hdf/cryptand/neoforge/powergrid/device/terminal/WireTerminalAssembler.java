/**
 * ===== 接线端子组装器（2026-08-22 用户架构统一抽象） =====
 *
 * 「接线端子」= 导线连接端口（PowerGrid CordJunction/Connector/Socket/DeviceConnector、
 * CEE 接触网悬挂块 / 受电弓底座端子、CEE 多端子设备、未来任意 mod 的端子方块）。
 * 统一为【端子元件组装器】：不建模内部电路元件（纯连接端口），但按声明端子数组装
 * 相应数量的【独立端子元件】(TerminalElement) 接入 Network（每个端子一个独立引擎
 * 节点 + 测试点，悬空端子补建节点）——导线可挂载、放置即建网、求解时作为网络
 * 节点/段边界/测试点。
 *
 * 用户架构：
 *   - 接线端子 = 组装器体系（Assembler/Assemblers 按 BE 类注册）里的【端子元件组装器】
 *   - 电气设备 = 有内部元件/储能/绑定的组装器（端子-元件-端子；核心已有 TerminalElement /
 *     Assembler / TerminalRegistry / VirtualDevice 抽象）
 *   - CEE 多端子设备 = 按 getNodePositions 数量组装 N 个独立端子元件（示例：CEE 双端子
 *     BE → 两个独立端子元件）
 *   - 导线渲染/连接 = Cryptand 自管（WireGraphSyncPayload → ClientWireGraphStore
 *     → SaggingWireRenderer；CryptandWirePlacement.addEdge 进自管 WireNetwork）
 *
 * 额外职责（比普通设备组装器多）：判定能否接线 + 端子精确位置。核心《自管导线
 * 放置》（CryptandWirePlacement / 放置建网）与《求解建模》只认本注册表
 * （WireTerminals），不再 per-mod 专门分支。
 */
package com.hdf.cryptand.neoforge.powergrid.device.terminal;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 接线端子组装器：端子元件组装器（按声明端子数组装 N 个独立 TerminalElement，
 * 不建模内部电路元件）+ 端子数/端子位置声明。
 * 实现本接口并注册到 {@link WireTerminals}（同时并入 {@code Assemblers} 分发），
 * 即可让任何端子方块接入 Cryptand 自管电网的放置/接线/建模，无需专门分支。
 */
public interface WireTerminalAssembler extends Assembler {

    /** 是否接线端子方块（当前 level 下能否挂 Cryptand 导线） */
    default boolean isTerminal(BlockGetter level, BlockPos pos, BlockState state) {
        return false;
    }

    /** 端子相对方块位置的偏移（0..1；世界坐标 = pos + offset）。默认块中心 */
    default Vec3 terminalOffset(BlockGetter level, BlockPos pos, BlockState state, int term) {
        return new Vec3(0.5, 0.5, 0.5);
    }

    /** 组装端子元件（2026-08-22 用户：不用空元件——按声明端子数组装独立端子元件）。
     *  每个端子一个 {@link TerminalElement}（独立引擎节点 + 测试点；悬空端子补建节点），
     *  幂等：已由第 4 步收集过的端子跳过。默认委托 WireTerminals 通用实现。 */
    default void assembleTerminals(BlockEntity be, Integer[] nodeIds, Network net) {
        if (be == null) return;
        com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals
                .assembleTerminals(be.getBlockPos(), Math.max(1, terminalCount()),
                        nodeIds, net, true);
    }

    /** 接线端子 = 无内部电路元件（纯端口）；端子元件由 {@link #assembleTerminals} 组装 */
    @Override
    default CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        return null;
    }
}
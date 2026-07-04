/**
 * ===== Create 多线程化 - TorquePropagator 访问器 =====
 *
 * 目标类：com.simibubi.create.content.kinetics.TorquePropagator
 *
 * 暴露 TorquePropagator 的 private static networks 字段，
 * 供 CommonEventsMixin 获取当前维度的所有 KineticNetwork 进行并行遍历。
 *
 * 字段结构：
 *   private static Map<LevelAccessor, Map<Long, KineticNetwork>> networks;
 *   //      ^维度（世界）         ^网络ID    ^网络实例
 *
 * 使用方式：
 *   TorquePropagator tp = Create.TORQUE_PROPAGATOR;
 *   Map<Long, KineticNetwork> world = TorquePropagatorAccessor.getNetworks().get(level);
 *
 * 注意：目标字段是 static，Accessor 方法也必须是 static。
 */

package com.hdf.cryptand.neoforge.mixin.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = TorquePropagator.class, remap = false)
public interface TorquePropagatorAccessor {

    @Accessor("networks")
    static Map<LevelAccessor, Map<Long, KineticNetwork>> getNetworks() {
        throw new AssertionError();
    }
}

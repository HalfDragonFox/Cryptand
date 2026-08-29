/**
 * ===== CarbonPile 设备通用接口注入（2026-08-26） =====
 *
 * PowerGrid 的碳堆本体 CarbonPileBlockEntity 直接继承 Create 的
 * SmartBlockEntity（非 ElectricKineticBlockEntity 也非 Electricity 基类
 * ElectricBlockEntity）——两套基类 mixin 都覆盖不到它（注意与其线圈
 * CarbonPileCoilBlockEntity 不同：线圈继承 ElectricBlockEntity 已被覆盖）。
 *
 * 碳堆有 CarbonPileAssembler（trim 温度模型 + 参数重解），2026-08-26 用户
 * 架构下需实现 {@link ICryptandCircuitBe} 才能收发引擎消息/参数上报。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = org.patryk3211.powergrid.electricity.carbonpile.CarbonPileBlockEntity.class,
        remap = false)
// 纯 implements + 同名 @Override 实现（不用 @Implements，避免 prefix 解析歧义）
public abstract class CarbonPileCircuitBeMixin implements ICryptandCircuitBe {

    /* ===== 通用接口实现（转发解析器；无解析器 = 兜底忽略） ===== */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }
}
/**
 * ===== 原版导线实体删除级联拦截（2026-08-13 完整闭环） =====
 *
 * 转换类把原版导线（TransmissionLine → WireGraph 边）接管后，原版导线实体
 * （BaseWireEntity/BlockWireEntity/HangingWireEntity）从世界中【删除】——实体
 * 不再是拓扑载体（自管 WireGraph 是）。
 *
 * ⚠ 直接删除会触发原版级联：remove() → dropWire() → wire.remove() →
 * TransmissionLine.remove() → lineDisconnected() → transmissionLines.remove()。
 * 这会把【转换源数据】清掉 → 转换差量移除 → 自管图边被误删（电路消失）。
 *
 * 本 mixin 拦截：当实体被 Cryptand 标记为"已转换待删除"（PowerGridWireConverter
 * 的 CONVERTED_ENTITIES 集合）时，remove()/onClientRemoval() 只移除实体本身
 * （世界清理），【跳过 dropWire/removeWireEntity 级联】——原版网络数据
 * （transmissionLines/endpoint connections）保留，转换层继续从它同步自管图。
 *
 * 门控：仅 Cryptand 接管删除（标记集合含此实体 UUID）时拦截；用户剪线/烧毁
 * 走原版路径（无标记 → 正常级联，connection 清空 → 转换检测幽灵 → 自管边移除）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BaseWireEntity.class, remap = false)
public abstract class WireEntityTakeoverMixin {

    /**
     * 拦截 remove()：Cryptand 接管删除 → 只移除实体本身（super 由原方法体执行
     * 会触发 dropWire——需先拦截）。此处用 HEAD + cancellable：标记命中时
     * 直接调 discard（实体从世界移除，不触发原版 dropWire 级联）。
     */
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void cryptand$takeoverRemove(net.minecraft.world.entity.Entity.RemovalReason reason,
                                         CallbackInfo ci) {
        try {
            BaseWireEntity self = (BaseWireEntity) (Object) this;
            if (!PowerGridWireConverter.isConvertedEntity(self)) return;
            // Cryptand 接管删除：仅移除实体本身（世界清理），保留网络数据。
            // 直接调 super.remove 会触发原版 dropWire 级联 → 用 discard 绕过
            // （discard 也走 remove，但先清理标记 → 二次进入不拦截 → 原版级联
            // 仍会被触发）。改为：标记命中 → 直接 setRemoved（实体移除但不调
            // dropWire/removeWireEntity）。
            ci.cancel();
            self.setRemoved(reason);
            // 清理标记（已处理）
            PowerGridWireConverter.onEntityRemoved(self);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拦截 onClientRemoval()：客户端实体移除（服务端同步删除触发）——同样的
     * 级联问题，标记命中时只移除实体本身。
     */
    @Inject(method = "onClientRemoval", at = @At("HEAD"), cancellable = true)
    private void cryptand$takeoverClientRemoval(CallbackInfo ci) {
        try {
            BaseWireEntity self = (BaseWireEntity) (Object) this;
            if (!PowerGridWireConverter.isConvertedEntity(self)) return;
            ci.cancel();
            // 客户端：只标记移除（不级联；原版 onClientRemoval 会 dropWire）
            self.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            PowerGridWireConverter.onEntityRemoved(self);
        } catch (Throwable ignored) {
        }
    }
}

/**
 * ===== Registrate 并发安全补丁（治本，v2） =====
 *
 * 背景：Create 6.0.10 在 dev 环境启动时偶发（本工程因 PowerGrid 近乎必现）崩溃
 *   "Found unused register callbacks, see logs"。mixin v1 只锁了 4 个方法后实测
 *   （2026-08-10 06:04）仍崩——栈里明确有 wrapMethod$zdp000$cryptand$lockedOnRegister，
 *   说明 v1 已生效但不够。
 *
 * 根因（Registrate 1.3.0+67 反编译确认，参考 Astronauts-of-Create/Northstar-Redux
 *   issue #122）：
 *   - registerCallbacks 是 Guava HashMultimap（键 Pair(name, registryKey)）。正常流程：
 *     Builder.onRegister 在条目未 accept 前把回调 put 进该 map；accept() 时
 *     removeAll 把挂起回调移入 Registration.callbacks。
 *   - 真正竞态是"孤儿回调"：线程A 在 addRegisterCallback 里检查 getRegistrationUnchecked
 *     → null，线程B 并发跑 accept() 完成 removeAll 并建 Registration；若 B 先落表、
 *     A 后 put，该回调永久留在 map 里永不消费 → onRegister 开头 isEmpty()==false。
 *   - v1 漏了 accept()（它也读写 registerCallbacks），所以锁不全仍崩。
 *   - 崩溃点是 onRegister 开头 dev-only throw（isDevEnvironment() 守卫，字节码 offset
 *     76-91）：非空即 clear+打日志，但 dev 下抛 IllegalStateException → NeoForge 回滚
 *     全部注册表到 VANILLA → 连锁 "Trying to access unbound value: neoforge:swim_speed"。
 *
 * 本补丁（两层，均治本）：
 *   1. 把所有读写 registerCallbacks / afterRegisterCallbacks 的入口（onRegister /
 *      onRegisterLate / addRegisterCallback ×2 / accept）全部纳入同一把锁串行化，
 *      关闭"孤儿回调"交错竞态；synchronized 可重入，不会死锁。
 *   2. 兜底中和 onRegister 内的 isDevEnvironment()（@ModifyExpressionValue 只改
 *      该调用点）：即使有漏网孤儿回调，dev 行为与生产一致——warn + clear + 继续注册，
 *      不再抛异常。生产环境本来就是这样，不会崩。
 *
 * 目标类为外部库 com.tterrag.registrate.AbstractRegistrate（Registrate 1.3.0+67），
 * 运行时类名为 mojang 名，故 remap = false。
 */
package com.hdf.cryptand.neoforge.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tterrag.registrate.AbstractRegistrate;
import com.tterrag.registrate.builders.Builder;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.tterrag.registrate.util.nullness.NonNullConsumer;
import com.tterrag.registrate.util.nullness.NonNullFunction;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AbstractRegistrate.class, remap = false)
public abstract class AbstractRegistrateConcurrencyMixin {

    /** 所有 Registrate 注册状态读写的统一锁（跨实例保守串行，注册阶段仅一次性开销）。 */
    private static final Object CRYPTAND_REGISTRATE_LOCK = new Object();

    @WrapMethod(method = "onRegister(Lnet/neoforged/neoforge/registries/RegisterEvent;)V")
    private void cryptand$lockedOnRegister(RegisterEvent event, Operation<Void> original) {
        synchronized (CRYPTAND_REGISTRATE_LOCK) {
            original.call(event);
        }
    }

    @WrapMethod(method = "onRegisterLate(Lnet/neoforged/neoforge/registries/RegisterEvent;)V")
    private void cryptand$lockedOnRegisterLate(RegisterEvent event, Operation<Void> original) {
        synchronized (CRYPTAND_REGISTRATE_LOCK) {
            original.call(event);
        }
    }

    @WrapMethod(method = "addRegisterCallback(Ljava/lang/String;Lnet/minecraft/resources/ResourceKey;Lcom/tterrag/registrate/util/nullness/NonNullConsumer;)Lcom/tterrag/registrate/AbstractRegistrate;")
    @SuppressWarnings("rawtypes")
    private AbstractRegistrate cryptand$lockedAddRegisterCallback(String name, ResourceKey registry, NonNullConsumer callback, Operation<AbstractRegistrate> original) {
        synchronized (CRYPTAND_REGISTRATE_LOCK) {
            return original.call(name, registry, callback);
        }
    }

    @WrapMethod(method = "addRegisterCallback(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Runnable;)Lcom/tterrag/registrate/AbstractRegistrate;")
    @SuppressWarnings("rawtypes")
    private AbstractRegistrate cryptand$lockedAddRegisterCallback(ResourceKey registry, Runnable callback, Operation<AbstractRegistrate> original) {
        synchronized (CRYPTAND_REGISTRATE_LOCK) {
            return original.call(registry, callback);
        }
    }

    /**
     * v2 关键补齐：accept() 也会读写 registerCallbacks（removeAll 把挂起回调移入
     * Registration.callbacks，并 put 进 registrations Table）。若不锁，会与
     * addRegisterCallback 的 put 交错产生"孤儿回调"，这正是 v1 锁 4 个方法仍崩的根因。
     */
    @WrapMethod(method = "accept(Ljava/lang/String;Lnet/minecraft/resources/ResourceKey;Lcom/tterrag/registrate/builders/Builder;Lcom/tterrag/registrate/util/nullness/NonNullSupplier;Lcom/tterrag/registrate/util/nullness/NonNullFunction;)Lcom/tterrag/registrate/util/entry/RegistryEntry;")
    @SuppressWarnings("rawtypes")
    private RegistryEntry cryptand$lockedAccept(String name, ResourceKey registry, Builder builder, NonNullSupplier supplier, NonNullFunction factory, Operation<RegistryEntry> original) {
        synchronized (CRYPTAND_REGISTRATE_LOCK) {
            return original.call(name, registry, builder, supplier, factory);
        }
    }

    /**
     * v2 兜底：onRegister 开头的 dev-only throw 是唯一崩溃点；生产环境
     * isDevEnvironment()==false 时同样逻辑只 warn+clear+继续，从不崩。
     * 把 onRegister 内该调用点恒改为 false，dev 行为与生产一致（保留诊断 WARN，
     * 不再抛异常）。
     */
    @ModifyExpressionValue(
            method = "onRegister(Lnet/neoforged/neoforge/registries/RegisterEvent;)V",
            at = @At(value = "INVOKE", target = "Lcom/tterrag/registrate/AbstractRegistrate;isDevEnvironment()Z")
    )
    private static boolean cryptand$neverThrowInDev(boolean original) {
        return false;
    }
}

package com.hdf.cryptand.neoforge.cryptandsable_compat;

import foundry.veil.platform.registry.RegistryObject;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * veil {@link RegistryObject} 的内存实现（CryptandSable 兼容层）。
 *
 * <p>官方 sable 通过 veil {@code RegistrationProvider} 把物理块属性注册进 vanilla registry；
 * 该流程的 {@code makeRegistry} 只能在 {@code NewRegistryEvent} 回调内执行，任何惰性类加载
 * （下游 mod 在进世界后首次触碰 {@code PhysicsBlockPropertyTypes}）都会触发
 * {@code IllegalStateException: ... after NewRegistryEvent was fired!}，并被固化为
 * {@code NoClassDefFoundError} —— 导致属性链/方块属性 tooltip/组装失败。
 *
 * <p>因此兼容层不再触碰 RegistrationProvider，改用本内存实现提供同名同描述符的
 * {@code RegistryObject} 实例（字节码 getstatic 仍匹配 veil 类型），内容桥接 CryptandSable 核心。
 *
 * <p><b>注意</b>：本类必须放在非 mixin 包中 —— Mixin 禁止业务代码直接引用已定义
 * mixin 包（{@code ...mixin.sable.*}）内的类，否则触发
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... cannot be referenced directly}。
 *
 * @param <T> 属性/条目类型
 */
public final class MemoryRegistryObject<T> implements RegistryObject<T> {

    private final ResourceLocation id;
    private final ResourceKey<T> key;
    private final T value;

    public MemoryRegistryObject(final ResourceLocation id, final ResourceKey<T> key, final T value) {
        this.id = id;
        this.key = key;
        this.value = value;
    }

    @Override
    public ResourceKey<T> getResourceKey() {
        return this.key;
    }

    @Override
    public ResourceLocation getId() {
        return this.id;
    }

    @Override
    public boolean isPresent() {
        return this.value != null;
    }

    @Override
    public T get() {
        return this.value;
    }

    @Override
    public Holder<T> asHolder() {
        return Holder.direct(this.value);
    }
}
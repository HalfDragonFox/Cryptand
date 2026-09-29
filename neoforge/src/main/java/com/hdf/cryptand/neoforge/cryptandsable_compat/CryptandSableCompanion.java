package com.hdf.cryptand.neoforge.cryptandsable_compat;

import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.SableCompanion;

/**
 * CryptandSable 的 {@link SableCompanion} 实现（最高优先级，惰性官方 API 面）。
 *
 * <p>经 {@code META-INF/services/dev.ryanhcode.sable.companion.SableCompanion} 注册，
 * 使 {@code SableCompanion.INSTANCE}（即旧 {@code Sable.HELPER}）解析到本实例。
 *
 * <p>【必选】extends 官方具体类 {@link ActiveSableCompanion}（而非仅 implements
 * {@code SableCompanion}）：官方 {@code Sable.HELPER = (ActiveSableCompanion)
 * SableCompanion.INSTANCE} 强转要求服务实现是 {@code ActiveSableCompanion} 子类；
 * 只 implements 接口会在 {@code Sable} 类初始化时抛
 * {@code ClassCastException: ... cannot be cast to ActiveSableCompanion}。
 *
 * <p>【不运行官方】本类不覆盖任何方法 → 全部继承官方 {@link ActiveSableCompanion} 实现。
 * 官方核心 mixins 已被 patch 剔除（Level 未实现 {@code dev.ryanhcode.sable.
 * mixinterface.plot.SubLevelContainerHolder} 等），官方方法内部 {@code instanceof
 * SubLevelContainerHolder} 兜底全部命中 false → 安全返回 null/空集合，绝不触碰官方
 * Container/PhysicsSystem/native。对外表现为"官方 API 面完整存在但无亚层参与"
 * （simulated/aeronautics 视为未组装，API 面不崩溃）。
 *
 * <p>核心语义（物理化/装配/查询）由 CryptandSable 专属 API
 * （{@link com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi}）提供，
 * 本桥仅保证官方接口解析不崩。
 */
@SableCompanion.LoadPriority(100000)
public final class CryptandSableCompanion extends ActiveSableCompanion {

    /** ServiceLoader 需要 public 无参构造。 */
    public CryptandSableCompanion() {
    }
}
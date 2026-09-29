/**
 * ===== create（机械动力）子包配置（2026-09-13）=====
 *
 * 归属修正：齿轮过应力销毁开关原本挂在 simulator 子包的 `circuit-simulation.toml` 里，
 * 但它显然是 **create 子包的功能** —— 现迁入本子包（`create.toml`），由子包自身注册
 * （core 只提供注册接口，不收集子包配置；core 也不引用任何子包配置类）。
 *
 * ⚠ 同时修正了"开关关不掉"的根因：旧实现让 **Mixin 插件在类加载期**读配置，
 * 而那个时刻 NeoForge 还没加载 ModConfigSpec，只能走兜底值（旧兜底是 true）
 * ⇒ 配置里关了 `enableCreateStressLimit`，`KineticBlockEntityMixin` 照样注入并销毁齿轮。
 * 现在：Mixin 常驻注入，**在 tick 里用标准配置路径判断**（{@link #stressLimitEnabled()}），
 * 开关改完下次进游戏即生效，不再有"类加载期读不到配置"的问题。
 */

package com.hdf.cryptand.neoforge.create.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ConfigCreate implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射）。 */
    public static final String DOMAIN = "create";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigCreate INSTANCE = new ConfigCreate();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String fileName() {
        return "create.toml";
    }

    @Override
    public ModConfigSpec spec() {
        return SPEC;
    }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供接口，不收集子包配置）。 */
    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "create.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /**
     * 齿轮过应力销毁开关（<b>默认关闭</b>：这是破坏性功能）。
     * <p>true → {@code KineticBlockEntityMixin} 在 tick 里检测齿轮所在 Create 网络的总应力
     * （{@code Σ impact × |转速RPM|}），达到阈值就销毁该齿轮；false → 完全跳过，恢复 Create 原版行为。
     * <p>在<b>运行期</b>读取，所以改完配置下次进游戏即生效（不需要为 mixin 注入而重启判定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CREATE_STRESS_LIMIT = CK
            .comment("齿轮过应力销毁（默认关闭）",
                     "true: 齿轮网络总应力(Σ impact×|RPM|) 达到阈值时销毁该齿轮",
                     "false(默认): 不销毁，恢复机械动力原版行为",
                     "运行期读取，改完下次进游戏生效")
            .define("enableCreateStressLimit", false);

    /**
     * 销毁阈值（应力单位）。
     * <p>Create 网络应力 = Σ(impact × |转速RPM|)，随转速线性增长；
     * 默认 65536 = 单齿轮(impact 4)在 16384RPM 时的应力（原硬编码 1024 会让 256RPM 就爆）。
     */
    public static final ModConfigSpec.DoubleValue CREATE_STRESS_LIMIT_THRESHOLD = CK
            .comment("齿轮过应力销毁阈值（应力单位）",
                     "Create 网络应力 = Σ(impact × |转速RPM|)，随转速线性增长",
                     "默认65536 = 单齿轮(impact 4)在 16384RPM 时的应力",
                     "仅在 enableCreateStressLimit=true 时生效")
            .defineInRange("createStressLimitThreshold", 65536.0, 1.0, 1.0E9);

    /** ⚠ build 必须在全部字段之后 */
    public static final ModConfigSpec SPEC = CK.build();

    /** 运行期读取开关；配置尚未加载（极早期）一律视为关闭 —— 破坏性功能的安全默认值。 */
    public static boolean stressLimitEnabled() {
        return SPEC.isLoaded() && ENABLE_CREATE_STRESS_LIMIT.get();
    }

    /** 运行期读取阈值；配置尚未加载时退回默认值。 */
    public static double stressLimitThreshold() {
        return SPEC.isLoaded() ? CREATE_STRESS_LIMIT_THRESHOLD.get() : 65536.0;
    }

    private ConfigCreate() {
    }
}

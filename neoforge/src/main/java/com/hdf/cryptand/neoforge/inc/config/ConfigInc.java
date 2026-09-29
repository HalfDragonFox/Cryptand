package com.hdf.cryptand.neoforge.inc.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigInc —— 集成网络核心（INC / 虚拟网络：物品/流体/能量/气体/信号传输）配置
 * （2026-08-30 用户架构：所有配置放到相关子包——原 circuit/debug 域的 INC 键迁入）。
 */
public final class ConfigInc implements CryptandConfigSpec {

    public static final String DOMAIN = "inc";

    public static final ConfigInc INSTANCE = new ConfigInc();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "inc.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "inc.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /**
     * INC（集成网络核心）总开关 —— 关闭所有支持时也应可关（2026-08-30）。
     * false → 虚拟网络不 tick/不传输（零每 tick 开销）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_INC = CK
            .comment("INC集成网络核心总开关",
                     "true(默认): 启用虚拟网络(物品/流体/能量/气体/信号)传输",
                     "false: 完全关闭INC(不tick/不传输)")
            .define("enableInc", true);

    /** INC 网络 tick 频率（Hz）——虚拟网络传输节拍。 */
    public static final ModConfigSpec.DoubleValue INC_TICK_FREQUENCY_HZ = CK
            .comment("INC网络tick频率(Hz)",
                     "虚拟网络(物品/流体/能量/气体/信号)传输节拍",
                     "默认20")
            .defineInRange("incTickFrequencyHz", 20.0, 1.0, 1000.0);

    /** INC 缓冲容量倍率。 */
    public static final ModConfigSpec.DoubleValue INC_BUFFER_CAP_MULTIPLIER = CK
            .comment("INC缓冲容量倍率",
                     "传输缓冲上限 = 倍率 × 速率",
                     "默认64")
            .defineInRange("incBufferCapMultiplier", 64.0, 0.0, 1.0E6);

    /** INC 输出重试间隔（ms）。 */
    public static final ModConfigSpec.IntValue INC_OUTPUT_RETRY_MS = CK
            .comment("INC输出重试间隔(ms)",
                     "输出受阻后的重试节拍",
                     "默认50")
            .defineInRange("incOutputRetryMs", 50, 1, 10000);

    public static final ModConfigSpec SPEC = CK.build();
}

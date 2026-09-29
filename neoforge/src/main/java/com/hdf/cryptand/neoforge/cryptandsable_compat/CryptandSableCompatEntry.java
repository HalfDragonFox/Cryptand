package com.hdf.cryptand.neoforge.cryptandsable_compat;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/** CryptandSable 兼容层入口（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "cryptandsable_compat", order = 70)
public final class CryptandSableCompatEntry implements SubpackageEntry {
    public static final CryptandSableCompatEntry INSTANCE = new CryptandSableCompatEntry();
    private CryptandSableCompatEntry() {
    }

    @Override
    public void registerConfigs() {
        com.hdf.cryptand.neoforge.cryptandsable_compat.config.ConfigCryptandSableCompat.register();
    }

    @Override
    public boolean enabled() {
        // ⚠ 2026-08-30 子包开关：兼容层跟随官方 Sable 集成总开关
        // （enableSableSupport=false → 兼容层一并关闭；核心引擎与其他子包不受影响）。
        try {
            // ⚠ 2026-08-30 类 mod 依赖：sable 支持由依赖表处理（dependencies("sable")）
            return true;
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "official sable compat (enableSableSupport; mixin 面自门控)";
    }

    @Override
    public java.util.List<String> dependencies() {
        // 类 mod 依赖：官方 Sable 集成（sable 子包）未启用 → 兼容层不加载
        return java.util.List.of("sable");
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：统一加载函数按 enabled 返回）
        return java.util.List.of("cryptand.sable.core.mixins.json", "cryptand.sable.sable.mixins.json");
    }
}

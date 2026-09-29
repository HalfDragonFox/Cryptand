/**
 * ===== Create: Aeronautics 联动（可选，未装不启用） =====
 *
 * 所有 Aeronautics 相关功能/mixin 必须先过 {@link #isLoaded()} 守卫：
 *   - 未安装 create_aeronautics → 不注入相关 mixin、不启用联动功能
 *   - 已安装 → 初始化联动（骨架，后续填充：飞艇/移动船体上的电力设备、
 *     电路随船移动、动力接入等）
 * 依赖为 compileOnly（不强制玩家安装）。
 *
 * ⚠ 2026-08-29 实锤（latest.log）：create-aeronautics-1.3.0 jar 内的 mod id 是
 *   {@code aeronautics}（含 {:code aeronautics_bundled} 子条目），【不是】
 *   create_aeronautics！旧 MOD_ID="create_aeronautics" 致 getModFileById 恒 null →
 *   isLoaded() 恒 false → 全部轮子 mixin SKIPPED（应力仍 4096）。改为多别名遍历。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import net.neoforged.fml.loading.FMLLoader;

public final class AeronauticsCompat {

    /** Create: Aeronautics 的主 mod id（实测日志确认） */
    public static final String MOD_ID = "aeronautics";

    /** 兼容别名（同一 create-aeronautics jar 内的可能 mod id / 旧版命名） */
    private static final String[] MOD_ID_ALIASES = {
            "aeronautics", "aeronautics_bundled", "create_aeronautics"
    };

    private static volatile Boolean loaded;

    private AeronauticsCompat() {
    }

    /** Aeronautics 是否已安装（懒检测 + 缓存，类加载阶段即可安全调用）。 */
    public static boolean isLoaded() {
        Boolean l = loaded;
        if (l == null) {
            synchronized (AeronauticsCompat.class) {
                l = loaded;
                if (l == null) {
                    l = checkLoaded();
                    loaded = l;
                }
            }
        }
        return l;
    }

    /** 遍历别名：任一命中即算已安装（实测：aeronautics / aeronautics_bundled）。 */
    private static boolean checkLoaded() {
        try {
            var list = FMLLoader.getLoadingModList();
            for (String id : MOD_ID_ALIASES) {
                try {
                    if (list.getModFileById(id) != null) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 联动初始化（仅安装时执行；mod 总线 commonSetup 调用）。 */
    public static void init() {
        if (!isLoaded()) {
            return;
        }
        // TODO(联动)：Aeronautics 已安装——后续填充：
        //   - 移动船体（Simulated Contraption）上的电气设备接线随船移动
        //   - 电力网络与移动结构解耦/重连
        //   - 推进器/螺旋桨等 Aeronautics 动力源 → Cryptand 电力
    }
}

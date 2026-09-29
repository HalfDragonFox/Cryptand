/**
 * ===== Cryptand 元件库单例 =====
 *
 * 加载 config/cryptand/library/ 下的 SPICE 库文件（.lib/.spice/.cir），
 * 供可编程元件方块展开、命令查询与界面展示使用。
 */

package com.hdf.cryptand.neoforge.core.library;

import com.hdf.cryptand.circuitsimulation.lib.SpiceLibrary;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class ComponentLibrary {

    /** 配置关闭时的空库（避免每次 new） */
    private static final SpiceLibrary EMPTY = new SpiceLibrary();

    private static volatile SpiceLibrary instance;

    private ComponentLibrary() {
    }

    /** 获取（惰性加载；服务端启动后首次访问触发）。配置关闭 → 返回空库。 */
    public static SpiceLibrary get() {
        if (!ConfigCircuit.ENABLE_SPICE_LIBRARY.get()) {
            return EMPTY;
        }
        SpiceLibrary lib = instance;
        if (lib == null) {
            synchronized (ComponentLibrary.class) {
                lib = instance;
                if (lib == null) {
                    lib = load();
                    instance = lib;
                }
            }
        }
        return lib;
    }

    /** 重新加载（/cryptand library reload）。配置关闭 → 返回空库。 */
    public static SpiceLibrary reload() {
        synchronized (ComponentLibrary.class) {
            if (!ConfigCircuit.ENABLE_SPICE_LIBRARY.get()) {
                instance = EMPTY;
                return instance;
            }
            instance = load();
            return instance;
        }
    }

    private static SpiceLibrary load() {
        Path dir;
        try {
            dir = FMLPaths.CONFIGDIR.get().resolve("cryptand/library");
        } catch (Throwable t) {
            dir = Path.of("config", "cryptand", "library");
        }
        return SpiceLibrary.load(dir);
    }
}

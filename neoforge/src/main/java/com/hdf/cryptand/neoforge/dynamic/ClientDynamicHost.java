package com.hdf.cryptand.neoforge.dynamic;

import com.hdf.cryptand.dynamic.core.DynamicFramework;
import com.hdf.cryptand.dynamic.core.Entry;
import com.hdf.cryptand.dynamic.core.ReloadReport;
import com.hdf.cryptand.dynamic.core.ScanReport;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiContext;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.util.List;

/**
 * 客户端专属门面（**唯一**引用 {@code net.minecraft.client.*} 的动态框架入口）。
 *
 * <p>分离原因（低侵入/一致性）：服务端路径不会加载本类，也就不会因缺客户端类而崩。
 * 调用方必须先判 {@code FMLEnvironment.dist.isClient()}。</p>
 */
public final class ClientDynamicHost {

    private ClientDynamicHost() {
    }

    /** 惰性初始化并返回框架（首次调用时装配）。 */
    public static DynamicFramework ensure() {
        final Minecraft mc = Minecraft.getInstance();
        return DynamicFrameworkHost.init(
                FMLPaths.GAMEDIR.get(),
                () -> new UiContext(mc.player),
                ClientMainExecutor.mc());
    }

    public static ScanReport scan() {
        return ensure().scan();
    }

    public static ReloadReport reload(String id) {
        return ensure().reload(id);
    }

    public static ReloadReport reloadCore(String coreId) {
        return ensure().reloadCore(coreId);
    }

    public static ReloadReport reloadAll() {
        return ensure().reloadAll();
    }

    /** 临时加载任意 jar（调用方须先校验配置开关 enableAdhocPluginLoad）。 */
    public static boolean loadAdHoc(java.nio.file.Path jar) {
        return ensure().loadAdHoc(jar);
    }

    public static boolean unload(String id) {
        return ensure().unload(id);
    }

    public static List<Entry> entries() {
        return ensure().entries();
    }

    public static String stats() {
        return ensure().stats().toString();
    }
}

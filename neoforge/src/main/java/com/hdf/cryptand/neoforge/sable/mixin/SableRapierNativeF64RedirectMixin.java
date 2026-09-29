/**
 * ===== 官方 sable natives → f64 引擎劫持（2026-09-08 用户需求） =====
 *
 * sable 子包对官方 sable 的优化：官方 sable 的 rapier natives 为 f32 编译
 * （Rapier3D.loadLibrary：classpath zip.l4z → .sable/natives/... → System.load）。
 * 本 mixin 在官方 System.load 前把目标 DLL 换成【按官方源码编译的 f64 版】
 * （excode/sable/sable_rapier_f64：rapier3d-f64 fork + parallel 多线程 +
 * marten Real=f64；JNI 符号与官方一致 Java_dev_ryanhcode_..._Rapier3D_*），
 * 使官方 sable 以 f64 引擎 + 多线程运行（大尺度/远原点场景精度修复）。
 *
 * 门控：
 *  - mixin 注入：官方 sable 已装即可（plugin 特判，不依赖 enableSableSupport）；
 *  - 运行时劫持：仅当 {@code sable.toml: enableSableRapier64=true}（spec.isLoaded
 *    守卫——官方 Rapier3D 静态加载发生在服务器启动创建物理管线时，晚于 config
 *    加载 → 运行期 spec 读取可靠）；
 *  - 失败回退：f64 资源缺失/加载失败 → warn 后照常加载官方 f32（不崩、可用优先）。
 *
 * 资源：/assets/cryptand/sable_natives/f64_official/sable_rapier_{arch}_{os}.dll
 * （f64 构建产物部署位；与 cryptandsable 门面 DLL 分开，避免同名冲突）。
 */
package com.hdf.cryptand.neoforge.sable.mixin;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** 官方 Rapier3D natives 加载 → f64 引擎（enableSableRapier64 门控；失败回退官方 f32）。
 *  ⚠ 官方类 runtimeOnly 不可见（同 SableAssemblyMoveMixin 约定）→ targets 字符串。 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", remap = false)
public abstract class SableRapierNativeF64RedirectMixin {

    private static final String F64_RESOURCE_BASE =
            "assets/cryptand/sable_natives/f64_official/";

    /** 替换官方 System.load 的目标 DLL（官方 copy 已把 f32 写到 path——先覆盖为 f64）。 */
    @Redirect(method = "loadLibrary",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;load(Ljava/lang/String;)V"))
    private static void cryptand$redirectNativeLoad(String path) {
        if (!isRapier64Enabled()) {
            // 官方默认（f32）原样加载
            System.load(path);
            return;
        }
        try {
            final String name = path.replace('\\', '/');
            final String base = name.substring(name.lastIndexOf('/') + 1);
            final String resource = F64_RESOURCE_BASE + base;
            final Path target = Paths.get(path).toAbsolutePath();
            try (InputStream in = SableRapierNativeF64RedirectMixin.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (in == null) {
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[SableF64] f64 native resource missing: {}（回退官方 f32）", resource);
                    System.load(path);
                    return;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(target.toAbsolutePath().toString());
            CryptandNeoForge.WAF_LOGGER.info(
                    "[SableF64] 官方 sable Rapier natives 已劫持为 f64 引擎（多线程 parallel）: {}",
                    target);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[SableF64] f64 劫持失败（回退官方 f32）: {}", t.toString());
            try {
                System.load(path);
            } catch (Throwable t2) {
                CryptandNeoForge.WAF_LOGGER.warn("[SableF64] 官方 f32 回退加载亦失败: {}", t2.toString());
            }
        }
    }

    /** f64 开关（sable.toml: enableSableRapier64；spec 未加载 → false 不劫持）。 */
    private static boolean isRapier64Enabled() {
        try {
            return ConfigSable.SPEC.isLoaded()
                    && ConfigSable.ENABLE_SABLE_RAPIER64.get();
        } catch (final Throwable t) {
            return false;
        }
    }
}

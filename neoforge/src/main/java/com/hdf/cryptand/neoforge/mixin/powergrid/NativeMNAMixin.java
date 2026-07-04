package com.hdf.cryptand.neoforge.mixin.powergrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Intercepts NativeMNA.tryLoad() to substitute our thread-safe DLL.
 * Loads our fixed libpowergridNative7.dll before PowerGrid's original
 * loading logic runs. Since System.loadLibrary checks if the library
 * is already loaded, our pre-load makes the original a no-op.
 */
@Mixin(targets = "org.patryk3211.powergrid.electricity.sim.solver.NativeMNA",
       remap = false)
public class NativeMNAMixin {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    @Inject(method = "tryLoad", at = @At("HEAD"), cancellable = true)
    private static void cryptand$loadFixedDll(CallbackInfo ci) {
        try {
            // Try to load our fixed DLL from the classpath
            String libName = "libpowergridNative7.dll";
            Path nativeDir = Paths.get(".pg-native");
            Files.createDirectories(nativeDir);
            Path libPath = nativeDir.resolve(libName);

            // Extract from our mod's resources
            ClassLoader cl = NativeMNAMixin.class.getClassLoader();
            try (InputStream stream = cl.getResourceAsStream(
                    "assets/cryptand/native/" + libName)) {
                if (stream != null) {
                    Files.copy(stream, libPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (Files.exists(libPath)) {
                System.load(libPath.toAbsolutePath().toString());
                LOGGER.info("[Cryptand] Loaded thread-safe powergridNative7.dll");
                ci.cancel(); // Skip original tryLoad - our DLL is already loaded
            }
        } catch (Throwable e) {
            LOGGER.warn("[Cryptand] Failed to load custom DLL, falling back to original", e);
            // Don't cancel - let original tryLoad run as fallback
        }
    }
}

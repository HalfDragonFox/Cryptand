package com.hdf.cryptand.neoforge;

import com.hdf.cryptand.CryptandExpectPlatform;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class CryptandExpectPlatformImpl {
    /**
     * This is our actual method to {@link CryptandExpectPlatform#getConfigDirectory()}.
     */
    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }
}

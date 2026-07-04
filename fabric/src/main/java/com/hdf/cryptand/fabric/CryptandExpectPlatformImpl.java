package com.hdf.cryptand.fabric;

import com.hdf.cryptand.CryptandExpectPlatform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class CryptandExpectPlatformImpl {
    /**
     * This is our actual method to {@link CryptandExpectPlatform#getConfigDirectory()}.
     */
    public static Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }
}

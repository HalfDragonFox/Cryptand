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

    /** EDA 电路图目录：<gamedir>/cryptand/circuits/（自动创建） */
    public static Path circuitsDir() {
        Path dir = FMLPaths.GAMEDIR.get().resolve("cryptand").resolve("circuits");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException ignored) {
        }
        return dir;
    }
}

//package com.hdf.cryptand.service.neoforge;
//
//import net.neoforged.fml.loading.FMLPaths;
//import com.hdf.cryptand.Cryptand;
//
//import java.io.IOException;
//import java.nio.file.Path;
//
//public class PathServiceImpl {
//    public static Path getConfigDirPath() {
//        Path configDir = FMLPaths.CONFIGDIR.get().resolve(Cryptand.MOD_ID);
//        try {
//            java.nio.file.Files.createDirectories(configDir);
//        } catch (IOException e) {
//            throw new RuntimeException("Failed to get config folder", e);
//        }
//        return configDir;
//    }
//}

package com.hdf.cryptand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Cryptand {
    public static final String MOD_ID = "cryptand";
    public static final Logger WAF_LOGGER = LogManager.getLogger();

    public static void init() {
//        registerResourcePacks();
        System.out.println(CryptandExpectPlatform.getConfigDirectory().toAbsolutePath().normalize().toString());
    }
//
//    @ExpectPlatform
//    public static void registerResourcePacks() {
//        // 这是一个占位方法，具体实现在 forge 和 fabric 模块中
//        throw new AssertionError();
//    }
}

package com.hdf.cryptand.service;

import dev.architectury.injectables.annotations.ExpectPlatform;

import java.nio.file.Path;

public class PathService {
    @ExpectPlatform
    public static Path getConfigDirPath() {
        // 这是一个占位方法，具体实现在 forge 和 fabric 模块中
        throw new AssertionError();
    }
}

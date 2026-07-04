package com.hdf.cryptand.core.MathTool;

import java.util.concurrent.ThreadLocalRandom;

public class MathTool {
    public static boolean probabilityGenerator(float probability) {
        // 快速边界处理（避免随机数生成）
        if (probability <= 0.0f) return false;
        if (probability >= 100.0f) return true;

        // 将百分比概率转换为整数（0~10000范围）
        int scaledProb = (int) (probability * 100.0f + 0.5f);  // 四舍五入

        // 生成随机整数并比较
        return ThreadLocalRandom.current().nextInt(10000) < scaledProb;
    }
}

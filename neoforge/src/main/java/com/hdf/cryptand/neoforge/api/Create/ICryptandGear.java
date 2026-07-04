package com.hdf.cryptand.neoforge.api.Create;

public interface ICryptandGear {
    int getGearColor();          // 返回 ARGB 格式颜色（内部主体颜色）
    int getGearOuterColor();     // 返回 ARGB 格式颜色（齿轮外部边框颜色）
}
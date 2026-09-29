package com.hdf.cryptand.neoforge.core.api.Create;

/**
 * 齿轮颜色查询接口（由 KineticBlockEntityMixin 实现，供客户端渲染查询）。
 */
public interface ICryptandGear {
    int getGearColor();          // 返回 ARGB 格式颜色（内部主体颜色）
    int getGearOuterColor();     // 返回 ARGB 格式颜色（齿轮外部边框颜色）
}

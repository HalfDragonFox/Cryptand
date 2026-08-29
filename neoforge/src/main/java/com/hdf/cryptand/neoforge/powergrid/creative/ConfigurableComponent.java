/**
 * ===== 可配置元件接口（电容 / 电感） =====
 *
 * 提供"数值（基准单位）+ 单位索引"的读写，供 ValueUnitMenu 通用配置 UI 使用。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import net.minecraft.core.BlockPos;

public interface ConfigurableComponent {

    /** 基准单位下的当前值（法拉 / 亨利） */
    float getValueBase();

    void setValueBase(float value);

    /** 当前单位索引（0=基准, 1=m, 2=µ） */
    int getUnitIndex();

    void setUnitIndex(int index);

    BlockPos getBlockPos();
}

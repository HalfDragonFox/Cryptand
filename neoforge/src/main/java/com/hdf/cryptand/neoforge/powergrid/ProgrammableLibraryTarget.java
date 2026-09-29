package com.hdf.cryptand.neoforge.powergrid;

import com.hdf.cryptand.circuitsimulation.lib.SpiceSubcircuit;
import com.hdf.cryptand.neoforge.core.api.CryptandLibraryTarget;
import com.hdf.cryptand.neoforge.powergrid.block.ProgrammableComponentBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;

/**
 * /cryptand library set 的 powergrid 实现：可编程元件方块
 * （ProgrammableComponentBlockEntity）。core 命令经 CryptandServices 查询到本
 * 实现才可设置——删除 powergrid 子包 → 本实现消失 → core 的 library set 自动
 * 提示不支持（core 零编译依赖）。
 */
public final class ProgrammableLibraryTarget implements CryptandLibraryTarget {

    @Override
    public boolean accepts(BlockEntity be) {
        return be instanceof ProgrammableComponentBlockEntity;
    }

    @Override
    public String apply(BlockEntity be, SpiceSubcircuit sub, Map<String, Double> params) {
        final ProgrammableComponentBlockEntity pcbe = (ProgrammableComponentBlockEntity) be;
        pcbe.setLibraryAndResolve(sub.name, params);
        return "端子数=" + pcbe.getPortCount();
    }
}

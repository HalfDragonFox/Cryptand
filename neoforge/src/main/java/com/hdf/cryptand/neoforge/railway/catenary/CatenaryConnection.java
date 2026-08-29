package com.hdf.cryptand.neoforge.railway.catenary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * 接触网连接（双向）：两个 CatenaryHolder 之间的悬线。
 * 移植自 CEE CatenaryConnection。
 */
public record CatenaryConnection(BlockPos pos1, BlockPos pos2) {

    public boolean isAny(BlockPos pos) {
        return pos1.equals(pos) || pos2.equals(pos);
    }

    public Vec3 getStartPos() {
        return pos1.getBottomCenter();
    }

    public Vec3 getEndingPos() {
        return pos2.getBottomCenter();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null || o.getClass() != this.getClass()) return false;
        var that = (CatenaryConnection) o;
        return (Objects.equals(this.pos1, that.pos1) && Objects.equals(this.pos2, that.pos2)) ||
                (Objects.equals(this.pos2, that.pos1) && Objects.equals(this.pos1, that.pos2));
    }

    @Override
    public int hashCode() {
        return pos1.hashCode() ^ pos2.hashCode();
    }

    @Override
    public String toString() {
        return pos1.toShortString() + " =|=|= " + pos2.toShortString();
    }

    public CatenaryConnection swap() {
        return new CatenaryConnection(pos2, pos1);
    }
}
package com.hdf.cryptand.neoforge.cryptandsable.api.model;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

/**
 * 核心自有整数包围盒（CryptandBounds3i）—— 完全独立于官方 sable 的类型。
 *
 * <p>替代官方 {@code dev.ryanhcode.sable.companion.math.BoundingBox3ic/BoundingBox3i}
 * （核心物理引擎零官方依赖）。含端点闭区间语义：{@code minX..maxX} 均包含。
 *
 * <p>常量（不可变）：读操作线程安全（字段 final）；可变性仅通过 {@link #set(...)} 就地
 * 修改（如 setBoundingBox 目标）——语义与官方 BoundingBox3i 一致，但类型完全自有。
 */
public final class CryptandBounds3i {

    public static final CryptandBounds3i EMPTY =
            new CryptandBounds3i(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    public CryptandBounds3i(final int minX, final int minY, final int minZ,
                            final int maxX, final int maxY, final int maxZ) {
        this.set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public CryptandBounds3i(final BlockPos min, final BlockPos max) {
        this.set(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    public CryptandBounds3i(final CryptandBounds3i other) {
        this.set(other);
    }

    public CryptandBounds3i() {
        this(0, 0, 0, 0, 0, 0);
    }

    public int minX() { return this.minX; }

    public int minY() { return this.minY; }

    public int minZ() { return this.minZ; }

    public int maxX() { return this.maxX; }

    public int maxY() { return this.maxY; }

    public int maxZ() { return this.maxZ; }

    /** 返回与平面矩形（x/z）的相交关系。 */
    @Contract(pure = true)
    public boolean intersects(final CryptandBounds3i other) {
        return this.maxX() >= other.minX() && this.maxY() >= other.minY()
                && this.maxZ() >= other.minZ() && this.minX() <= other.maxX()
                && this.minY() <= other.maxY() && this.minZ() <= other.maxZ();
    }

    /** x/z 平面是否包含该点（y 忽略；供装配器/结构定位）。 */
    @Contract(pure = true)
    public boolean containsXZ(final double x, final double z) {
        return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
    }

    /** 是否包含整数点（闭区间）。 */
    @Contract(pure = true)
    public boolean contains(final int x, final int y, final int z) {
        return x >= this.minX && x <= this.maxX
                && y >= this.minY && y <= this.maxY
                && z >= this.minZ && z <= this.maxZ;
    }

    public int widthX() { return this.maxX - this.minX + 1; }

    public int widthY() { return this.maxY - this.minY + 1; }

    public int widthZ() { return this.maxZ - this.minZ + 1; }

    @Contract(value = "_->this", mutates = "this")
    public CryptandBounds3i set(final CryptandBounds3i other) {
        return this.set(other.minX, other.minY, other.minZ, other.maxX, other.maxY, other.maxZ);
    }

    @Contract(value = "_,_,_,_,_,_->this", mutates = "this")
    public CryptandBounds3i set(final int minX, final int minY, final int minZ,
                                final int maxX, final int maxY, final int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        return this;
    }

    /** 是否为空（无有效区间）。 */
    @Contract(pure = true)
    public boolean isEmpty() {
        return this.minX > this.maxX || this.minY > this.maxY || this.minZ > this.maxZ;
    }

    /** 由方块集合推算包围盒；空集合 → null。 */
    public static @Nullable CryptandBounds3i from(final Iterable<BlockPos> blocks) {
        final Iterator<BlockPos> iterator = blocks.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        BlockPos pos = iterator.next();
        int minX = pos.getX();
        int minY = pos.getY();
        int minZ = pos.getZ();
        int maxX = minX;
        int maxY = minY;
        int maxZ = minZ;
        while (iterator.hasNext()) {
            pos = iterator.next();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new CryptandBounds3i(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static List<Integer> toList(final CryptandBounds3i bb) {
        return List.of(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    @Override
    public String toString() {
        return "[" + this.minX + "," + this.minY + "," + this.minZ
                + ".." + this.maxX + "," + this.maxY + "," + this.maxZ + "]";
    }
}
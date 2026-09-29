package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

/**
 * ===== 块设备上的字节级 IO（2026-09-18）=====
 *
 * <p>为什么单独抽一层：{@link DiskImage} 的最小单位是<b>块</b>（1~16MB，按容量自适应），
 * 而文件系统的最小单位是<b>扇区</b>（512B）。这两者不是整数倍关系（块总是扇区的整数倍，
 * 反过来不成立），所以"写一个扇区"必须<b>读-改-写整个块</b> —— 这个细节只应该存在一处，
 * 否则每个文件系统各写一遍，早晚有一处忘了改回。</p>
 *
 * <p>⚠ 本类不做缓存：{@link DiskImage} 自己就是块缓存。再加一层只会让"谁改了数据"变模糊。</p>
 */
public final class BlockIo {

    /** FAT 的行业标准扇区大小；也用作块设备逻辑扇区 */
    public static final int SECTOR = 512;

    private final DiskImage img;

    public BlockIo(DiskImage img) {
        this.img = img;
    }

    public DiskImage image() {
        return img;
    }

    public long capacityBytes() {
        return img.capacityBytes();
    }

    public long totalSectors() {
        return img.capacityBytes() / SECTOR;
    }

    /** 读一个逻辑扇区（越界 ⇒ ERR_BAD_ARGS，不返回半截数据） */
    public byte[] readSector(long sector) {
        final long off = sector * SECTOR;
        if (off < 0 || off + SECTOR > img.capacityBytes()) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "sector out of range: " + sector);
        }
        final int blockSize = img.blockSize();
        final byte[] block = img.readBlock((int) (off / blockSize));
        final byte[] out = new byte[SECTOR];
        System.arraycopy(block, (int) (off % blockSize), out, 0, SECTOR);
        return out;
    }

    /** 写一个逻辑扇区（读-改-写块；跨块写入是调用方的 bug，直接报错而不是静默写坏） */
    public void writeSector(long sector, byte[] data) {
        final long off = sector * SECTOR;
        if (off < 0 || off + SECTOR > img.capacityBytes()) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "sector out of range: " + sector);
        }
        final int blockSize = img.blockSize();
        final int inBlock = (int) (off % blockSize);
        if (inBlock + SECTOR > blockSize) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "sector crosses block boundary at " + off);
        }
        final byte[] block = img.readBlock((int) (off / blockSize));
        System.arraycopy(data, 0, block, inBlock, SECTOR);
        img.writeBlock((int) (off / blockSize), block);
    }

    /** 连续字节读（可跨扇区；长度按实际可读范围截断） */
    public byte[] read(long byteOffset, int length) {
        final byte[] out = new byte[length];
        long off = byteOffset;
        int done = 0;
        while (done < length) {
            final byte[] sec = readSector(off / SECTOR);
            final int inSec = (int) (off % SECTOR);
            final int n = Math.min(SECTOR - inSec, length - done);
            System.arraycopy(sec, inSec, out, done, n);
            done += n;
            off += n;
        }
        return out;
    }

    /** 连续字节写（可跨扇区） */
    public void write(long byteOffset, byte[] data, int length) {
        long off = byteOffset;
        int done = 0;
        while (done < length) {
            final long secNo = off / SECTOR;
            final byte[] sec = readSector(secNo);
            final int inSec = (int) (off % SECTOR);
            final int n = Math.min(SECTOR - inSec, length - done);
            System.arraycopy(data, done, sec, inSec, n);
            writeSector(secNo, sec);
            done += n;
            off += n;
        }
    }

    // ==================== 小端读写（FAT 全程小端） ====================

    public static int u8(byte[] a, int off) {
        return a[off] & 0xFF;
    }

    public static int u16(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8);
    }

    public static long u32(byte[] a, int off) {
        return (a[off] & 0xFFL) | ((a[off + 1] & 0xFFL) << 8) | ((a[off + 2] & 0xFFL) << 16) | ((a[off + 3] & 0xFFL) << 24);
    }

    public static void put16(byte[] a, int off, int v) {
        a[off] = (byte) (v & 0xFF);
        a[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    public static void put32(byte[] a, int off, long v) {
        a[off] = (byte) (v & 0xFF);
        a[off + 1] = (byte) ((v >>> 8) & 0xFF);
        a[off + 2] = (byte) ((v >>> 16) & 0xFF);
        a[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    /** ASCII 定长字段（FAT 的 OEM 名/卷标/类型串都是空格补齐） */
    public static void putAscii(byte[] a, int off, String s, int len) {
        final byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            a[off + i] = i < raw.length ? raw[i] : (byte) ' ';
        }
    }

    public static String ascii(byte[] a, int off, int len) {
        return new String(a, off, len, java.nio.charset.StandardCharsets.US_ASCII);
    }
}

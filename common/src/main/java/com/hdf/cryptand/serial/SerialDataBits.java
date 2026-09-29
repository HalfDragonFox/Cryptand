package com.hdf.cryptand.serial;

/** 串口数据位宽（标准 5/6/7/8） */
public enum SerialDataBits {
    DATA_5(5), DATA_6(6), DATA_7(7), DATA_8(8);

    /** 位宽数值 */
    public final int bits;

    SerialDataBits(int bits) {
        this.bits = bits;
    }
}

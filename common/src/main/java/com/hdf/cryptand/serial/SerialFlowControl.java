package com.hdf.cryptand.serial;

/** 串口流控（硬件 RTS/CTS 与软件 XON/XOFF 可组合） */
public enum SerialFlowControl {
    NONE, RTS_CTS, XON_XOFF, RTS_CTS_XON_XOFF
}

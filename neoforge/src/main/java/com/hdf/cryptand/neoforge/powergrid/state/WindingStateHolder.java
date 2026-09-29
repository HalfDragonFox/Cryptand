/**
 * ===== 励磁绕组自身状态持有者 =====
 *
 * 数据直接存到励磁绕组 BlockEntity 实例（mixin @Unique 字段），不走全局注册表。
 * 换向器通过 level 本地查询附近励磁绕组实例后强转本接口读取频率/交流标记。
 */
package com.hdf.cryptand.neoforge.powergrid.state;

public interface WindingStateHolder {

    /** 励磁网络频率（Hz），0 = 直流 */
    double cryptand$getFreq();

    /** 励磁是否交流（频率 ≥ motorMaxDriveFrequencyHz） */
    boolean cryptand$isAc();

    /** tick 内更新状态 */
    void cryptand$updateFreq(double freq, boolean ac);
}

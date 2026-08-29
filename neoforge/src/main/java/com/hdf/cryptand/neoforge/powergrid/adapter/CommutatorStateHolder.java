/**
 * ===== 换向器（电动机/发电机）自身状态持有者 =====
 *
 * 数据直接存到换向器 BlockEntity 实例（mixin @Unique 字段），不走全局注册表。
 * 其他实体（励磁绕组/发电机线圈）通过 level 本地查询换向器实例后强转本接口读取。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

public interface CommutatorStateHolder {

    /** 电枢网络频率（Hz），0 = 直流 */
    double cryptand$getArmFreq();

    /** 本机励磁频率（Hz，来自附近励磁绕组） */
    double cryptand$getExcFreq();

    /** 电枢输入功率（W，getPower()） */
    float cryptand$getPower();

    /** 电枢电流（A，getCurrent()）——真实铜耗 I²R 用 */
    float cryptand$getCurrent();

    /** 加在换向器（电枢）上的电压（V） */
    double cryptand$getVoltage();

    /** 实际转子转速频率（Hz，|ω|/2π）；堵转=0 */
    double cryptand$getRotorFreq();

    /** 本机励磁是否交流 */
    boolean cryptand$isAcExcited();

    /** 励磁/电枢类型是否不匹配 */
    boolean cryptand$isMismatched();

    /** tick 内更新全部状态 */
    void cryptand$updateState(double armFreq, double excFreq, float power, float current,
                              double voltage, double rotorFreq, boolean acExcited, boolean mismatched);
}

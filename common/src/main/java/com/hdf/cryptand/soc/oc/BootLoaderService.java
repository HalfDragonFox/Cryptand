/**
 * ===== 引导服务（Cryptand Boot 的宿主侧契约，2026-09-17 / 重塑 2026-09-27）=====
 *
 * <p>用户定案（2026-09-27 原话）：<b>"EEPROM 属于外部 BIOS 部分，然后虚拟机内为真实芯片模拟"</b>、
 * <b>"BIOS 类似现实做引导，负责从盘中读取文件加载到虚拟机内运行"</b>。</p>
 *
 * <p>⇒ 本接口不是"宿主替 guest 找系统"，而是 <b>BIOS 的读盘服务</b>，现实对应 = <b>INT 13h</b>。
 * 两级分工照现实 PC 摆：</p>
 * <pre>
 *   第一级  Cryptand BIOS（宿主 Java = EEPROM／主板 BIOS flash + 上电取指那一段）
 *           枚举候选盘 → 按 {@link com.hdf.cryptand.soc.os.BootPlan} 选**第一有效文件**
 *           → 写进 guest 内存 0x0 → **启动虚拟机**
 *           → 把「引导盘是哪一块」当参数交给程序（= INT 19h 传 DL，见宿主侧 declareBootDisk）
 *           → 之后不再替它做决定，但**继续提供读盘服务**（就是本接口）
 *   第二级  bootloader（0x0 上那段程序，跑在虚拟机里）
 *           经本接口把**系统文件**从引导盘读进 guest 内存 → 跳过去
 *           （裸机语义：交出去不回来）
 * </pre>
 *
 * <p>⚠ 为什么"读盘"这件事留在宿主，而这**不**违反"虚拟机内是真实芯片模拟"：
 * 读盘不是策略，是<b>硬件能力</b>。真实机器上 INT 13h 也确实跑在 CPU 上，但它读的是主板挂在
 * CPU 上的盘控制器；我们的盘是 OC 的 <b>filesystem 组件</b>（open/read/seek，必须经
 * {@code machine.invoke}），芯片侧根本没有这条总线 —— 所以由宿主兑现。
 * 芯片侧的真相没变：**它从 0x0 执行，盘对它只是"经 BIOS 服务可达的设备"**，
 * 而不是"宿主替它把系统读好塞给它"。</p>
 *
 * <p>所以这里<b>只有</b>一个方法。盘是 BIOS 选好的，bootloader 不参与选盘 ——
 * 旧契约的 {@code diskCount()} + {@code loadProgram(index)}（"bootloader 自己枚举盘"）与后来的
 * {@code loadSystem()}（"宿主替 guest 决定读哪块盘的哪个文件"）都已删除：它们各自把一份**策略**
 * 塞进了宿主，而那正是引导程序自己的事。</p>
 */
package com.hdf.cryptand.soc.oc;

public interface BootLoaderService {

    /**
     * 从 **BIOS 选定的那块盘**上读一个文件，放进 guest 内存的 {@code loadAddr}
     * —— <b>INT 13h 的等价物</b>（BIOS 读盘，由引导程序请求）。
     *
     * <p>两个入参都来自 guest，这正是 INT 13h 的形状：<b>读哪个文件</b>由引导程序说，
     * <b>放到哪个地址</b>也由引导程序说（ES:BX）。宿主只做"打开盘、读字节、写进内存"，
     * 不替 guest 挑文件、也不替它改地址。</p>
     *
     * @param path     盘上的路径（NUL 已由核心剥掉），例如 {@code /boot/system.bin}
     * @param loadAddr guest 物理载入地址（**调用方指定**；落在引导区 0x0..64KB 里会被拒 ——
     *                 那是引导程序此刻正在执行的 ROM 区，对照 {@code BootPlan.LOADER_ROM_BYTES}）
     * @return 长度 &gt; 0 时：{@code [长度, 载入地址]}；读不到返回 {@code null}
     *         （bootloader 会据它打 "no system file on the boot drive" 并停机，不会静默跳空）
     */
    long[] readFile(String path, long loadAddr);
}

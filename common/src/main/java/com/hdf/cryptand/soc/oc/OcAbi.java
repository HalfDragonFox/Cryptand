/**
 * ===== Cryptand ⇄ OpenComputers 的 ABI 约定（纯 Java，零 MC / 零 OC 依赖，2026-09-16）=====
 *
 * <p>C 固件（RV32）通过 **MMIO 寄存器组** 发起组件调用，本类定义这套寄存器的布局与状态机。
 * 这么做的好处：与 soc 既有的 {@code RegBankDevice} 完全同构 —— 设备侧只看寄存器读写，
 * 不引入任何新概念（用户在评估中确认"组件 ABI 先 MMIO 打底"）。</p>
 *
 * <h3>协议（一问一答，单通道）</h3>
 * <pre>
 *   固件：写 COMPONENT / METHOD / ARG_COUNT / ARG0..ARGn  →  写 CALL = 1
 *   核心：读到 CALL=1 ⇒ 组包交给 ComponentBus ⇒ 结果写 RESULT0..n / RESULT_COUNT ⇒ STATUS = DONE(2) 或 ERROR(3)
 *   固件：轮询 STATUS（DONE/ERROR）→ 取结果 → 写 STATUS = IDLE(0) 释放通道
 * </pre>
 *
 * <p>⚠ 二期可加 ecall 通道（RV32 系统调用）与 C 头文件封装（{@code component_invoke(...)}），
 * 但**寄存器通道始终保留**：它是唯一能脱离 MC 独立自测的形态。</p>
 */
package com.hdf.cryptand.soc.oc;

public final class OcAbi {

    private OcAbi() {
    }

    // ==================== 寄存器偏移（相对设备基址）====================

    /** 请求通道：写 1 触发一次调用（核心读走后清零） */
    public static final int REG_CALL = 0x00;
    /** 状态：0=空闲 1=忙 2=完成 3=错误（核心写，固件读） */
    public static final int REG_STATUS = 0x04;
    /** 组件句柄（平台预注册的整数 id；固件用 LIST 得到映射） */
    public static final int REG_COMPONENT = 0x08;
    /** 方法 id（平台预注册；未知 ⇒ STATUS=ERROR） */
    public static final int REG_METHOD = 0x0C;
    /** 参数个数 */
    public static final int REG_ARG_COUNT = 0x10;
    /** 参数区：ARG0..ARGn（每个 4 字节；字符串/字节数组按"缓冲区模式"另走 REG_BUF_*） */
    public static final int REG_ARG0 = 0x20;
    /** 参数区最大槽数 */
    public static final int ARG_SLOTS = 8;
    /** 返回值个数（核心写） */
    public static final int REG_RESULT_COUNT = 0x60;
    /** 结果区：RESULT0..RESULTn */
    public static final int REG_RESULT0 = 0x70;
    /** 结果区最大槽数 */
    public static final int RESULT_SLOTS = 8;
    /** 错误码（STATUS=ERROR 时有效；核心写） */
    public static final int REG_ERROR = 0xB0;
    /** 组件列表游标（固件遍历 components() 用） */
    public static final int REG_LIST_INDEX = 0xB4;
    /** 列表项：地址低 32 位 / 高 32 位 */
    public static final int REG_LIST_ADDR_LO = 0xB8;
    public static final int REG_LIST_ADDR_HI = 0xBC;
    /** 列表项：组件名 id */
    public static final int REG_LIST_NAME = 0xC0;
    /** 列表项个数（核心写） */
    public static final int REG_LIST_COUNT = 0xC4;
    /**
     * 缓冲区地址（固件写）：字符串 / 字节数组参数所在的 **guest 物理地址**。
     *
     * <p>标量参数走 {@link #REG_ARG0} 之外，凡是"一串字节"的参数（{@code gpu.set} 的文本、
     * {@code gpu.blit} 的 w×h 字符阵列）都放 guest 内存里，用这两个寄存器把地址和长度交给宿主，
     * 由宿主调 {@code SocBoard.readMemory} 取出来。固件侧见
     * {@code excode/firmware/common/hal.c} 的 {@code OC_REG_BUF_ADDR}。</p>
     */
    public static final int REG_BUF_ADDR = 0xC8;
    /** 缓冲区长度（固件写）；0 = 本次调用没有缓冲区参数 */
    public static final int REG_BUF_LEN = 0xCC;
    /**
     * 缓冲区**实际用量**（宿主写，固件读；0 = 本次没用缓冲区）—— 0xD0 起有空位。
     *
     * <p>⚠ 只服务**寄存器通道**（引导服务那套）。走邮箱通道的文件操作**不需要它**：
     * 邮箱的 {@code MB_RESULT0} 已经在写回路径上（{@code FS_READ} → 实读字节数 / EOF = -1；
     * {@code FS_LIST} → 写入字节数）。留着它是为了两条通道的 ABI 描述保持同构，
     * 也给"寄存器通道也要做文件 I/O"留位。</p>
     */
    public static final int REG_BUF_USED = 0xD0;
    /** 单次缓冲区上限（防御：固件写疯了也不至于让宿主读爆内存） */
    public static final int BUF_MAX = 64 * 1024;
    /** 设备寄存器区总大小（装配时按此预留地址空间） */
    public static final int REG_SPAN = 0x100;

    // ==================== 外部设备邮箱区（内存映射，2026-09-17 用户定案）====================
    //
    // 用户定案原文：「寄存器仅限自己的内容，外部设备全部靠类似 linux 或者映射为一段内存操作，
    // 然后写入后可以由程序决定是否等待，或者通过状态机在执行其他任务时看一眼，所有外设当作耗时操作」。
    //
    // 所以职责被一分为二：
    //   · 内部外设（UART/TIMER/GPIO/PWM/ADC/INTC）→ 用上面的 REG_* **寄存器**（本地、零往返）；
    //   · 外部设备（OC 组件：GPU/屏幕/磁盘/网络/键盘…）→ 用这里的**内存邮箱**，全程不碰 MMIO。
    //
    // ⚠ 为什么必须这样（实测算出来的）：寄存器区在 MMIO 空间，**每一次 sw/lw 都是一次完整往返**
    //   （native 停下 → JNI 消息 → Java 消息泵 → 设备模型 → mmioResult → native 继续，实测 ≈0.44ms）。
    //   固件按老协议自旋读 STATUS 时每 5 个周期就 lw 一次 ⇒ 把 100 MHz 的机器拖到 **0.01 MHz**
    //   （日志指纹：devices/cycles ≈ 1/5）。而**轮询 RAM 是零往返的** —— 只烧自己的 CPU 周期，
    //   不产生任何跨宿主交互。这就是这套邮箱区能成立的根因。

    /**
     * 邮箱区基址：紧跟在**固件主 RAM 之后**的一段普通 RAM（不是 MMIO）。
     *
     * <p>⚠ 这里的位置是这套设计成立与否的关键，踩过一次就懂：native 内核**自持 RAM**，
     * 只有落在它 {@code ramBase .. ramBase+ramSize} 范围内的地址才是"普通内存访问"（零往返）；
     * 范围之外一律变成 **MMIO 事务**（一次完整往返 ≈0.44ms）。所以邮箱**绝不能**放独立窗口
     * （如 0x3000_0000）—— 那样每次读写邮箱都要跨宿主一次，等于把刚修好的病又请回来。</p>
     *
     * <p>于是采用：<b>RAM 装配时按 {@code 固件RAM + MAILBOX_SPAN} 分配</b>，邮箱占据固件
     * 链接脚本用不到的那一段（固件只认前 128KB）。这样固件对邮箱的 sw/lw 就是最普通的
     * 内存访问，宿主侧则用已经打通（修"屏幕全黑"时建立）的 {@code CpuCore.readMemory/writeMemory}
     * 读写同一块内存 —— 双方共享内存，零 MMIO、零 JNI 往返。</p>
     */
    public static final long MAILBOX_BASE = 0x2002_0000L;   // = RAM_BASE(0x2000_0000) + 128K

    /** 通道数：多通道才能让"多个外设请求同时挂着"（流水线），而不必串行等 */
    public static final int MAILBOX_CHANNELS = 64;

    /**
     * 每通道结构跨度（字节）—— **必须是 96（0x60）**，不是"看起来够用"的 64。
     *
     * <p>本通道的字段一直排到 {@link #MB_BUF_LEN}(0x5C) + 4 = 0x60。若按 64 排，通道 i 的
     * BUF_ADDR/BUF_LEN 就正好落在**通道 i+1 的 ARG1/ARG2** 上（相对 i+1 基址的 +0x18/+0x1C）
     * —— 单请求串行时看不出来（当前只用通道 0），一旦多通道并发就会互相踩参数。
     * 2026-09-17 定稿 ABI 时核对出来，固件侧 {@code OC_MB_STRIDE} 必须同步改 96。</p>
     */
    public static final int MAILBOX_STRIDE = 96;

    /** 邮箱区总大小（装配时按此预留地址空间） */
    public static final int MAILBOX_SPAN = MAILBOX_CHANNELS * MAILBOX_STRIDE;

    /** 通道内字段偏移：请求状态机（固件写 REQUEST，宿主写 BUSY/DONE/ERROR） */
    public static final int MB_STATE = 0x00;
    /** 序号（宿主回写时带上，固件比对以识别 ABA） */
    public static final int MB_SEQ = 0x04;
    /** 组件句柄（组件表下标） */
    public static final int MB_COMPONENT = 0x08;
    /** 方法 id */
    public static final int MB_METHOD = 0x0C;
    /** 标量参数个数 */
    public static final int MB_ARGC = 0x10;
    /** 8 个标量参数（各 4 字节）：0x14 .. 0x30 */
    public static final int MB_ARG0 = 0x14;
    /** 返回个数 */
    public static final int MB_RESULT_COUNT = 0x34;
    /** 8 个返回值（各 4 字节）：0x38 .. 0x54 */
    public static final int MB_RESULT0 = 0x38;
    /** 缓冲区地址（guest 物理地址；大块数据仍在 RAM 里，零额外拷贝） */
    public static final int MB_BUF_ADDR = 0x58;
    /** 缓冲区长度 */
    public static final int MB_BUF_LEN = 0x5C;

    /** 通道状态：空闲 */
    public static final int MB_STATE_IDLE = 0;
    /** 通道状态：固件已投递请求，等宿主认领 */
    public static final int MB_STATE_REQUEST = 1;
    /** 通道状态：宿主已认领，正在执行 */
    public static final int MB_STATE_BUSY = 2;
    /** 通道状态：完成，结果已写回（固件可读 RESULT_*） */
    public static final int MB_STATE_DONE = 3;
    /** 通道状态：失败（错误码见 MB_ERROR 槽，复用 REG_ERROR 的取值域） */
    public static final int MB_STATE_ERROR = 4;

    /** 通道基址（按下标） */
    public static long mailboxChannel(int index) {
        return MAILBOX_BASE + (long) index * MAILBOX_STRIDE;
    }

    // ==================== 组件句柄与方法 id（与固件 hal.h 一一对应）====================

    /**
     * 组件表下标：GPU（显卡）。
     *
     * <p>⚠ OC 里**画字符的是 gpu 组件**（{@code li.cil.oc.server.component.GraphicsCard}），
     * screen 组件只有开关/分辨率/触摸这类显示控制，**没有 set/fill** —— 所以屏幕输出必须走 gpu。
     * 另外首次绘制前要 {@code gpu.bind(屏幕地址)}。</p>
     */
    public static final int HANDLE_GPU = 0;
    /** 组件表下标：屏幕（显示控制） */
    public static final int HANDLE_SCREEN = 1;
    /** 组件表下标：键盘 */
    public static final int HANDLE_KEYBOARD = 2;

    /** GPU 方法：{@code set(x, y, value:string[, vertical:boolean])} —— 缓冲区 = 文本 */
    public static final int GPU_METHOD_SET = 0;
    /** GPU 方法：{@code fill(x, y, width, height, char:string)} —— 缓冲区 = 1 字节字符 */
    public static final int GPU_METHOD_FILL = 1;
    /** GPU 方法：{@code getResolution()} */
    public static final int GPU_METHOD_GET_RES = 2;
    /** GPU 方法：{@code setForeground(value[, palette])} */
    public static final int GPU_METHOD_SET_FG = 3;
    /** GPU 方法：{@code setBackground(value[, palette])} */
    public static final int GPU_METHOD_SET_BG = 4;
    /**
     * GPU 方法：{@code blit(x, y, w, h)} + 缓冲区 = w×h 字节的字符阵列（一次画一整屏）。
     *
     * <p>⚠ OC 组件层**没有**"一次写一块字符"的方法（{@code bitblt} 是显存页之间的拷贝，
     * 参数是 buffer 索引），所以宿主侧要把它**拆成 h 次 {@code set(x, y+i, 第 i 行字符串)}**。</p>
     */
    public static final int GPU_METHOD_BLIT = 5;

    /**
     * GPU 方法 id → OC 组件方法名。
     *
     * <p>核心把请求建模成 {@code "#<id>"}（见 {@code OcArchitectureCore.pumpCall}），
     * 由平台层的 {@code ComponentBus} 用本表翻成 OC 的真实方法名。</p>
     */
    // ==================== 方法声明表（唯一来源，2026-09-18 收敛）====================
    //
    // 为什么要有这张表（用户 2026-09-18："继续"）：官方把"这次调用能不能在工作线程直接跑、
    // 吃不吃预算"写在回调注解里（{@code @Callback(direct/limit)}，li.cil.oc.api.machine.Callback），
    // 而我们是**散落判断** —— 方向表在 isOutbound、方法名在 gpuMethodName/fsMethodName、
    // 预算与线程处理写在组件桥各处。收敛成一张表后：宿主分发、固件 hal、闸门断言
    // 都从同一处读，"第二处判断"从此不存在。
    //
    // 字段含义：
    //   component  —— 组件名（gpu / filesystem / pe / disk / boot）
    //   methodId   —— ABI 方法号（各组件自成一域，配 component 才唯一）
    //   name       —— OC 侧方法名（宿主按它分发；空串 = 本 ABI 的便捷方法，OC 侧没有同名）
    //   outbound   —— 宿主 → guest 的字节流（要把数据写进固件自带的缓冲区）
    //   mainThread —— 是否必须在主线程兑现（**我们自己的编目**：碰世界 / 碰 OC 的 managed 状态）
    //   limit      —— OC direct 预算的除数（0 = 不吃预算；具体数值以 OC 回调注解为准）
    //
    // ⚠ mainThread 列是"当前分发的编目"，用于后续把线程决策也收敛到这里；
    //   在收敛完成前，实际执行者仍是组件桥（见 OcComponentBus / OcArchitectureCore.drainCalls）。
    public record MethodSpec(String component, int methodId, String name,
                             boolean outbound, boolean mainThread, int limit) {
    }

    /** 表缓存（懒建：Java 不允许静态字段前向引用，而这张表偏偏要引用下面那些常量） */
    private static java.util.List<MethodSpec> cache;

    /** 方法表（顺序无关；查表用 component+methodId）—— 首次调用时建立并缓存 */
    public static java.util.List<MethodSpec> methods() {
        java.util.List<MethodSpec> c = cache;
        if (c == null) {
            c = java.util.List.of(
            // ---- GPU（0..5）：字符绘制走组件桥，可在工作线程直调 ----
            new MethodSpec(GPU_COMPONENT_NAME, GPU_METHOD_SET, "set", false, false, 0),
            new MethodSpec(GPU_COMPONENT_NAME, GPU_METHOD_FILL, "fill", false, false, 0),
            new MethodSpec(GPU_COMPONENT_NAME, GPU_METHOD_GET_RES, "getResolution", false, false, 0),
            new MethodSpec(GPU_COMPONENT_NAME, GPU_METHOD_SET_FG, "setForeground", false, false, 0),
            new MethodSpec(GPU_COMPONENT_NAME, GPU_METHOD_SET_BG, "setBackground", false, false, 0),
            new MethodSpec(GPU_COMPONENT_NAME, GPU_METHOD_BLIT, "blit", false, false, 0),
            // ---- filesystem（16..31）：盘操作要碰 OC 的 manifest/句柄表 ⇒ 主线程兑现 ----
            new MethodSpec("filesystem", FS_OPEN, "open", false, true, 0),
            new MethodSpec("filesystem", FS_READ, "read", true, true, 0),
            new MethodSpec("filesystem", FS_WRITE, "write", false, true, 0),
            new MethodSpec("filesystem", FS_SEEK, "seek", false, true, 0),
            new MethodSpec("filesystem", FS_CLOSE, "close", false, true, 0),
            new MethodSpec("filesystem", FS_LIST, "list", true, true, 0),
            new MethodSpec("filesystem", FS_DELETE, "delete", false, true, 0),
            new MethodSpec("filesystem", FS_RENAME, "rename", false, true, 0),
            new MethodSpec("filesystem", FS_MKDIR, "makeDirectory", false, true, 0),
            new MethodSpec("filesystem", FS_EXISTS, "exists", false, true, 0),
            new MethodSpec("filesystem", FS_SIZE, "size", false, true, 0),
            new MethodSpec("filesystem", FS_IS_DIR, "isDirectory", false, true, 0),
            new MethodSpec("filesystem", FS_LAST_MODIFIED, "lastModified", false, true, 0),
            new MethodSpec("filesystem", FS_SPACE, "spaceTotal", false, true, 0),
            new MethodSpec("filesystem", FS_IS_READONLY, "isReadOnly", false, true, 0),
            new MethodSpec("filesystem", FS_STAT, "", false, true, 0),
            // ---- PE（装机服务，0..4）：全部回文本 ⇒ 出方向；宿主自己干活，主线程 ----
            new MethodSpec(PE_COMPONENT_NAME, PE_METHOD_TARGETS, "targets", true, true, 0),
            new MethodSpec(PE_COMPONENT_NAME, PE_METHOD_FORMAT, "format", true, true, 0),
            new MethodSpec(PE_COMPONENT_NAME, PE_METHOD_INSTALL, "install", true, true, 0),
            new MethodSpec(PE_COMPONENT_NAME, PE_METHOD_INSTALL_ALL, "installAll", true, true, 0),
            new MethodSpec(PE_COMPONENT_NAME, PE_METHOD_INFO, "info", true, true, 0),
            // ---- 块级盘服务（0..2）：读是出方向、写是入方向 ----
            new MethodSpec(DISK_COMPONENT_NAME, DISK_METHOD_INFO, "diskInfo", false, true, 0),
            new MethodSpec(DISK_COMPONENT_NAME, DISK_METHOD_BLOCK_READ, "blockRead", true, true, 0),
            new MethodSpec(DISK_COMPONENT_NAME, DISK_METHOD_BLOCK_WRITE, "blockWrite", false, true, 0),
            // ---- 引导服务（0）：BIOS 从引导盘读一个文件写进 guest 内存（写 guest 内存，由引导专用路径处理）----
            new MethodSpec("boot", BOOT_METHOD_READ_FILE, "readFile", false, true, 0));
            cache = c;
        }
        return c;
    }

    /** 表里的一条（找不到 ⇒ null）—— 组件名 + 方法号是复合键 */
    public static MethodSpec spec(String component, int methodId) {
        for (final MethodSpec m : methods()) {
            if (m.component().equals(component) && m.methodId() == methodId) {
                return m;
            }
        }
        return null;
    }

    // （methods() 就在表旁边实现，这里不再重复定义 —— 同一件事只有一处）

    /**
     * GPU 方法 id → OC 组件方法名（表驱动；与 OC 的 GraphicsCard 方法同名）。
     */
    public static String gpuMethodName(int methodId) {
        return methodName(GPU_COMPONENT_NAME, methodId);
    }

    /**
     * 文件系统方法 id → OC 侧方法名（表驱动）。
     *
     * <p>FS_STAT 的表项 name 为空 —— 它是本 ABI 的便捷补充，由宿主一次读多个查询拼出来，
     * OC 侧没有同名方法。</p>
     */
    public static String fsMethodName(int methodId) {
        return methodName("filesystem", methodId);
    }

    private static String methodName(String component, int methodId) {
        final MethodSpec m = spec(component, methodId);
        return m == null ? "" : m.name();
    }


    // ==================== 文件系统方法 id（16..31，2026-09-18）====================
    //
    // 依据：.ai_cache/cryptand-fs-design.md §3.3（ABI 定稿表）。设计原则三条：
    //   ① 语义一一对应 OC 的 li.cil.oc.api.fs.FileSystem（错误语义照 §3.3.3 的表）；
    //   ② 粒度照顾裸机 C + FreeRTOS + 128KB RAM —— 只有"标量进/出、缓冲区进/出"，
    //      **不引入任何"对象"**；缓冲区一律由固件自带（地址 + 容量），宿主只回答长度；
    //   ③ id 接在 GPU（0..5）之后：6..15 留给 GPU 扩展/screen/keyboard，文件系统从 16 起。
    //
    // 缓冲区方向由**方法 id** 决定（固件与宿主共用下面这张表，没有第三种状态）：
    //   入 = 固件→宿主（宿主读 guest 内存）：FS_OPEN / FS_WRITE / FS_DELETE / FS_RENAME /
    //        FS_MKDIR / FS_EXISTS / FS_SIZE / FS_IS_DIR / FS_LAST_MODIFIED / FS_STAT
    //   出 = 宿主→guest（宿主写 guest 内存）：FS_READ / FS_LIST
    //   出方向的"实际写了多少字节"用 RESULT0 表达（FS_READ: 字节数 / EOF=-1；
    //   FS_LIST: 写入字节数），**不需要**额外寄存器 —— 邮箱通道下 MB_RESULT0 已经在写回路径上。

    /** 16：{@code open(path, mode)} → result0 = handle(>0)；mode 0=r 1=w 2=a 3=r+ 4=w+ 5=a+ */
    public static final int FS_OPEN = 16;
    /** 17：{@code Handle.read(into)} → result0 = 实读字节数，**-1 = EOF**；出方向缓冲区 */
    public static final int FS_READ = 17;
    /** 18：{@code Handle.write(value)} → result0 = 1/0（照 OC 的 boolean 语义）；入方向缓冲区 */
    public static final int FS_WRITE = 18;
    /** 19：{@code Handle.seek(to)} → arg1 = whence(0=set 1=cur 2=end)、arg2 = 有符号偏移；result0 = 新位置 */
    public static final int FS_SEEK = 19;
    /** 20：{@code Handle.close()} */
    public static final int FS_CLOSE = 20;
    /**
     * 21：{@code list(path)} → result0 = 写入字节数（-1 = 不是目录/不存在）；**同时用入方向与出方向缓冲区**。
     *
     * <p>⚠ 唯一一个"两个方向都要缓冲区"的方法，约定必须记牢（它是 {@link #FS_READ} 那种
     * "只有出方向"的特例的反面）：</p>
     * <ul>
     *   <li>固件把 **path 以 NUL 结尾**放在缓冲区**起始**，{@code MB_BUF_LEN} = <b>容量</b>（不是路径长度）；</li>
     *   <li>宿主先读出路径，再把 NUL 分隔的条目名**原地覆盖**写回缓冲区起始 —— 不新增寄存器，
     *       也不要求固件准备两块缓冲；</li>
     *   <li>result0 = 实际写入量；若它小于"本该返回的长度"，固件据此判断**装不下**（扩缓冲重试）。</li>
     * </ul>
     */
    public static final int FS_LIST = 21;
    /** 22：{@code delete(path)} → result0 = 1/0（非法/非空目录/不存在都是 0，不是错误） */
    public static final int FS_DELETE = 22;
    /** 23：{@code rename(from, to)} → result0 = 1/0；源不存在 ⇒ ERR_NOT_FOUND；缓冲区 = from + NUL + to */
    public static final int FS_RENAME = 23;
    /** 24：{@code makeDirectory(path)} → result0 = 1/0（**递归建父目录**，对齐组件层行为） */
    public static final int FS_MKDIR = 24;
    /** 25：{@code exists(path)} → result0 = 1/0（查询类，永不 ERROR） */
    public static final int FS_EXISTS = 25;
    /** 26：{@code size(path)} → result0 = 字节数（目录/不存在 = 0） */
    public static final int FS_SIZE = 26;
    /** 27：{@code isDirectory(path)} → result0 = 1/0 */
    public static final int FS_IS_DIR = 27;
    /** 28：{@code lastModified(path)} → result0 = Unix 秒 */
    public static final int FS_LAST_MODIFIED = 28;
    /** 29：{@code spaceTotal()/spaceUsed()} → result0 = 总配额、result1 = 已用；-1 = 无限制 */
    public static final int FS_SPACE = 29;
    /** 30：{@code isReadOnly()} → result0 = 1/0 */
    public static final int FS_IS_READONLY = 30;
    /** 31：非 OC 原生（便捷）：一次拿全 exists/isDir/size/mtime/readOnly，供 {@code ls -l} 省往返 */
    public static final int FS_STAT = 31;

    /**
     * 该方法是不是"**出方向**"（宿主 → guest）：调用完成后要把数据写进**固件自带的缓冲区**
     * （{@code MB_BUF_ADDR} = 地址、{@code MB_BUF_LEN} = 容量），并把 {@code result0} 换成
     * **实际写入的字节数**。
     *
     * <p>只有两个：{@link #FS_READ}（读出的字节；EOF 由平台层给 -1）、{@link #FS_LIST}
     * （NUL 分隔的目录名）。其余方法要么是纯标量、要么是"入方向"（固件把数据交给宿主，
     * 例如 path / 待写字节），都不碰出方向缓冲。</p>
     *
     * <p>方向**只由这张表决定**（固件与宿主共用），没有额外的方向标志位 —— 见
     * {@code cryptand-fs-design.md §3.3.2}。</p>
     */
    public static boolean isFsOutbound(int methodId) {
        return isOutbound("filesystem", methodId);
    }

    /** 首个文件系统方法 id（断言用：FS 段必须从 16 起，别挤进 GPU 的 6..15 保留区） */
    public static final int FS_METHOD_FIRST = FS_OPEN;
    /** 末个文件系统方法 id */
    public static final int FS_METHOD_LAST = FS_STAT;

    /**
     * 文件系统方法 id → OC 侧方法名（平台层按名字分发；与 {@link #gpuMethodName} 同构）。
     *
     * <p>{@link #FS_STAT} 没有对应 —— 它是本 ABI 的便捷补充，由宿主一次读多个查询拼出来。</p>
     */
    // ⚠ fsMethodName 已迁到上面的方法声明表（METHODS）—— 原 switch 与表并存就是"第二处判断"，
    //   内测期一律不留：名字、方向、线程、预算四件事只在表里写一次。

    /**
     * {@code FS_OPEN} 的 mode：对应 OC 的 {@code r/w/a/r+/w+/a+}。
     *
     * <p>数值必须与 hal.h 的 {@code OC_FS_MODE_*} 一致（由 scripts/AI/check-abi-sync.mjs 把关）。</p>
     */
    public static final int FS_MODE_R = 0;
    public static final int FS_MODE_W = 1;
    public static final int FS_MODE_A = 2;
    public static final int FS_MODE_RPLUS = 3;
    public static final int FS_MODE_WPLUS = 4;
    public static final int FS_MODE_APLUS = 5;

    /** {@code FS_SEEK} 的 whence（宿主把它翻成 OC 的 {@code SeekFrom}） */
    public static final int FS_SEEK_SET = 0;
    public static final int FS_SEEK_CUR = 1;
    public static final int FS_SEEK_END = 2;

    // ==================== 引导服务（Cryptand Boot 用，2026-09-17）====================

    /**
     * 特殊句柄：引导服务（**不是组件表下标**，核心在泵里优先识别它）。
     * 固件侧见 {@code excode/firmware/cryptand-boot/main.c} 的 OC_HANDLE_BOOT。
     */
    public static final int HANDLE_BOOT = 0x0F;

    /**
     * ===== PE 服务（安装环境，用户 2026-09-26 定案）=====
     *
     * <p>系统软盘引导进来的是 <b>Cryptand OS PE</b>（命令行安装环境），它提供分区 / 格式化 /
     * 安装（含一键安装）命令，UI OS 与非 UI OS 都走这同一个 PE。</p>
     *
     * <p>⚠ 与 {@link #HANDLE_BOOT} 同样是<b>特殊句柄</b>（不是组件表下标）：PE 是"宿主提供的装机能力"，
     * 不属于 OC 机箱里插的任何组件 —— 所以核心在翻译调用时直接把它翻成组件名 {@code "pe"}，
     * 由平台侧的 PeDispatcher 兑现。</p>
     *
     * <p>协议：入方向字符串参数一律走**缓冲区**（{@code address\0fsName\0label} 这种 NUL 分隔形式，
     * 与 {@code FS_RENAME} 的 {@code from\0to} 同构）；出方向把**人类可读的结果文本**以 byte[]
     * 返回 —— 核心会把它写进固件自己的缓冲区，PE 直接打印（不需要另做字符串通道）。</p>
     */
    public static final int HANDLE_PE = 0x10;

    /** PE：列出可安装的目标盘（返回文本：每行 index / address / 容量 / 是否已有系统） */
    public static final int PE_METHOD_TARGETS = 0;
    /** PE：格式化一块盘（args[0] = 分区 KB；缓冲区 = address\0fsName\0label） */
    public static final int PE_METHOD_FORMAT = 1;
    /** PE：把一个程序镜像装进一块盘（缓冲区 = address\0programId） */
    public static final int PE_METHOD_INSTALL = 2;
    /** PE：一键安装 = 格式化 + 安装 + 回读校验（缓冲区 = address\0programId） */
    public static final int PE_METHOD_INSTALL_ALL = 3;
    /** PE：查一块盘的现状（缓冲区 = address） */
    public static final int PE_METHOD_INFO = 4;
    /** PE 方法号上限（越界 ⇒ ERR_UNKNOWN_METHOD） */
    public static final int PE_METHOD_LAST = PE_METHOD_INFO;

    /**
     * GPU 组件名（核心把 {@link #HANDLE_GPU} 翻成这个名字，平台侧按它分派）。
     *
     * <p>用户 2026-09-30 定案：我们的显卡叫 {@code rc_gpu}，与屏的 {@code rc_screen} 对称。
     * 名字的**单一来源**是本常量 —— 平台侧（{@code SocPartKind.CARD_GPU}、{@code OcComponentBus}）
     * 与 OC 组件注册全都引用它，不再各写一遍字面量（写两遍就会漂移）。</p>
     */
    public static final String GPU_COMPONENT_NAME = "rc_gpu";

    /** PE 组件名（核心把 {@link #HANDLE_PE} 翻成这个名字，平台侧按它分派） */
    public static final String PE_COMPONENT_NAME = "pe";

    // ==================== 块级盘服务（通用 C 库的地基，用户 2026-09-18 定案）====================
    //
    // 用户定案原文："把格式化相关代码做成通用 c 的库，然后系统库加入即可"。
    //
    // 为什么必须有这一层：格式化的产物是**盘上的结构** —— 分区、分区内的 BPB / 双 FAT / 根目录。
    // 要让这些结构的"决定权"归 guest 侧的通用 C 库（各系统链接同一份），guest 就必须能
    // **按偏移读写分区的任意字节**；而此前 guest 只有两条路：
    //   · 文件级（FS_* → OC 的 filesystem 组件）—— 只能读写"文件"，碰不到结构；
    //   · PE 通道（HANDLE_PE → 宿主 PeDispatcher）—— 结构由**宿主**写，逻辑不在 guest。
    // 两者都做不到"guest 自己格式化"。所以这里补上第三样：**块级读写**（Host 只当存储）。
    //
    // 分区表的归属（本阶段取舍）：分区表仍由宿主保管（{@code disk.json}，人可读、可离线测），
    // guest 用 DISK_METHOD_SET_PARTITIONS 提交布局；而分区**内部**的每个字节都由 guest 写。

    /** DISK 组件名（核心把 {@link #HANDLE_DISKBLK} 翻成这个名字，平台侧按它分派） */
    public static final String DISK_COMPONENT_NAME = "disk";

    /**
     * 特殊句柄：块级盘服务（与 {@link #HANDLE_BOOT} / {@link #HANDLE_PE} 同为"宿主能力"，
     * 不是机箱里的组件）。
     */
    public static final int HANDLE_DISKBLK = 0x11;

    /**
     * {@code DISK_METHOD_INFO(targetIndex)} → result0 = 容量字节、result1 = 分区数、
     * result2.. = 各分区大小（按分区下标 0 起；受 RESULT_SLOTS 限制最多前 6 个）。
     */
    public static final int DISK_METHOD_INFO = 0;

    /**
     * {@code DISK_METHOD_BLOCK_READ(targetIndex, partIndex, offset, len)} → **出方向**：
     * 宿主把分区的 {@code len} 字节写进 guest 缓冲区（语义与 {@link #FS_READ} 同构）。
     */
    public static final int DISK_METHOD_BLOCK_READ = 1;

    /**
     * {@code DISK_METHOD_BLOCK_WRITE(targetIndex, partIndex, offset)} + 缓冲区 = 待写字节
     * → result0 = 实际写入字节数。**入方向**：数据在 guest 内存里，宿主读出去写盘。
     *
     * <p>写是"读-改-写"（宿主块设备是 1~16MB 的块，而这里按 512B 扇区写）：块级缓存由
     * {@code DiskImage} 负责，**先读块再改再写回** —— 否则一次 512B 的写会把同块内其它数据抹掉。</p>
     */
    public static final int DISK_METHOD_BLOCK_WRITE = 2;

    /** DISK 方法号上限（越界 ⇒ ERR_UNKNOWN_METHOD） */
    public static final int DISK_METHOD_LAST = DISK_METHOD_BLOCK_WRITE;

    /**
     * **出方向判定**（宿主 → guest 缓冲区）：这次调用的返回值里是不是"要写进固件缓冲区的一段字节"。
     *
     * <p>取值的<b>唯一一张表</b>（两侧共用，没有额外的方向标志位）：</p>
     * <ul>
     *   <li>{@code filesystem}：只有 {@link #isFsOutbound} 那几种方法（read / list）；</li>
     *   <li>{@code pe}：<b>所有</b>方法都回文本 —— PE 的输出就是它返回的那段结果文本
     *       （见 PeDispatcher：targets / format / install / install-all / info 都返回人类可读的行）。</li>
     * </ul>
     *
     * <p>⚠ 2026-09-26 真机踩到：出方向判定原先只看 {@code isFsOutbound(methodId)}，
     * PE 的方法号不在表里 ⇒ 平台层明明返回了 byte[]，核心却**不写回 guest 缓冲区** ⇒
     * PE 屏幕上永远显示 "(no output)"（命令跑了、结果丢了）。</p>
     */
    public static boolean isOutbound(String component, int methodId) {
        final MethodSpec m = spec(component, methodId);
        // 表里查不到就是"不是出方向"（原实现会对任意组件套用 FS 的方向表 —— 那样
        // component="gpu" + methodId=17 会被误判成出方向；表驱动后这种误判不可能发生）。
        return m != null && m.outbound();
    }

    /**
     * ===== 引导服务唯一的方法：{@code boot.readFile(path, loadAddr)} —— BIOS 的**读盘服务** =====
     *
     * <p>现实对应就是 <b>INT 13h</b>：BIOS 提供"把盘上的内容读进内存"的服务，由盘上的引导程序
     * <b>请求</b>它。真实 BIOS 自己既不解析文件系统、也不替引导程序决定内核装在哪 ——
     * 它只负责"从盘上读出来、放进你给的内存地址"。所以这里严格照它：</p>
     * <table border="1">
     *   <caption>参数与返回值</caption>
     *   <tr><th>位</th><th>含义</th><th>INT 13h 的对应</th></tr>
     *   <tr><td>缓冲区（{@link #REG_BUF_ADDR}/{@link #REG_BUF_LEN}）</td>
     *       <td>NUL 结尾的**路径**（要哪个文件）</td><td>CHS/LBA = "读盘上的哪一块"</td></tr>
     *   <tr><td>{@code arg0}</td><td>装到 guest 的哪个物理地址</td><td>ES:BX = 目标缓冲区</td></tr>
     *   <tr><td>{@code result0}</td><td>实读字节数（<b>0</b> = 盘上没有这个文件）</td>
     *       <td>AL = 实际读出的扇区数</td></tr>
     *   <tr><td>{@code result1}</td><td>实际载入地址（= 请求的地址；宿主不替它改地址）</td>
     *       <td>（BX 由调用方自己持有）</td></tr>
     * </table>
     *
     * <p>⚠ 服务里**没有"哪块盘"这个参数**：读的就是 BIOS 当前引导的那块盘 ——
     * 与 INT 13h 同一个道理（INT 19h 已经把 DL 设好，之后的 13h 默认读那块盘）。
     * 见 {@link com.hdf.cryptand.soc.os.BootPlan} 与宿主侧 {@code OcBootLoader.declareBootDisk}。</p>
     *
     * <p>⚠ 收口史（2026-09-25 → 09-27），三代的区别值得记住，因为它正是"哪一层在替谁做决定"：</p>
     * <ol>
     *   <li>{@code DISK_COUNT} + {@code LOAD(index)}（最早）：bootloader **自己枚举盘、逐块扫描**
     *       —— 相当于把 BIOS 的选盘搬进了引导程序；</li>
     *   <li>{@code loadSystem()}（2026-09-25 之后）：宿主**替 guest 决定**读哪块盘的哪个文件
     *       （路径写死在宿主 {@code Programs.BOOT_PATH}），而且名字把"BIOS 读盘"说成了
     *       "宿主把系统交给我" —— **策略错位 + 命名失真**；</li>
     *   <li>本方法（2026-09-27 定案："BIOS 类似现实做引导，负责从盘中读取文件加载到虚拟机内运行"）：
     *       只留**通用取文件**这一次调用 —— <b>取哪个文件由 guest 说，怎么读由 BIOS 说</b>。</li>
     * </ol>
     */
    public static final int BOOT_METHOD_READ_FILE = 0;

    // ==================== 句柄空间的分工（用户 2026-09-18 定案）====================
    //
    // 「**芯片内部模块**走自管理，像**硬盘软盘等还是走 OC**」——所以句柄空间只有两段：
    //   0 .. N-1   OC 组件表下标：**盘（filesystem）与 GPU/屏幕/键盘一样在这里**
    //   0x0F       引导服务（HANDLE_BOOT，特殊句柄：它是 OC 之外的引导期服务）
    // 芯片内部模块（TIMER/UART/GPIO/PWM/ADC/INTC）**根本不经过句柄**：固件直接读写板级
    // MMIO 寄存器（本地、零往返），那是"自管理"的含义，不是"另一套句柄"。
    //
    // ⚠ 曾经加过 HANDLE_DISK_BASE（把盘做成"0x10 + 盘序号"的伪组件）—— 已按此定案回滚：
    //   盘走 OC 组件表，固件用 components() 枚举到的下标访问它。

    /** 引导镜像魔数（宿主写进 guest 内存的头 8 字节，Boot 会校验一遍） */
    public static final String APP_MAGIC = "CRYPTAPP";

    // ==================== 状态码 ====================

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_BUSY = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_ERROR = 3;

    // ==================== 错误码 ====================

    public static final int ERR_NONE = 0;
    public static final int ERR_NO_BUS = 1;
    public static final int ERR_UNKNOWN_COMPONENT = 2;
    public static final int ERR_UNKNOWN_METHOD = 3;
    public static final int ERR_BAD_ARGS = 4;
    public static final int ERR_COMPONENT_FAILED = 5;
    public static final int ERR_TIMEOUT = 6;
    // ---- 文件系统段（7..14，照 OC 的异常/返回值语义，见 cryptand-fs-design.md §3.3.3）----
    /** 不存在 / 模式不允许 / 目标是目录（OC 抛 {@code FileNotFoundException}） */
    public static final int ERR_NOT_FOUND = 7;
    /** 句柄无效或已关闭（OC 抛 {@code IOException("bad file descriptor")}） */
    public static final int ERR_BAD_HANDLE = 8;
    /** 空间不足（OC 抛 {@code IOException("not enough space")}） */
    public static final int ERR_NO_SPACE = 9;
    /** 只读盘（OC 抛 {@code IllegalArgumentException("label is read only")} / Drive 的 "drive is read only"） */
    public static final int ERR_READ_ONLY = 10;
    /** 路径非法（OC 抛 {@code IOException("path contains invalid characters")} / 越界路径） */
    public static final int ERR_INVALID_PATH = 11;
    /** 句柄超限（OC 抛 {@code IOException("too many open handles")}） */
    public static final int ERR_TOO_MANY_HANDLES = 12;
    /** 模式/参数非法（OC 抛 {@code IllegalArgumentException("unsupported/invalid mode")}、seek 负数） */
    public static final int ERR_BAD_MODE = 13;
    /** ⚠ 仅 {@link #FS_LIST} 使用：缓冲区装不下（OC 无对应语义 —— ABI 必需补充，
     *  此时 result0 = 所需字节数，让固件扩大缓冲后重试，**绝不静默截断**） */
    public static final int ERR_BUF_TOO_SMALL = 14;

    // ==================== 运行时"配对"约定（平台/核心两侧共用，防拼错）====================

    /** 设备名（soc 板装配时用这个名字挂到 MMIO） */
    public static final String DEVICE_NAME = "OC-BRIDGE";

    /** 能力名（板级 capability 列表里暴露；固件可据此发现桥接是否存在） */
    public static final String CAPABILITY = "oc.bridge";

    /** 人类可读的错误码 → 文本（日志与固件侧调试用） */
    public static String errorText(int code) {
        return switch (code) {
            case ERR_NONE -> "ok";
            case ERR_NO_BUS -> "no component bus";
            case ERR_UNKNOWN_COMPONENT -> "unknown component";
            case ERR_UNKNOWN_METHOD -> "unknown method";
            case ERR_BAD_ARGS -> "bad arguments";
            case ERR_COMPONENT_FAILED -> "component call failed";
            case ERR_TIMEOUT -> "call timeout";
            case ERR_NOT_FOUND -> "not found";
            case ERR_BAD_HANDLE -> "bad handle";
            case ERR_NO_SPACE -> "no space";
            case ERR_READ_ONLY -> "read only";
            case ERR_INVALID_PATH -> "invalid path";
            case ERR_TOO_MANY_HANDLES -> "too many open handles";
            case ERR_BAD_MODE -> "bad mode";
            case ERR_BUF_TOO_SMALL -> "buffer too small";
            default -> "error(" + code + ")";
        };
    }
}

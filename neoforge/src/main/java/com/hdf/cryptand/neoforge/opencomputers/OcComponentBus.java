/**
 * ===== Cryptand ⇄ OpenComputers 的组件总线宿主实现（2026-09-17，P2 兑现组件调用）=====
 *
 * <p>核心侧（common 的 {@code OcArchitectureCore.pumpCall}）把固件的 MMIO 组件调用组包成
 * {@link Call}（方法名先给 {@code "#<方法id>"}），本类把它兑现成 OC 的真实组件调用
 * {@code Machine.invoke(address, "set", args)} —— 屏幕上的文字就是从这里出去的。</p>
 *
 * <h3>分层</h3>
 * <p>核心只见 {@link ComponentBus} 的中间类型（String / Boolean / Integer / Long / Double /
 * byte[]），OC 的 {@code Machine} / {@code Object[]} 只出现在本类里 ⇒ "OC 不加载也能单独跑芯片"
 * 依旧成立（本类不会被实例化）。</p>
 *
 * <h3>线程：就在工作线程上直接调用，不切主线程（依据 OC 源码，2026-09-17 核实）</h3>
 * <ul>
 *   <li>{@code invoke} 由 {@code CryptandOcArchitecture.runThreaded} 调用，而它是 OC 的
 *       <b>工作线程</b>（{@code Machine.scala:1051} {@code threadPool.schedule(this, ...)}）；</li>
 *   <li>OC 官方 Lua 架构同样在工作线程里直接调 {@code machine.invoke}：
 *       {@code luaj/ComponentAPI.scala:67} {@code owner.invoke(() => machine.invoke(address, method, params))}；</li>
 *   <li>{@code Machine.invoke}（{@code Machine.scala:402-424}）只做可见性检查后转发到
 *       {@code Component.invoke}，<b>没有线程断言、没有加锁</b>；</li>
 *   <li>真正的共享状态（屏幕字符缓冲）由 {@code GraphicsCard} 自己保护：它的每个回调都过
 *       {@code screen.synchronized(...)}（{@code GraphicsCard.scala:62-74}），而缓冲刷到客户端
 *       由 OC 主线程 tick 里的 {@code TextBuffer.update()} 负责。</li>
 * </ul>
 * <p>⇒ 同线程直接调用是官方路径，无需 {@code ExecutionResult.SynchronizedCall} 绕主线程。</p>
 *
 * <p>⚠ 与线程无关、但同样必须处理的另一件事是 direct 回调的"调用预算"：{@code Machine.invoke}
 * 对 {@code direct = true} 的回调按 {@code 1/limit} 扣（{@code Callback.java:88}，默认
 * {@code limit = Integer.MAX_VALUE} ≈ 不扣），而<b>显卡自己还会按 tier 再扣一次</b>
 * （{@code setCosts}：T1 = 1/64，{@code GraphicsCard.scala:81}）⇒ <b>逐行写屏幕</b>时 25 行一屏 =
 * 0.39，已经贴着 T1 内存的默认预算 0.5（{@code computer.callBudgets}，{@code application.conf:163}）。
 * 2026-09-17 起 blit 改走"写显存页 + 一次 bitblt"（见下），<b>写页免费、静态画面每次上屏只要
 * 0.001 预算</b>，所以这条"等一个 tick 续画"的路径退化成兜底：只有页路径不可用、
 * 或真的撞上预算时才走 {@link #setWithBudgetRetry(String, int, int, String)}
 * （预算在主线程 tick 里重置，{@code Machine.scala:556}），而不是让后半屏永远画不上。</p>
 *
 * <h3>坐标：1 基，原样透传（不做 ±1）</h3>
 * <p>OC 的 {@code gpu.set/fill/get/copy} 收的是 <b>1 基</b>坐标（内部 {@code checkInteger(i) - 1}，
 * 见 {@code GraphicsCard.scala:499/526/558}），而 Cryptand 固件也是 1 基
 * （{@code console.c} 的 {@code originCol/originRow = 1}、{@code lv_port_disp.c} 的
 * {@code gpu_blit(1, 1, ...)}）⇒ 两侧一致，直接透传。</p>
 *
 * <h3>BLIT = 写显存页 + 一次 bitblt（2026-09-17 改；预算/能耗依据逐条列在下面）</h3>
 * <p>OC 组件层确实没有"一次写一块字符"的方法（{@code gpu.set} 的文本里换行符不会换行：
 * {@code TextBuffer.set} 对 {@code wcwidth <= 0} 的码点直接跳过，{@code TextBuffer.scala:113-157}），
 * <b>但 GPU 自己就持有和屏幕同构的显存页</b>：{@code GpuTextBuffer} 里包着一个
 * {@code util.TextBuffer}，与屏幕用的是同一个类（{@code GpuTextBuffer.scala:21,163-190}）
 * ⇒ 正解是<b>{@code allocateBuffer} 出一块页 → {@code setActiveBuffer(页)} → 逐行写页 →
 * 一次 {@code bitblt(页 → 页 0)}</b>。三条依据：</p>
 * <ul>
 *   <li><b>写页不扣预算也不耗电</b>：{@code resolveInvokeCosts} 只在目标是页 0（绑定的屏幕）时才
 *       {@code consumeCallBudget}/{@code consumePower}，写页直接 {@code true}
 *       （{@code GraphicsCard.scala:124-131}）。原来"h 行 set 到屏幕"在 T1 上是
 *       <b>h×(1/64) 预算 + 码点数×0.0025 能耗</b>（25 行 = 0.39 预算 + 5.0 能耗），
 *       写页则是 <b>0 预算 0 能耗</b>（只花 CPU 时间）。</li>
 *   <li><b>上屏只要一次 bitblt</b>：干净页 0.001 预算，脏页
 *       {@code bitbltCost × 页面积/GPU 上限面积}（{@code GraphicsCard.scala:220-233}）；
 *       能耗按格算 {@code gpuCopyCost/15 ≈ 2.08e-5}（{@code GraphicsCard.scala:235-242}）
 *       ⇒ 同一次整屏上屏约 <b>0.001~0.4 预算 + 0.013 能耗</b>，能耗比逐行 set 少约 120~190 倍
 *       （逐行 set 的能耗是 2.5~5.0）。</li>
 *   <li>⚠ <b>脏页做整屏 bitblt 时 OC 会"故意"先抛一次 {@link LimitReachedException}</b>
 *       （{@code budgetExhausted} 状态机：{@code GraphicsCard.scala:257-276}）⇒
 *       <b>紧接着重试一次</b>就会走"清零剩余预算代价"那一支立刻放行，绝不能当错误处理
 *       （T3/T4 的 {@code bitbltCost} 必然超过 tierCredit，所以每帧都会撞一次）。</li>
 * </ul>
 * <p>页是<b>复用</b>的（只有尺寸变了才 free 后重分配），尺寸取"这块 blit 里真的能显示的部分"
 * （裁剪到屏幕分辨率）：页面积直接决定脏页 blt 的预算代价，裁剪后还保证一定装得进显存
 * （{@code totalVRAM = GPU 上限面积 × vramSize ≥ 屏幕面积}）。</p>
 * <p>只有这些情况才<b>退回逐行 {@code set}</b>（并打一条 warn，屏幕绝不能因为优化变黑）：
 * {@code allocateBuffer}/{@code setActiveBuffer}/{@code bitblt}/{@code getBufferSize} 在运行时
 * OC 里不存在（{@code javap} 已核实本版 8614366 全都有）、显存不够、屏幕不支持 blt、
 * 或 bitblt 重试到上限仍撞预算。</p>
 *
 * <p>⚠ 还有一个必须处理的坑：<b>OC 会把显存页写进存档</b>（{@code GraphicsCard.saveData}，
 * {@code GraphicsCard.scala:678-689}），读档时原样恢复（{@code loadData}，{@code GraphicsCard.scala:661-676}），
 * 而释放只发生在机器收到 {@code computer.stopped} 的时候（{@code GraphicsCard.scala:584-587}）
 * ⇒ 上一次"开着机器存档"，这一次开机就会 {@code not enough video memory}
 * （实测：T1 显卡 vram=800 格，旧页 40x16=640 格 ⇒ 只剩 160 格）。桥在每次开机首次分配失败时
 * <b>一次性回收全部旧页</b>（{@code freeAllBuffers}）再重试一次 —— 这台机器的显示通路只有
 * Cryptand 固件在用（架构不是 Lua，没有别的程序会分配页），可以安全回收。</p>
 *
 * <h3>首次绘制前补 bind</h3>
 * <p>{@code GraphicsCard} 只有 {@code bind(屏幕地址)} 之后才有 {@code screenInstance}，
 * 否则 {@code set/fill/getResolution} 全都返回 {@code (nil, "no screen")}
 * （{@code GraphicsCard.scala:62-74}）⇒ 第一次真正要用屏幕时先 bind 一次，屏幕组件消失/换屏后
 * 会自动重新绑定（见 {@link #invalidateBound()}）。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi;
import com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsSink;
import com.hdf.cryptand.soc.board.DisplayCharset;
import com.hdf.cryptand.soc.board.DisplayPainter;
import com.hdf.cryptand.soc.board.DisplayPresenter;
import com.hdf.cryptand.soc.board.DisplayWindow;
import com.hdf.cryptand.soc.board.ScreenImageQuantizer;
import com.hdf.cryptand.soc.board.ScreenOutputFace;
import com.hdf.cryptand.soc.board.TrueColorScreen;
import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.oc.OcAbi;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class OcComponentBus implements ComponentBus {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    /**
     * 组件名（OC 侧 {@code withComponent(...)} 注册的名字，见各组件源码）。
     *
     * <p>GPU 名取自 ABI 常量的**单一来源**（用户 2026-09-30 定案：我们的显卡叫 {@code rc_gpu}，
     * 与 {@code rc_screen} 对称），不再各写一遍字面量。</p>
     */
    public static final String NAME_GPU = OcAbi.GPU_COMPONENT_NAME;
    public static final String NAME_RC_SCREEN = "rc_screen";
    private static final String NAME_KEYBOARD = "keyboard";

    /** 机箱自身的节点名（{@code Machine.scala:74} {@code withComponent("computer", ...)}） */
    private static final String NAME_COMPUTER = "computer";

    /**
     * 组件表排序：固件用**下标**寻址（{@link OcAbi#HANDLE_GPU}=0 / {@link OcAbi#HANDLE_SCREEN}=1 /
     * {@link OcAbi#HANDLE_KEYBOARD}=2），所以必须把这三类顶到最前面，其余按名字+地址的字典序跟在后面。
     *
     * <p>⚠ 排序是<b>必须</b>的：{@code Machine.components()} 底下是 Scala 的
     * {@code mutable.Map}（{@code Machine.scala:87}），迭代顺序不稳定，不能当"固件的下标"用。</p>
     */
    private static final Comparator<Entry> ORDER = Comparator
            .comparingInt((Entry e) -> rank(e.component()))
            .thenComparing(Entry::component)
            .thenComparing(Entry::address);

    private static final Object[] NO_ARGS = new Object[0];

    /**
     * 最近一帧的整屏文本（每行去尾空格）—— 无人化断言用。
     *
     * <p>为什么必须在这里缓存：屏幕内容只以"w×h 字符阵列"的形式经过组件桥；OC 的 gpu 组件
     * 内部状态在 Scala 侧，外面读不到。所以**这里是宿主唯一能拿到"固件到底画了什么"的地方**
     * （2026-09-25 加：此前只能靠截图 OCR，像素化到无法断言）。</p>
     */
    private volatile String lastFrameText = "";

    /** 最近一帧的非空字节数（诊断） */
    private volatile int lastFrameNonZero;

    /** OC 的 tick 长度（20 tps）：direct 调用预算由主线程每 tick 重置，撞限额时按这个粒度等 */
    private static final long TICK_MILLIS = 50L;

    /** 一次 blit 最多为预算等待几个 tick（超过就报错，避免把工作线程长时间占住） */
    private static final int MAX_BUDGET_WAITS = 4;

    /** 页 0 恒为"绑定的屏幕"（{@code RESERVED_SCREEN_INDEX}），可分配的显存页从 1 开始 */
    private static final int SCREEN_PAGE = 0;

    /**
     * {@code bitblt} 的重试上限（见 {@link #bitbltToScreen}）。
     *
     * <p>第 1 次重试<b>不睡</b>：OC 对"脏页整屏 blt"是<b>故意</b>先抛一次
     * （{@code GraphicsCard.scala:257-276} 的 {@code budgetExhausted} 状态机：置位并抛出，本次不执行；
     * 紧接着再调就会走"清零剩余预算代价"的分支照常执行）。再往后仍失败才是真的没预算
     * （{@code consumeCallBudget} 抛的，{@code Machine.scala:309-317}），只能等下一个 tick 重置。</p>
     */
    private static final int MAX_BITBLT_RETRIES = 3;

    private final Machine machine;

    /** 已 bind 的屏幕地址（空串 = 还没 bind 过；与当前屏幕不符时会重新 bind） */
    private String boundScreen = "";

    /** 复用的显存页索引（{@link #SCREEN_PAGE} = 还没分配；页 0 是屏幕，不能占） */
    private int vramPage = SCREEN_PAGE;

    /** 该页分配时的尺寸；与本次 blit 需要的尺寸不符就 free 后重分配 */
    private int pageWidth;
    private int pageHeight;

    /** 页路径被<b>永久</b>关闭（OC 缺 API / 屏幕不支持 blt / 分配参数被拒）⇒ 之后一律走逐行 set */
    private boolean pagePathDisabled;

    /** 已经回收过"上次存档恢复出来的旧显存页"（每次开机只做一次，见 {@link #allocatePage}） */
    private boolean vramReclaimed;

    /** 图形面（图像）的暂存设备（任务 G；见 {@link #imageFace(int, int)}） */
    private TrueColorScreen imageFace;

    /** 图形面上屏的帧数（诊断/无人化断言） */
    private long imageFrames;

    /** 图形面的色深 = RGB565（与 {@link DisplayWindow#FORMAT_RGB565} 唯一对应；不另立常量表） */
    /**
     * 图形面**暂存设备**的色深：帧（{@link DisplayWindow#FORMAT_RGB565}）就是 16bpp，
     * 暂存面只做"解码 RGB565 / 给字符化读颜色"，所以恒为 16。
     *
     * <p>⚠ 真彩屏**设备**的色深不在这里 —— 它由屏自己声明（{@code sink.bpp()}：
     * 1/8/16/24，见 {@code TrueScreenSettings.screenBppByTier}），编码见
     * {@code ScreenFrameEncoder}。</p>
     */
    private static final int IMAGE_BPP = 16;

    /** 字符屏的调色板最多问这么多档（OC 屏的 16 色；问不到就按问到的档位用，绝不内置色表兜底） */
    private static final int SCREEN_PALETTE_LIMIT = 16;

    /** 诊断计数：拆分出的 set 行数 / blit 帧数 / 走页路径的帧数 / bitblt 重试次数 / 退回逐行的帧数 / 撞预算等待次数 */
    private long blitRows;
    private long blitFrames;
    private long pageBlits;
    private long bitbltRetries;
    private long rowFallbacks;
    private long budgetWaits;
    private long blitBudgetWaits;
    private long failedCalls;

    public OcComponentBus(Machine machine) {
        this.machine = machine;
    }

    // ==================== 组件表 ====================

    /**
     * 机器当前可见组件的有序表（下标 = 固件的组件句柄）。
     *
     * <p>枚举方式取自 OC 的公开 API：{@code Machine.components()} 返回
     * {@code java.util.Map<String,String>}（<b>地址 → 组件名</b>），内容就是
     * {@code Machine.scala:765-776} 维护的那张"可见组件表"（只有通过可见性检查的组件才会进去）。</p>
     *
     * <p>两处必须自己处理：</p>
     * <ul>
     *   <li><b>剔除机箱自身</b>：{@code Machine.scala:681} 把自己（{@code withComponent("computer")}）
     *       也放进了这张表 ⇒ 不过滤的话固件的下标会整体偏移一格；</li>
     *   <li><b>排序</b>：见 {@link #ORDER}。</li>
     * </ul>
     */
    @Override
    public List<Entry> components() {
        final List<Entry> out = new ArrayList<>();
        try {
            final Map<String, String> raw = machine.components();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            // 先做一次快照：这张表由主线程在 tick 里增删（component_added/removed），
            // 工作线程直接迭代底层 HashMap 视图可能撞上结构变化。
            final List<Map.Entry<String, String>> snapshot = new ArrayList<>(raw.entrySet());
            final String self = selfAddress();
            for (final Map.Entry<String, String> e : snapshot) {
                final String address = e.getKey();
                final String name = e.getValue();
                if (address == null || address.isEmpty() || name == null || name.isEmpty()) {
                    continue;
                }
                if (address.equals(self) || NAME_COMPUTER.equals(name)) {
                    continue;                       // 机箱自己不是固件能调的组件
                }
                out.add(new Entry(address, name));
            }
            out.sort(ORDER);
        } catch (Throwable t) {
            noteFailure("components()", t);
            return List.of();
        }
        return List.copyOf(out);
    }

    /** 组件名 → 固件下标优先级 */
    private static int rank(String component) {
        return switch (component) {
            case NAME_GPU -> 0;
            case NAME_RC_SCREEN -> 1;
            case NAME_KEYBOARD -> 2;
            default -> 3;
        };
    }

    /** 机箱自身节点的地址（{@code Machine} 继承 {@code Context}，{@code Context.node()} 即机箱节点） */
    private String selfAddress() {
        try {
            final var node = machine.node();
            return node == null || node.address() == null ? "" : node.address();
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 调用兑现 ====================

    @Override
    public Result invoke(Call call) {
        if (call == null) {
            return Result.error("null call");
        }
        try {
            return dispatch(call);
        } catch (LimitReachedException e) {
            // OC 的 direct 调用预算用完（GraphicsCard 的 setForeground/fill 会显式扣预算）
            noteFailure(call, e);
            return Result.error("call budget exhausted");
        } catch (Throwable t) {
            // 绝不让异常穿到核心的 pumpCall（那里虽有兜底，但错误信息会被吞成 ERR_COMPONENT_FAILED）
            noteFailure(call, t);
            return Result.error(describe(call) + " → " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }

    /** 诊断用：把结果值列表写成"类型:长度"（不打印内容，避免刷屏） */
    private static String describeValues(java.util.List<Object> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            final Object v = values.get(i);
            if (v instanceof byte[] b) {
                sb.append("byte[").append(b.length).append(']');
            } else if (v == null) {
                sb.append("null");
            } else {
                sb.append(v.getClass().getSimpleName()).append(':').append(v);
            }
        }
        return sb.append(']').toString();
    }

    private Result dispatch(Call call) throws Exception {
        // ★ 文件系统（盘走 OC，用户定案）：盘的**语义**是 OC 的 filesystem 组件，
        //   但**实现**是我们的 CryptandFileSystem —— 所以 FS_* 方法段（16..31）直接
        //   落到 FsCallDispatcher，**不能**走 machine.invoke：OC 侧根本不存在 "#24" 这种方法号，
        //   走那条路只会拿到 "unknown method"（实测症状：固件侧 fs_* 全部返回 -ERR_COMPONENT_FAILED）。
        if ("filesystem".equals(call.component())) {
            final int fsMethod = methodId(call.method());
            if (fsMethod >= OcAbi.FS_METHOD_FIRST && fsMethod <= OcAbi.FS_METHOD_LAST) {
                final com.hdf.cryptand.soc.fs.CryptandFileSystem fs = OcDiskMounts.forNode(call.address());
                if (fs == null) {
                    // 诊断（2026-09-18）：把核心给的节点地址与挂载时登记过的地址一起打出来 ——
                    // 两者不一致 ⇒ 登记拿到的是另一个地址（asManagedEnvironment 可能另建节点）；
                    // 集合为空 ⇒ registerNode 压根没被调用。
                    LOG.warn("[OpenComputers] 盘调用找不到文件系统：core.address={} 已登记={}",
                            call.address(), OcDiskMounts.registeredNodes());
                    return Result.error(OcAbi.ERR_UNKNOWN_COMPONENT,
                            "no disk mounted for node " + call.address());
                }
                return new com.hdf.cryptand.soc.fs.FsCallDispatcher(fs)
                        .invoke(fsMethod, call.args(), call.buffer());
            }
        }
        // ★ PE 服务（Cryptand OS PE 的装机能力）：与 filesystem 一样是"我们的组件"，
        //   但它的地址来自核心的固定句柄映射（OcAbi.HANDLE_PE ⇒ "pe"），不走 OC 的组件表。
        //   ⚠ 必须放在 resolveAddress 之前：OC 网络里没有这个组件，去解析只会拿到空地址。
        if (OcPeBridge.ADDRESS.equals(call.component())) {
            final Result pe = OcPeBridge.invoke(methodId(call.method()), call.args(), call.buffer());
            // 诊断（2026-09-26）：PE 的"结果丢弃"问题在两个方向上都有可能 ——
            // 平台层没返回 byte[]（values 里是标量/空），或核心没写回 guest 缓冲区。
            // 这一行把前半段钉死：方法号 / ok / values 的类型与长度。
            LOG.info("[PE] invoke method={} ok={} values={} err={}",
                    call.method(), pe.ok(), describeValues(pe.values()), pe.error());
            return pe;
        }
        final String address = resolveAddress(call);
        if (address.isEmpty()) {
            return Result.error("no such component: " + describe(call));
        }

        final int methodId = methodId(call.method());
        if (methodId < 0) {
            // 非 "#<id>" 形式：理论上不会出现（核心固定发 "#N"），按契约原样当方法名用
            return result(rawInvoke(address, call.method(), ocArgs(call.args()).toArray()));
        }
        if (!isGpu(address, call.component())) {
            return Result.error("method id " + call.method() + " 只在 " + NAME_GPU
                    + " 组件上有映射（组件 = " + call.component() + "）");
        }
        return switch (methodId) {
            case OcAbi.GPU_METHOD_SET -> gpuSet(address, call);
            case OcAbi.GPU_METHOD_FILL -> gpuFill(address, call);
            case OcAbi.GPU_METHOD_BLIT -> gpuBlit(address, call);
            // getResolution 同样依赖 screenInstance（未 bind 时返回 "no screen"）⇒ 一并补 bind
            case OcAbi.GPU_METHOD_GET_RES -> ensureBound(address)
                    ? result(rawInvoke(address, "getResolution", NO_ARGS))
                    : Result.error("no screen");
            default -> {
                final String name = OcAbi.gpuMethodName(methodId);
                if (name.isEmpty()) {
                    yield Result.error("unsupported gpu method id " + call.method());
                }
                yield result(rawInvoke(address, name, ocArgs(call.args()).toArray()));
            }
        };
    }

    /** {@code gpu.set(x, y, value)}：缓冲区是文本（{@link Call#bufferText()} 已按 UTF-8 解好） */
    private Result gpuSet(String gpu, Call call) throws Exception {
        if (!ensureBound(gpu)) {
            return Result.error("no screen");
        }
        declareCharacterPainter();          // 谁在画 = OC 字符 API ⇒ 派生结论落地（ScreenOutputFace）
        final List<Object> args = ocArgs(call.args());
        args.add(call.bufferText());            // 第 3 个参数 = 要画的字符串
        final Object[] r = rawInvoke(gpu, "set", args.toArray());
        final String err = ocFailure(r);
        if (err != null) {
            invalidateBound();                  // 屏幕没了/换屏：下次调用重新 bind
            return Result.error("gpu.set: " + err);
        }
        return result(r);
    }

    /** {@code gpu.fill(x, y, width, height, char)}：缓冲区是 1 字节字符（固件传 {@code &ch}） */
    private Result gpuFill(String gpu, Call call) throws Exception {
        if (!ensureBound(gpu)) {
            return Result.error("no screen");
        }
        declareCharacterPainter();          // 谁在画 = OC 字符 API ⇒ 派生结论落地（ScreenOutputFace）
        final byte[] buffer = call.buffer();
        if (buffer == null || buffer.length == 0) {
            return Result.error("gpu.fill: 缺少字符缓冲区");
        }
        final List<Object> args = ocArgs(call.args());
        args.add(decodeCells(buffer, 0, 1));    // 第 5 个参数 = 填充字符（1 个字符）
        final Object[] r = rawInvoke(gpu, "fill", args.toArray());
        final String err = ocFailure(r);
        if (err != null) {
            invalidateBound();
            return Result.error("gpu.fill: " + err);
        }
        return result(r);
    }

    /**
     * {@code blit(x, y, w, h)} + w×h 字节字符阵列 → 屏幕。
     *
     * <p>行优先（固件 {@code console.c} 的 {@code flushBuf[r * CON_COLS + c]}）⇒ 第 i 行取
     * {@code buffer[i*w, (i+1)*w)}。</p>
     *
     * <p>主路径 = 写复用的显存页 + 一次 {@code bitblt} 上屏（{@link #blitViaPage}）；
     * 页路径这次用不了时退回逐行 {@code set}（{@link #blitRowsLegacy}）。
     * 诊断行里直接写明这一帧走的是哪条（"走显存页 …" / "退回逐行 set …"）。</p>
     */
    private Result gpuBlit(String gpu, Call call) throws Exception {
        final List<Object> args = ocArgs(call.args());
        if (args.size() < 4) {
            return Result.error("blit: 需要 (x, y, w, h) 四个参数，收到 " + args.size() + " 个");
        }
        final int x = asInt(args.get(0));
        final int y = asInt(args.get(1));
        final int w = asInt(args.get(2));
        final int h = asInt(args.get(3));
        if (w <= 0 || h <= 0) {
            return Result.error("blit: 尺寸非法 " + w + "x" + h);
        }
        final long need = (long) w * (long) h;
        final byte[] cells = call.buffer();
        if (cells == null || cells.length < need) {
            return Result.error("blit: 字符阵列不足（需要 " + need + " 字节，收到 "
                    + (cells == null ? 0 : cells.length) + " 字节）");
        }
        if (!ensureBound(gpu)) {
            return Result.error("no screen");
        }
        declareCharacterPainter();          // 谁在画 = OC 字符 API（页↔屏三平面搬运）⇒ 派生结论落地
        // 诊断：只报"真的有内容"的 blit（前 500 帧）。固件每 tick 都整屏重刷，不过滤的话
        // 日志会被开机那几帧空屏淹没 —— 而恰恰是空屏让人误判。
        // 配合固件侧 `[Cryptand OS] flush row0=[...]` 一起看：
        //   固件有字 + 宿主非空字节 0 ⇒ 缓冲区读错了（根因在 REG_BUF_ADDR / guest 读内存）
        //   两边都有字但屏幕仍黑     ⇒ 根因在 OC 侧（屏幕没开 / 分辨率 / 绑到了别的屏幕）
        int nonZero = 0;
        for (int i = 0; i < (int) need; i++) {
            if (cells[i] != 0 && cells[i] != ' ') {
                nonZero++;
            }
        }
        blitFrames++;
        BlitOutcome outcome = pagePathDisabled ? null : blitViaPage(gpu, x, y, w, h, cells);
        if (outcome == null) {
            rowFallbacks++;
            outcome = blitRowsLegacy(gpu, x, y, w, h, cells);
        }
        lastFrameText = frameText(cells, (int) w, (int) h);
        lastFrameNonZero = nonZero;
        if (nonZero > 0 && blitFrames <= 500) {
            LOG.info("[OpenComputers] 组件桥 blit 诊断：{}x{} 于 ({}, {})，非空字节 {}，{}，首行=\"{}\"",
                    w, h, x, y, nonZero, outcome.path(), decodeCells(cells, 0, w));
        }
        return outcome.result();
    }

    // ==================== 显存窗口上屏（宿主接收端，2026-09-18） ====================
    //
    // 与上面 gpuBlit 那条路的区别：数据**不来自组件调用**，而是宿主自己从 guest RAM 里取出来的
    // （用户定案的内存映射显存）。正因为三平面（字符 + 每格前景/背景）都在手上，
    // 每格颜色才是真实的 —— gpu.set 那条路只能用一对"全局当前色"。
    //
    // 做法：按**同色分段**写（见 DisplayPainter）—— 每段一次 setForeground + setBackground +
    // set(整串)。写页不扣 direct 预算、不耗电（GraphicsCard.scala:124-131），
    // 所以颜色分段的代价只是几次 Java 调用，不是预算。

    /**
     * 这台机器当下能不能用 GPU 页上屏、一页装多少格（{@link DisplayPresenter#plan} 的输入）。
     *
     * <p>一页能装多少格 = <b>屏幕面积</b>：OC 的 {@code totalVRAM} 至少覆盖一屏
     * （{@code totalVRAM = GPU 上限面积 × vramSize ≥ 屏幕面积}），而我们写页时总是把页裁剪到
     * "真的能显示"的区域（见 {@link #blitViaPage}）⇒ 整屏永远装得下一页，屏幕面积就是准确答案；
     * 更大的屏会由 {@link DisplayPainter#bands} 拆成多带。</p>
     */
    public DisplayPresenter.Capability presentCapability() {
        if (pagePathDisabled) {
            return DisplayPresenter.Capability.none();
        }
        final String gpu = gpuAddress();
        if (gpu.isEmpty()) {
            return DisplayPresenter.Capability.none();
        }
        try {
            if (!ensureBound(gpu)) {
                return DisplayPresenter.Capability.none();
            }
            final int[] screen = screenSize(gpu);
            if (screen == null) {
                return DisplayPresenter.Capability.none();
            }
            return new DisplayPresenter.Capability(true, screen[0] * screen[1]);
        } catch (Throwable t) {
            // 探测失败只意味着"这一帧按 CPU 走"，不是错误（没显卡/没屏幕/方法缺失都在这里）
            return DisplayPresenter.Capability.none();
        }
    }

    /**
     * 把显存窗口里的一帧搬上屏。
     *
     * @return 实际使用的后端（{@link DisplayWindow#BACKEND_GPU} / {@link DisplayWindow#BACKEND_CPU}），
     *         由调用方回写进窗口，供固件读出来打 UART（无人化断言用）
     */
    public int presentFrame(DisplayWindow.Frame frame, DisplayPresenter.Plan plan) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final String gpu = gpuAddress();
        if (gpu.isEmpty()) {
            // 没显卡：真实硬件下屏幕就是黑的。这里不报错（与 GPU 未 bind 的 "no screen" 同理），
            // 后端的判定留给调用方 —— 它会把 CPU 写回窗口，固件打 UART 时看得见
            return DisplayWindow.BACKEND_CPU;
        }
        try {
            if (!ensureBound(gpu)) {
                return DisplayWindow.BACKEND_CPU;
            }
            declareCharacterPainter();      // 谁在画 = 字符帧（窗口三平面）⇒ 派生结论落地
            if (plan != null && plan.gpu() && !pagePathDisabled) {
                final List<DisplayPainter.Band> bands = DisplayPainter.bands(plan, frame);
                boolean ok = true;
                for (final DisplayPainter.Band band : bands) {
                    if (!presentBandViaPage(gpu, frame, band)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    finishPresentedFrame(frame, "GPU 显存页 + " + bands.size() + " 次 bitblt");
                    return DisplayWindow.BACKEND_GPU;
                }
                rowFallbacks++;
            }
            // CPU 后端：逐行直接写屏（页 0），颜色同样按段设。
            // ⚠ 必须**裁剪到屏幕尺寸**：固件的窗口可能比屏大（它问不到分辨率时用默认 80x25），
            //   越界写会让 OC 的 gpu.set 报错 ⇒ 整帧上屏失败。裁剪是"看得见的部分照画"，
            //   不是兜底路径（页路径那边本来就按 min(frame, screen) 裁）。
            final int[] screen = screenSize(gpu);
            final int rows = screen == null ? frame.rows() : Math.min(frame.rows(), screen[1]);
            final int cols = screen == null ? frame.cols() : Math.min(frame.cols(), screen[0]);
            for (int r = 0; r < rows; r++) {
                paintRows(gpu, frame, r, r, 0, cols - 1, 0, 0);
            }
            finishPresentedFrame(frame, "CPU 逐行写屏（页 0）");
            return DisplayWindow.BACKEND_CPU;
        } catch (Throwable t) {
            noteFailure("显存窗口上屏", t);
            return DisplayWindow.BACKEND_CPU;
        }
    }

    // ==================== 输出面（TEXT / GRAPHICS）的自动派生（任务 G） ====================
    //
    // 用户定案（repo/gpu-auto-convert-output-2026-09-27.md）：
    //   · 程序侧只有一种输出：**画图像**；没有 guest 显式切模式的入口（原设想的 gpu.setMode 已作废）；
    //   · 转字符还是直接画，由**虚拟机（GPU/屏链路）**按「谁在画 × 屏能显示什么」自动决定：
    //       目标是真彩屏 ⇒ 直接写 VRAM 画像素；目标是字符屏 ⇒ 自动把图像量化成字符格 + fg/bg；
    //   · 程序用 OC 的字符 API（gpu.set/fill/copy 传字符）时按字符语义走（真彩屏上就是 TEXT 面）。
    //
    // ★ 规则只有一份：common 的 {@link ScreenOutputFace#derive}（离线闸门 ScreenOutputFaceSelfTest
    //   钉死真值表）。本类只有**两个"谁在画"的入口**各把结论落地一次：字符驱动入口
    //   （{@link #declareCharacterPainter()}）与图形面入口（{@link #presentImage}）。
    //   除此之外**没有任何地方**读/写屏设备的模式位 —— 无人化工具的 action=mode 是调试手段。

    /**
     * 字符驱动的入口：声明"谁在画 = OC 字符 API"，把派生的输出面（恒为 TEXT）落到绑定屏的设备上。
     *
     * <p>为什么要落这一下：图形面写入会把设备置成 GRAPHICS（报文让客户端渲染像素面）；
     * 程序接着用字符 API 画字时必须回到 TEXT —— 否则会出现"程序在打字、屏幕还停在像素帧"这种
     * 无从排查的现象（真机上踩过：模式位被上一次像素测试留在 GRAPHICS）。</p>
     */
    private void declareCharacterPainter() {
        applyFace(ScreenOutputFace.Painter.CHARACTER, TrueScreenGraphicsApi.sink(screenAddress()));
    }

    /**
     * 把派生结论落到设备上。
     *
     * @param sink 绑定屏的像素通道（{@code null} = 这块屏只能显示字符 ⇒ 没有模式位可落）
     * @return 派生出来的输出面
     */
    private TrueColorScreen.Mode applyFace(ScreenOutputFace.Painter painter, TrueScreenGraphicsSink sink) {
        final ScreenOutputFace.Capability capability = ScreenOutputFace.capabilityOf(sink != null);
        final TrueColorScreen.Mode face = ScreenOutputFace.derive(painter, capability);
        if (sink != null) {
            sink.applyDerivedFace(face);
        }
        return face;
    }

    /**
     * 图形面（图像）的暂存设备：程序画的那张图先落到这里，再按目标屏能力"直接画 / 转字符"。
     *
     * <p>用设备（{@link TrueColorScreen}）当暂存而不是自己解字节：RGB565 的<b>解码</b>只有设备
     * 那一处实现（{@code rgbAt}），这里的字节又和设备 16bpp VRAM 布局逐字节一致 ⇒ 真彩屏那一支
     * 连解码都不需要（整块 arraycopy），字符屏那一支借它的 {@code rgbAt} 读颜色。</p>
     */
    private TrueColorScreen imageFace(int width, int height) {
        TrueColorScreen face = imageFace;
        if (face == null || face.width() != width || face.height() != height) {
            face = TrueColorScreen.of(width, height, IMAGE_BPP);
            imageFace = face;
        }
        // 暂存面本身就是"图形面"（谁在画 = IMAGE，屏能显示 = 有像素面）⇒ 由同一条规则派生
        face.setMode(ScreenOutputFace.derive(ScreenOutputFace.Painter.IMAGE,
                ScreenOutputFace.Capability.PIXELS));
        return face;
    }

    /**
     * 把一帧**图形面**（{@link DisplayWindow#FORMAT_RGB565}）输出给屏幕 —— "转字符还是直接画"
     * 由虚拟机自动决定的落点。
     *
     * @return 实际使用的后端（{@link DisplayWindow#BACKEND_GPU} / {@link DisplayWindow#BACKEND_CPU}），
     *         由调用方回写进窗口，供固件读出来打 UART（无人化断言用）
     */
    public int presentImage(DisplayWindow.ImageFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        final String gpu = gpuAddress();
        if (gpu.isEmpty()) {
            return DisplayWindow.BACKEND_CPU;
        }
        try {
            if (!ensureBound(gpu)) {
                return DisplayWindow.BACKEND_CPU;
            }
            final TrueColorScreen face = imageFace(frame.width(), frame.height());
            if (face.vram().length < frame.rgb565().length) {
                LOG.warn("[真彩屏] 图形面暂存装不下：{}x{} 需要 {} 字节，设备只有 {} 字节 —— 本帧放弃",
                        frame.width(), frame.height(), frame.rgb565().length, face.vram().length);
                return DisplayWindow.BACKEND_CPU;
            }
            System.arraycopy(frame.rgb565(), 0, face.vram(), 0, frame.rgb565().length);

            final TrueScreenGraphicsSink sink = TrueScreenGraphicsApi.sink(screenAddress());
            // ★ 唯一的判定：谁在画 = 图形面（IMAGE）× 屏能显示什么（有没有像素面）
            final TrueColorScreen.Mode derived = ScreenOutputFace.derive(
                    ScreenOutputFace.Painter.IMAGE, ScreenOutputFace.capabilityOf(sink != null));
            imageFrames++;
            if (derived == TrueColorScreen.Mode.GRAPHICS) {
                // 目标是真彩屏 ⇒ 直接写 VRAM 画像素（GRAPHICS 面；报文由屏的主线程 tick 发）
                //
                // ⚠ 顺序：先 configure（几何/色深变化会**换掉设备对象**），再落派生面 ——
                //   反过来的话新设备还是默认的 TEXT，报文就会带着 TEXT 让客户端去渲染字符面。
                // ⚠ 色深由**屏**声明（1/8/16/24，用户定案 2026-09-29）：帧仍是 RGB565，
                //   色深差异由 ScreenFrameEncoder 在"写进 VRAM"这一步消化
                //   （16bpp 走整块 arraycopy 快路径，调色板色深走最近色，直色走 setRgb）。
                final int bpp = sink.bpp();
                sink.configure(frame.width(), frame.height(), bpp);
                sink.applyDerivedFace(derived);
                final TrueColorScreen device = sink.device();
                if (device == null || device.width() != frame.width() || device.height() != frame.height()) {
                    LOG.warn("[真彩屏] 图形面直接画像素失败：像素设备不可用或尺寸不符（device={}，帧 {}x{}）—— 本帧放弃",
                            device == null ? "null" : device.width() + "x" + device.height(),
                            frame.width(), frame.height());
                    return DisplayWindow.BACKEND_CPU;
                }
                // 调色板色深先装表（直色设备是空操作），再按设备自己的 bpp 编码整帧
                com.hdf.cryptand.soc.board.ScreenFrameEncoder.preparePalette(device);
                com.hdf.cryptand.soc.board.ScreenFrameEncoder.encodeInto(
                        device, frame.rgb565(), frame.width(), frame.height());
                sink.markFrameChanged();
                LOG.info("[真彩屏] 图形面直接画像素（目标真彩屏）：{}x{} @{}bpp mode={} via=port 第 {} 帧",
                        frame.width(), frame.height(), bpp, device.mode(), imageFrames);
                return DisplayWindow.BACKEND_GPU;
            }
            // 目标只能显示字符 ⇒ 图像自动转字符（唯一的字符化实现）
            return presentImageAsCharacters(gpu, frame, face);
        } catch (Throwable t) {
            noteFailure("图形面上屏", t);
            return DisplayWindow.BACKEND_CPU;
        }
    }

    /**
     * 图形面 → 字符屏那条分支：量化成字符格 + fg/bg，再写进该屏的字符缓冲。
     *
     * <p>字形来自 {@link ScreenImageQuantizer}（背后是 {@code TextFont8x8} 那份唯一字模），
     * 颜色来自<b>该屏自己的调色板</b>（问屏要，不内置色表）—— 没有第二套字形/颜色来源。</p>
     */
    private int presentImageAsCharacters(String gpu, DisplayWindow.ImageFrame frame, TrueColorScreen face) {
        try {
            final int[] screen = screenSize(gpu);
            if (screen == null || screen[0] <= 0 || screen[1] <= 0) {
                LOG.warn("[真彩屏] 图形面转字符失败：拿不到屏幕的字符格数（getBufferSize(0)）");
                return DisplayWindow.BACKEND_CPU;
            }
            final int[] palette = screenPalette(gpu);
            if (palette == null) {
                return DisplayWindow.BACKEND_CPU;        // 原因已响亮打过日志
            }
            final int[] argb = new int[frame.width() * frame.height()];
            for (int y = 0; y < frame.height(); y++) {
                for (int x = 0; x < frame.width(); x++) {
                    // 颜色解码走设备自己的 rgbAt（RGB565 展开只有那一处实现）
                    argb[y * frame.width() + x] = 0xFF000000 | (face.rgbAt(x, y) & 0xFFFFFF);
                }
            }
            final ScreenImageQuantizer.Result q = ScreenImageQuantizer.quantize(
                    argb, frame.width(), frame.height(), screen[0], screen[1], palette);
            int runs = 0;
            for (int row = 0; row < q.rows(); row++) {
                int col = 0;
                while (col < q.cols()) {
                    final int fg = q.fgAt(col, row);
                    final int bg = q.bgAt(col, row);
                    final StringBuilder text = new StringBuilder();
                    int end = col;
                    while (end < q.cols() && q.fgAt(end, row) == fg && q.bgAt(end, row) == bg) {
                        text.append(q.charAt(end, row));
                        end++;
                    }
                    String err = ocFailure(rawInvoke(gpu, "setForeground", new Object[]{fg}));
                    if (err != null) {
                        throw new IllegalStateException("图形面转字符: setForeground(" + fg + ") -> " + err);
                    }
                    err = ocFailure(rawInvoke(gpu, "setBackground", new Object[]{bg}));
                    if (err != null) {
                        throw new IllegalStateException("图形面转字符: setBackground(" + bg + ") -> " + err);
                    }
                    err = ocFailure(rawInvoke(gpu, "set", new Object[]{col + 1, row + 1, text.toString()}));
                    if (err != null) {
                        throw new IllegalStateException("图形面转字符: set(" + (col + 1) + ","
                                + (row + 1) + ") -> " + err);
                    }
                    blitRows++;
                    runs++;
                    col = end;
                }
            }
            LOG.info("[真彩屏] 图形面自动转字符（目标字符屏）：{}x{} 像素 → {}x{} 格、同色分段 {} 段、"
                            + "调色板 {} 档 第 {} 帧",
                    frame.width(), frame.height(), q.cols(), q.rows(), runs, palette.length, imageFrames);
            return DisplayWindow.BACKEND_CPU;
        } catch (Throwable t) {
            noteFailure("图形面转字符", t);
            return DisplayWindow.BACKEND_CPU;
        }
    }

    /**
     * 该屏自己的调色板（颜色唯一来源）。
     *
     * <p>问屏要，绝不内置色表：第 0 档问不到 ⇒ 本帧放弃并响亮报错（没有颜色来源就没法量化）；
     * 第 n 档问不到 ⇒ 认为该屏调色板到此为止（1/2 色深的屏就是这样），用问到的档位继续，
     * 并记一条 warn 说明档数。</p>
     */
    private int[] screenPalette(String gpu) throws Exception {
        final int[] palette = new int[SCREEN_PALETTE_LIMIT];
        int size = 0;
        for (int i = 0; i < palette.length; i++) {
            final Object[] r = rawInvoke(gpu, "getPaletteColor", new Object[]{i});
            final String err = ocFailure(r);
            if (err != null || r == null || r.length == 0 || !(r[0] instanceof Number)) {
                if (i == 0) {
                    LOG.warn("[真彩屏] 图形面转字符失败：这场屏给不出第 0 号调色板色（{}）—— "
                                    + "颜色来源必须唯一，不用内置色表兜底",
                            err == null ? "返回不是数字" : err);
                    return null;
                }
                LOG.warn("[真彩屏] 图形面转字符：该屏调色板只问到 {} 档（第 {} 档：{}）",
                        size, i, err == null ? "返回不是数字" : err);
                break;
            }
            palette[size++] = ((Number) r[0]).intValue();
        }
        if (size == 0) {
            return null;
        }
        return java.util.Arrays.copyOf(palette, size);
    }

    /** 一"带"走页路径：分配/复用页 → 分段写页 → 一次 bitblt 搬到屏幕 */
    private boolean presentBandViaPage(String gpu, DisplayWindow.Frame frame, DisplayPainter.Band band) {
        try {
            final int[] screen = screenSize(gpu);
            if (screen == null) {
                notePageFallback("present: getBufferSize(0) 拿不到屏幕尺寸");
                return false;
            }
            final int cw = Math.min(frame.cols(), screen[0]);
            final int ch = Math.min(band.rows(), screen[1] - band.rowFrom());
            if (cw <= 0 || ch <= 0) {
                return true;            // 整带落在屏幕外：没东西可画，不算失败
            }
            if (vramPage == SCREEN_PAGE || pageWidth != cw || pageHeight != ch) {
                if (!allocatePage(gpu, cw, ch)) {
                    return false;       // 失败原因已经打过日志
                }
            }
            if (!setActiveBuffer(gpu, vramPage)) {
                LOG.info("[OpenComputers] 组件桥：显存页 {} 已失效（present），重新分配", vramPage);
                vramPage = SCREEN_PAGE;
                pageWidth = 0;
                pageHeight = 0;
                if (!allocatePage(gpu, cw, ch) || !setActiveBuffer(gpu, vramPage)) {
                    disablePagePath(gpu, "present: setActiveBuffer(" + vramPage + ") 失败");
                    return false;
                }
            }
            // 页内坐标从 1 起：屏幕的 (0, band.rowFrom()) 映射成页的 (1, 1)
            paintRows(gpu, frame, band.rowFrom(), band.rowFrom() + ch - 1, 0, cw - 1, 0, band.rowFrom());
            setActiveBuffer(gpu, SCREEN_PAGE);
            final Object[] blt = bitbltToScreen(gpu, 1, band.rowFrom() + 1, cw, ch);
            final String err = ocFailure(blt);
            if (err != null) {
                notePageFallback("present: bitblt → 屏幕失败（" + err + "）");
                return false;
            }
            pageBlits++;
            blitRows += ch;
            return true;
        } catch (LimitReachedException e) {
            notePageFallback("present: bitblt 撞预算，重试 " + MAX_BITBLT_RETRIES + " 次仍失败");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            disablePagePath(gpu, "present: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            return false;
        }
    }

    /**
     * 把 {@code rowFrom..rowTo} 行、{@code colFrom..colTo} 列按<b>同色分段</b>写进当前活动页。
     *
     * <p>{@code originCol/originRow} 是"这块内容在目标页里的左上角"（0 基屏幕坐标）：
     * 写屏时是 {@code (0,0)}，写页时是 {@code (0, 带首行)}。坐标在这里 +1 变 1 基 ——
     * OC 的 {@code gpu.set} 收 1 基坐标，而固件侧本来就是 1 基（{@code console.c} 的
     * originCol/originRow = 1），所以整个项目里只有这一处做换算。</p>
     */
    /** 调色板诊断计数（临时） */
    private static int diagPaintRows = 0;

    private void paintRows(String gpu, DisplayWindow.Frame frame, int rowFrom, int rowTo,
                           int colFrom, int colTo, int originCol, int originRow) throws Exception {
        if (diagPaintRows < 5) {
            final byte[] fgs = frame.fg();
            final byte[] bgs = frame.bg();
            final int[] fgHist = new int[16];
            final int[] bgHist = new int[16];
            for (int i = 0; i < fgs.length && i < bgs.length; i++) {
                fgHist[fgs[i] & 0xF]++;
                bgHist[bgs[i] & 0xF]++;
            }
            final StringBuilder sf = new StringBuilder();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (fgHist[i] > 0) {
                    sf.append(i).append(':').append(fgHist[i]).append(' ');
                }
                if (bgHist[i] > 0) {
                    sb.append(i).append(':').append(bgHist[i]).append(' ');
                }
            }
            LOG.info("[OpenComputers] 三平面诊断（临时）：{}x{} fg 索引直方图={} bg 索引直方图={}",
                    frame.cols(), frame.rows(), sf, sb);
            diagPaintRows++;
        }
        for (int r = rowFrom; r <= rowTo; r++) {
            for (final DisplayPainter.Run run : DisplayPainter.runs(frame, r, colFrom, colTo)) {
                final int x = run.col() - originCol + 1;
                final int y = r - originRow + 1;
                // 每段先上色再写串：页里的每格颜色就是"写它时的当前色"。
                // ⚠ 窗口三平面的颜色是**调色板索引**（0=white…0xF=black，见固件 display.h 的契约）：
                //   这里用 common 的**唯一一张**表翻成 RGB 再用（OcPalette）。
                //   不能把索引直接交给 OC 的 setForegroundColor(index, true)：很多屏是 1 位单色档，
                //   会抛 IllegalArgumentException("color palette not supported")，整条页路径被永久降级
                //   成逐行 gpu.set（2026-09-27 真机实测）。固件侧的索引语义不变。
                final int fgRgb = com.hdf.cryptand.soc.board.OcPalette.rgb(run.fg());
                final int bgRgb = com.hdf.cryptand.soc.board.OcPalette.rgb(run.bg());
                String err = ocFailure(rawInvoke(gpu, "setForeground", new Object[]{fgRgb}));
                if (err != null) {
                    throw new IllegalStateException("present: setForeground(" + run.fg() + "->" + fgRgb + ") -> " + err);
                }
                err = ocFailure(rawInvoke(gpu, "setBackground", new Object[]{bgRgb}));
                if (err != null) {
                    throw new IllegalStateException("present: setBackground(" + run.bg() + ") -> " + err);
                }
                err = ocFailure(rawInvoke(gpu, "set", new Object[]{x, y, run.text()}));
                if (err != null) {
                    throw new IllegalStateException("present: set(" + x + "," + y + ") -> " + err);
                }
                blitRows++;
            }
        }
    }

    /** 上屏完成后的诊断记录（与 {@link #gpuBlit} 那条路共用 lastFrameText / lastFrameNonZero） */
    private void finishPresentedFrame(DisplayWindow.Frame frame, String path) {
        blitFrames++;
        lastFrameText = frameText(frame.code(), frame.cols(), frame.rows());
        int nonZero = 0;
        for (final byte b : frame.code()) {
            if (b != 0 && b != ' ') {
                nonZero++;
            }
        }
        lastFrameNonZero = nonZero;
        if (nonZero > 0 && blitFrames <= 500) {
            LOG.info("[OpenComputers] 显存窗口上屏诊断：{}x{}，非空字节 {}，{}，首行=\"{}\"",
                    frame.cols(), frame.rows(), nonZero, path,
                    decodeCells(frame.code(), 0, frame.cols()));
        }
    }

    /** 诊断：本总线处理过多少帧（"读不到屏幕"时用来区分"总线没被用过"与"缓存没写对"） */
    public String statsText() {
        return "bus=#" + Integer.toHexString(System.identityHashCode(this))
                + " frames=" + blitFrames + " rows=" + blitRows
                + " nonZeroLast=" + lastFrameNonZero;
    }

    /** 最近一次 blit 的整屏文本（供 MCP 工具 `oc_screen` 做无人化断言） */
    public String lastFrameText() {
        return lastFrameText;
    }

    /** 把一帧字符阵列切成"每行去尾空格"的文本 */
    private static String frameText(byte[] cells, int w, int h) {
        final StringBuilder sb = new StringBuilder();
        for (int r = 0; r < h; r++) {
            final StringBuilder line = new StringBuilder(w);
            for (int c = 0; c < w; c++) {
                final int i = r * w + c;
                final byte b = i < cells.length ? cells[i] : 0;
                line.append(DisplayCharset.decode(b));   // 码页与上屏同一份（见 decodeCells）
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            sb.append(line, 0, end);
            if (r + 1 < h) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 一次 blit 的处置：{@code result} 是交给固件的返回，{@code path} 只进诊断日志 */
    private record BlitOutcome(Result result, String path) {
    }

    /**
     * 页路径：把这块字符写进<b>复用的显存页</b>，再一次 {@code bitblt} 把页搬到页 0（屏幕）。
     *
     * <p>{@code x/y} 偏移不用摊进页里：页的行号从 1 起，blt 时用 {@code fromCol/fromRow = 1,1}、
     * 目标 {@code col/row = x,y} 就能搬到屏幕的同一位置。</p>
     *
     * @return 本次处置结果；{@code null} = 页路径这次用不了（已按需打过 warn）⇒ 调用方退回逐行 set
     */
    private BlitOutcome blitViaPage(String gpu, int x, int y, int w, int h, byte[] cells) {
        try {
            final int[] screen = screenSize(gpu);
            if (screen == null) {
                // OC 明确回了错误（"no screen" / "invalid buffer index"）⇒ 屏幕这一瞬间不可用
                // （拆掉 / 正在合并/重算），这是**临时**状态 ⇒ 只本帧退回逐行 set，下一帧再试。
                // ⚠ 真正的"没有这个方法"是抛异常，由下面的 catch 处理成永久退回。
                notePageFallback("getBufferSize(0) 拿不到屏幕尺寸（屏幕刚被拆掉/正在合并？）");
                return null;
            }
            final int col = Math.max(x, 1);
            final int row = Math.max(y, 1);
            // 页面尺寸 = 这块 blit 里"真的能显示"的部分（OC 写屏时自己也按屏幕分辨率裁剪，
            // TextBufferProxy.set 会把行截到 data.width、util.TextBuffer.set 会丢掉越界的行）：
            //   ① 页面积直接决定脏页 blt 的预算代价（determineBitbltBudgetCost 按面积比例算），
            //      页面开大了白白多花预算；
            //   ② 页面积 ≤ 屏幕面积 ⇒ 一定装得进显存（totalVRAM = GPU 上限面积 × vramSize ≥ 屏幕面积）；
            //   ③ 截断规则两边一致：页宽 = 屏幕能放下这行的宽度 ⇒ 写页得到的格子和写屏一模一样。
            final int cw = Math.min(w, screen[0] - col + 1);
            final int ch = Math.min(h, screen[1] - row + 1);
            if (cw <= 0 || ch <= 0) {
                return new BlitOutcome(Result.ok(Boolean.TRUE),
                        "整块落在屏幕外（屏幕 " + screen[0] + "x" + screen[1] + "，不画）");
            }
            if (vramPage == SCREEN_PAGE || pageWidth != cw || pageHeight != ch) {
                if (!allocatePage(gpu, cw, ch)) {
                    return null;                    // 失败原因已经打过日志
                }
            }
            if (!setActiveBuffer(gpu, vramPage)) {
                // 页在 OC 侧没了：机器停机/重启时 GPU 会 removeAllBuffers（GraphicsCard.scala:584-587）
                LOG.info("[OpenComputers] 组件桥：显存页 {} 已失效，重新分配", vramPage);
                vramPage = SCREEN_PAGE;
                pageWidth = 0;
                pageHeight = 0;
                if (!allocatePage(gpu, cw, ch) || !setActiveBuffer(gpu, vramPage)) {
                    disablePagePath(gpu, "setActiveBuffer(" + vramPage + ") 失败");
                    return null;
                }
            }
            // 逐行写页：目标页 ≠ 0 ⇒ 不扣 direct 预算、不耗电（GraphicsCard.scala:124-131）
            for (int i = 0; i < ch; i++) {
                final Object[] r = rawInvoke(gpu, "set", new Object[]{1, 1 + i, decodeCells(cells, i * w, w)});
                final String err = ocFailure(r);
                if (err != null) {
                    setActiveBuffer(gpu, SCREEN_PAGE);
                    return new BlitOutcome(Result.error("blit: 写显存页第 " + i + " 行失败（" + err + "）"),
                            "写显存页失败");
                }
                blitRows++;
            }
            // 活动页交还屏幕：fill/set/getResolution 这些方法的行为必须保持原样（它们打的是活动页）
            setActiveBuffer(gpu, SCREEN_PAGE);
            final Object[] blt = bitbltToScreen(gpu, col, row, cw, ch);
            final String err = ocFailure(blt);
            if (err != null) {
                return new BlitOutcome(Result.error("blit: 显存页 → 屏幕失败（" + err + "）"),
                        "显存页 → 屏幕失败");
            }
            pageBlits++;
            return new BlitOutcome(Result.ok(Boolean.TRUE),
                    "走显存页 " + cw + "x" + ch + "（页 " + vramPage + "）+ 1 次 bitblt"
                            + (cw < w || ch < h ? "（已按屏幕 " + screen[0] + "x" + screen[1] + " 裁剪）" : ""));
        } catch (LimitReachedException e) {
            // bitblt 重试到上限仍没预算（真的撞了每 tick 一份的预算）⇒ 本帧退回逐行 set，屏幕不能空
            notePageFallback("bitblt 撞预算重试 " + MAX_BITBLT_RETRIES + " 次仍失败");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BlitOutcome(Result.error("blit: 等 OC 预算时线程被打断"), "被中断");
        } catch (Throwable t) {
            // 到这儿就是"这台 OC / 这块屏幕走不了页路径"（缺方法抛 NoSuchMethodException、
            // 屏幕不是 component.TextBuffer 抛 UnsupportedOperationException…）⇒ 永久退回逐行 set
            disablePagePath(gpu, t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            return null;
        }
    }

    /**
     * 分配一块 {@code cw×ch} 的显存页并记下来（{@code GraphicsCard.allocateBuffer}，
     * 页号从 1 开始，{@code GraphicsCard.scala:158-177}）。
     *
     * @return false = 这次用不了页路径；是否<b>永久</b>关闭由失败原因决定（见方法内注释）
     */
    private boolean allocatePage(String gpu, int cw, int ch) throws Exception {
        releasePage(gpu);
        Object[] r = rawInvoke(gpu, "allocateBuffer", new Object[]{cw, ch});
        String err = ocFailure(r);
        if (err != null && err.contains("video memory") && !vramReclaimed) {
            // ⚠ 显存被"上一次存档里的旧页"占着。OC 会把显存页写进 GPU 物品的数据组件、
            // 读档时原样恢复（GraphicsCard.scala:678-689 saveData / 661-676 loadData），
            // 而释放只发生在机器收到 computer.stopped 时（GraphicsCard.scala:584-587）
            // ⇒ 上一次会话"开着机器存了档"，这次开机就会分配不到页（实测：
            //    T1 显卡 vram=800 格、旧页 40x16=640 格 ⇒ 只剩 160 格，40x16 分配必然失败）。
            // 这台机器的显示通路只有 Cryptand 固件在用（架构不是 Lua，没有别的程序分配页），
            // 所以每次开机**一次性**把落下的旧页全部回收，然后重试分配；再失败才本帧退回逐行 set。
            vramReclaimed = true;
            final Object[] freed = rawInvoke(gpu, "freeAllBuffers", NO_ARGS);
            final String ferr = ocFailure(freed);
            r = rawInvoke(gpu, "allocateBuffer", new Object[]{cw, ch});
            err = ocFailure(r);
            LOG.info("[OpenComputers] 组件桥：显存被上次存档恢复的旧页占着（allocateBuffer({}x{}) → {}），"
                            + "回收旧页（{}）后重试 → {}",
                    cw, ch, "not enough video memory", ferr != null ? ferr : (asInt(freed[0]) + " 块"),
                    err != null ? err : ("页 " + asInt(r[0])));
        }
        if (err != null) {
            if (err.contains("video memory")) {
                // 显存不够只说明"这一刻没页可用"，不是 API 问题 ⇒ 只本帧退回（下一帧还会再试）
                notePageFallback("显存不足（allocateBuffer(" + cw + "x" + ch + ") → " + err + "）");
            } else {
                disablePagePath(gpu, "allocateBuffer(" + cw + "x" + ch + ") → " + err);
            }
            return false;
        }
        final int page = asInt(r[0]);
        if (page == SCREEN_PAGE) {
            disablePagePath(gpu, "allocateBuffer 没有返回有效页号");
            return false;
        }
        vramPage = page;
        pageWidth = cw;
        pageHeight = ch;
        LOG.info("[OpenComputers] 组件桥：分配显存页 {} = {}x{}（写页不扣 direct 预算、不耗电；"
                        + "之后每次整屏上屏只花一次 bitblt）", page, cw, ch);
        return true;
    }

    /**
     * 交还并释放复用的显存页。
     *
     * <p>⚠ 顺序是要紧的：<b>先把活动页切回屏幕（页 0）再 free</b> —— free 掉活动页虽然 OC 自己也会把
     * {@code bufferIndex} 掉回 0（{@code GraphicsCard.scala:189-191}），但显式切回来才能保证
     * 任何时刻 {@code fill/set} 都打在屏幕上。释放失败（例如机器停机时 GPU 已经把页删了，
     * 返回 "no buffer at index"）不该影响绘制，所以异常一律吞掉。</p>
     */
    private void releasePage(String gpu) {
        final int page = vramPage;
        vramPage = SCREEN_PAGE;
        pageWidth = 0;
        pageHeight = 0;
        if (page == SCREEN_PAGE) {
            return;
        }
        try {
            setActiveBuffer(gpu, SCREEN_PAGE);
            final String err = ocFailure(rawInvoke(gpu, "freeBuffer", new Object[]{page}));
            if (err != null) {
                LOG.info("[OpenComputers] 组件桥：释放显存页 {} 时 OC 报「{}」", page, err);
            }
        } catch (Throwable t) {
            LOG.info("[OpenComputers] 组件桥：释放显存页 {} 失败（{}）", page, t.toString());
        }
    }

    /**
     * 关闭页路径，永久退回逐行 {@code set}（屏幕上照旧有输出，只是每行一次调用会重新吃预算与能耗）。
     *
     * <p>只在"确定性原因"上调用：API 不存在、参数被拒、屏幕不支持 blt。临时的原因
     * （显存一时不够、bitblt 一时撞预算）走 {@link #notePageFallback}，下一帧还会再试页路径。</p>
     */
    private void disablePagePath(String gpu, String why) {
        if (pagePathDisabled) {
            return;
        }
        pagePathDisabled = true;
        LOG.warn("[OpenComputers] 组件桥：显存页路径不可用（{}）⇒ 退回逐行 gpu.set"
                + "（屏幕照旧有字，但整屏刷新会重新吃掉 direct 预算与能耗）", why);
        releasePage(gpu);
    }

    /** 页路径这一帧没用上（临时原因）：节流打一条，下一帧继续试 */
    private void notePageFallback(String why) {
        if (rowFallbacks <= 3 || rowFallbacks % 200 == 0) {
            LOG.warn("[OpenComputers] 组件桥：本帧退回逐行 gpu.set（{}）", why);
        }
    }

    /**
     * 屏幕当前分辨率（{@code getBufferSize(0)}：页 0 恒为绑定的屏幕，{@code GraphicsCard.scala:214-218}）。
     *
     * <p>只用来把显存页裁剪到"真的能显示"的区域；调用走的是 {@code @Callback(direct = true)}，
     * 页 0 的取尺寸路径本身不扣预算（只有 set/fill/copy 这些写屏操作才扣）。</p>
     *
     * @return {@code [宽, 高]}；null = 拿不到（无屏幕 / 这台 OC 没这个方法）
     */
    private int[] screenSize(String gpu) throws Exception {
        final Object[] r = rawInvoke(gpu, "getBufferSize", new Object[]{SCREEN_PAGE});
        if (r == null || r.length < 2 || ocFailure(r) != null) {
            return null;
        }
        final int sw = asInt(r[0]);
        final int sh = asInt(r[1]);
        return sw > 0 && sh > 0 ? new int[]{sw, sh} : null;
    }

    /**
     * 切活动显存页（页 0 = 屏幕）。这是 {@code @Callback(direct = true)} 但<b>不扣预算也不耗电</b>
     * （{@code GraphicsCard.scala:138-151}，内部只改 {@code bufferIndex}）。
     *
     * @return false = OC 拒绝（无效页号 / 没有屏幕）
     */
    private boolean setActiveBuffer(String gpu, int page) throws Exception {
        return ocFailure(rawInvoke(gpu, "setActiveBuffer", new Object[]{page})) == null;
    }

    /**
     * {@code bitblt(dst=0, col, row, w, h, src=页, 1, 1)}：把页的 (1,1)..(w,h) 搬到屏幕的
     * (col,row)..。参数是<b>页索引</b>而不是地址（{@code GraphicsCard.scala:244-291}），
     * dst/src 都显式给 ⇒ 不依赖"当前活动页"这个隐含状态。
     *
     * <p>⚠ OC 的"故意失败"节流必须重试：脏页 {@code overBudget > 0} 且 {@code budgetExhausted == false}
     * 时，它置位后抛 {@link LimitReachedException}（本次 blt 不执行，Lua 那边拿到 nil）；
     * <b>紧接着的第二次调用</b>会走"清零剩余预算代价"的分支并照常执行
     * （{@code GraphicsCard.scala:257-276}）。所以第 1 次重试不睡；只有再失败才认为真的没预算
     * （{@code consumeCallBudget} 抛的），等一个 tick 让主线程把预算重置（{@code Machine.scala:556}）。</p>
     */
    private Object[] bitbltToScreen(String gpu, int col, int row, int w, int h) throws Exception {
        final Object[] args = new Object[]{SCREEN_PAGE, col, row, w, h, vramPage, 1, 1};
        LimitReachedException last = null;
        for (int attempt = 0; attempt <= MAX_BITBLT_RETRIES; attempt++) {
            try {
                return rawInvoke(gpu, "bitblt", args);
            } catch (LimitReachedException e) {
                last = e;
                bitbltRetries++;
                if (attempt == 0) {
                    continue;                       // 故意的第一次 ⇒ 立刻重试即可放行，不睡
                }
                if (attempt >= MAX_BITBLT_RETRIES) {
                    break;
                }
                blitBudgetWaits++;
                if (blitBudgetWaits <= 3 || blitBudgetWaits % 200 == 0) {
                    LOG.info("[OpenComputers] 组件桥：bitblt 撞上 OC 的 direct 调用预算，等 {}ms 重试"
                                    + "（第 {} 次；只有'内容变了的整屏 blit'才吃预算，静态画面只要 0.001）",
                            TICK_MILLIS, blitBudgetWaits);
                }
                Thread.sleep(TICK_MILLIS);
            }
        }
        throw last;
    }

    /**
     * 兜底路径：把 w×h 阵列拆成 h 次 {@code set(x, y+i, 第 i 行)} 直接打到屏幕。
     *
     * <p>页路径不可用时才走这条路：每次 {@code set} 扣 {@code setCosts(tier)} 预算并按码点耗电
     * （{@code GraphicsCard.scala:124-131}），所以它保留了 {@link #setWithBudgetRetry} 的"等一个 tick 续画"。</p>
     */
    private BlitOutcome blitRowsLegacy(String gpu, int x, int y, int w, int h, byte[] cells) throws Exception {
        for (int row = 0; row < h; row++) {
            final String line = decodeCells(cells, row * w, w);
            final Object[] r = setWithBudgetRetry(gpu, x, y + row, line);
            blitRows++;
            final String err = ocFailure(r);
            if (err != null) {
                invalidateBound();
                return new BlitOutcome(Result.error("blit: 第 " + row + " 行 set 失败（" + err + "）"),
                        "逐行 set 失败");
            }
        }
        return new BlitOutcome(Result.ok(Boolean.TRUE), "退回逐行 set " + h + " 次（页路径不可用）");
    }

    /**
     * 画一行，撞上 OC 的 direct 调用预算时**等下一个 tick** 再画。
     *
     * <p>为什么必须等：预算是"每 tick 一份"的（{@code computer.callBudgets}，默认
     * T1=0.5 / T2=1.0 / T3=1.5 / T4=2.0，{@code application.conf:163}），由主线程 tick 里重置
     * （{@code Machine.scala:556} {@code callBudget = maxCallBudget}）；而显卡每行 {@code set}
     * 要扣 {@code setCosts(tier)}（T1 = 1/64，{@code GraphicsCard.scala:81}）
     * ⇒ <b>T1 显卡 + T1 内存（预算 0.5）时，25 行一屏 = 0.39，已经贴着上限</b>；同一 tick 再来
     * 一次 blit（或几次 fill，各 1/32）就会撞限额抛 {@link LimitReachedException}
     * （{@code Machine.scala:309-317}）。此时若直接放弃，固件每帧都从头重画 ⇒ <b>后半屏永远
     * 画不上</b>；等一个 tick 续画则整块画得完。</p>
     *
     * <p>⚠ 等的是 OC 的工作线程（{@code Machine.run()} 所在线程）：主线程 {@code Machine.update()}
     * 重置预算时<b>不需要</b> {@code Machine.this} 锁（{@code Machine.scala:528/556}）⇒ 不会死锁；
     * 代价只是这台计算机慢几个 tick（上限 {@link #MAX_BUDGET_WAITS} 个）。</p>
     */
    private Object[] setWithBudgetRetry(String gpu, int x, int y, String line) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return rawInvoke(gpu, "set", new Object[]{x, y, line});
            } catch (LimitReachedException e) {
                if (attempt >= MAX_BUDGET_WAITS) {
                    throw e;
                }
                budgetWaits++;
                if (budgetWaits <= 3 || budgetWaits % 200 == 0) {
                    LOG.info("[OpenComputers] Cryptand 组件桥：撞上 OC 的 direct 调用预算，等 {}ms 续画"
                                    + "（第 {} 次；低 tier 内存/显卡预算小，可换高 tier 内存或调 computer.callBudgets）",
                            TICK_MILLIS, budgetWaits);
                }
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    // ==================== 地址 / 方法 id / 参数 ====================

    /**
     * 取调用目标地址：核心已按组件句柄填好（空串表示"交给平台选第一个匹配组件"，见
     * {@link Call} 的契约）。
     */
    private String resolveAddress(Call call) {
        if (!call.address().isEmpty()) {
            return call.address();
        }
        for (final Entry e : components()) {
            if (e.component().equals(call.component())) {
                return e.address();
            }
        }
        return "";
    }

    /** {@code "#3"} → 3；不是 {@code #} 形式返回 -1（调用方按"原样方法名"处理） */
    private static int methodId(String method) {
        if (method == null || method.length() < 2 || method.charAt(0) != '#') {
            return -1;
        }
        try {
            return Integer.parseInt(method.substring(1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 方法 id 表只覆盖 gpu；组件名缺失时（理论上不会）按地址反查 */
    private boolean isGpu(String address, String component) {
        if (NAME_GPU.equals(component)) {
            return true;
        }
        if (!component.isEmpty()) {
            return false;
        }
        for (final Entry e : components()) {
            if (e.address().equals(address)) {
                return NAME_GPU.equals(e.component());
            }
        }
        return false;
    }

    /** 屏幕地址：排序后第一个 {@code screen}，正好就是固件句柄 {@link OcAbi#HANDLE_SCREEN} 指向的那块 */
    private String screenAddress() {
        for (final Entry e : components()) {
            if (NAME_RC_SCREEN.equals(e.component())) {
                return e.address();
            }
        }
        return "";
    }

    /** GPU 地址：排序后第一个 {@code gpu}（与 {@link #screenAddress()} 同一套排序，见 {@link #ORDER}） */
    private String gpuAddress() {
        for (final Entry e : components()) {
            if (NAME_GPU.equals(e.component())) {
                return e.address();
            }
        }
        return "";
    }

    /**
     * 真正要用屏幕之前补一次 {@code bind(屏幕地址)}（OC 的 {@code bind} 是
     * {@code @Callback} 非 direct，内部走 {@code node.network.node(address)} 校验地址 —— 见
     * {@code GraphicsCard.scala:293-321}）。
     *
     * @return false = 这台机器没有可见屏幕（调用方返回明确的 "no screen" 错误）
     */
    private boolean ensureBound(String gpu) throws Exception {
        final String screen = screenAddress();
        if (screen.isEmpty()) {
            return false;
        }
        if (screen.equals(boundScreen)) {
            return true;                        // 已绑定（且屏幕没换）
        }
        final Object[] r = rawInvoke(gpu, "bind", new Object[]{screen});
        final String err = ocFailure(r);
        if (err != null) {
            boundScreen = "";
            LOG.warn("[OpenComputers] Cryptand 架构：GPU {} bind({}) 失败：{}", gpu, screen, err);
            return false;
        }
        boundScreen = screen;
        LOG.info("[OpenComputers] Cryptand 架构：GPU {} 已 bind 屏幕 {}", gpu, screen);
        return true;
    }

    /** 屏幕不可用（拆掉 / 换了）时清掉绑定记忆 ⇒ 下次调用自动重新 bind */
    private void invalidateBound() {
        boundScreen = "";
    }

    private Object[] rawInvoke(String address, String method, Object[] args) throws Exception {
        return machine.invoke(address, method, args);
    }

    /**
     * 中间类型 → OC 参数。
     *
     * <p>{@code Integer/Boolean/String/Double/byte[]} 直接透传（OC 的
     * {@code Arguments.checkInteger} 接受 Number 并按整数取用），其余数值类型折成
     * {@code int}/{@code double}；不认识的对象丢弃为 {@code null}（占位不挪位）。</p>
     */
    private static List<Object> ocArgs(List<Object> args) {
        final List<Object> out = new ArrayList<>(args.size() + 1);
        for (final Object a : args) {
            out.add(toOcArg(a));
        }
        return out;
    }

    private static Object toOcArg(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Double || value instanceof byte[]) {
            return value;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /**
     * OC 回调结果 → {@link Result}。
     *
     * <p>⚠ {@code Result.values} 用 {@code List.copyOf} 构造（不允许 null 元素），所以 OC 的
     * {@code nil} 占位（例如失败形态 {@code (nil, "no screen")}）只能丢弃 —— 失败形态由
     * {@link #ocFailure} 单独识别。</p>
     */
    private static Result result(Object[] values) {
        return new Result(true, toIntermediates(values), "");
    }

    private static List<Object> toIntermediates(Object[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        final List<Object> out = new ArrayList<>(values.length);
        for (final Object v : values) {
            final Object c = toIntermediate(v);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static Object toIntermediate(Object v) {
        if (v == null) {
            return null;
        }
        // OC 的 Registry.convert 可能给出 Byte/Short/Integer/Long/Float/Double/String/Boolean/Array[Byte]
        // （Registry.scala:148-190）——这里统一收成 ComponentBus 允许的中间类型。
        if (v instanceof String || v instanceof Boolean || v instanceof Integer
                || v instanceof Double || v instanceof byte[]) {
            return v;
        }
        if (v instanceof Long l) {
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE
                    ? Integer.valueOf(l.intValue()) : (Object) l.doubleValue();
        }
        if (v instanceof Number n) {
            return n instanceof Float ? (Object) n.doubleValue() : (Object) n.intValue();
        }
        return null;                            // Map / 数组 / Value 等不是中间类型 ⇒ 丢弃
    }

    /**
     * 识别 OC 回调的"失败形态"：{@code result(null, "错误文本")}。
     *
     * <p>OC 组件用这种返回表达失败（例如 {@code GraphicsCard} 未 bind 时的
     * {@code Array(null, "no screen")}，{@code GraphicsCard.scala:66}），成功则是
     * {@code result(true)} 之类的单值。</p>
     *
     * @return 错误文本，或 null（不是失败形态）
     */
    private static String ocFailure(Object[] values) {
        if (values == null || values.length < 2 || values[0] != null) {
            return null;
        }
        return values[1] instanceof String s && !s.isEmpty() ? s : null;
    }

    /**
     * 字符阵列切片 → 字符串。
     *
     * <p>⚠ 必须按固件真正用的<b>码页</b>（CP437）逐字节解码，1 字节 = 1 码点 —— 绝不能按 UTF-8：
     * 固件的字符阵列是<b>单字节网格</b>（{@code console.c} 每格一个 {@code char}），UTF-8 会把
     * 相邻的 2~3 个高位字节合并成一个码点，整行随之前移、网格错位。</p>
     *
     * <p>⚠ 这里曾经用 {@code ISO_8859_1} 直转，理由写的是"1 字节 = 1 码点、网格对齐" ——
     * 网格确实是对齐了，但<b>字形全错</b>：固件画阴影与实心块用的是 CP437 的
     * {@code 0xB0/0xB1/0xB2/0xDB}（= ░▒▓█），Latin-1 把它们解成 {@code °±²Û}
     * （度数符号 / 正负号 / 平方 / Û）⇒ LVGL 画面里的进度条与阴影在真机上全是乱码。
     * CP437 与 Latin-1 <b>都</b>保证"1 字节 1 格"，差别只在字形对不对 ⇒ 取 CP437
     * （码页表来自 JDK 自带的 {@code IBM437}，见 {@link DisplayCharset}）。</p>
     *
     * <p>控制字节（{@code 0} / {@code \n} / {@code \r}）会被 OC 的 {@code TextBuffer.set} 跳过
     * （{@code wcwidth <= 0} 的码点不占格、不写屏）—— 一旦出现就是<b>整行左移</b>，
     * 所以 {@link DisplayCharset} 明确把 {@code 0x00-0x1F} 与 {@code 0x7F} 当空白。</p>
     */
    private static String decodeCells(byte[] cells, int offset, int length) {
        return DisplayCharset.decodeRange(cells, offset, offset + length - 1);
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String describe(Call call) {
        return call.component() + " " + call.method() + " @" + call.address();
    }

    // ==================== 诊断 ====================

    /** 失败日志节流：前 3 次 + 之后每 200 次一条（组件调用每帧都会来，不节流会淹掉 latest.log） */
    private void noteFailure(Object what, Throwable t) {
        failedCalls++;
        if (failedCalls <= 3 || failedCalls % 200 == 0) {
            LOG.warn("[OpenComputers] Cryptand 组件调用失败（第 {} 次）：{} → {}",
                    failedCalls, what, t.toString());
        }
    }

    /** 已绑定的屏幕地址（空串 = 未绑定）；诊断用 */
    /**
     * 绑定屏幕的**字符分辨率**（{@code getBufferSize(0)}）—— 给"宿主注入控制台尺寸"用。
     *
     * <p>为什么宿主来给：显示拓扑在宿主手里，而固件问显卡这条路会**说谎** ——
     * 没有绑定屏时 {@code gpu.getResolution} 回的是 1x1（不是报错），固件于是把自己当成
     * 1x1 的终端（真机实测：整屏只剩一格字）。配置块里的 {@code disp.cols/disp.rows}
     * 是同一个事实的唯一来源，固件优先读它。</p>
     *
     * @return {@code [宽, 高]}；null = 没有可用屏幕（没显卡 / 没绑屏 / 方法缺失）
     */
    public int[] boundScreenTextSize() {
        final String gpu = gpuAddress();
        if (gpu.isEmpty()) {
            return null;
        }
        try {
            if (!ensureBound(gpu)) {
                return null;
            }
            return screenSize(gpu);
        } catch (Throwable t) {
            return null;        // 探测失败只是"这一项没有"（没屏幕也在其中），不是错误
        }
    }

    public String boundScreenAddress() {
        return boundScreen;
    }

    /** 写进显存页（主路径）与打到屏幕（兜底路径）的 set 行数合计；诊断用 */
    public long blitRows() {
        return blitRows;
    }

    /** gpu_blit 帧数；诊断用 */
    public long blitFrames() {
        return blitFrames;
    }

    /** 走"显存页 + 一次 bitblt"上屏的帧数；诊断用（= {@link #blitFrames()} - {@link #rowFallbacks()} 时最理想） */
    public long pageBlits() {
        return pageBlits;
    }

    /** bitblt 的重试次数（含 OC 对脏页"故意抛一次"的那次）；诊断用 */
    public long bitbltRetries() {
        return bitbltRetries;
    }

    /** 退回逐行 set 的帧数；诊断用（> 0 说明页路径不可用，屏幕照旧有字） */
    public long rowFallbacks() {
        return rowFallbacks;
    }

    /** 逐行 set 等 tick 续画的次数；诊断用（> 0 说明内存/显卡 tier 偏低，且页路径没用上） */
    public long budgetWaits() {
        return budgetWaits;
    }

    /** bitblt 等 tick 重试的次数；诊断用（正常应远小于 {@link #blitFrames()}） */
    public long blitBudgetWaits() {
        return blitBudgetWaits;
    }

    /** 当前复用的显存页号（0 = 未分配）；诊断用 */
    public int vramPageIndex() {
        return vramPage;
    }

    /** 失败次数；诊断用 */
    public long failedCalls() {
        return failedCalls;
    }
}

package com.hdf.cryptand.neoforge.opencomputers;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真彩屏**驱动方闸门**（任务 F-1，2026-09-27；用户定案：真彩屏只能我们的 GPU 驱动，原版
 * {@code opencomputers:graphicscard*} 不能，且必须**明确拒绝**：日志 + 计数明确拒绝，而"驱动不了"
 * 由"读=中性值、写=no-op"保证 —— 第一版用抛异常当拒绝通道，2026-09-27 的 P0 修复把它改掉了，
 * 见下面第零节）。
 *
 * <h2>零、2026-09-27 P0 修复：闸门**绝不抛异常**（本类的唯一行为变更）</h2>
 *
 * <p>第一版闸门用 {@code throw IllegalStateException} 当"拒绝"的错误通道 ⇒ 真机崩服：原版显卡
 * <b>不是只走 guest 的 {@code gpu.bind}</b>，它还会被**网络消息派发**驱动（
 * {@code Machine.tryClose → Node.sendToReachable("computer.stopped") → Network.send →
 * GraphicsCard.onMessage → GraphicsCard.screen(...) → 屏方法}），而 {@code Network.send} **不捕获异常**
 * ⇒ 异常穿出 tick ⇒ 服务端崩（crash report 原文
 * {@code neoforge/run/crash-reports/crash-2026-09-21_06.01.50-server.txt}）。</p>
 *
 * <p>所以本类只**回答问题**，从不抛：</p>
 * <ul>
 *   <li>{@link #requireCryptandDriver(Object, String)} 返回 {@code boolean}：{@code true} = 允许，
 *       调用方照常做真实读写；{@code false} = 拒绝，调用方**必须降级**：读 = 中性值、写 = no-op；</li>
 *   <li>拒绝的可观察性 = 节流日志 + 累计计数（{@link #refusals()}），不再用异常当错误通道。</li>
 * </ul>
 *
 * <p>⚠ 原版显卡的 {@code gpu.bind} 现在**会返回成功**——不是我们没拒绝，而是 OC 那条回调的返回值
 * 在字节码/源码里是**硬编码**的（{@code GraphicsCard.scala:302-318}：类型判定通过后走
 * {@code screen(s => ...); result(true)}，屏侧拿不到任何影响返回值的杠杆），所以"回错误元组"这条
 * 路在 OC 原版显卡上不存在。屏侧能做的只有"读给中性值、写全部无效"，即<b>驱动不了</b>。</p>
 *
 * <h2>一、OC 原版显卡是怎么拿到并 bind 一块屏的（事实依据）</h2>
 *
 * <p><b>是"类型解析"，不是组件调用</b>。OC 只认一个条件：目标节点的
 * {@code host} 是不是 {@code li.cil.oc.api.internal.TextBuffer}。证据是**依赖 jar 里的字节码**
 * （{@code curse.maven:opencomputers-rebooted-1634364:8614366}，与 {@code neoforge/build.gradle}
 * 第 404/405 行声明的版本逐字一致；{@code javap -p -c li.cil.oc.server.component.GraphicsCard}）：</p>
 * <ul>
 *   <li>{@code bind(Context, Arguments)}：{@code instanceof li/cil/oc/api/internal/TextBuffer}
 *       @ 偏移 +88 → {@code checkcast} @ +118；不匹配则 {@code ldc "not a screen"} @ +163。
 *       对应源码 {@code GraphicsCard.scala:293-321}。</li>
 *   <li>{@code onConnect(Node)}：同一条 {@code instanceof} @ 偏移 +47 —— 存档重连那条路
 *       （{@code GraphicsCard.scala:632-641}）也**只**看这个类型。</li>
 * </ul>
 *
 * <p>⇒ 原版显卡**只有这两条获取路径**，两条都是同一个类型判定；一旦拿到，它就把屏对象缓存在
 * {@code screenInstance} 里，之后 {@code set/get/fill/copy/bitblt/setResolution/…} 全部
 * **直接调用屏对象的 {@code api.internal.TextBuffer} 方法**（{@code GraphicsCard.scala:62-76 }
 * 的私有辅助 {@code screen(...)}）。</p>
 *
 * <p>同样来自字节码的第二个事实（本闸门的快路径就靠它）：那个私有辅助
 * {@code screen(int, scala.Function1)} 的字节码是 {@code monitorenter} @ +44 →
 * {@code Function1.apply} @ +48 → {@code monitorexit} @ +60 ——
 * <b>原版显卡对屏的每一次调用，都是在持有"屏对象监视器"的状态下发生的</b>
 * （源码同一处：{@code case Some(screen) => screen.synchronized(f(screen))}）。</p>
 *
 * <h2>二、判定点（屏侧驱动入口的全部）</h2>
 *
 * <p>我们**无法**在原版显卡那侧改代码，能改的只有"屏这一侧"。屏侧的驱动入口分两类：</p>
 * <ol>
 *   <li>{@code traits.TextBufferProxy.data} —— 字符/颜色/调色板/分辨率/色深/像素读写的
 *       **唯一数据入口**（{@code set/get/fill/copy/rawSet…/setResolution/setViewport/
 *       setColorDepth} 都要摸它）；</li>
 *   <li>少数**不摸 data** 的只读状态、VRAM 清理与**状态写**：{@code getMaximumWidth/getMaximumHeight/
 *       getViewportWidth/getViewportHeight/getMaximumColorDepth}、
 *       {@code traits.VideoRamRasterizer.removeAllBuffers()}，以及
 *       {@code setResolution/setViewport/setColorDepth} 与两个"通知客户端"的钩子
 *       {@code onBufferColorChange/onBufferPaletteChange}。</li>
 * </ol>
 * <p>判定规则一条：</p>
 * <blockquote><b>调用栈里出现原版显卡帧（{@code li.cil.oc.server.component.GraphicsCard} /
 * {@code QuadGraphicsCard}）⇒ 驱动方是原版显卡 ⇒ 记节流日志 + 返回"拒绝"。</b></blockquote>
 *
 * <p>⚠ 第 2 类为什么不止 5 个只读 getter：不抛异常之后，{@code data} 只能"给一个假缓冲"，而
 * {@code setResolution/setViewport} 会**直接改真字段并广播 {@code screen_resized}**、
 * {@code setColorDepth} 与两个颜色/调色板钩子会**直接发报文**（都不经过 data）—— 不各自过闸门，
 * 原版显卡就仍然"写得进去"（改掉我们的 viewport / 色深 / 客户端颜色）。所以修复后调用点从第一版的
 * 7 处扩到 **12 处**（{@code TextBuffer.scala} 11 处：{@code data} + 5 个只读 getter +
 * {@code setResolution/setViewport/setColorDepth} + {@code onBufferColorChange/onBufferPaletteChange}；
 * {@code VideoRamRasterizer.scala} 1 处：{@code removeAllBuffers}），全部只回答"允许/拒绝"，一处都不抛。
 * 这 12 处由本类的离线闸门**逐行扫描**钉住（每个调用点都必须是 {@code if (闸门…) …} 的判定用法，
 * 不许裸调用）。</p>
 *
 * <h2>三、为什么要先看 {@link Thread#holdsLock(Object)}（成本账，实测）</h2>
 *
 * <p>纯调用栈判定每次要 6.1 µs（JDK 21，{@code StackWalker.walk} 限 12 帧，本机实测
 * 6100 ns/次）——屏的文本路径是"每次 gpu.set 都过闸门、甚至逐格调用"，这个量级会把写屏拖慢
 * 数倍。而 {@code Thread.holdsLock} 只要 26 ns（同机实测）。由第一节的字节码事实：
 * <b>原版显卡一定持锁调用</b> ⇒ 用"没持锁就直接放行"做快路径，只有持锁那一侧才做栈判定
 * （此时要么是原版显卡在驱动＝该拒绝，要么是我们自己 {@code owner.synchronized} 里的报文钩子
 * ＝放行，两者都是低频）。成本落到常规路径上是 26 ns/次；装好闸门后实测见
 * {@link OfflineGateSelfTest}（{@code main} 里每次跑都会打印）。</p>
 *
 * <p>⚠ 这条快路径依赖上面那条字节码事实（版本 8614366）。将来若升级 OC 且 {@code screen(...)}
 * 不再持锁，本闸门的快路径会漏判 —— 升级 OC 版本后必须重跑 {@code javap} 复核
 * {@code GraphicsCard} 是否仍在 {@code monitorenter} 之后调用屏方法。</p>
 *
 * <h2>四、被拒绝时会发生什么（可观察行为）</h2>
 * <ul>
 *   <li><b>给调用方</b>：什么都不抛。读类入口返回中性值
 *       （{@link #NEUTRAL_WIDTH}×{@link #NEUTRAL_HEIGHT}、视口 0×0、四色调色板深度）；
 *       写类入口（含 {@code data} 写、{@code setResolution/setViewport/setColorDepth}）**全部 no-op**，
 *       且不产生任何客户端报文 ⇒ 原版显卡**写不进任何内容、改不了任何状态**，服务端也不会因为
 *       一条网络消息崩。</li>
 *   <li><b>日志</b>：一行 {@code "[真彩屏] ✖ 拒绝原版 OC 显卡驱动本屏（op=…）…"}（前 8 次逐条、
 *       之后每 64 次一条，防刷屏）；累计次数见 {@link #refusals()}（无人化断言/诊断用）。</li>
 *   <li><b>唯一的残留</b>：{@code gpu.getScreen()} 读的是 {@code s.node.address}
 *       这个字段访问器（不是驱动操作、且逐次门禁会拖慢我们自己的 node 访问），所以原版显卡
 *       被拒后仍可能读到本屏地址；它读不到、也写不进任何屏幕内容。</li>
 * </ul>
 *
 * <p>⚠ 本类**不引用任何 {@code li.cil.oc.*} 类型**（只比较类名字符串）⇒ 没装 OC 时加载本类
 * 不会 NoClassDefFoundError；本类也不引用任何 Minecraft 类型 ⇒ 能直接在裸 JVM 上跑离线闸门。</p>
 *
 * <h2>五、离线闸门（裸 JVM，无 MC、无 OC）</h2>
 *
 * <pre>
 *   cmd /c gradlew.bat :neoforge:compileJava :neoforge:compileScala
 *   java -cp "neoforge/build/classes/java/main;neoforge/build/classes/scala/main;&lt;slf4j-api.jar&gt;" \
 *        com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate
 * </pre>
 *
 * <p>它用 {@code javax.tools} 现场编译一个**替身** {@code li.cil.oc.server.component.GraphicsCard}
 * （方法形状与 OC 的 {@code screen(index,f)} 一致：{@code synchronized (screen) { f.apply(screen); }}），
 * 经反射调用 ⇒ 栈上真的出现原版显卡帧、且真的持有屏监视器，然后照 OC 的 {@code onMessage} /
 * {@code bind} 的形状对该屏做一整套读写，断言：不抛异常、写全部无效、读为中性值、一条报文都不发、
 * VRAM 页一个都不清；再跑"我们的 GPU（不持锁）"与"持锁但非原版显卡"两个反例，最后实测快路径 ns/次。
 * 退出码非 0 = 有断言失败。</p>
 */
public final class TrueScreenDriveGate {

    /** 原版显卡环境类（bind/onConnect 里那个类型判定的持有者）。 */
    public static final String VANILLA_GPU = "li.cil.oc.server.component.GraphicsCard";

    /** 四联卡：内部就是四个 {@link #VANILLA_GPU}，帧名同样命中。 */
    public static final String VANILLA_QUAD_GPU = "li.cil.oc.server.component.QuadGraphicsCard";

    // ==================== 被拒驱动方读到的"中性值"（唯一来源） ====================

    /** 中性最大宽度：最小合法屏（被拒驱动方看到自己"没有屏可驱动"）。 */
    public static final int NEUTRAL_WIDTH = 1;

    /** 中性最大高度：同 {@link #NEUTRAL_WIDTH}。 */
    public static final int NEUTRAL_HEIGHT = 1;

    /** 中性视口宽：0 = 没有视口。 */
    public static final int NEUTRAL_VIEWPORT_WIDTH = 0;

    /** 中性视口高：0 = 没有视口。 */
    public static final int NEUTRAL_VIEWPORT_HEIGHT = 0;

    private static final Logger LOG = LoggerFactory.getLogger("cryptand/truescreen");

    /** 栈判定只向上看这么多帧就够：驱动方到屏方法的距离 ≤ 4（见类注释第一节）。 */
    private static final int MAX_FRAMES = 12;

    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final Function<Stream<StackWalker.StackFrame>, Boolean> VANILLA_GPU_ON_STACK =
            frames -> frames.limit(MAX_FRAMES).anyMatch(frame -> {
                final String name = frame.getClassName();
                return VANILLA_GPU.equals(name) || VANILLA_QUAD_GPU.equals(name);
            });

    private static final AtomicInteger REFUSALS = new AtomicInteger();

    private TrueScreenDriveGate() {
    }

    /**
     * 驱动方闸门：**只回答，不抛异常**（2026-09-27 P0 修复；原版显卡会被 {@code Network.send}
     * 的网络消息派发驱动，异常会穿出 tick 崩服务端）。
     *
     * @param screen 屏组件对象（原版显卡在它上面 {@code synchronized}，见类注释第一节）
     * @param op     被判定/被拒的操作名（写进日志，便于定位是哪条 API）
     * @return {@code true} = 调用方是 Cryptand GPU（或我们自己不持锁的路径）⇒ 照常做真实读写；
     *         {@code false} = 原版显卡 ⇒ **调用方必须降级**（读中性值、写 no-op），不得抛异常
     */
    public static boolean requireCryptandDriver(final Object screen, final String op) {
        // 快路径（26 ns）：原版显卡一定持锁调用（GraphicsCard.screen(...) 的 monitorenter）。
        if (screen == null || !Thread.holdsLock(screen)) {
            return true;
        }
        // 慢路径（6 µs）：只有持锁那一侧才做栈判定 —— 要么原版显卡（拒绝），要么我们自己的
        // 报文钩子（放行）。低频，量级可接受。
        if (!WALKER.walk(VANILLA_GPU_ON_STACK)) {
            return true;
        }
        refuse(op);
        return false;
    }

    /** 记一次拒绝（节流日志 + 计数）。绝不抛异常。 */
    private static void refuse(final String op) {
        final int n = REFUSALS.incrementAndGet();
        if (n <= 8 || n % 64 == 0) {
            LOG.error("[真彩屏] ✖ 拒绝原版 OC 显卡驱动本屏（op={}）：真彩屏只允许 Cryptand GPU 驱动；"
                            + "原版 opencomputers:graphicscard 的读一律拿到中性值、写一律 no-op（全部无效、"
                            + "不产生任何报文），服务端不会因此崩（累计拒绝 {} 次）", op, n);
        }
    }

    /** 累计拒绝次数（无人化断言/诊断用）。 */
    public static int refusals() {
        return REFUSALS.get();
    }

    /** 离线闸门入口（裸 JVM；退出码非 0 = 有断言失败）。 */
    public static void main(final String[] args) throws Exception {
        if (!OfflineGateSelfTest.run()) {
            System.exit(1);
        }
    }

    // ============================================================================================ //
    // 离线闸门：裸 JVM 复现"原版显卡经 Network.send 读/写本屏"（无 MC、无 OC）
    // ============================================================================================ //

    static final class OfflineGateSelfTest {

        /** 替身：形状与 OC 的私有辅助 {@code screen(index, f)} 一致（monitorenter → f(screen)）。 */
        private static final String STAND_IN_SOURCE =
                "package li.cil.oc.server.component;\n"
                        + "public final class GraphicsCard {\n"
                        + "    public static Object screen(Object screen, java.util.function.Function<Object, Object> f) {\n"
                        + "        synchronized (screen) {\n"
                        + "            return f.apply(screen);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n";

        private static int passed;
        private static int failed;

        private static int sink;

        private OfflineGateSelfTest() {
        }

        static boolean run() throws Exception {
            System.out.println("== TrueScreenDriveGate 离线闸门（裸 JVM；无 MC、无 OC） ==");
            final Class<?> standIn = compileStandIn();
            final Method screen = standIn.getMethod("screen", Object.class, Function.class);
            System.out.println("  替身帧类 = " + standIn.getName() + "（loaded by " + standIn.getClassLoader() + "）");

            crashShape(screen, false);
            crashShape(screen, true);
            ourGpuUnlocked();
            ourGpuLockedNotVanilla();
            sourceScan();
            benchmark(screen);

            System.out.println("== 离线闸门结果：" + passed + " 通过 / " + failed + " 失败 ==");
            return failed == 0;
        }

        // ------------------------------------------------------------------ //
        // 用例 1/2：原版显卡（真持锁 + 真 GraphicsCard 帧）读写本屏
        // ------------------------------------------------------------------ //

        /**
         * @param bindShape false = 复现崩溃报告那条路（{@code onMessage("computer.stopped")} 的错误屏分支，
         *                  {@code Machine.tryClose → Network.send → GraphicsCard.onMessage → screen(...)}）；
         *                  true = {@code gpu.bind} 的 reset 分支（含 {@code removeAllBuffers()}）。
         */
        private static void crashShape(final Method screen, final boolean bindShape) {
            final String tag = bindShape ? "B(bind)" : "A(onMessage/Network.send)";
            final FakeScreen s = new FakeScreen();
            final String before = s.snapshot();
            final int refusalsBefore = refusals();
            final int[] seen = new int[5];
            final int[] removed = new int[1];
            try {
                // Network.send 不捕获异常 ⇒ 任何穿出的异常都等于"服务端崩"，这里就是失败。
                screen.invoke(null, s, (Function<Object, Object>) o -> {
                    if (bindShape) {
                        driveBindShape(s, seen, removed);
                    } else {
                        driveOnMessageShape(s, seen);
                    }
                    return null;
                });
            } catch (final Throwable t) {
                check(tag + "：经 Network.send 形状读/写本屏不抛异常", false,
                        "抛出了 " + t.getCause() + "（= 真机上就是服务端崩）");
                return;
            }
            check(tag + "：经 Network.send 形状读/写本屏不抛异常", true, null);
            check(tag + "：闸门判定为拒绝并计数（+" + (refusals() - refusalsBefore) + "）",
                    refusals() > refusalsBefore, "refusals=" + refusals());
            check(tag + "：真状态一格未动（几何/视口/色深/前景背景色/VRAM 页）", s.snapshot().equals(before),
                    "before=" + before + "\n         after =" + s.snapshot());
            check(tag + "：一条客户端报文都没发（notifications=" + s.notifications + "）", s.notifications == 0, null);
            check(tag + "：读到的全是中性值（max=" + seen[0] + "x" + seen[1] + ", viewport=" + seen[2] + "x" + seen[3]
                    + ", depthOrdinal=" + seen[4] + "）",
                    seen[0] == NEUTRAL_WIDTH && seen[1] == NEUTRAL_HEIGHT
                            && seen[2] == NEUTRAL_VIEWPORT_WIDTH && seen[3] == NEUTRAL_VIEWPORT_HEIGHT
                            && seen[4] == FakeScreen.FOUR_BIT_ORDINAL, null);
            check(tag + "：data 读到的字符面是空白的中性假屏（宽 x 高 = " + s.lastDataWidth + "x" + s.lastDataHeight + "）",
                    s.lastDataWidth == NEUTRAL_WIDTH && s.lastDataHeight == NEUTRAL_HEIGHT, null);
            if (bindShape) {
                check(tag + "：removeAllBuffers() 返回 0（我们 GPU 登记的 VRAM 页一个都没清）", removed[0] == 0,
                        "返回 " + removed[0]);
                check(tag + "：VRAM 页仍为 " + s.vramPages, s.vramPages.size() == 3, null);
            }
        }

        /** 照 OC {@code GraphicsCard.onMessage} 的错误屏分支（缓存源码 591-628 行）的形状。 */
        private static void driveOnMessageShape(final FakeScreen s, final int[] seen) {
            final int gmw = 64;
            final int gmh = 32;
            final int gpuDepth = 3;
            seen[0] = s.getMaximumWidth();
            seen[1] = s.getMaximumHeight();
            s.setResolution(Math.min(gmw, seen[0]), Math.min(gmh, seen[1]));
            seen[4] = s.getMaximumColorDepthOrdinal();
            s.setColorDepth(Math.min(gpuDepth, seen[4]));
            s.setForegroundColor(0xFFFFFF, false);
            final int w = s.getWidth();
            final int h = s.getHeight();
            s.getBackgroundColor();
            s.isBackgroundFromPalette();
            if (s.getColorDepthOrdinal() > 0) {
                s.setBackgroundColor(0x0000FF);
            } else {
                s.setBackgroundColor(0x000000);
            }
            s.fill(0, 0, w, h, 0x20);
            final String message = "Unrecoverable Error";
            s.set((w - message.length()) / 2, -1, message, false);
            s.set(0, 2, "line", false);
            seen[2] = s.getViewportWidth();
            seen[3] = s.getViewportHeight();
        }

        /** 照 OC {@code GraphicsCard.bind(reset=true)}（缓存源码 302-318 行）的形状。 */
        private static void driveBindShape(final FakeScreen s, final int[] seen, final int[] removed) {
            final int gmw = 64;
            final int gmh = 32;
            final int gpuDepth = 3;
            seen[0] = s.getMaximumWidth();
            seen[1] = s.getMaximumHeight();
            s.setResolution(Math.min(gmw, seen[0]), Math.min(gmh, seen[1]));
            seen[4] = s.getMaximumColorDepthOrdinal();
            s.setColorDepth(Math.min(gpuDepth, seen[4]));
            s.setForegroundColor(0xFFFFFF, false);
            s.setBackgroundColor(0x000000);
            removed[0] = s.removeAllBuffers();
            seen[2] = s.getViewportWidth();
            seen[3] = s.getViewportHeight();
        }

        // ------------------------------------------------------------------ //
        // 用例 3/4：反例 —— 我们的 GPU 必须照常写得进去（行为不变）
        // ------------------------------------------------------------------ //

        private static void ourGpuUnlocked() {
            final FakeScreen s = new FakeScreen();
            final int refusalsBefore = refusals();
            s.set(0, 0, "X", false);
            s.fill(1, 1, 3, 2, '#');
            s.setResolution(20, 10);
            s.setViewport(10, 5);
            s.setForegroundColor(0x123456, false);
            final boolean wrote = s.real.cells[0][0] == 'X' && s.real.cells[1][1] == '#';
            check("C(我们的 GPU，不持锁)：写生效 + 不拒绝（refusals +" + (refusals() - refusalsBefore) + "）",
                    wrote && refusals() == refusalsBefore && s.notifications > 0,
                    "cells[0][0]=" + (char) s.real.cells[0][0] + ", notifications=" + s.notifications);
        }

        private static void ourGpuLockedNotVanilla() {
            final FakeScreen s = new FakeScreen();
            final int refusalsBefore = refusals();
            // 我们自己的报文钩子形状：持屏监视器，但栈上没有原版显卡帧 ⇒ 必须放行（行为不变）。
            synchronized (s) {
                s.set(0, 0, "Y", false);
                s.onBufferColorChange();
            }
            check("D(持锁但不是原版显卡＝我们的报文钩子)：放行、写生效",
                    s.real.cells[0][0] == 'Y' && refusals() == refusalsBefore && s.notifications > 0,
                    "cells[0][0]=" + (char) s.real.cells[0][0] + ", notifications=" + s.notifications);
        }

        // ------------------------------------------------------------------ //
        // 用例 5：源码闸门 —— 每个闸门调用点都必须"用返回值"，不许裸调用
        // ------------------------------------------------------------------ //

        private static void sourceScan() {
            final String[] files = {
                    "neoforge/src/main/scala/com/hdf/cryptand/neoforge/truescreen/common/component/TextBuffer.scala",
                    "neoforge/src/main/scala/com/hdf/cryptand/neoforge/truescreen/common/component/traits/VideoRamRasterizer.scala",
            };
            int sites = 0;
            boolean ok = true;
            final StringBuilder detail = new StringBuilder();
            for (final String rel : files) {
                final Path p = resolveRepoFile(rel);
                if (p == null) {
                    check("E(源码闸门)：找不到 " + rel + " ⇒ SKIP（把工作目录设成仓库根再跑）", true, "SKIP");
                    continue;
                }
                try {
                    final List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        final String line = lines.get(i);
                        final int at = line.indexOf("requireCryptandDriver(this, \"");
                        if (at < 0) {
                            continue;
                        }
                        sites++;
                        final int ifAt = line.indexOf("if (");
                        final boolean conditional = ifAt >= 0 && ifAt < at;
                        if (!conditional || line.contains("throw")) {
                            ok = false;
                            detail.append("\n        ").append(rel).append(':').append(i + 1).append(": ").append(line.trim());
                        }
                    }
                } catch (final Exception e) {
                    ok = false;
                    detail.append("\n        ").append(rel).append(": ").append(e);
                }
            }
            check("E(源码闸门)：闸门调用点全部是 if(闸门) 的判定用法（共 " + sites + " 处，无裸调用、无 throw）"
                    + (ok ? "" : detail.toString()), ok && sites >= 12, "sites=" + sites);
        }

        private static Path resolveRepoFile(final String rel) {
            Path dir = Path.of("").toAbsolutePath();
            for (int i = 0; i < 5 && dir != null; i++) {
                final Path candidate = dir.resolve(rel);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                dir = dir.getParent();
            }
            return null;
        }

        // ------------------------------------------------------------------ //
        // 用例 6：快路径成本（ns/次）—— 防线不许退化
        // ------------------------------------------------------------------ //

        private interface Op {
            void run();
        }

        private static void benchmark(final Method screen) throws Exception {
            final Object s = new FakeScreen();
            final int iters = 2_000_000;
            final long holdsLock = best(iters, () -> {
                if (Thread.holdsLock(s)) {
                    sink++;
                }
            });
            final long fastPath = best(iters, () -> {
                if (requireCryptandDriver(s, "bench")) {
                    sink++;
                }
            });
            final int slowIters = 20_000;
            final long slowPath = best(slowIters, () -> {
                try {
                    screen.invoke(null, s, (Function<Object, Object>) o -> {
                        if (requireCryptandDriver(s, "bench")) {
                            sink++;
                        }
                        return null;
                    });
                } catch (final Throwable t) {
                    throw new IllegalStateException(t);
                }
            });
            final double lockNs = holdsLock / (double) iters;
            final double fastNs = fastPath / (double) iters;
            final double slowNs = slowPath / (double) slowIters;
            System.out.printf("  [BENCH] Thread.holdsLock 单独 = %.1f ns/次；闸门快路径（不持锁放行）= %.1f ns/次"
                            + "（= 基线的 %.2f 倍）；闸门慢路径（持锁 + 原版显卡帧：判定 + 拒绝）= %.0f ns/次（sink=%d）%n",
                    lockNs, fastNs, fastNs / lockNs, slowNs, sink);
            // 与基线做**比值**判定（机器忙时两者一起变大 ⇒ 比值稳定，不会误报）；
            // 绝对值那一半只用来兜底"快路径意外走了栈判定"这种量级错误（6 µs 级别）。
            final double budget = Math.max(60.0, lockNs * 3.0);
            check(String.format("F(快路径)：闸门快路径 ≈ Thread.holdsLock 基线（%.1f ns/次 vs %.1f ns/次，"
                            + "预算 %.1f ns/次）—— 防线不退化", fastNs, lockNs, budget),
                    fastNs <= budget, String.format("%.1f ns/次", fastNs));
        }

        private static long best(final int iters, final Op op) {
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < iters; i++) {
                    op.run();
                }
            }
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                final long t0 = System.nanoTime();
                for (int i = 0; i < iters; i++) {
                    op.run();
                }
                final long dt = System.nanoTime() - t0;
                if (dt < best) {
                    best = dt;
                }
            }
            return best;
        }

        // ------------------------------------------------------------------ //

        private static Class<?> compileStandIn() throws Exception {
            final Path dir = Files.createTempDirectory("cryptand-drive-gate-standin");
            final Path pkg = dir.resolve("li").resolve("cil").resolve("oc").resolve("server").resolve("component");
            Files.createDirectories(pkg);
            final Path src = pkg.resolve("GraphicsCard.java");
            Files.writeString(src, STAND_IN_SOURCE, StandardCharsets.UTF_8);
            final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IllegalStateException("本 JVM 没有 javax.tools 编译器（需要 JDK 而不是 JRE）");
            }
            if (javac.run(null, null, null, src.toString()) != 0) {
                throw new IllegalStateException("替身 GraphicsCard 编译失败：" + src);
            }
            final URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            return Class.forName("li.cil.oc.server.component.GraphicsCard", true, loader);
        }

        private static void check(final String what, final boolean ok, final String detail) {
            if (ok) {
                passed++;
                System.out.println("  [PASS] " + what);
            } else {
                failed++;
                System.out.println("  [FAIL] " + what + (detail == null ? "" : "\n        " + detail));
            }
        }
    }

    /**
     * 我方真彩屏的替身：**每个方法体就是 {@code TextBuffer.scala} / {@code VideoRamRasterizer.scala}
     * 里对应调用点那一行**（闸门判定 + 降级分支），用于离线复现"原版显卡读/写本屏"。
     */
    static final class FakeScreen {

        /** 中性色深（四色调色板）：与真实现的 {@code RefusedView} 用同一个档位。 */
        static final int FOUR_BIT_ORDINAL = 1;

        // ---- 真状态（只有我们的 GPU 能改） ----
        final int maxW = 64;
        final int maxH = 32;
        int viewportW = 64;
        int viewportH = 32;
        int depthOrdinal = 3;
        int foreground = 0xFFFFFF;
        int background = 0x000000;
        int notifications;
        final List<Integer> vramPages = new ArrayList<>(List.of(1, 2, 3));
        final DataBuf real = new DataBuf(64, 32, false);

        // ---- 被拒驱动方看到的"只读假屏"（= TextBuffer.RefusedView 的替身） ----
        final DataBuf refused = new DataBuf(TrueScreenDriveGate.NEUTRAL_WIDTH, TrueScreenDriveGate.NEUTRAL_HEIGHT, true);
        int lastDataWidth;
        int lastDataHeight;

        // ============ 闸门调用点（与生产代码逐条对应） ============

        DataBuf data() {
            final DataBuf d = TrueScreenDriveGate.requireCryptandDriver(this, "data") ? real : refused;
            lastDataWidth = d.w;
            lastDataHeight = d.h;
            return d;
        }

        int getMaximumWidth() {
            if (TrueScreenDriveGate.requireCryptandDriver(this, "getMaximumWidth")) {
                return maxW;
            }
            return TrueScreenDriveGate.NEUTRAL_WIDTH;
        }

        int getMaximumHeight() {
            if (TrueScreenDriveGate.requireCryptandDriver(this, "getMaximumHeight")) {
                return maxH;
            }
            return TrueScreenDriveGate.NEUTRAL_HEIGHT;
        }

        int getViewportWidth() {
            if (TrueScreenDriveGate.requireCryptandDriver(this, "getViewportWidth")) {
                return viewportW;
            }
            return TrueScreenDriveGate.NEUTRAL_VIEWPORT_WIDTH;
        }

        int getViewportHeight() {
            if (TrueScreenDriveGate.requireCryptandDriver(this, "getViewportHeight")) {
                return viewportH;
            }
            return TrueScreenDriveGate.NEUTRAL_VIEWPORT_HEIGHT;
        }

        int getMaximumColorDepthOrdinal() {
            if (TrueScreenDriveGate.requireCryptandDriver(this, "getMaximumColorDepth")) {
                return depthOrdinal;
            }
            return FOUR_BIT_ORDINAL;
        }

        boolean setResolution(final int w, final int h) {
            if (!TrueScreenDriveGate.requireCryptandDriver(this, "setResolution")) {
                return false;
            }
            final boolean changed = real.w != w || real.h != h;
            return changed;
        }

        boolean setViewport(final int w, final int h) {
            if (!TrueScreenDriveGate.requireCryptandDriver(this, "setViewport")) {
                return false;
            }
            final boolean changed = viewportW != w || viewportH != h;
            viewportW = w;
            viewportH = h;
            if (changed) {
                notifications++;
            }
            return changed;
        }

        boolean setColorDepth(final int ordinal) {
            if (!TrueScreenDriveGate.requireCryptandDriver(this, "setColorDepth")) {
                return false;
            }
            final boolean changed = depthOrdinal != ordinal;
            depthOrdinal = ordinal;
            if (changed) {
                notifications++;
            }
            return changed;
        }

        void onBufferColorChange() {
            if (!TrueScreenDriveGate.requireCryptandDriver(this, "onBufferColorChange")) {
                return;
            }
            notifications++;
        }

        void onBufferPaletteChange(final int index) {
            if (!TrueScreenDriveGate.requireCryptandDriver(this, "onBufferPaletteChange")) {
                return;
            }
            notifications++;
        }

        int removeAllBuffers() {
            if (!TrueScreenDriveGate.requireCryptandDriver(this, "removeAllBuffers")) {
                return 0;
            }
            final int n = vramPages.size();
            vramPages.clear();
            return n;
        }

        // ============ api.internal.TextBuffer 的读写（照 TextBufferProxy 的形状） ============

        int getWidth() {
            return data().w;
        }

        int getHeight() {
            return data().h;
        }

        int getColorDepthOrdinal() {
            return data() == refused ? FOUR_BIT_ORDINAL : depthOrdinal;
        }

        void set(final int col, final int row, final String s, final boolean vertical) {
            if (data().set(col, row, s.codePointAt(0))) {
                notifications++;
            }
        }

        void fill(final int col, final int row, final int w, final int h, final int ch) {
            if (data().fill(col, row, w, h, ch)) {
                notifications++;
            }
        }

        void copy(final int col, final int row, final int w, final int h, final int tx, final int ty) {
            if (data().copy(col, row, w, h, tx, ty)) {
                notifications++;
            }
        }

        void setForegroundColor(final int color, final boolean fromPalette) {
            if (data() == refused) {
                // 假屏的颜色 setter 是 no-op ⇒ 值永远"不等" ⇒ 一定会走钩子（钩子被闸门挡住）。
                onBufferColorChange();
                return;
            }
            if (foreground != color) {
                foreground = color;
                onBufferColorChange();
            }
        }

        void setBackgroundColor(final int color) {
            if (data() == refused) {
                onBufferColorChange();
                return;
            }
            if (background != color) {
                background = color;
                onBufferColorChange();
            }
        }

        int getBackgroundColor() {
            return data() == refused ? 0x000000 : background;
        }

        boolean isBackgroundFromPalette() {
            return data() == refused;
        }

        String snapshot() {
            return "geometry=" + real.w + "x" + real.h
                    + ", viewport=" + viewportW + "x" + viewportH
                    + ", depth=" + depthOrdinal
                    + ", fg=" + foreground + ", bg=" + background
                    + ", notifications=" + notifications
                    + ", vram=" + vramPages
                    + ", cells=" + java.util.Arrays.deepToString(real.cells);
        }
    }

    /**
     * 字符面替身：{@code refused=true} 就是 {@code RefusedView} 的语义 —— 任何写都返回 false
     * 且什么都不改（于是 {@code if (data.set(...)) onBufferSet(...)} 这类钩子根本不会触发）。
     */
    static final class DataBuf {

        final int w;
        final int h;
        final int[][] cells;
        private final boolean refused;

        DataBuf(final int w, final int h, final boolean refused) {
            this.w = w;
            this.h = h;
            this.refused = refused;
            this.cells = new int[h][w];
            for (int y = 0; y < h; y++) {
                java.util.Arrays.fill(cells[y], ' ');
            }
        }

        boolean set(final int col, final int row, final int ch) {
            if (refused || row < 0 || row >= h || col < 0 || col >= w) {
                return false;
            }
            final boolean changed = cells[row][col] != ch;
            cells[row][col] = ch;
            return changed;
        }

        boolean fill(final int col, final int row, final int w0, final int h0, final int ch) {
            if (refused || w0 <= 0 || h0 <= 0) {
                return false;
            }
            boolean changed = false;
            for (int y = Math.max(row, 0); y < Math.min(row + h0, h); y++) {
                for (int x = Math.max(col, 0); x < Math.min(col + w0, w); x++) {
                    changed |= cells[y][x] != ch;
                    cells[y][x] = ch;
                }
            }
            return changed;
        }

        boolean copy(final int col, final int row, final int w0, final int h0, final int tx, final int ty) {
            if (refused || w0 <= 0 || h0 <= 0 || (tx == 0 && ty == 0)) {
                return false;
            }
            boolean changed = false;
            for (int y = Math.min(h - 1, row + h0 - 1); y >= Math.max(0, row); y--) {
                for (int x = Math.min(w - 1, col + w0 - 1); x >= Math.max(0, col); x--) {
                    final int sy = y - ty;
                    final int sx = x - tx;
                    if (sy < 0 || sy >= h || sx < 0 || sx >= w) {
                        continue;
                    }
                    changed |= cells[y][x] != cells[sy][sx];
                    cells[y][x] = cells[sy][sx];
                }
            }
            return changed;
        }
    }
}

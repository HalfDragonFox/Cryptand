package com.hdf.cryptand.neoforge.opencomputers;

import li.cil.oc.api.Network;
import li.cil.oc.api.internal.TextBuffer;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.AbstractManagedEnvironment;

import com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi;
import com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsSink;
import com.hdf.cryptand.soc.board.DisplayTopology;
import com.hdf.cryptand.soc.board.GpuMemory;
import com.hdf.cryptand.soc.oc.OcAbi;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== Cryptand 显卡 → OC 的 gpu 组件（2026-09-26，用户定案）=====
 *
 * <p>用户原话："显示器必须有显卡才能被 RV 识别"、"我们的自定义显卡支持 oc 原版和真彩色屏幕两种"、
 * "显卡占用内存，内存不够会导致渲染出问题"、"显卡有定义通道，1 个通道则支持 1 个屏幕（包括拼接的
 * 屏幕算一个），4 个则表示 4 个"。</p>
 *
 * <h3>为什么必须有这个类</h3>
 * <p>以前 {@code CardDriver.createEnvironment} 直接返回 {@code noEnvironment()} ——
 * 于是机箱里插的 {@code cryptand:graphicscard*} <b>从来没有变成过 gpu 组件</b>：
 * OC 的组件表里没有 gpu（宿主日志那句"没有可见的 gpu 组件"），Cryptand 架构的
 * {@code OcComponentBus} 找不到显卡去 {@code bind}/{@code bitblt}，
 * 固件的画字调用只能失败（真机症状：{@code flush row0=[Cryptand OS ] blit=FAIL}、屏幕永远空白）。</p>
 *
 * <h3>职责边界（照项目分层）</h3>
 * <ul>
 *   <li><b>本类只做"卡"</b>：通道数（能挂几块逻辑屏）、当前色/调色板、显存页、以及把写操作落到
 *       <b>被 bind 的那块屏</b>上；</li>
 *   <li><b>屏</b>用 OC 的公开接口 {@link TextBuffer}（OC 原版屏就是它）。真彩屏将来实现同一套
 *       写入口（或走 {@code DisplayTopology.ScreenKind} 分派）⇒ 卡不需要知道屏是字符还是像素；</li>
 *   <li><b>★ 任务 G（2026-09-27）：本类的回调只有 OC 的字符 API 这一条。</b>用户定案"程序直接画
 *       图像输出，转字符还是直接画由虚拟机（GPU 部分）自动决定"⇒ "设备处于 GRAPHICS 时把
 *       set/fill 当像素写"那条**需要外部切模式**的路径已整条删除（gpu.setMode 也作废）；
 *       图形面（图像）的出口在屏链路：{@code OcComponentBus.presentImage(...)}，判定规则是
 *       common 的 {@code ScreenOutputFace.derive}（唯一一份）。详见本类"图形面走哪条路"那一段注释。</li>
 *   <li><b>内存口径</b>在 common 的 {@code GpuMemory}（基础占用 + 每屏 VRAM），这里只负责
 *       "按通道数拒绝超额挂载"与<b>挂屏那一刻的内存闸门</b>，不自己再算一套数值。</li>
 * </ul>
 *
 * <h3>与 OC 原版显卡的关系</h3>
 * <p>方法<b>名字与语义</b>照 OC 的 gpu（{@code set/get/fill/copy/bind/getResolution/setForeground/
 * setBackground/setPaletteColor/allocateBuffer/setActiveBuffer/getBufferSize/freeBuffer/
 * freeAllBuffers/bitblt}）—— 因为 {@code OcComponentBus} 与 Cryptand 固件都是按这套名字调用的；
 * 实现是我们自己的（没有一行来自 OC）。差异只有一处：<b>通道数</b>是我们加的语义
 * （OC 的显卡只认"一次一块屏"），超了明确报错，不静默抢屏。</p>
 */
public final class CryptandGpuEnvironment extends AbstractManagedEnvironment {

    /** 日志（诊断/无人化断言用；只有 log4j 会进 run/logs/latest.log） */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    /** 屏页（0 = 屏幕本身，与 OC 一致）；1..n = 显存页 */
    private static final int SCREEN_PAGE = 0;

    // ⚠⚠ 两套坐标空间，混了就"差一格"（真机踩过：读屏时 x=宽 直接 IndexOutOfBoundsException）：
    //   · **回调参数**（Lua / 组件 API / 固件）是 **1 基**：gpu.set(1, 1, ...) 是第一格；
    //   · OC 的 {@code api.internal.TextBuffer}（util.TextBuffer.scala:106/113/160/180）
    //     是 **0 基**，且 get 越界**直接抛** IndexOutOfBoundsException。
    //   本项目里"页"（我们的显存）也按 1 基（因为 OcComponentBus 写页用的是 1,1+i），
    //   所以只有**真正落到屏上**的那一刻才做 -1 换算（见 toScreenX/toScreenY）。

    /** 这张卡能同时驱动的**逻辑屏**数（= 物品档位的"输出通道数"；拼接屏算一块） */
    private final int channels;

    /** 卡的名字（日志/诊断用，如 graphicscard3） */
    private final String cardId;

    /** 已经 bind 上的屏（节点地址 → 屏）。容量上限 = {@link #channels} */
    private final List<Bound> bound = new ArrayList<>();

    /**
     * 本机的**显示预算**（内存池 + 卡表 + 屏表）—— 架构侧算好的唯一一份显存口径。
     *
     * <p>⚠ 必须**延迟求值**（Supplier，不是对象）：组件环境在机箱物品装载时就被建出来，
     * 那一刻 OC 还没有架构对象（架构是 {@code Machine.start} 里按处理器反射实例化的），
     * 预算得等到 bind 时才是本机那一份。取值路径：
     * {@code host(MachineHost) → machine() → architecture() → CryptandOcArchitecture.gpuBudget()}。</p>
     *
     * <p>{@code null} = 本机不是 Cryptand 的 C/RV32 架构（例如 OC 原版 Lua 架构里插了我们的卡）：
     * 那时没有"内存池 = 内存条容量"这个口径可比，本卡就照 OC 的显卡语义工作（见 {@link #bind}）。</p>
     */
    private final java.util.function.Supplier<GpuMemory.Budget> budgetSource;

    /** 上一次认下的预算对象（换对象 = 架构重算了/重开机了 ⇒ 要把自己的屏重新登记进去） */
    private GpuMemory.Budget lastBudget;

    /** "本机不是 Cryptand 架构、没有显存口径"只打一次（组件每 tick 都可能被调） */
    private boolean budgetMissingLogged;

    /** 当前活动页（0 = 屏幕；>0 = {@link #pages} 里的下标 + 1） */
    private int activePage = SCREEN_PAGE;

    /** 显存页（页 1 起；每页三平面：字符 + 前景 + 背景，与屏幕同构） */
    private final List<Page> pages = new ArrayList<>();

    private int foreground = 0xFFFFFF;
    private int background = 0x000000;

    /**
     * {@code setForeground(color, palette=true)} 的语义：给的**是调色板索引**而不是 RGB。
     *
     * <p>字符模式下设备按格存 RGB/索引，标志只是照 OC 记着（{@code isForegroundFromPalette} 会被读）。
     * （任务 G：像素画笔那条路已删，"标志决定像素落地值"的用法随之消失。）</p>
     */
    private boolean foregroundFromPalette;

    /**
     * 一块被 bind 上的屏：地址 + 它的写入口。
     *
     * <p>任务 F-2（2026-09-27）：旧自研真彩屏那套（{@code CryptandScreenEnvironment} 与
     * {@code soc/block/TrueScreenBlockEntity}）已整体删除 ⇒ 这里只剩一条屏来源：OC 的公开接口
     * {@link TextBuffer}（我方 Scala 移植屏与 OC 原版屏都实现它）。像素通道另由**按地址登记**的
     * {@link TrueScreenGraphicsSink} 提供（{@code TrueScreenGraphicsApi}）。</p>
     */
    private record Bound(String address, TextBuffer screen, TrueScreenGraphicsSink sink) {

        /**
         * 这块屏的**像素面**（我方移植屏 = Scala 移植件的真彩屏设备，与 guest 显存同一份）；
         * 没登记 ⇒ null ⇒ 该屏**只能显示字符**。任务 G：这个事实是输出面派生的输入之一
         * （{@code ScreenOutputFace.capabilityOf(pixelDevice() != null)}），本类自己不再拿它决定
         * 怎么画（图形面出口在 {@code OcComponentBus.presentImage}）。
         */
        com.hdf.cryptand.soc.board.TrueColorScreen pixelDevice() {
            return sink == null ? null : sink.device();
        }

    }

    /** 一个显存页：三平面（字符 + 每格前景/背景）—— 与屏幕同构，bitblt 才能逐格搬 */
    private static final class Page {
        private final int width;
        private final int height;
        private final String[] cells;
        private final int[] fg;
        private final int[] bg;

        Page(int width, int height) {
            this.width = width;
            this.height = height;
            this.cells = new String[width * height];
            this.fg = new int[width * height];
            this.bg = new int[width * height];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = " ";
            }
        }
    }

    /**
     * @param cardId       卡的名字（诊断用，如 graphicscard3）
     * @param channels     通道数（= 物品档位的"输出通道数"）
     * @param componentName OC 组件类型名 —— 由 {@code SocPartKind.CARD_GPU.ocComponent()} 给（单一来源，
     *                      免得"物品上写 gpu、组件里叫 graphics"这种两处各写一遍的漂移）
     * @param budgetSource  本机显示预算的**延迟取值**（架构侧唯一一份口径；见字段说明）。{@code null}
     *                      ⇒ 本卡不做内存闸门（本机没有 Cryptand 内存池这个概念）
     */
    public CryptandGpuEnvironment(String cardId, int channels, String componentName,
                                  java.util.function.Supplier<GpuMemory.Budget> budgetSource) {
        this.cardId = cardId == null ? OcAbi.GPU_COMPONENT_NAME : cardId;
        this.channels = Math.max(1, channels);
        this.budgetSource = budgetSource;
        final String name = componentName == null || componentName.isBlank()
                ? OcAbi.GPU_COMPONENT_NAME : componentName;
        // 组件名必须是 OcAbi.GPU_COMPONENT_NAME（rc_gpu；OcComponentBus 的句柄表按名字排，
        // HANDLE_GPU = 0；固件的画字调用就是打到这个名字上的）
        // —— 名字来源是 SocPartKind.CARD_GPU.ocComponent()，它引用的也是同一个常量。
        setNode(Network.newNode(this, Visibility.Network)
                .withComponent(name, Visibility.Network)
                .create());
    }

    /** 通道数（能挂几块逻辑屏） */
    public int channels() {
        return channels;
    }

    /** 已挂上的屏数 */
    public int boundCount() {
        synchronized (bound) {
            return bound.size();
        }
    }

    // ==================== 绑定 ====================

    /**
     * {@code bind(address)}：把这张卡接到一块屏上。
     *
     * <p>⚠ 通道数就是这里的上限：已经挂了 {@link #channels} 块还来新的 ⇒
     * <b>明确报错</b>（用户："1 个通道则支持 1 个屏幕…4 个则表示 4 个"）——
     * 静默抢屏会让"插了第二块屏却永远不亮"变成最难查的那种故障。</p>
     *
     * <p>★ 内存那一半（用户 2026-09-26："显卡占用内存，内存不够会导致渲染出问题"；2026-09-27 定案
     * 把表现点钉在这里）：把这块屏的真实分辨率/形态记进本机预算再算一次，放不下就<b>拒绝这次 bind</b>
     * 并把"差多少 KB"回给调用方 ——机器照常运行，只是这块屏没挂上（加内存条/减屏/换小分辨率之后
     * 再 bind 一次即可）。</p>
     *
     * <p>⚠ 池 = 内存条**声明的**容量（用户 2026-09-27："外部比如内存只是<b>指示器</b>"）：它只声明
     * "这台虚拟机有多少内存"，不构成宿主侧另一份可支配的内存。所以这里的闸门是**设备侧**的
     * （渲染放不下就明确拒绝），<b>不是</b>引导期闸门 —— 引导期已不做内存账（内存归虚拟机自己管）。</p>
     *
     * @return {@code false} = 该地址不是一块屏（照 OC 语义）；{@code (false, 说明)} = 显存放不下
     *         （说明里带差多少 KB）或屏还没成形
     */
    @Callback(value = "bind", direct = true, doc = "function(address:string):boolean -- bind this card to a screen")
    public Object[] bind(Context context, Arguments args) {
        final String address = args.checkString(0);
        final Bound target = resolveScreen(address);
        if (target == null) {
            return new Object[]{false};
        }
        synchronized (bound) {
            for (final Bound b : bound) {
                if (b.address().equals(address)) {
                    return new Object[]{true};              // 同一个地址重复 bind = 幂等
                }
            }
            if (bound.size() >= channels) {
                throw new IllegalArgumentException("no free channel: " + cardId + " drives " + channels
                        + " screen(s), already " + bound.size() + " (" + addresses() + ")");
            }
            // ★ 内存闸门 —— **表现点在渲染侧**（用户 2026-09-26："显卡占用内存，内存不够会导致
            //   渲染出问题"；2026-09-27 定案把内存账从引导期拿掉之后，这里是唯一的内存判定点）。
            //   通道数上面已经挡住，这里只补**内存那一半**：把这块屏的**真实分辨率/形态**加进
            //   本机屏表再算一次；放不下就明确拒绝这次 bind（回错误 + 日志，机器照常跑）。
            //   ⚠ 拒绝必须是"回一条 OC 错误"，绝不抛异常穿透到 OC 线程 —— 抛出去会变成"组件调用异常"，
            //     用户看到的是"bind 崩了"，而不是"内存不够、该加内存条"。
            final DisplayTopology.Screen entry = screenEntry(address, target);
            if (entry == null) {
                // 分辨率 0 = 屏还没成形，显存算不出来 ⇒ 明确拒绝，绝不按猜测的数字记账
                LOG.error("[OpenComputers] ✖ 拒绝 bind {} -> {}：屏幕分辨率为 {}x{}（屏还没成形）⇒ "
                                + "显存占用算不出来，先让屏幕成形再 bind",
                        cardId, address, target.screen().getWidth(), target.screen().getHeight());
                return new Object[]{false, "screen has no resolution yet"};
            }
            final GpuMemory.Budget budget = budgetSource == null ? null : budgetSource.get();
            if (budget == null) {
                // 本机不是 Cryptand 的 C/RV32 架构（如 OC 原版 Lua 架构）⇒ 没有"内存池 = 内存条
                // **声明的**容量（指示器）"这个口径可比，OC 自己的内存模型负责。**明说一次**，不假装校验过。
                if (!budgetMissingLogged) {
                    budgetMissingLogged = true;
                    LOG.warn("[OpenComputers] gpu {} 所在机器不是 Cryptand C/RV32 架构 ⇒ 没有 Cryptand 显存口径"
                            + "（内存池 = 内存条声明的容量，指示器）可比：本卡按 OC 显卡语义工作，不做内存闸门",
                            cardId);
                }
            } else {
                adoptBudget(budget);
                try {
                    budget.validateWith(entry);
                } catch (IllegalStateException e) {
                    LOG.error("[OpenComputers] ✖ 拒绝 bind {} -> {}：{} —— 怎么办：加内存条（池大一点）、"
                                    + "减屏/换更小的分辨率，或换通道数更多的卡；本次 bind 未生效（机器照常运行）",
                            cardId, address, e.getMessage());
                    return new Object[]{false, e.getMessage()};
                }
                budget.attach(entry);
                // 记账后打一行口径：实机日志里"这块屏吃了多少显存、池还剩多少"一眼可见
                LOG.info("[OpenComputers] gpu {} bind 成功，内存口径 {}", cardId, budget.summaryWith(null));
            }
            bound.add(target);
        }
        // 绑定即记录一行：无人化断言要能看出"绑到了谁、是字符屏还是我方真彩屏"
        LOG.info("[OpenComputers] gpu.bind {} -> addr={} 类型={} 分辨率={}x{}", cardId, address,
                target.sink() != null ? "Cryptand 移植屏（Scala，像素通道已登记）" : "OC 原版 screen",
                target.screen().getWidth(), target.screen().getHeight());
        return new Object[]{true};
    }

    /** {@code getResolution()} → 当前活动屏的分辨率（没 bind 时返回 nil，照 OC） */
    @Callback(value = "getResolution", direct = true, doc = "function():number, number -- the resolution of the screen")
    public Object[] getResolution(Context context, Arguments args) {
        final TextBuffer screen = activeScreen();
        if (screen == null) {
            return new Object[]{null, null};
        }
        return new Object[]{screen.getWidth(), screen.getHeight()};
    }

    /** 当前活动屏（最后 bind 的那块；OC 原版屏与真彩屏都按公开接口 {@link TextBuffer} 用） */
    private TextBuffer activeScreen() {
        final Bound b = activeBound();
        return b == null ? null : b.screen();
    }

    /** 当前活动绑定（含"有没有像素面"这一事实 —— 输出面派生的输入之一） */
    private Bound activeBound() {
        synchronized (bound) {
            return bound.isEmpty() ? null : bound.get(bound.size() - 1);
        }
    }

    /**
     * 地址 → 屏。走**公开的节点接口**：同一张网络上的节点，其 {@code host()} 就是那块屏的
     * {@link TextBuffer}（OC 原版屏正是这个接口）。
     *
     * <p>★ 任务 F-2（2026-09-27）：旧自研真彩屏（{@code CryptandScreenEnvironment}）已随旧件
     * 整体删除 ⇒ 这里只剩一个分支。像素通道仍按**节点地址**从 {@code TrueScreenGraphicsApi} 的
     * 登记表取（我方移植屏接入网络时登记）；它只表达"这块屏有没有像素面"这一个事实
     * （任务 G：输出面由 {@code ScreenOutputFace} 派生，不再由外部模式位决定）。</p>
     */
    private Bound resolveScreen(String address) {
        try {
            final Node self = node();
            if (self == null || self.network() == null) {
                return null;
            }
            for (final Node n : self.network().nodes()) {
                if (n == null || !address.equals(n.address())) {
                    continue;
                }
                if (n.host() instanceof TextBuffer tb) {
                    // ② 新移植屏（Scala）：它的 screen 组件本身就是 TextBuffer，像素通道按**节点地址**
                    //    登记在 TrueScreenGraphicsApi 里（组件接入网络时登记、断开时注销）。
                    //    ⚠ 这里**不 import Scala 类型**（生产者在 java 源集里看不到它们）：
                    //      "是不是我方移植屏"由**包名**判定，"有没有像素通道"由登记表判定。
                    final boolean cryptandPort = isCryptandPortScreen(n.host());
                    final TrueScreenGraphicsSink sink = cryptandPort
                            ? TrueScreenGraphicsApi.sinkRequired(address, "gpu.resolveScreen")
                            : TrueScreenGraphicsApi.sink(address);
                    return new Bound(address, tb, sink);
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /**
     * 这个 OC 组件宿主是不是**我方移植屏**（Scala 移植件的 screen 组件）。
     *
     * <p>判定方式刻意用**包名**而不是 {@code instanceof}：移植件在 scala 源集，本文件在 java 源集，
     * 编译期看不到那些类型（同 SocContent 的源集边界）。包名判定在这里是稳定的：移植件的所有类
     * 都在 {@code com.hdf.cryptand.neoforge.truescreen.*} 下，其它 Cryptand 组件不在。</p>
     */
    private static boolean isCryptandPortScreen(Object host) {
        return host != null && host.getClass().getName().startsWith("com.hdf.cryptand.neoforge.truescreen.");
    }

    /**
     * 把本机预算认下来：**换了新对象**就把自己已经 bind 的屏重新登记进去。
     *
     * <p>为什么需要：组件环境对象**跨重启存活**（OC 只在机箱卸载时才销毁它），而架构每次开机、
     * 每次内存重算都会换一份新预算（屏表从零开始）⇒ 不重新登记的话，重启后"本卡已 bind 的屏"
     * 在内存口径里会凭空少几块（而通道那半边照旧记着，两边就对不上了）。</p>
     */
    private void adoptBudget(GpuMemory.Budget budget) {
        if (budget == lastBudget) {
            return;
        }
        lastBudget = budget;
        for (final Bound b : bound) {
            final DisplayTopology.Screen s = screenEntry(b.address(), b);
            if (s != null) {
                budget.attach(s);
            }
        }
    }

    /**
     * 一块屏在**显存口径**里的形态（{@link GpuMemory} 要的就是这几个数）。
     *
     * <p>分辨率/色深一律**问屏自己**，不写死：我方真彩屏按设备的像素尺寸与色深
     * （{@code w×h×bpp/8}）；OC 原版屏是字符屏（头 + 三平面，与显存窗口同一份算法）。
     * 拼接屏在屏那一侧本来就是**一块逻辑屏**（OC 的 TextBuffer 给的就是拼合后的分辨率），
     * 所以这里不再乘块数 —— "拼接算一个"由屏那一侧负责，只有一处。</p>
     *
     * @return {@code null} = 分辨率还没成形（0），算不出显存（调用方必须明确拒绝，不猜数字）
     */
    private DisplayTopology.Screen screenEntry(String address, Bound target) {
        if (target.pixelDevice() != null) {
            final com.hdf.cryptand.soc.board.TrueColorScreen device = target.pixelDevice();
            if (device.width() <= 0 || device.height() <= 0) {
                return null;
            }
            return new DisplayTopology.Screen(address, DisplayTopology.ScreenKind.TRUE_COLOR,
                    device.width(), device.height(), device.depth().bits(), cardId);
        }
        final TextBuffer screen = target.screen();
        if (screen.getWidth() <= 0 || screen.getHeight() <= 0) {
            return null;
        }
        return new DisplayTopology.Screen(address, DisplayTopology.ScreenKind.TEXT,
                screen.getWidth(), screen.getHeight(), 0, cardId);
    }

    private String addresses() {
        final StringBuilder sb = new StringBuilder();
        for (final Bound b : bound) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(b.address());
        }
        return sb.toString();
    }

    // ==================== 画 ====================

    /** {@code set(x, y, value[, vertical])} */
    @Callback(value = "set", direct = true, doc = "function(x:number, y:number, value:string[, vertical:boolean])")
    public Object[] set(Context context, Arguments args) {
        final int x = args.checkInteger(0);
        final int y = args.checkInteger(1);
        final String value = args.checkString(2);
        final boolean vertical = args.optBoolean(3, false);
        final Page page = activePageObject();
        if (page != null) {
            int cx = x;
            int cy = y;
            for (int i = 0; i < value.length(); i++) {
                put(page, cx, cy, String.valueOf(value.charAt(i)));
                if (vertical) {
                    cy++;
                } else {
                    cx++;
                }
            }
            return new Object[]{true};
        }
        final TextBuffer screen = requireScreen();
        screen.setForegroundColor(foreground);
        screen.setBackgroundColor(background);
        // OC 的 set 是 void、越界返回 false（util.TextBuffer.scala:113）⇒ 这里只需换算坐标
        screen.set(toScreenX(x), toScreenY(y), value, vertical);
        return new Object[]{true};
    }

    /** {@code get(x, y)} → 该格的字符 */
    @Callback(value = "get", direct = true, doc = "function(x:number, y:number):string -- the character at that position")
    public Object[] get(Context context, Arguments args) {
        final int x = args.checkInteger(0);
        final int y = args.checkInteger(1);
        final Page page = activePageObject();
        if (page != null) {
            final int at = index(page, x, y);
            return new Object[]{at < 0 ? " " : page.cells[at]};
        }
        final TextBuffer screen = requireScreen();
        final int sx = toScreenX(x);
        final int sy = toScreenY(y);
        // 读越界**不能抛**（util.TextBuffer.get 越界会抛 IndexOutOfBoundsException，
        // 而"读一格"失败应当是可判定的空，不是异常）：范围外一律空格。
        if (sx < 0 || sy < 0 || sx >= screen.getWidth() || sy >= screen.getHeight()) {
            return new Object[]{" "};
        }
        return new Object[]{String.valueOf(screen.get(sx, sy))};
    }

    /** {@code fill(x, y, width, height, char)} */
    @Callback(value = "fill", direct = true, doc = "function(x:number, y:number, width:number, height:number, char:string)")
    public Object[] fill(Context context, Arguments args) {
        final int x = args.checkInteger(0);
        final int y = args.checkInteger(1);
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        final String ch = args.checkString(4);
        final Page page = activePageObject();
        if (page != null) {
            for (int row = y; row < y + height; row++) {
                for (int col = x; col < x + width; col++) {
                    put(page, col, row, ch);
                }
            }
            return new Object[]{true};
        }
        final TextBuffer screen = requireScreen();
        screen.setForegroundColor(foreground);
        screen.setBackgroundColor(background);
        screen.fill(toScreenX(x), toScreenY(y), width, height, ch.isEmpty() ? ' ' : ch.charAt(0));
        return new Object[]{true};
    }

    /** {@code copy(x, y, width, height, tx, ty)} —— 同一页内搬矩形 */
    @Callback(value = "copy", direct = true, doc = "function(x:number, y:number, width:number, height:number, tx:number, ty:number)")
    public Object[] copy(Context context, Arguments args) {
        final int x = args.checkInteger(0);
        final int y = args.checkInteger(1);
        final int width = args.checkInteger(2);
        final int height = args.checkInteger(3);
        final int tx = args.checkInteger(4);
        final int ty = args.checkInteger(5);
        final Page page = activePageObject();
        if (page != null) {
            final String[] cells = new String[width * height];
            final int[] fgs = new int[width * height];
            final int[] bgs = new int[width * height];
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    final int from = index(page, x + col, y + row);
                    final int at = row * width + col;
                    cells[at] = from < 0 ? " " : page.cells[from];
                    fgs[at] = from < 0 ? foreground : page.fg[from];
                    bgs[at] = from < 0 ? background : page.bg[from];
                }
            }
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    final int at = index(page, tx + col, ty + row);
                    if (at < 0) {
                        continue;
                    }
                    final int from = row * width + col;
                    page.cells[at] = cells[from];
                    page.fg[at] = fgs[from];
                    page.bg[at] = bgs[from];
                }
            }
            return new Object[]{true};
        }
        final TextBuffer screen = requireScreen();
        screen.copy(toScreenX(x), toScreenY(y), width, height, toScreenX(tx), toScreenY(ty));
        return new Object[]{true};
    }

    // ==================== 颜色 ====================

    @Callback(value = "setForeground", direct = true, doc = "function(color:number[, palette:boolean]):number")
    public Object[] setForeground(Context context, Arguments args) {
        final int previous = foreground;
        foreground = args.checkInteger(0);
        foregroundFromPalette = args.optBoolean(1, false);
        final TextBuffer screen = activeScreen();
        if (screen != null) {
            // palette=true ⇒ 给它"这是调色板索引"的语义（OC 的 TextBuffer 也这么用）
            screen.setForegroundColor(foreground, foregroundFromPalette);
        }
        return new Object[]{previous};
    }

    @Callback(value = "setBackground", direct = true, doc = "function(color:number[, palette:boolean]):number")
    public Object[] setBackground(Context context, Arguments args) {
        final int previous = background;
        background = args.checkInteger(0);
        final TextBuffer screen = activeScreen();
        if (screen != null) {
            screen.setBackgroundColor(background);
        }
        return new Object[]{previous};
    }

    @Callback(value = "getForeground", direct = true, doc = "function():number")
    public Object[] getForeground(Context context, Arguments args) {
        return new Object[]{foreground};
    }

    @Callback(value = "getBackground", direct = true, doc = "function():number")
    public Object[] getBackground(Context context, Arguments args) {
        return new Object[]{background};
    }

    @Callback(value = "setPaletteColor", direct = true, doc = "function(index:number, color:number):number")
    public Object[] setPaletteColor(Context context, Arguments args) {
        final int index = args.checkInteger(0);
        final int color = args.checkInteger(1);
        final TextBuffer screen = activeScreen();
        if (screen == null) {
            return new Object[]{0xFFFFFF};
        }
        final int previous = screen.getPaletteColor(index);
        screen.setPaletteColor(index, color);
        return new Object[]{previous};
    }

    @Callback(value = "getPaletteColor", direct = true, doc = "function(index:number):number")
    public Object[] getPaletteColor(Context context, Arguments args) {
        final int index = args.checkInteger(0);
        final TextBuffer screen = activeScreen();
        return new Object[]{screen == null ? 0xFFFFFF : screen.getPaletteColor(index)};
    }

    // ==================== 显存页 ====================

    /**
     * {@code allocateBuffer(width, height)} → 页号（1 起）。
     *
     * <p>页是<b>这张卡自己的显存</b>：{@code OcComponentBus} 用它做"整屏写一页再一次 bitblt 上屏"，
     * 避免逐行 {@code set}（OC 的 set 要扣预算、按码点耗电）。页尺寸以**屏幕分辨率**为上限，
     * 超了就明确报错（不静默裁剪）。</p>
     */
    @Callback(value = "allocateBuffer", direct = true, doc = "function(width:number, height:number):number -- allocate a VRAM page")
    public Object[] allocateBuffer(Context context, Arguments args) {
        final int width = args.checkInteger(0);
        final int height = args.checkInteger(1);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid buffer size " + width + "x" + height);
        }
        // 上限 = 屏的字符格数（页与屏同构：三平面逐格搬，见 bitblt）
        final TextBuffer screen = activeScreen();
        final int maxWidth = screen == null ? Integer.MAX_VALUE : screen.getWidth();
        final int maxHeight = screen == null ? Integer.MAX_VALUE : screen.getHeight();
        if (width > maxWidth || height > maxHeight) {
            throw new IllegalArgumentException("buffer too large: " + width + "x" + height
                    + " exceeds screen " + maxWidth + "x" + maxHeight);
        }
        synchronized (pages) {
            pages.add(new Page(width, height));
            return new Object[]{pages.size()};               // 页号从 1 起（0 = 屏幕）
        }
    }

    /** {@code setActiveBuffer(index)}：切换后续写操作的目标（0 = 屏幕） */
    @Callback(value = "setActiveBuffer", direct = true, doc = "function(index:number):boolean")
    public Object[] setActiveBuffer(Context context, Arguments args) {
        final int index = args.checkInteger(0);
        if (index != SCREEN_PAGE && (index < 1 || index > pages.size())) {
            return new Object[]{false};
        }
        activePage = index;
        return new Object[]{true};
    }

    @Callback(value = "getActiveBuffer", direct = true, doc = "function():number")
    public Object[] getActiveBuffer(Context context, Arguments args) {
        return new Object[]{activePage};
    }

    /** {@code getBufferSize([index])} → (width, height)；不给下标就用当前活动页 */
    @Callback(value = "getBufferSize", direct = true, doc = "function([index:number]):number, number")
    public Object[] getBufferSize(Context context, Arguments args) {
        final Page page = args.count() > 0 ? pageAt(args.checkInteger(0)) : activePageObject();
        if (page != null) {
            return new Object[]{page.width, page.height};
        }
        final TextBuffer screen = activeScreen();
        if (screen == null) {
            return new Object[]{null, null};
        }
        return new Object[]{screen.getWidth(), screen.getHeight()};
    }

    @Callback(value = "freeBuffer", direct = true, doc = "function(index:number):boolean")
    public Object[] freeBuffer(Context context, Arguments args) {
        final int index = args.checkInteger(0);
        synchronized (pages) {
            if (index < 1 || index > pages.size()) {
                return new Object[]{false};
            }
            pages.set(index - 1, null);                      // 页号不复用：OC 的页号也是单调的
        }
        if (activePage == index) {
            activePage = SCREEN_PAGE;
        }
        return new Object[]{true};
    }

    @Callback(value = "freeAllBuffers", direct = true, doc = "function():number -- free all VRAM pages")
    public Object[] freeAllBuffers(Context context, Arguments args) {
        synchronized (pages) {
            int freed = 0;
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i) != null) {
                    pages.set(i, null);
                    freed++;
                }
            }
            activePage = SCREEN_PAGE;
            return new Object[]{freed};
        }
    }

    /**
     * {@code bitblt(dst, col, row, width, height, src, fromCol, fromRow)}：页 ↔ 屏幕之间搬矩形。
     *
     * <p>页号 0 = 屏幕。搬的是<b>三平面</b>（字符 + 每格前景/背景），所以颜色跟着格子走 ——
     * 这正是"整屏一次上屏"能保住每格颜色的原因（逐行 {@code set} 只能带一对全局当前色）。</p>
     */
    @Callback(value = "bitblt", direct = true, doc = "function(dst:number, col:number, row:number, width:number, height:number, src:number, fromCol:number, fromRow:number):boolean")
    public Object[] bitblt(Context context, Arguments args) {
        final int dst = args.checkInteger(0);
        final int col = args.checkInteger(1);
        final int row = args.checkInteger(2);
        final int width = args.checkInteger(3);
        final int height = args.checkInteger(4);
        final int src = args.checkInteger(5);
        final int fromCol = args.checkInteger(6);
        final int fromRow = args.checkInteger(7);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid blit size " + width + "x" + height);
        }
        final Page dstPage = pageAt(dst);
        final Page srcPage = pageAt(src);
        if (dst == SCREEN_PAGE && srcPage != null) {
            return new Object[]{blitPageToScreen(srcPage, fromCol, fromRow, col, row, width, height)};
        }
        if (src == SCREEN_PAGE && dstPage != null) {
            return new Object[]{blitScreenToPage(dstPage, col, row, fromCol, fromRow, width, height)};
        }
        if (dstPage != null && srcPage != null) {
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    final int from = index(srcPage, fromCol + c, fromRow + r);
                    if (from < 0) {
                        continue;
                    }
                    final int at = index(dstPage, col + c, row + r);
                    if (at < 0) {
                        continue;
                    }
                    dstPage.cells[at] = srcPage.cells[from];
                    dstPage.fg[at] = srcPage.fg[from];
                    dstPage.bg[at] = srcPage.bg[from];
                }
            }
            return new Object[]{true};
        }
        return new Object[]{false};
    }


    // ==================== 图形面（图像）走哪条路：**不在本类**（任务 G，2026-09-27）====================
    //
    // 用户定案（repo/gpu-auto-convert-output-2026-09-27.md）：
    //   · 程序侧只有一种输出：**画图像**；没有 guest 显式切模式的入口（原设想的 gpu.setMode 已作废）；
    //   · "转字符还是直接画"由虚拟机（GPU/屏链路）按「谁在画 × 屏能显示什么」自动决定；
    //   · 程序用 OC 的字符 API（gpu.set/fill/copy 传字符）时按字符语义走（真彩屏上就是 TEXT 面）。
    //
    // ⇒ 本类（GPU 组件的回调）现在**只有一条**：OC 的字符 API（用户定案第 3 条）。
    //   原来这里那套"设备处于 GRAPHICS 模式时 set/fill/copy 当像素写"的路径有个致命前提 ——
    //   模式位要由**外部**设置（无人化工具的 action=mode），这正是定案要删掉的东西；它的另一半
    //   （图像 → 真彩屏 VRAM / 图像 → 字符屏量化）搬到了屏链路的单一路径上：
    //     · 判定规则：common 的 {@code com.hdf.cryptand.soc.board.ScreenOutputFace.derive}（只有一份，离线闸门钉死）
    //     · 图形面出口：{@code OcComponentBus.presentImage(...)}（真彩屏 ⇒ 直接写 VRAM；
    //       字符屏 ⇒ {@code ScreenImageQuantizer} 量化成字符格 + fg/bg 后写该屏字符缓冲）
    //     · 无人化工具的 action=mode 保留为**调试手段**（直接强制设备的派生状态位），不是功能路径。
    //   顺带：全项目"就近调色板"的算式从此也只有一份（{@code ScreenImageQuantizer.nearestPaletteIndex}）。
    // ==================== 内部 ====================

    /** 把页的一段搬到屏幕上（**按同色分段**写：每段一次 setForeground + setBackground + set 整串） */
    private boolean blitPageToScreen(Page page, int fromCol, int fromRow, int col, int row, int width, int height) {
        final TextBuffer screen = requireScreen();
        for (int r = 0; r < height; r++) {
            int c = 0;
            while (c < width) {
                final int at = index(page, fromCol + c, fromRow + r);
                if (at < 0) {
                    c++;
                    continue;
                }
                final int fg = page.fg[at];
                final int bg = page.bg[at];
                final StringBuilder run = new StringBuilder();
                int end = c;
                while (end < width) {
                    final int e = index(page, fromCol + end, fromRow + r);
                    if (e < 0 || page.fg[e] != fg || page.bg[e] != bg) {
                        break;
                    }
                    run.append(page.cells[e]);
                    end++;
                }
                screen.setForegroundColor(fg);
                screen.setBackgroundColor(bg);
                screen.set(toScreenX(col + c), toScreenY(row + r), run.toString(), false);
                c = end;
            }
        }
        return true;
    }

    /** 把屏幕的一段搬进页（颜色逐格取，屏幕接口给得出每格颜色） */
    private boolean blitScreenToPage(Page page, int col, int row, int fromCol, int fromRow, int width, int height) {
        final TextBuffer screen = requireScreen();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                final int sx = fromCol + c;
                final int sy = fromRow + r;
                if (sx < 1 || sy < 1 || sx > screen.getWidth() || sy > screen.getHeight()) {
                    continue;
                }
                final int at = index(page, col + c, row + r);
                if (at < 0) {
                    continue;
                }
                page.cells[at] = String.valueOf(screen.get(toScreenX(sx), toScreenY(sy)));
                page.fg[at] = screen.getForegroundColor(toScreenX(sx), toScreenY(sy));
                page.bg[at] = screen.getBackgroundColor(toScreenX(sx), toScreenY(sy));
            }
        }
        return true;
    }

    private Page activePageObject() {
        return pageAt(activePage);
    }

    private Page pageAt(int index) {
        if (index < 1) {
            return null;
        }
        synchronized (pages) {
            return index > pages.size() ? null : pages.get(index - 1);
        }
    }

    private static int index(Page page, int x, int y) {
        // 与 OC 一致：**1 基**坐标，越界返回 -1（不静默落到别的格子）
        if (x < 1 || y < 1 || x > page.width || y > page.height) {
            return -1;
        }
        return (y - 1) * page.width + (x - 1);
    }

    private void put(Page page, int x, int y, String value) {
        final int at = index(page, x, y);
        if (at < 0) {
            return;
        }
        page.cells[at] = value;
        page.fg[at] = foreground;
        page.bg[at] = background;
    }

    /** 1 基（回调/固件/页）→ 0 基（OC 的 TextBuffer） */
    private static int toScreenX(int x) {
        return x - 1;
    }

    private static int toScreenY(int y) {
        return y - 1;
    }

    private TextBuffer requireScreen() {
        final TextBuffer screen = activeScreen();
        if (screen == null) {
            throw new IllegalStateException("no screen bound to " + cardId);
        }
        return screen;
    }
}

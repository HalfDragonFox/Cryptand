package com.hdf.cryptand.neoforge.truescreen;

import com.hdf.cryptand.soc.board.TrueColorScreen;

/**
 * ===== 真彩屏 GRAPHICS（像素模式）的生产者接口（2026-09-27 任务 D）=====
 *
 * <p>本接口由移植件（Scala 源集）实现，按**节点地址**登记进 {@link TrueScreenGraphicsApi}；
 * 生产者在绑屏之后这样用：</p>
 * <pre>
 *   TrueScreenGraphicsSink sink = TrueScreenGraphicsApi.sink(address);
 *   if (sink != null) {
 *       sink.configure(width, height, bpp);      // 几何变化时才需要（幂等）
 *       TrueColorScreen d = sink.device();       // 与 guest 显存同构的设备，直接写像素
 *       ...                                      // 或整帧 arraycopy 进 d.vram()
 *       sink.markFrameChanged();                 // 就绪位：主线程 tick 把这一帧发出去
 *   }
 * </pre>
 *
 * <p><b>线程纪律</b>：生产者（OC 组件回调）跑在机器线程上，只准写设备自己的缓冲 + 调
 * {@link #markFrameChanged()}（一个 volatile 计数）；**发送报文只在主线程 tick 里做** ——
 * 与既定的"屏幕显存走中间缓冲 + 就绪位、每 tick 有新数据再同步"一致。</p>
 *
 * <p>本接口故意放在 <b>java</b> 源集：opencomputers 子包（生产者）在 java 源集里能直接编译引用；
 * 实现在 scala 源集（{@code common/TrueScreenGraphics.scala}）—— Scala 实现 Java 接口天然可行。</p>
 */
public interface TrueScreenGraphicsSink {

    /** 当前设备（未 configure 前为 null）。 */
    TrueColorScreen device();

    /**
     * 把 GPU 侧**派生**出来的输出面落到设备上（2026-09-27 任务 G）。
     *
     * <p>设备自己的 {@code TEXT}/{@code GRAPHICS} 是<b>内部派生状态</b>，不是 guest 开关：
     * 规则只有一份（common 的 {@code ScreenOutputFace.derive}：谁在画 × 屏能显示什么），
     * 这里只是"把它写进设备"的唯一落地点。无人化工具里的 {@code action=mode} 是**调试手段**，
     * 不是功能路径。</p>
     */
    void applyDerivedFace(TrueColorScreen.Mode face);

    /** 建/重建设备（几何或色深变化时）。幂等：参数没变就不重建、不清内容。 */
    void configure(int width, int height, int bpp);

    /**
     * 本屏声明的像素色深（bpp）：1 / 8 / 16 / 24。
     *
     * <p>由<b>屏</b>声明（用户定案 2026-09-29：四档真彩屏按 1bit/8bit/16bit/24bit 来），
     * 生产者据此编码帧 —— 编码见 common 的 {@code ScreenFrameEncoder}。不再假设 16bpp。</p>
     */
    int bpp();

    /**
     * 整屏校验值（用户需求 2026-09-29）：屏自己持有、<b>懒算并缓存</b> ——
     * 屏内容一变（{@link #markFrameChanged()}）标记失效，下一次要发帧时算一遍，
     * 之后给<b>所有观看者</b>复用同一个值，不重复计算。
     *
     * <p>算式只有一处（common 的 {@code ScreenFrameChecksum}）；客户端更新后本地再算一次与之比对。</p>
     */
    int frameChecksum();

    /** 锁帧：被锁时客户端保留上一帧（不重复上传），与 common 设备的 lock 同义。 */
    void lock();

    void unlock();

    /** 写调色板一格（越界/直色色深由设备自己明确抛错，不静默吞）。 */
    void setPaletteColor(int index, int color);

    /** 就绪位：生产者写完一帧后调用 —— 主线程 tick 会把最新帧/几何/调色板/锁状态发给附近玩家。 */
    void markFrameChanged();

    /**
     * 这块屏的方块坐标（宿主不是方块实体时返回 null）。
     *
     * <p>给"按坐标操作"的无人化工具用：{@code TrueScreenGraphicsApi} 用它在**同一张按地址的表**上
     * 做一次查找，**不另建第二张登记表**（登记/注销只有一处，见 TrueScreenGraphicsApi 的说明）。</p>
     */
    net.minecraft.core.BlockPos blockPos();
}

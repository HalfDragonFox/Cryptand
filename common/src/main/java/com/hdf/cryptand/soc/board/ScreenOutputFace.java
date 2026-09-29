package com.hdf.cryptand.soc.board;

/**
 * ===== 输出面（TEXT / GRAPHICS）的**唯一派生规则**（common，纯 Java 零 MC，2026-09-27 任务 G）=====
 *
 * <p>用户定案（{@code repo/gpu-auto-convert-output-2026-09-27.md}）：</p>
 * <blockquote>程序直接画图像输出，然后具体是转字符还是直接画由虚拟机即 gpu 部分自动转换输出给屏幕</blockquote>
 *
 * <p>⇒ 屏设备的 {@code TEXT}/{@code GRAPHICS} <b>不是 guest 开关</b>（原设想的 {@code gpu.setMode} 已作废），
 * 而是由虚拟机（GPU/屏链路）**派生**出来的内部状态。派生的两个输入恰好对应两句话：</p>
 * <ul>
 *   <li><b>谁在画</b>（{@link Painter}）：程序用的是 OC 的<b>字符 API</b>（{@code gpu.set/fill/copy} 传字符、
 *       页↔屏的三平面 {@code bitblt}），还是在输出<b>图形面</b>（图像/像素）；</li>
 *   <li><b>屏能显示什么</b>（{@link Capability}）：这块屏自带像素面（我方真彩屏有 VRAM），
 *       还是只能显示字符（OC 原版屏这类字符屏）。</li>
 * </ul>
 *
 * <h3>真值表（本类就是它，别处不许再判一次）</h3>
 * <pre>
 *   谁在画    屏能显示        输出面      行为
 *   CHARACTER 任意            TEXT       字符面：真彩屏走它的 TEXT 面，字符屏走字符缓冲（同一条路）
 *   IMAGE     有像素面        GRAPHICS   直接写 VRAM 画像素（真彩屏，TrueScreenGraphics 通道）
 *   IMAGE     只能字符        TEXT       ★ 需要转换：图形面量化成字符格 + fg/bg 再输出到该屏字符缓冲
 * </pre>
 *
 * <p>即：{@code TEXT} 有两种来路（程序真在画字符 / 只能字符的屏上的图像被转成了字符），
 * 但**字符屏侧的转换出口只有一个**（{@link ScreenImageQuantizer}）；{@code GRAPHICS} 只有一种来路。</p>
 *
 * <h3>为什么必须放 common</h3>
 * <p>拿掉 Minecraft，这段判断一样成立：它只是"两个枚举 → 一个枚举"的纯函数。放这里的好处有三条，
 * 每条都直接对应项目纪律：① 两个 loader 与离线闸门读的是同一份规则（GC 侧不会出现第二份判断）；
 * ② 它能被离线闸门钉死（{@link ScreenOutputFaceSelfTest}），不必为改一次判断起客户端；
 * ③ 它是"非 MC 强相关内容一律优先放 common"（AGENTS.md）的直接适用对象。</p>
 *
 * <p>跑法（离线闸门）：{@code java -cp <common classes> com.hdf.cryptand.soc.board.ScreenOutputFaceSelfTest}；
 * gradle 任务登记（{@code :common:runScreenFaceTest}）需要 {@code common/build.gradle} 的授权，见任务 G 报告。</p>
 */
public final class ScreenOutputFace {

    /**
     * <b>谁在画</b>：这次输出是哪一种绘制。
     *
     * <p>它的取值由**调用种类**给出（哪个回调 / 哪条输出链），**不**由任何模式位、也不由 guest 声明给出
     * —— 那正是"不是 guest 开关"的落点。</p>
     */
    public enum Painter {

        /**
         * OC 的字符 API：{@code gpu.set/get/fill/copy}（传字符）与页↔屏的三平面 {@code bitblt}。
         *
         * <p>用户定案第 3 条："程序用 OC 的字符 API（gpu.set/fill/copy 传字符）时按字符语义走
         * （真彩屏上就是 TEXT 面）"。</p>
         */
        CHARACTER,

        /**
         * 图形面（图像/像素）：程序"直接画图像输出"的那条链。
         *
         * <p>它输出的是**一整张图**（像素），因此虚拟机侧要按目标屏的能力决定"直接画"还是"转字符"。</p>
         */
        IMAGE
    }

    /** <b>屏能显示什么</b>：由屏自己派生（真彩屏有像素面/VRAM；OC 原版屏只能字符），不由 guest 声明。 */
    public enum Capability {

        /** 自带像素面（我方真彩屏：VRAM + 调色板 + 锁帧）⇒ 能直接画像素。 */
        PIXELS,

        /** 只能显示字符（OC 原版屏这类字符屏）⇒ 图形面必须被量化成字符格。 */
        CHARACTERS_ONLY
    }

    private ScreenOutputFace() {
    }

    /**
     * 派生输出面 —— <b>全链路唯一的一条规则</b>。
     *
     * @param painter    谁在画（调用种类）
     * @param capability 屏能显示什么（屏自己的能力）
     * @return 该走哪个面（真彩屏设备模型的 {@link TrueColorScreen.Mode}；字符屏也用它表达
     *         "这一帧是当字符显示的"）
     */
    public static TrueColorScreen.Mode derive(Painter painter, Capability capability) {
        if (painter == null || capability == null) {
            throw new IllegalArgumentException("painter/capability 都不能为 null（判定不许猜）");
        }
        if (painter == Painter.CHARACTER) {
            return TrueColorScreen.Mode.TEXT;             // 定案第 3 条：字符 API 一律字符语义
        }
        return capability == Capability.PIXELS
                ? TrueColorScreen.Mode.GRAPHICS           // 定案第 2 条：目标真彩屏 ⇒ 直接写 VRAM
                : TrueColorScreen.Mode.TEXT;              // 目标字符屏 ⇒ 转字符后仍是字符面
    }

    /**
     * 这一步要不要做<b>图像 → 字符</b>的转换。
     *
     * <p>= "谁在画图形面" × "屏只能字符"。为 true 时调用方必须调
     * {@link ScreenImageQuantizer#quantize} 把图形面量化成字符格 + fg/bg，
     * 再写进该屏的字符缓冲 —— <b>没有第二条字符化实现</b>。</p>
     */
    public static boolean requiresImageToCharacters(Painter painter, Capability capability) {
        return derive(painter, capability) == TrueColorScreen.Mode.TEXT
                && painter == Painter.IMAGE;
    }

    /** 屏能力 → 枚举（由"有没有像素面"这一个事实现场派生，不引入第二个标志位）。 */
    public static Capability capabilityOf(boolean screenHasPixelFace) {
        return screenHasPixelFace ? Capability.PIXELS : Capability.CHARACTERS_ONLY;
    }
}

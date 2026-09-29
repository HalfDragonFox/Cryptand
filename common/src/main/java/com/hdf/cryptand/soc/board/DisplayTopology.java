package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 显示拓扑（屏幕的所有权与能力，2026-09-18）=====
 *
 * <p>用户定案："MCU/SOC/CPU 必须有 GPU 才能**看见**显示区域，即屏幕必须被 GPU 挂载；
 * 然后显卡的话 OC 原版的可以驱动字符屏，然后我们自定义的显卡可以驱动字符屏和真彩屏"。</p>
 *
 * <h3>三条规则</h3>
 * <ol>
 *   <li><b>屏不能直连内核</b>：显示区域只由显卡承载。没有 GPU 模块 ⇒ 这台机器看不见任何显示区域
 *       （不是"黑屏"，而是**根本没有显示设备**）。</li>
 *   <li><b>显卡能力决定能驱动什么屏</b>：OC 原版显卡只驱动**字符屏**；Cryptand 显卡驱动字符屏 + 真彩屏。
 *       对 OC 原版显卡 bind 真彩屏 ⇒ **明确报错**，绝不静默降级。</li>
 *   <li>屏按 id 唯一（重复挂载要报错）。</li>
 * </ol>
 *
 * <p>⚠ 这条规则只作用在**我们自己的 SoC 模型**里。对 **OC 物品**仍保持官方语义
 * （screen 与 gpu 是两个独立组件、靠网络邻接），否则会破坏 OC 兼容与官方 BIOS 的
 * list("screen") → bind 流程。</p>
 */
public final class DisplayTopology {

    /** 屏幕类型 */
    public enum ScreenKind {
        /** 字符屏：字符格 + 每格前景/背景色（OC 的 TextBuffer 语义） */
        TEXT("字符屏"),
        /** 真彩屏：像素 + 色深（1..32 bpp） */
        TRUE_COLOR("真彩屏");

        private final String label;

        ScreenKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 显卡种类（决定它能驱动什么屏） */
    public enum GpuKind {
        /** OC 原版显卡：只能驱动字符屏 */
        OC_VANILLA("OC 原版显卡"),
        /** Cryptand 显卡：字符屏 + 真彩屏 */
        CRYPTAND("Cryptand 显卡");

        private final String label;

        GpuKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一块屏。
     *
     * @param gpuName 承载它的显卡模块名（**永不为空**：屏必须挂在显卡下）
     * @param bpp     色深（字符屏忽略；真彩屏 1..32）
     */
    public record Screen(String id, ScreenKind kind, int cols, int rows, int bpp, String gpuName) {
    }

    private DisplayTopology() {
    }

    /**
     * 把若干物理屏**拼合**成一块逻辑屏（用户定案："一个屏幕如果被拼起来算一个"）。
     *
     * <p>语义（与 OC 的屏幕拼合一致）：</p>
     * <ul>
     *   <li><b>屏数上限按逻辑屏算</b>：4 块拼成一大块 = 占 1 个屏位，不占 4 个；</li>
     *   <li><b>VRAM 按拼合后的总分辨率算</b>：cols = 每块列数 × 横向块数，rows = 每块行数 × 纵向块数
     *       （不是"每块各算一份"）；</li>
     *   <li>物理块数只影响硬件成本（每一块屏都是一个物品/方块），不影响屏位与显存口径。</li>
     * </ul>
     *
     * @param blocksX 横向拼几块、blocksY 纵向拼几块（都要 &gt;= 1）
     */
    public static Screen merge(String id, ScreenKind kind, int colsPerBlock, int rowsPerBlock,
                              int blocksX, int blocksY, int bpp, String gpuName) {
        if (colsPerBlock <= 0 || rowsPerBlock <= 0 || blocksX <= 0 || blocksY <= 0) {
            throw new IllegalArgumentException("拼合的每块分辨率与块数都必须 > 0");
        }
        return new Screen(id, kind, colsPerBlock * blocksX, rowsPerBlock * blocksY, bpp, gpuName);
    }

    /** 这台机器上的全部显卡模块 */
    public static List<SocModule> gpus(List<SocModule> modules) {
        final List<SocModule> out = new ArrayList<>();
        for (final SocModule m : modules) {
            if (m.name().startsWith("GPU")) {
                out.add(m);
            }
        }
        return out;
    }

    /** 有没有显示能力（没有 GPU 就看不见显示区域） */
    public static boolean hasGpu(List<SocModule> modules) {
        return !gpus(modules).isEmpty();
    }

    /** 这块显卡能不能驱动这种屏（用户定案的能力表） */
    public static boolean canDrive(GpuKind gpu, ScreenKind screen) {
        return gpu == GpuKind.CRYPTAND || screen == ScreenKind.TEXT;
    }

    /**
     * 按模块清单推断这块显卡的种类。
     *
     * <p>接口即能力：走 PCIe/QSPI 的是我们自己的显卡（能真彩）；走 8080/并口这类
     * "接面板控制器"的按 OC 原版能力处理（只有字符层）——这样 MCU 档的屏行为也是自洽的。</p>
     */
    public static GpuKind gpuKindOf(SocModule gpu) {
        return switch (gpu.iface()) {
            case PCIE, QSPI, DP -> GpuKind.CRYPTAND;
            default -> GpuKind.OC_VANILLA;
        };
    }

    /** 这台机器的显示装配过程（屏必须挂到已存在的显卡上） */
    public static final class Layout {

        private final List<SocModule> modules;
        private final List<Screen> screens = new ArrayList<>();

        public Layout(List<SocModule> modules) {
            this.modules = modules == null ? List.of() : modules;
        }

        /**
         * 把一块屏挂到某块显卡上（**唯一的挂载方式**）。
         *
         * @throws IllegalStateException 没有这块显卡 / 显卡不支持这种屏 / 屏 id 重复
         */
        public Layout attach(String gpuName, Screen screen) {
            final SocModule gpu = SocModules.byName(modules, gpuName);
            if (gpu == null) {
                throw new IllegalStateException("屏 " + screen.id() + " 无处挂载：这台机器没有显卡模块 "
                        + gpuName + "（没有 GPU 就看不见显示区域）");
            }
            if (!gpuName.equals(screen.gpuName())) {
                throw new IllegalStateException("屏 " + screen.id() + " 声明挂在 " + screen.gpuName()
                        + " 上，但装配时挂到了 " + gpuName);
            }
            if (!canDrive(gpuKindOf(gpu), screen.kind())) {
                throw new IllegalStateException("屏 " + screen.id() + " 是 " + screen.kind().label()
                        + "，而 " + gpuKindOf(gpu).label() + "（" + gpuName + "）只能驱动字符屏 —— "
                        + "真彩屏需要 Cryptand 显卡");
            }
            for (final Screen s : screens) {
                if (s.id().equals(screen.id())) {
                    throw new IllegalStateException("屏 id 重复：" + screen.id());
                }
            }
            screens.add(screen);
            return this;
        }

        /** 这台机器看得见的显示区域 */
        public List<Screen> screens() {
            return List.copyOf(screens);
        }

        /** 有没有可见显示区域 */
        public boolean visible() {
            return !screens.isEmpty();
        }

        /** 一行摘要（日志/UI/无人化断言） */
        public String summary() {
            if (screens.isEmpty()) {
                return "display: none（没有显卡承载屏幕 ⇒ 看不见显示区域）";
            }
            final StringBuilder sb = new StringBuilder("display: ");
            for (final Screen s : screens) {
                sb.append(s.id()).append('(').append(s.kind().label());
                if (s.kind() == ScreenKind.TRUE_COLOR) {
                    sb.append(' ').append(s.cols()).append('x').append(s.rows()).append('@').append(s.bpp()).append("bpp");
                } else {
                    sb.append(' ').append(s.cols()).append('x').append(s.rows());
                }
                sb.append(" via ").append(s.gpuName()).append(") ");
            }
            return sb.toString().trim();
        }
    }
}

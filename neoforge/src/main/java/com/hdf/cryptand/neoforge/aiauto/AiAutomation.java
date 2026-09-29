package com.hdf.cryptand.neoforge.aiauto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== AI 自动化核心（aiauto 子包，2026-09-15）=====
 *
 * <p>把"AI 想看/想驱动某个东西"抽象成若干个 {@link AiTarget}：每个目标自己负责
 * 描述、打开、截图；本类负责<b>目标注册表、产物目录、截图认领、程序化打开后的
 * 等待-截图-还原状态机</b>，以及总开关门控。</p>
 *
 * <h3>产物（AI 直接读）</h3>
 * <pre>
 *   &lt;gameDir&gt;/cryptand/ai-auto/latest-&lt;目标&gt;-&lt;对象&gt;.txt   文本描述（如布局树）
 *   &lt;gameDir&gt;/cryptand/ai-auto/latest-&lt;目标&gt;-&lt;对象&gt;.png   真实截图
 * </pre>
 *
 * <h3>为什么必须开着 MC</h3>
 * <p>目标多为游戏内界面（LDLib2 等），依赖客户端渲染栈，无 headless 后端。</p>
 */
public final class AiAutomation {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final Map<String, AiTarget> TARGETS = new LinkedHashMap<>();
    private static final Map<String, Long> PENDING_SHOT = new ConcurrentHashMap<>();

    private AiAutomation() {
    }

    // ==================== 目标注册 ====================

    public static void register(AiTarget target) {
        TARGETS.put(target.id(), target);
        LOGGER.info("[aiauto] 注册自动化目标：{}（{}）{}", target.id(), target.displayName(),
                target.available() ? "" : " — 当前不可用");
    }

    public static AiTarget target(String id) {
        return TARGETS.get(id);
    }

    public static List<AiTarget> targets() {
        return new ArrayList<>(TARGETS.values());
    }

    public static List<String> targetIds() {
        return new ArrayList<>(TARGETS.keySet());
    }

    // ==================== 门控与目录 ====================

    /** 总开关（aiauto.toml#enableAiAutomation，默认关） */
    public static boolean allowed() {
        try {
            return com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto.enableAiAutomation();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 产物目录：<gameDir>/cryptand/ai-auto/ */
    public static Path outDir() {
        return FMLPaths.GAMEDIR.get().resolve("cryptand").resolve("ai-auto");
    }

    /** 产物文件名：latest-<目标>-<对象>.<后缀>（目标/对象里的非法字符替换为下划线） */
    public static Path artifact(String targetId, String item, String extension) {
        final String safeTarget = sanitize(targetId);
        final String safeItem = sanitize(item);
        return outDir().resolve("latest-" + safeTarget
                + (safeItem.isEmpty() ? "" : "-" + safeItem) + "." + extension);
    }

    private static String sanitize(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** MC 截图目录（Screenshot.grab 的落点） */
    public static Path mcScreenshotDir() {
        return FMLPaths.GAMEDIR.get().resolve("screenshots");
    }

    // ==================== 驱动 ====================

    /** 客户端每帧调用：目标自定义 tick + 会话推进 + 截图认领 */
    public static void tick() {
        if (!allowed()) {
            return;
        }
        for (AiTarget target : targets()) {
            try {
                if (target.available()) {
                    target.tick();
                }
            } catch (Throwable ex) {
                LOGGER.warn("[aiauto] 目标 {} tick 异常", target.id(), ex);
            }
        }
        serviceSession();
        servicePendingShot();
        // （曾在此调用 AiCameraTools.tick() 做假人相机每帧矫正；
        //   离屏/多视角渲染方案已废弃并删除，详见 aiauto/offscreen-render-archive-2026-09-15。）
    }

    // ==================== 截图 ====================

    /**
     * 触发一次 MC 原生截图，并登记"稍后认领"。
     *
     * <p>⚠ 1.21.1 的 {@code Screenshot.grab(File, RenderTarget, Consumer)} <b>不接受文件名</b>
     * （MC 自动生成时间戳名），所以只能记录触发时刻，再扫描 screenshots/ 认领新出现的 PNG。</p>
     */
    public static void grabScreenshot(String targetId, String item) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            // ⚠ 1.21.1 的 Screenshot.takeScreenshot(RenderTarget) 对 null 不做兜底
            //   （实测 NPE: Cannot read field "width" because "arg" is null），
            //   必须显式传主渲染目标 —— 传 null 会让截图支路整体静默失败。
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> {
            });
            PENDING_SHOT.put(targetId + "|" + item, System.currentTimeMillis());
            LOGGER.info("[aiauto] 已触发截图 {} / {}", targetId, item);
        } catch (Throwable ex) {
            LOGGER.warn("[aiauto] 截图失败", ex);
        }
    }

    /** 认领 MC 刚写好的截图，复制到 latest-<目标>-<对象>.png */
    private static void servicePendingShot() {
        if (PENDING_SHOT.isEmpty()) {
            return;
        }
        PENDING_SHOT.entrySet().removeIf(entry -> {
            final String[] parts = entry.getKey().split("\\|", 2);
            final String targetId = parts[0];
            final String item = parts.length > 1 ? parts[1] : "";
            try {
                if (System.currentTimeMillis() - entry.getValue() > 15_000L) {
                    LOGGER.warn("[aiauto] 截图认领超时，放弃：{}", entry.getKey());
                    return true;                       // 超时放弃，避免永久挂起
                }
                final Path newest = newestScreenshotSince(entry.getValue());
                if (newest == null) {
                    return false;                      // 还没写完，下帧再试
                }
                CLAIMED.add(newest.toString());        // 一张图只认领一次，避免多张截图抢到同一张
                Files.createDirectories(outDir());
                final Path dst = item.isEmpty()
                        ? outDir().resolve("latest-" + targetId + ".png")
                        : artifact(targetId, item, "png");
                Files.copy(newest, dst, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[aiauto] 截图已就绪：{}", dst);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    /**
     * 找出"刚写好并且已经写完"的截图。
     *
     * <p>⚠ MC 的 {@code Screenshot.grab} 是异步写盘：文件先被创建（0 字节），内容随后才落盘。
     * 如果只看修改时间就复制，会抢到一个空文件 —— 实测踩过（认领出 0 字节 PNG）。
     * 所以这里要求<b>大小非 0 且连续两帧不变</b>才算写完。</p>
     */
    private static Path newestScreenshotSince(long since) throws IOException {
        final Path dir = mcScreenshotDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path best = null;
        long bestTime = since - 3000L;                 // 容忍写入延迟
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                if (!path.getFileName().toString().endsWith(".png")) {
                    continue;
                }
                if (CLAIMED.contains(path.toString())) {
                    continue;                          // 已经认领过的截图不再重复使用
                }
                final long mtime = Files.getLastModifiedTime(path).toMillis();
                if (mtime < bestTime) {
                    continue;
                }
                final long size = Files.size(path);
                if (size <= 0L) {
                    continue;                          // 还没开始写
                }
                final Long previous = SHOT_SIZE.put(path.toString(), size);
                if (previous == null || previous != size) {
                    continue;                          // 大小还在变 —— 下帧再看
                }
                bestTime = mtime;
                best = path;
            }
        }
        return best;
    }

    /** 截图文件上次观察到的大小（用于判断"写完了没"） */
    private static final Map<String, Long> SHOT_SIZE = new ConcurrentHashMap<>();

    /** 已认领过的截图源文件（一次截图只对应一张图） */
    private static final java.util.Set<String> CLAIMED = ConcurrentHashMap.newKeySet();

    // ==================== 程序化打开 → 截图 → 还原 ====================

    private static String sessionTarget;
    private static String sessionItem;
    private static int sessionDelay = -1;
    private static boolean sessionShot;
    private static Screen sessionReturn;

    /**
     * 一条命令完成：<b>程序化打开 → 等布局稳定 → 截图 → 还原原屏幕</b>。
     *
     * @param withShot true = 打开后自动截图（false 只打开）
     */
    public static boolean startCapture(String targetId, String item, boolean withShot) {
        if (!allowed() || !com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto.allowAutoOpen()) {
            return false;
        }
        final AiTarget target = target(targetId);
        if (target == null || !target.available()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Screen previous = mc.screen;
        if (!target.open(item)) {
            return false;
        }
        sessionTarget = targetId;
        sessionItem = item;
        sessionDelay = com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto.captureDelayFrames();
        sessionShot = !withShot;
        sessionReturn = previous;
        return true;
    }

    public static boolean sessionActive() {
        return sessionTarget != null;
    }

    private static void serviceSession() {
        if (sessionTarget == null) {
            return;
        }
        if (sessionDelay > 0) {
            sessionDelay--;
            return;
        }
        final AiTarget target = target(sessionTarget);
        if (!sessionShot) {
            if (target != null) {
                target.shot(sessionItem);
            }
            sessionShot = true;
            sessionDelay = 60;                          // 等 MC 异步写盘 + 认领
            return;
        }
        Minecraft.getInstance().setScreen(sessionReturn);
        LOGGER.info("[aiauto] 会话结束（{} / {}），已还原原屏幕", sessionTarget, sessionItem);
        sessionTarget = null;
        sessionReturn = null;
    }
}

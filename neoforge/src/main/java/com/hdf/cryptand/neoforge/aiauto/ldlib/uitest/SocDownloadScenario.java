package com.hdf.cryptand.neoforge.aiauto.ldlib.uitest;

import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.hdf.cryptand.neoforge.soc.ui.SocUiFactory;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

/**
 * ===== SoC 工具链下载面板 · 真客户端 UI 测试（无人值守）=====
 *
 * <p>这是 Cryptand 的第一个 LDLib2 UITest 场景，跑法：</p>
 * <pre>
 *   gradlew :neoforge:runClient -PldTest=cryptand_soc_download
 *   gradlew :neoforge:runClient -PldTest=group:cryptand      # 跑本组全部场景
 * </pre>
 *
 * <p>它自动：构建面板 → 等布局 → 断言标题 → <b>导出 aiauto 布局树</b> → 截图 → 关屏。
 * 产物在 {@code neoforge/build/ldlib2-uitest/}（report.json + screenshots/），
 * 以及 {@code neoforge/run/cryptand/ai-auto/latest-ldlib-download.txt}（供 AI 直接读）。</p>
 *
 * <p>⚠ 两个实测约束（照 LDLib2 自带 SnakeHudScenario）：① 即使不碰世界也要
 * {@code requiresWorld(true)} —— 屏幕构建依赖 Player；② 位置断言必须用 {@code ticks()} 而非
 * {@code frames()}（数据绑定走 20Hz 的 ModularUI#tick）。</p>
 */
@LDLRegisterClient(name = "cryptand_soc_download", group = "cryptand", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SocDownloadScenario implements UIScenario {

    @Override
    public void configure(ScenarioOptions options) {
        options.defaultSettleMs(30).tags("cryptand", "soc", "ui").requiresWorld(true).guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.openModularUI("soc_download", ctx -> SocUiFactory.downloadPanel())
                .awaitModularUI()
                .awaitElement(".panel_bg")
                .checkTextContains(".panel_title", "工具链下载")
                .frames(5)                      // 等字体/布局稳定再截图

                // 把"计算后布局"导出给 AI（与 aiauto 同一份产物）
                .step("aiauto：导出布局树", ctx -> {
                    try {
                        ctx.attach("layoutTree", LdlibPanels.dump("download").toString());
                        ctx.attach("artifactDir", AiAutomation.outDir().toString());
                    } catch (Exception ex) {
                        ctx.attach("layoutTree", "导出失败：" + ex.getMessage());
                    }
                })

                .screenshot("01_download_panel")
                .closeScreen();
    }
}

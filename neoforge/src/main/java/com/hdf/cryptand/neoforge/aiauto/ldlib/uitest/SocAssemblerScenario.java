package com.hdf.cryptand.neoforge.aiauto.ldlib.uitest;

import com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibPanels;
import com.hdf.cryptand.neoforge.soc.content.SocContent;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import net.minecraft.core.BlockPos;

/**
 * ===== Cryptand 组装台 · 真客户端 UI 测试（无人值守）=====
 *
 * <p>走真方块路径：清理场地 → 放置方块 → 等服务端 BE 同步到客户端 → 真右键 →
 * 等 ModularUI 出尺寸 → 断言标题 → 导出布局树 → 截图。</p>
 */
@LDLRegisterClient(name = "cryptand_soc_assembler", group = "cryptand", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class SocAssemblerScenario implements UIScenario {

    private static final BlockPos POS = new BlockPos(8, 65, 8);

    @Override
    public void configure(ScenarioOptions options) {
        options.defaultSettleMs(30).tags("cryptand", "soc", "ui").requiresWorld(true).guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.clearArea(POS, 2)
                .setBlock(POS, SocContent.SOC_ASSEMBLER.get())
                .awaitClientBlockEntity(POS)
                .useBlock(POS)                       // 真右键 → 真 open-screen 包
                // ⚠ 方块 UI 走 BlockUIMenuType → 打开的是容器屏幕（带菜单/槽位同步），
                //   不是纯客户端的 ModularUIScreen —— 实测等错类会 5s 超时
                .awaitScreen(com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerScreen.class)
                .awaitModularUI()                    // 等 UI 有尺寸（首帧布局后）
                .awaitElement(".panel_bg")
                .checkTextContains(".panel_title", "组装台")
                .frames(5)
                .step("aiauto：导出布局树", ctx -> {
                    try {
                        ctx.attach("layoutTree", LdlibPanels.dump("assembler").toString());
                    } catch (Exception ex) {
                        ctx.attach("layoutTree", "导出失败：" + ex.getMessage());
                    }
                })
                .screenshot("01_assembler_panel")
                .closeScreen();
    }
}

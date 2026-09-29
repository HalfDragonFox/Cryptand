/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/RenderTypes.java
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 *
 * == 屏幕渲染子集，加一处新增（任务 B 的唯一渲染语义新增）==
 * 保留：BLOCK_OVERLAY（屏幕"朝上"指示箭头的叠加层）、FONT_QUAD（字形 quad）、
 *       createFontTex(String,ResourceLocation,boolean) / createFontTex(int)（字体图集，含
 *       linear/nearest 过滤与"抗锯齿/非抗锯齿"两套图集）。
 * 丢弃：机器人底盘/旗帜、全息投影、MFU、升级件、createTexturedQuad —— 都是未移植的子系统
 *       （阶段一闭包里就没有它们）。
 * 新增：createScreenTex —— VRAM 合成图的 RenderType。它**逐字段照抄 FONT_QUAD**
 *       （POSITION_TEX_COLOR + COLOR_DEPTH_WRITE + LEQUAL_DEPTH_TEST + NO_TRANSPARENCY + NO_CULL），
 *       只把贴图从 WHITE_TEXTURE 换成"屏幕内容那张 DynamicTexture"。
 *       这是任务 B 允许的唯一渲染语义新增：内容来源从"字符 quad"变成"一张 VRAM 贴图"，
 *       必须有一个绑定该贴图的 RenderType；其它状态位一个都没动（NO_CULL 的理由见下）。
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

public class RenderTypes extends RenderType {
    /** 我方命名空间（= TrueScreenTextures.ModId）。 */
    public static final String MOD_ID = "cryptand";

    private static final RenderStateShard.ShaderStateShard POSITION_TEX_COLOR_SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);
    private static final RenderStateShard.TextureStateShard WHITE_TEXTURE =
            new RenderStateShard.TextureStateShard(ResourceLocation.withDefaultNamespace("textures/misc/white.png"), false, false);

    public static final RenderType BLOCK_OVERLAY = create(MOD_ID + ":overlay_block",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 1024, false, false, CompositeState.builder()
                    .setShaderState(POSITION_TEX_SHADER)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .createCompositeState(false));

    public static final RenderType FONT_QUAD = create(MOD_ID + ":font_quad",
            DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1024, false, false, CompositeState.builder()
                    .setShaderState(POSITION_TEX_COLOR_SHADER)
                    .setTextureState(WHITE_TEXTURE)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setTransparencyState(NO_TRANSPARENCY)
                    // NO_CULL required: block rendering works because ScreenRenderer.transform()
                    // applies mirrorScale(1,-1,1) which flips Y and reverses winding to CCW (front-face).
                    // GUI rendering has no Y-flip, so quads are CW (back-face) and get culled without this.
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    private static class CustomTextureState extends RenderStateShard.TexturingStateShard {
        public CustomTextureState(int id) {
            super("custom_tex_" + id, () -> {
                RenderSystem.setShaderTexture(0, id);
            }, () -> {});
        }
    }

    private static class LinearTexturingState extends RenderStateShard.TexturingStateShard {
        public LinearTexturingState(boolean linear) {
            super(linear ? "lin_font_texturing" : "near_font_texturing", () -> {
                RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
            }, () -> {});
        }
    }

    private static final LinearTexturingState NEAR = new LinearTexturingState(false);
    private static final LinearTexturingState LINEAR = new LinearTexturingState(true);

    public static RenderType createFontTex(String name, ResourceLocation texture, boolean linear) {
        return create(MOD_ID + ":font_stat_" + name,
                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1024, false, false, CompositeState.builder()
                        .setShaderState(POSITION_TEX_COLOR_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setTexturingState(linear ? LINEAR : NEAR)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        // NO_CULL required: see FONT_QUAD comment above.
                        .setCullState(NO_CULL)
                        .createCompositeState(false));
    }

    public static RenderType createFontTex(int id) {
        return create(MOD_ID + ":font_dyn_" + id,
                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1024, false, false, CompositeState.builder()
                        .setShaderState(POSITION_TEX_COLOR_SHADER)
                        .setTexturingState(new CustomTextureState(id))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        // NO_CULL required: see FONT_QUAD comment above.
                        .setCullState(NO_CULL)
                        .createCompositeState(false));
    }

    /**
     * 任务 B 新增：屏幕内容（VRAM 合成图）的 RenderType —— 字段与 FONT_QUAD 一一对应，
     * 只有贴图换成那张 DynamicTexture。图集过滤按 TrueScreenSettings.textLinearFiltering
     * （OC 侧该开关当前无引用点，这里同样不改语义：仍用 TextureStateShard 的默认 nearest/blur=false）。
     */
    public static RenderType createScreenTex(String name, ResourceLocation texture, boolean linear) {
        return create(MOD_ID + ":screen_tex_" + name,
                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096, false, false, CompositeState.builder()
                        .setShaderState(POSITION_TEX_COLOR_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTexturingState(linear ? LINEAR : NEAR)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .createCompositeState(false));
    }

    private RenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufSize, boolean delegate, boolean sorting, Runnable setup, Runnable clear) {
        super(name, format, mode, bufSize, delegate, sorting, setup, clear);
        throw new Error();
    }
}

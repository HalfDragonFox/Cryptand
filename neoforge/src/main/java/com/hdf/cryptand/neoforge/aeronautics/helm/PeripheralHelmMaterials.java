/**
 * ===== 外设船舵材质重建（客户端，2026-09-13） =====
 *
 * 航空学官方船舵的舵轮木辐条是【动态材质】：`wheel.obj` 里 `planks` 只是占位（橡木），
 * 渲染时把该 sprite 换成玩家选定木板的贴图（BE 默认 {@code Blocks.SPRUCE_PLANKS}）。
 * 本类原样搬官方 {@code SteeringWheelRenderer.generateModel/getSpriteOnSide} 的实现
 * （Create `BakedModelHelper.swapSprites` + `WaterWheelRenderer.OAK_PLANKS_TEMPLATE` 模板 sprite），
 * 不通过 mixin 改官方类 —— 拷贝实现即可（用户："有必要的话可以直接拷贝代码实现，防止 mixin 侵入"）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.simibubi.create.content.kinetics.waterwheel.WaterWheelRenderer;
import com.simibubi.create.foundation.model.BakedModelHelper;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

public final class PeripheralHelmMaterials {

    private PeripheralHelmMaterials() {
    }

    /** 舵轮模型（占位 `planks` → 指定木板方块的贴图） */
    public static BakedModel wheelModel(BlockState material) {
        BakedModel template = PeripheralHelmPartialModels.WHEEL.get();
        TextureAtlasSprite planks = getSpriteOnSide(material, Direction.UP);
        if (planks == null) {
            return template;
        }
        Map<TextureAtlasSprite, TextureAtlasSprite> map = new Reference2ReferenceOpenHashMap<>();
        map.put(WaterWheelRenderer.OAK_PLANKS_TEMPLATE.get(), planks);
        return BakedModelHelper.generateModel(template, map::get);
    }

    /** 取方块某面的贴图 sprite（官方实现原样；找不到 → 粒子贴图） */
    private static TextureAtlasSprite getSpriteOnSide(BlockState state, Direction side) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        if (model == null) {
            return null;
        }
        RandomSource random = RandomSource.create();
        random.setSeed(42L);
        List<BakedQuad> quads = model.getQuads(state, side, random);
        if (!quads.isEmpty()) {
            return quads.get(0).getSprite();
        }
        random.setSeed(42L);
        quads = model.getQuads(state, null, random);
        if (!quads.isEmpty()) {
            for (BakedQuad quad : quads) {
                if (quad.getDirection() == side) {
                    return quad.getSprite();
                }
            }
        }
        return model.getParticleIcon();
    }
}

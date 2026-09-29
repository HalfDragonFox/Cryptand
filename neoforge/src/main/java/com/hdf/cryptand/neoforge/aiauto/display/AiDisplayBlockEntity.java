package com.hdf.cryptand.neoforge.aiauto.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ===== AI 展示方块实体：把任意物品/方块"摆出来看" =====
 *
 * <p>用途：AI 改了模型、贴图、物品注册之后，需要一个能<b>立刻看到渲染结果</b>的地方。
 * 把要展示的东西塞进来（物品或方块状态），{@link AiDisplayRenderer} 就会渲染它，
 * 再配合"转视角 + 截图"就能完成"改 → 看 → 再改"的闭环。</p>
 *
 * <p>纯调试设施：无玩法逻辑，数据可空，破坏即丢。</p>
 */
public class AiDisplayBlockEntity extends BlockEntity {

    private ItemStack displayItem = ItemStack.EMPTY;
    private BlockState displayBlock;
    private float scale = 1.0F;
    private float spin = 45.0F;      // 每秒旋转角度，0 = 不转
    private float yOffset = 1.0F;    // 显示高度（方块上方多少格）

    public AiDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(AiDisplayBlocks.DISPLAY_BE.get(), pos, state);
    }

    public ItemStack displayItem() {
        return displayItem;
    }

    public BlockState displayBlock() {
        return displayBlock;
    }

    public float scale() {
        return scale;
    }

    public float spin() {
        return spin;
    }

    public float yOffset() {
        return yOffset;
    }

    /** 设置展示内容（服务端或客户端本地都可调；客户端改只影响本地观感） */
    public void setDisplay(ItemStack item, BlockState block, float scale, float spin, float yOffset) {
        this.displayItem = item == null ? ItemStack.EMPTY : item;
        this.displayBlock = block;
        this.scale = scale <= 0 ? 1.0F : scale;
        this.spin = spin;
        this.yOffset = yOffset;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!displayItem.isEmpty()) {
            tag.put("DisplayItem", displayItem.save(registries));
        }
        tag.putFloat("Scale", scale);
        tag.putFloat("Spin", spin);
        tag.putFloat("YOffset", yOffset);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        displayItem = tag.contains("DisplayItem")
                ? ItemStack.parse(registries, tag.getCompound("DisplayItem")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        scale = tag.contains("Scale") ? tag.getFloat("Scale") : 1.0F;
        spin = tag.contains("Spin") ? tag.getFloat("Spin") : 45.0F;
        yOffset = tag.contains("YOffset") ? tag.getFloat("YOffset") : 1.0F;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

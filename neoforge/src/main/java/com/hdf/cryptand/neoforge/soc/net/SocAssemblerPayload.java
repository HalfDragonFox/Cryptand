package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.block.SocAssemblerBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 组装台操作包（C2S，2026-09-15）=====
 *
 * <p>面板按钮动作：组装 / 取出全部 / 请求状态。服务端权威执行并回报
 * {@link SocAssemblerStatusPayload}。</p>
 */
public record SocAssemblerPayload(BlockPos pos, int action) implements CustomPacketPayload {

    public static final int ACTION_ASSEMBLE = 0;
    public static final int ACTION_TAKE = 1;
    public static final int ACTION_STATUS = 2;

    public static final Type<SocAssemblerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_assembler"));

    public static final StreamCodec<ByteBuf, SocAssemblerPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SocAssemblerPayload::pos,
            ByteBufCodecs.VAR_INT, SocAssemblerPayload::action,
            SocAssemblerPayload::new);

    @Override
    public Type<SocAssemblerPayload> type() {
        return TYPE;
    }

    public static void send(BlockPos pos, int action) {
        PacketDistributor.sendToServer(new SocAssemblerPayload(pos, action));
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof SocAssemblerBlockEntity assembler)) {
            return;
        }
        switch (action) {
            case ACTION_ASSEMBLE -> assembler.assemble();
            case ACTION_TAKE -> {
                for (ItemStack stack : assembler.takeAll()) {
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
            }
            default -> {
            }
        }
        final List<String> lines = new ArrayList<>(assembler.describe());
        if (!assembler.output().isEmpty()) {
            lines.add("输出：" + assembler.output().getHoverName().getString() + "（点【取出全部】拿走）");
        }
        PacketDistributor.sendToPlayer(player, new SocAssemblerStatusPayload(pos, lines));
    }
}

package com.hdf.cryptand.neoforge.net;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.List;

/**
 * 网络缓存存档（2026-08-29 用户方案 B：退出世界网络缓存仍在 → 重进正常传输）。
 * <p>
 * 每维度（主存于各 ServerLevel）data/inc_network_buffer.dat，把
 * {@link NetworkBuffer} 中【每网络的真实物品】落盘：
 * <ul>
 *   <li>{@link #get}：进世界从存档加载，恢复各网络缓存池；</li>
 *   <li>{@link #save}：世界保存时自动调用，从持有缓存读真实 ItemStack 写 NBT；</li>
 *   <li>调用方在【每次缓存变更】（预提取/交付回池/拆除后）调 {@code setDirty()}，
 *       保证下次存档写入（同 WireSavedData 机制）。</li>
 * </ul>
 */
public class IncNetworkBufferSavedData extends SavedData {

    public static final String DATA_NAME = "inc_network_buffer";

    /** 持有真实缓冲的引用（每维度一个 NetworkBuffer，由 DimensionNetworkManager 提供） */
    private final NetworkBuffer buffer;

    private IncNetworkBufferSavedData(NetworkBuffer buffer) {
        this.buffer = buffer;
    }

    /** 获取（并触发加载）当前世界网络缓存存档 */
    public static IncNetworkBufferSavedData get(ServerLevel level, NetworkBuffer buffer) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        () -> new IncNetworkBufferSavedData(buffer),
                        (tag, prov) -> load(tag, prov, buffer)),
                DATA_NAME);
    }

    private static IncNetworkBufferSavedData load(CompoundTag tag,
                                                  HolderLookup.Provider provider,
                                                  NetworkBuffer buffer) {
        IncNetworkBufferSavedData data = new IncNetworkBufferSavedData(buffer);
        ListTag networks = tag.getList("networks", Tag.TAG_COMPOUND);
        for (int i = 0; i < networks.size(); i++) {
            try {
                CompoundTag e = networks.getCompound(i);
                String key = e.getString("key");
                ListTag stacks = e.getList("stacks", Tag.TAG_COMPOUND);
                for (int j = 0; j < stacks.size(); j++) {
                    ItemStack.parse(provider, stacks.getCompound(j))
                            .ifPresent(st -> buffer.add(key, st));
                }
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER
                        .warn("[INC-savedata] parse network entry err key={}", 
                                networks.getCompound(i).getString("key"), t);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag networks = new ListTag();
        for (Object key : buffer.poolKeys()) {
            List<ItemStack> stacks = buffer.snapshot(key);
            if (stacks.isEmpty()) continue;
            CompoundTag e = new CompoundTag();
            e.putString("key", String.valueOf(key));
            ListTag stackList = new ListTag();
            for (ItemStack st : stacks) {
                if (st == null || st.isEmpty()) continue;
                stackList.add(st.saveOptional(provider));
            }
            if (!stackList.isEmpty()) {
                e.put("stacks", stackList);
                networks.add(e);
            }
        }
        if (!networks.isEmpty()) tag.put("networks", networks);
        return tag;
    }
}
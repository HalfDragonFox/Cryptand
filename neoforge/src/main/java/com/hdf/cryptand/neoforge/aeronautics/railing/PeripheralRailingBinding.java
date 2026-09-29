/**
 * ===== 外设栏杆（按钮）绑定（2026-09-13）=====
 *
 * 用户需求："外设拉杆增加一个叫做外设栏杆（按钮）的版本，用于绑定单个按键或多个按键输出不同信号，
 * 比如XX按键输出1红石信号，用于换挡器，可以自定义按键数量，可以支持多个按键同时绑定一个信号输出"
 *
 * <p>数据模型 = 一张<b>按键映射表</b>：
 * <pre>
 *   条目（Entry）= 一组按键（0..31）+ 一个输出信号（0..15）
 *   表的顺序即优先级：从上往下取【第一个命中的条目】；都没命中 ⇒ 输出 0
 * </pre>
 * 例：`按钮 0 → 信号 1`、`按钮 1 → 信号 2`、`按钮 4 与 按钮 5 → 信号 7`（两个键同一个信号）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public record PeripheralRailingBinding(String deviceId, List<Entry> entries,
                                      /** 强制设备类型（0 = 自动，按后端检测；见 {@code PeripheralForceKinds}） */
                                      int forceKind) {

    /** 未绑定按键的哨兵值（新建条目默认如此） */
    public static final int NO_BUTTON = -1;

    /**
     * 一个映射条目：按下 {@code button} ⇒ 输出 {@code signal}（0..15）。
     *
     * <p>用户 2026-09-14 定稿："每条只能绑定一个按键" —— <b>一键一信号</b>。
     * 需要两个按键出同一档位就建两条（换挡器正是这个用法：一个档位一条）。
     *
     * <p>{@code button} 用统一按键索引空间（0..63 = SDL 按钮；64..71 = 方向帽 8 向，见
     * {@link PeripheralHelmInput.Sample#button(int)}），{@link #NO_BUTTON} = 尚未绑定。
     */
    public record Entry(int signal, int button) {

        public Entry {
            signal = Mth.clamp(signal, 0, 15);
        }

        /** 是否已绑定按键 */
        public boolean bound() {
            return button >= 0;
        }

        public boolean matches(PeripheralHelmInput.Sample sample) {
            return bound() && sample.button(button);
        }
    }

    /** 默认：未绑定设备、空表 */
    public static final PeripheralRailingBinding DEFAULT =
            new PeripheralRailingBinding("", List.of(), 0);

    public boolean bound() {
        return deviceId != null && !deviceId.isEmpty();
    }

    /** 当前应输出的红石信号：按表顺序取第一个命中的条目；没有命中 = 0。 */
    public int signalOf(PeripheralHelmInput.Sample sample) {
        if (sample == null) {
            return 0;
        }
        for (Entry entry : entries) {
            if (entry.matches(sample)) {
                return entry.signal();
            }
        }
        return 0;
    }

    // ==================== 持久化 ====================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Device", deviceId == null ? "" : deviceId);
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag e = new CompoundTag();
            e.putInt("Signal", entry.signal());
            e.putInt("Button", entry.button());
            list.add(e);
        }
        tag.put("Entries", list);
        tag.putInt("ForceKind", forceKind);   // 固定化参数 → 落盘
        return tag;
    }

    public static PeripheralRailingBinding load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return DEFAULT;
        }
        List<Entry> entries = new ArrayList<>();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            // 兼容旧格式（每条多键的 int[] Buttons）：取第一个按键 —— 单键语义下多出来的没有意义
            int button;
            if (e.contains("Button")) {
                button = e.getInt("Button");
            } else {
                int[] legacy = e.getIntArray("Buttons");
                button = legacy.length > 0 ? legacy[0] : NO_BUTTON;
            }
            entries.add(new Entry(e.getInt("Signal"), button));
        }
        return new PeripheralRailingBinding(tag.getString("Device"), List.copyOf(entries),
                tag.contains("ForceKind") ? tag.getInt("ForceKind") : 0);
    }

    /** 便捷：替换条目表（返回新实例；record 不可变） */
    public PeripheralRailingBinding withEntries(List<Entry> newEntries) {
        return new PeripheralRailingBinding(deviceId, List.copyOf(newEntries), forceKind);
    }

    public PeripheralRailingBinding withDevice(String newDeviceId) {
        return new PeripheralRailingBinding(newDeviceId == null ? "" : newDeviceId, entries, forceKind);
    }

    public PeripheralRailingBinding withForceKind(int newForceKind) {
        return new PeripheralRailingBinding(deviceId, entries, newForceKind);
    }
}

package com.hdf.cryptand.neoforge.soc.link;

import com.hdf.cryptand.soc.link.SocLinkTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 链路表的世界持久化（2026-09-28 建，2026-09-29 修端口身份）=====
 *
 * <p>规则（协议协商、源/中间组件、级联 128、木桶速率）全在 common 的纯数据
 * {@link SocLinkTable}（闸门 {@code :common:runLinkTest}）；设备接口列表在
 * {@link SocDeviceInterfaces}（设计裁定 C）。本类只做两件事：<b>随维度落盘</b> + <b>静态门面</b>。</p>
 *
 * <p><b>2026-09-29 硬缺口修复</b>：以前落盘只写「坐标 + 设备类型」，<b>端口身份丢了</b> ——
 * 重载后 {@code EndpointKey.port} 全是空串，"断开指定端口"与面板高亮必然失配（09-29 实况调研
 * 头号缺口）。现在落盘走 {@link SocLinkTable.Row}（两端坐标 / 设备 / <b>端口</b> + 协商出的协议），
 * 读盘按端口身份重建端点；行级编解码是纯数据（{@link #encodeRows} / {@link #decodeRows}），
 * 由 {@code :neoforge:runLinkGateTest} 离线钉住往返一致。</p>
 *
 * <p>门面只保留<b>一条路</b>：端点身份 = 「坐标 + 设备类型 + 端口」，设备级（无端口）连线已删除
 * （09-29 清理：全项目无调用方，且它正是端口身份丢失的来源）。</p>
 */
public final class SocLinkStore extends SavedData {

    private static final Logger LOG = LoggerFactory.getLogger("cryptand/soc");
    private static final String DATA_NAME = "cryptand_soc_links";
    private static final String KEY_LINKS = "Links";

    /**
     * 端点重建/连线用的通道数<b>上界</b>（{@link SocDeviceInterfaces#ports} 内部把通道数 clamp 到 1..8）。
     *
     * <p>端口身份是 {@code deviceId + port}，与通道数无关：通道数只决定"该设备有几个 dp 点"。
     * 按上界重建 ⇒ 任何真实存在的端口都能还原（存档时 4 通道、现在枚举成 2 通道也不会丢线）。</p>
     */
    static final int PORT_CHANNELS = 8;

    private final SocLinkTable table = SocLinkTable.of();

    private SocLinkStore() {
    }

    public static SocLinkStore get(Level level) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        return server.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SocLinkStore::new, SocLinkStore::load), DATA_NAME);
    }

    // ------------------------------------------------------------------ 门面

    /** 该坐标上的全部链路（机箱与它槽位里的卡同坐标 ⇒ 一次拿全）。 */
    public static List<SocLinkTable.Link> linksAt(Level level, BlockPos pos) {
        final SocLinkStore s = get(level);
        return s == null ? List.of() : s.table.linksAt(toPos(pos));
    }

    /**
     * 连两个<b>接口点</b>（画布/配对面板用）：{@code (坐标, 设备类型, 端口 id)}。
     *
     * <p>端点的 interfaces 只含该端口的协议 ⇒ 异色（异接口）两端交集为空，这里会自然失败并给出
     * 中文原因 —— 面板不需要自己判色，判据只有一份（common 的 {@code PortKind.sameKind}）。</p>
     */
    public static SocLinkTable.Result link(Level level, BlockPos aPos, String aDevice, String aPort,
                                           BlockPos bPos, String bDevice, String bPort) {
        final SocLinkStore s = get(level);
        if (s == null) {
            return SocLinkTable.Result.fail("这里不是服务端世界");
        }
        final SocLinkTable.Endpoint a = SocDeviceInterfaces.endpoint(aPos, aDevice, aPort, PORT_CHANNELS);
        final SocLinkTable.Endpoint b = SocDeviceInterfaces.endpoint(bPos, bDevice, bPort, PORT_CHANNELS);
        if (a == null || b == null) {
            return SocLinkTable.Result.fail("端口不存在（"
                    + (a == null ? aDevice + ":" + aPort : bDevice + ":" + bPort) + "）");
        }
        final SocLinkTable.Result res = s.table.connect(a, b);
        if (res.ok()) {
            s.setDirty();
        }
        return res;
    }

    /** 精确断开两个<b>接口点</b>之间的那条线（返回是否真的断开）。 */
    public static boolean unlink(Level level, BlockPos aPos, String aDevice, String aPort,
                                 BlockPos bPos, String bDevice, String bPort) {
        final SocLinkStore s = get(level);
        if (s == null) {
            return false;
        }
        final boolean removed = s.table.disconnect(
                new SocLinkTable.EndpointKey(toPos(aPos), aDevice, aPort),
                new SocLinkTable.EndpointKey(toPos(bPos), bDevice, bPort));
        if (removed) {
            s.setDirty();
        }
        return removed;
    }

    // ------------------------------------------------------------------ 落盘

    /** 链路 → NBT 行列表（两端坐标 / 设备 / 端口 + 协议 id）。 */
    static ListTag encodeRows(List<SocLinkTable.Row> rows) {
        final ListTag list = new ListTag();
        for (final SocLinkTable.Row r : rows) {
            final CompoundTag e = new CompoundTag();
            e.putInt("AX", r.a().x());
            e.putInt("AY", r.a().y());
            e.putInt("AZ", r.a().z());
            e.putString("AD", r.aDevice());
            e.putString("AP", r.aPort());
            e.putInt("BX", r.b().x());
            e.putInt("BY", r.b().y());
            e.putInt("BZ", r.b().z());
            e.putString("BD", r.bDevice());
            e.putString("BP", r.bPort());
            e.putString("P", r.protocolId());
            list.add(e);
        }
        return list;
    }

    /** NBT 行列表 → 链路行（缺端口读成空串；条目本身坏掉时跳过，不抛）。 */
    static List<SocLinkTable.Row> decodeRows(CompoundTag tag) {
        final List<SocLinkTable.Row> out = new ArrayList<>();
        if (tag == null) {
            return out;
        }
        final ListTag list = tag.getList(KEY_LINKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag e = list.getCompound(i);
            out.add(new SocLinkTable.Row(
                    new SocLinkTable.Pos(e.getInt("AX"), e.getInt("AY"), e.getInt("AZ")),
                    e.getString("AD"), e.getString("AP"),
                    new SocLinkTable.Pos(e.getInt("BX"), e.getInt("BY"), e.getInt("BZ")),
                    e.getString("BD"), e.getString("BP"),
                    e.getString("P")));
        }
        return out;
    }

    /** 行 → 端点：按端口身份重建（设备/端口已不在表里时返回 null ⇒ 读盘丢弃该行并回报原因）。 */
    private static SocLinkTable.Endpoint resolve(SocLinkTable.Pos pos, String deviceId, String port) {
        return SocDeviceInterfaces.endpoint(
                new BlockPos(pos.x(), pos.y(), pos.z()), deviceId, port, PORT_CHANNELS);
    }

    private static SocLinkStore load(CompoundTag tag, HolderLookup.Provider provider) {
        final SocLinkStore s = new SocLinkStore();
        final int n = s.table.importRows(decodeRows(tag), SocLinkStore::resolve, LOG::warn);
        if (n > 0) {
            LOG.info("[Link] 读盘恢复 {} 条链路（含端口身份）", n);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put(KEY_LINKS, encodeRows(table.rows()));
        return tag;
    }

    private static SocLinkTable.Pos toPos(BlockPos p) {
        return new SocLinkTable.Pos(p.getX(), p.getY(), p.getZ());
    }
}

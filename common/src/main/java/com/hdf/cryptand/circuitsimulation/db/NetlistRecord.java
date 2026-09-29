package com.hdf.cryptand.circuitsimulation.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 网表数据库记录类型（2026-08-15 用户架构：按具体网络组织的四类数据）。
 * <p>
 * 全部为【纯数据记录】（不可变 record，无 Minecraft 依赖，common 核心可用）：
 * <ul>
 *   <li>{@link NetworkRecord}   —— 网络核心数据表（频率/接地节点/维度/时间步长等）</li>
 *   <li>{@link AssemblerRecord} —— 网络组装器表（64 位 id + 组合顺序，可再组合）</li>
 *   <li>{@link WireRecord}      —— 网络导线表（双端点键 + 电气/渲染参数）</li>
 *   <li>{@link RendererRecord}  —— 渲染器表（材质/颜色/悬垂率等渲染参数）</li>
 *   <li>{@link NetworkCacheRecord} —— 网络求解缓存表（签名 + 结构快照 + ctx 映射，跨区块恢复）</li>
 *   <li>{@link DeviceInfoRecord} —— 设备信息表（dim + 坐标主键 + 组装器提供的 KV 二进制）</li>
 *   <li>{@link NetlistSnapshot} —— 全库快照（一次保存/恢复整库用）</li>
 * </ul>
 * 数量字段（x/y/z、terminalCount 等）用 int；64 位 id（网络/组装器/导线）用 long。
 */
public final class NetlistRecord {

    private NetlistRecord() {}

    /** 网络核心数据（网络核心数据表 network）。frequency>0 = AC 相量；0 = DC。 */
    public record NetworkRecord(
            long id,               // 64 位网络 id
            String name,           // 网络名（可 null）
            double frequency,      // 交流频率 Hz（0 = DC）
            int groundNode,        // 参考节点（地）
            String dimension,      // 所在维度（"minecraft:overworld" 等；可 null）
            double dt,             // 时间步长（秒）
            long version,          // 结构版本（拓扑变更 +1）
            String extra           // 扩展数据（JSON 字符串；可 null）
    ) {
        public static NetworkRecord of(long id) {
            return new NetworkRecord(id, null, 0, 0, null, 0.05, 0, null);
        }
    }

    /** 网络组装器（网络组装器表 assembler）。64 位 id 识别；order 记录组合顺序。 */
    public record AssemblerRecord(
            long id,               // 64 位组装器 id（全局唯一）
            long networkId,        // 所属网络 id
            int order,             // 组合顺序（网络内序号，重建时按序恢复）
            String deviceClass,    // 设备方块类简单名（"SwitchBlockEntity" 等）
            int x, int y, int z,   // 世界坐标
            int terminalCount,     // 端子数
            String params          // 参数快照（JSON；可 null）
    ) {
        public static AssemblerRecord of(long id, long networkId, String deviceClass,
                                         int x, int y, int z) {
            return new AssemblerRecord(id, networkId, 0, deviceClass, x, y, z, 2, null);
        }
    }

    /** 网络导线（网络导线表 wire）。a/b 端点键（"pos#term"）。 */
    public record WireRecord(
            long id,               // 64 位导线 id
            long networkId,        // 所属网络 id
            String aKey,           // 端点 A 键（WirePoint.key）
            String bKey,           // 端点 B 键
            double resistance,     // 段电阻 Ω
            double length,         // 物理长度
            String temperatureKey, // 温度模型 key（可 null）
            String rendererId,     // 渲染器引用 id（"copper" 等；可 null）
            int colorOverride,     // 染色覆盖 ARGB（0 = 渲染器默认）
            boolean selfPlaced,    // 是否自管放置
            String itemId          // 导线物品 ID（剪线返回；可 null）
    ) {
        public static WireRecord of(long id, long networkId, String aKey, String bKey) {
            return new WireRecord(id, networkId, aKey, bKey, 0, 0, null, null, 0, false, null);
        }
    }

    /** 渲染器（渲染器表 renderer）。id 为渲染器引用键；networkId=0 表示全局默认。 */
    public record RendererRecord(
            String id,             // 渲染器 id（"copper"/"iron"/"golden" 等；PK）
            long networkId,        // 所属网络（0 = 全局默认）
            String texture,        // 材质路径（可 null = 纯色模式）
            int color,             // 颜色代码 ARGB（-1 = 未设置）
            double sag,            // 悬垂率
            double thickness,      // 导线粗细（格）
            String params          // 扩展渲染参数（JSON；可 null）
    ) {
        public static RendererRecord of(String id) {
            return new RendererRecord(id, 0, null, -1, 2.0, 0.0625, null);
        }
    }

    /** 网络求解缓存（network_cache 表，2026-08-15 用户要求：网络缓存机制——
     *  求解结果直接缓存到 SQLite，命中直接恢复（跳过重建/求解），未命中重新
     *  建立寻找）。signature = 分量内容稳定哈希（排序端点键 + 排序边参数 +
     *  频率），跨会话确定 → 重启后同一网络同一状态命中同一缓存。
     *  <p>2026-08-19 完整结构缓存（用户要求：缓存网络内所有数据含电路结构，
     *  支持跨区块传输）：新增 structure（NetworkSnapshot 二进制 base64 = 基础
     *  元件完整电路结构）与 mapping（ctx 映射：blockTerminals/pointToEngine/
     *  openTerminal/wireSegments/波形 JSON）。命中恢复时直接重建 Network + ctx，
     *  跳过 buildContextFromGraph 重建——跨区块加载后电路结构不丢失。 */
    public record NetworkCacheRecord(
            String signature,    // 稳定内容签名（PK：分量内容哈希 + 频率）
            long networkId,      // 所属网络 64 位 id（信息性）
            String seedKey,      // 构建种子端点键
            double frequency,    // 求解频率 Hz
            int nodeCount,       // 节点数（恢复时校验，防错位）
            String mode,         // SolveMode 名（REAL_DC / COMPLEX_AC）
            String voltages,     // 节点电压 CSV（幅值/RMS）
            String complex,      // 主导频率相量 CSV（re0,im0,re1,im1...；可 null）
            String structure,    // 电路结构快照 base64（NetworkSnapshot 二进制；可 null=旧记录）
            String mapping,      // ctx 映射 JSON（blockTerminals/pointToEngine/openTerminal/段/波形；可 null）
            long updatedAt       // 更新时间戳
    ) {
        /** 兼容旧调用（无结构/映射） */
        public NetworkCacheRecord(String signature, long networkId, String seedKey,
                                  double frequency, int nodeCount, String mode,
                                  String voltages, String complex, long updatedAt) {
            this(signature, networkId, seedKey, frequency, nodeCount, mode,
                    voltages, complex, null, null, updatedAt);
        }
    }

    /**
     * 设备信息记录（2026-09-15 用户："BE 侧走 Sqlite 保存即可，接管 NBT 保存，保存组装器
     * 等信息"）。
     * <p>
     * 一个方块实体 = 一行：`dim + (x,y,z)` 主键，`info` 是该设备组装器提供的 KV 二进制
     * （`NetworkStructureCodec.writeKv`：K=String，V=通用变量）。
     * <p>
     * 为什么走 SQLite 而不是 BE 的 NBT：Cryptand 自管的运行时状态（温度、电机转速/应力、
     * 组装器信息）不属于原版方块的"配置"，塞进 NBT 会把存档格式和原版 BE 耦合死；
     * 独立成表后，设备信息与网络结构缓存同库同生命周期（世界保存一起落盘、加载一起读回）。
     */
    public record DeviceInfoRecord(
            String dim,          // 维度 id（主键之一；跨维度同坐标不冲突）
            int x, int y, int z, // 方块坐标（主键）
            String deviceClass,  // 设备类名（原版全限定名；按它找组装器）
            byte[] info,         // 组装器提供的 KV 二进制（可 null = 无信息）
            long updatedAt       // 更新时间戳
    ) {
    }

    /** 全库快照：一次保存/恢复整库（网络 + 组装器 + 导线 + 渲染器）。 */
    public record NetlistSnapshot(
            List<NetworkRecord> networks,
            List<AssemblerRecord> assemblers,
            List<WireRecord> wires,
            List<RendererRecord> renderers
    ) {
        public NetlistSnapshot {
            networks = List.copyOf(networks);
            assemblers = List.copyOf(assemblers);
            wires = List.copyOf(wires);
            renderers = List.copyOf(renderers);
        }

        public static NetlistSnapshot empty() {
            return new NetlistSnapshot(
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList());
        }

        public static NetlistSnapshot of(List<NetworkRecord> networks,
                                         List<AssemblerRecord> assemblers,
                                         List<WireRecord> wires,
                                         List<RendererRecord> renderers) {
            return new NetlistSnapshot(
                    networks == null ? new ArrayList<>() : networks,
                    assemblers == null ? new ArrayList<>() : assemblers,
                    wires == null ? new ArrayList<>() : wires,
                    renderers == null ? new ArrayList<>() : renderers);
        }

        public int totalCount() {
            return networks.size() + assemblers.size() + wires.size() + renderers.size();
        }
    }
}

/**
 * ===== 亚层持久化接口（2026-08-31） =====
 *
 * 用户要求：持久化交给一个类处理，可选择性使用 sqlite 或 sable 原版的方法
 * （大规模可能 sqlite 好）。本接口是后端契约：
 *
 *   save(level, data)   —— 保存/更新一个亚层（幂等）
 *   load(level, uuid)   —— 读一个亚层（null=不存在）
 *   loadAll(level)      —— 读某世界全部亚层
 *   delete(level, uuid) —— 删除一个亚层（拆卸落盘）
 *   flush() / close()   —— 落盘/关闭
 *
 * 数据格式：{@link PersistedSubLevel}（uuid/runtimeId/anchor/bounds/方块快照列表）。
 * 方块快照用 NBT（CompoundTag）序列化——与 sable 原版 SubLevelSerializer 风格一致，
 * 也方便 SQLite BLOB 存储。
 */
package com.hdf.cryptand.neoforge.cryptandsable.persistence;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;

public interface ISubLevelPersistenceBackend {

    /** 保存/更新一个亚层（幂等；内部可异步写）。 */
    void save(ServerLevel level, PersistedSubLevel data);

    /** 读一个亚层（null=不存在）。 */
    PersistedSubLevel load(ServerLevel level, UUID subLevelId);

    /** 读某世界全部亚层（世界进入时恢复）。 */
    List<PersistedSubLevel> loadAll(ServerLevel level);

    /** 删除一个亚层（拆卸落盘）。 */
    void delete(ServerLevel level, UUID subLevelId);

    /** 清空某维度全部亚层持久化记录（强制删除所有；removeAll 用）。 */
    void clearAll(ServerLevel level);

    /** 等待所有排队写完成（世界保存/卸载前调用，保证落盘）。 */
    void flush();

    /** 关闭（世界卸载/游戏退出）。 */
    void close();
}

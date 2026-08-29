package com.hdf.cryptand.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 表抽象基类（2026-08-13 用户架构：本 mod 提供 SQLite 存储接口）。
 * <p>
 * 子类在构造时提供 CREATE TABLE 语句，由本基类在打开时执行（IF NOT EXISTS，
 * 幂等）。读写经 {@link SqliteStore}（单写线程 + 异步队列 + 批量事务 + 同步读），
 * 线程安全且与求解线程池并行不阻塞。
 */
public abstract class SqliteTable {

    protected final SqliteStore store;
    protected final String name;

    protected SqliteTable(SqliteStore store, String name, String createSql)
            throws SQLException {
        this.store = store;
        this.name = name;
        store.execute((Connection c) -> {
            try (Statement st = c.createStatement()) {
                st.execute(createSql);
            }
        });
    }

    public String name() { return name; }

    /** 存储句柄（子类读写用） */
    protected SqliteStore store() { return store; }
}

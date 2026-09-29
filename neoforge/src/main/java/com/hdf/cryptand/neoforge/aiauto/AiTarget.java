package com.hdf.cryptand.neoforge.aiauto;

import java.util.List;

/**
 * ===== AI 自动化目标（aiauto 子包的通用扩展点）=====
 *
 * <p>一个目标 = 一个可被程序化驱动、并把结果导出成 AI 可读产物的东西。
 * 典型实现是"某个 UI 库的界面流水线"（如 {@code ldlib} 目标：布局树 + 截图 + 调试器），
 * 也可以是配方浏览、物品栏、日志面板等任何"想让 AI 看一眼/驱动一下"的对象。</p>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@link #id()} 是命令里用的短名（如 {@code ldlib}），全小写、无空格；</li>
 *   <li>所有产物写到 {@link AiAutomation#outDir()}，文件名用 {@code latest-<item>.{txt,png}}；</li>
 *   <li>实现必须<b>永不抛异常到驱动层</b>：失败返回 false 并记日志；</li>
 *   <li>{@link #available()} 用于"该 mod 不在场时优雅隐藏"，命令列出目标时会标注。</li>
 * </ul>
 */
public interface AiTarget {

    /** 命令用短名，如 {@code ldlib} */
    String id();

    /** 给人看的中文名，如 {@code LDLib2 界面} */
    String displayName();

    /** 该目标当前是否可用（依赖的 mod 是否在场等） */
    default boolean available() {
        return true;
    }

    /** 可自动化的对象清单（如已登记的界面名）；无对象时返回空表 */
    default List<String> items() {
        return List.of();
    }

    /** 描述某个对象（文本概要，供命令/日志用） */
    default String describe(String item) {
        return item;
    }

    /** 导出某对象的文本描述（布局树等）；返回写出的文件，失败返回 null */
    default java.nio.file.Path dump(String item) {
        return null;
    }

    /** 程序化打开某对象（无需人工点击）；失败返回 false */
    default boolean open(String item) {
        return false;
    }

    /** 对当前屏幕截图（认领到 latest-<item>.png）；失败返回 false */
    default boolean shot(String item) {
        return false;
    }

    /** 打开该目标自带的调试器（若有）；失败返回 false */
    default boolean debug() {
        return false;
    }

    /** 某个对象此刻是否适合截图（例如界面已经在屏幕上、布局已稳定） */
    default boolean ready(String item) {
        return true;
    }

    /** 每帧驱动（目标自己的自动行为，如自动导出/自动截图）；仅在总开关打开时调用 */
    default void tick() {
    }

    /**
     * 执行一个"动作"（游戏操作类，区别于 {@link #dump} 的只读报告）。
     *
     * <p>实现应走正常游戏通道（如玩家命令），<b>不得绕过权限与服务端校验</b>；
     * 不支持的动作返回 false。</p>
     */
    default boolean act(String action, java.util.List<String> args) {
        return false;
    }
}

# AGENTS.md — Cryptand 工作区智能体约定

> 本文件供所有编码 agent（VS Code Copilot / 本 DSH 助手 / 其他）在工作区内工作时读取。

## 工作区记忆（必须优先阅读）

> ⚠️ **记忆以 OpenViking（OV）为准，`ai_memory/` 是即将删除的过渡目录。**
>
> - **读**：用 OV 工具（`mcp__openviking__find` / `search` / `grep` / `read`）查
>   `viking://resources/cryptand/ai_memory/`（与本地 `ai_memory/` 一一对应，含 `repo/`）。
> - **写**：新记忆一律写到 OV（`mcp__openviking__write`），**不要新增本地 md 文件**。
> - `ai_memory/` 仅作迁移期对照，**不要**把它当单一事实来源；它后续会被删除。

**每次会话开始、以及任何代码改动之前，必须先阅读（对应 OV 上的同名文件）：**

1. `ai_memory/README.md` — 记忆库说明与读写约定
2. `ai_memory/cryptand-core-architecture.md` — 🔴 最高优先级：主线程=纯同步、仿真核心=全部计算、核心禁止写回 Level、模型=set/compute/get 黑盒、电气设备数学公式建模
3. `ai_memory/encoding-risk.md` — 🔴 最高优先级：含中文文件一律编辑器工具 UTF-8 写入，**绝不用 PowerShell Set-Content/Out-File 写盘**
4. `ai_memory/file-snapshot-undo.md` — 🟡 写文件前先做快照（`scripts/undo.ps1 snapshot`），改坏可手动撤回（`scripts/undo.ps1 restore -Id <ID>`）
5. `ai_memory/scripts-registry.md` — 🟡 脚本清单：正式脚本统一在 `scripts/` 管理（含 undo.ps1 及各脚本作用/调用方式）
6. `ai_memory/repo/`（98 份）— 🟡 仓库级记忆镜像：设备模型（电机/变压器/导线/电容等）、仿真核心/网络缓存、Panel/万用表 UI、Sable 物理集成等分主题档案；改动对应主题前先查 `repo/` 对应文件（索引见 `ai_memory/README.md`）

## 项目一句话

Cryptand = Minecraft 联动优化型模组：为电力/机械模组提供异步多线程仿真核心 + 原生矩阵求解（OpenBLAS/SuperLU JNI）+ 数学公式建模（EMF=Kω 等），打通 PowerGrid/Create/Sable/Aeronautics 等「电-机-热」耦合。

## 开发注意

- 架构：Architectury multi-loader（common + neoforge + fabric），common 纯 Java 零 MC 依赖
- 🔴 **非 MC 强相关内容一律优先放 common（用户 2026-09-18 强调）**：
  能写成纯 Java（不 import Minecraft/NeoForge）的逻辑全部放 `common`，MC 侧只保留"MC 概念 → 纯数据"的翻译层；
  **C 固件编译产物与链接脚本也放 common 的 resources**（`common/src/main/resources/assets/cryptand/soc/`），
  这样两个 loader 与**离线沙盒测试**读的是同一份字节。每条 common 逻辑都要有离线闸门（`runXxxTest`），
  不需要 MC、不需要客户端；真机验证尽量少做（一次两分钟）。判断标准：这段代码拿掉 Minecraft 还成立吗？
- 编译：VS Code 任务（cryptand compile）或 `cmd /c gradlew.bat ...`（见 encoding-risk.md）
- 🔴 **查第三方库实现：优先读 `.ai_cache/` 里的完整源码，不要先反编译**（索引见
  `ai_memory/repo/ai-cache-source-index.md`，含 LDLib2 / Multiblocked2 / OpenComputers / sable 等及各自 commit）；
  **以后所有 `git clone` 的第三方源码一律放 `.ai_cache/`**；源码树里没有的类（如 LDLib2 的 taffy 依赖）才用 `javap`
- 🟡 **AI 自动化子包 `aiauto`**（给不同 mod 提供 AI 自动化接口，按 mod 分模块；当前有 **ldlib** 目标）：
  ⚠ **需要开 MC 客户端**（LDLib2 无 headless 后端，不能离线渲染）。
  在 `config/cryptand/aiauto.toml` 打开 `enableAiAutomation=true` 后，界面一打开就自动导出到
  `neoforge/run/cryptand/ai-auto/latest-ldlib-<界面名>.{txt,png}`：**txt** = 每元素计算后 pos/size/文本（判断溢出/挤没/换行），
  **png** = 真实截图（AI 可直接读图）。命令：`/cryptand aiauto [list] | aiauto ldlib list|dump|shot|open|capture|debug`
  （`capture <界面名>` = 程序化打开→等稳定→截图→还原，全自动）。新界面一行接入：
  `LdlibPanels.register(name, root, true, stylesheets...)`；新 mod 目标：实现 `AiTarget` 后 `AiAutomation.register(...)`
- 任何与会话记忆相关的更新**写入 OpenViking**（`viking://resources/cryptand/ai_memory/`），
  **不要**再写入本地 `ai_memory/` 目录（该目录后续会删除）
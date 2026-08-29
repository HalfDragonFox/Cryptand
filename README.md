# Cryptand

> **让 Minecraft 整合包跑得更流畅**

**Cryptand** 是一个 Minecraft **联动优化型模组**，围绕两个核心目标：

1. **性能优化** — 为电力/机械模组提供异步多线程仿真核心与原生矩阵求解加速，减少主线程卡顿
2. **模组联动** — 打通 PowerGrid / Create / CEE / Create: Aeronautics / Sable / 铁路交通等模组的「电-机-热」耦合玩法，并拓展专属物品与功能

---

## ✨ 核心特性

### ⚡ 自研电路仿真引擎（common 纯 Java，零 MC 依赖）

- **MNA 求解器家族**：DC/时域（`RealMnaSolver`）、AC 相量（`ComplexMnaSolver`）、多音（`MultiToneSolver`）、谐波平衡（`HarmonicBalanceSolver`）、块对角合成（`BlockDiagonalSolver`）、合并网络一次稀疏求解（`MergedNetworkSolver`）、分段线性/渐进非线性
- **float 热路径**：`Float*Solver`（内存减半 + 带宽/SIMD 收益），恒定参数仍由 double 计算
- **数学公式建模**：电机/发电机/变压器/电容/绕组/温度/应力等一律用公式链建模（EMF=Kω、dP/dt 稳态涌现、热扩散），不做阈值 if/else 近似；模型黑盒接口 `set 输入 → 计算函数 → get 输出`
- **内建自测**：电机反电动势、变压器耦合、电容充电、多音、开路/短路等自动化验证

### 🚀 高性能求解加速

- **原生绑定（JNI）**：OpenBLAS LAPACK 稠密 LU（`dgesv/zgesv/sgesv/cgesv`）+ SuperLU 稀疏 LU（double/float 复数，CSC）
- **SIMD**：AVX2；OpenBLAS 采用 `DYNAMIC_ARCH` 运行时多微架构内核分派
- **并行安全**：网络间并行 `solveAll` + 线程安全 JNIEnv（`GetEnv/AttachCurrentThread` 修正跨线程复用 UB）
- **可选原生**：DLL 缺失时自动回退纯 Java 求解器，功能完整、跨平台可用

### 🧵 全新异步架构（主线程零计算）

- **主线程只做同步**：事件 / 建网 / 消息投递，零求解计算
- **独立异步仿真核心**：求解 / 重建 / 拆分合并全部在核心线程执行，与主线程仅**消息交互**（`postTopology` / `postSolve` / 结果回调）
- **核心禁止写回 Level**：只操作纯虚拟网络/网表（common 零 MC 依赖）
- **配套**：网络缓存系统、SQLite 持久化、SimClock 有界预算调度、SimHttpServer 建模仿真内核

### 🔌 模组联动

| 联动 | 内容 |
|---|---|
| **PowerGrid** | 电网仿真多线程加速、稳定性修复（线程安全），超时保护，与 Cryptand 内核深度整合 |
| **Create** | 动能方块 Mixin、应力/动力处理、电机制动/堵转保护 |
| **Create: Aeronautics** | 轮应力公式、动力学稳定性适配 |
| **Sable / CEE** | 物理化亚层端点、受电弓（pantograph）逻辑、极网联动 |
| **Railway** | 接触网（catenary）、受电弓取电、列车供电链路 |
| **测量仪表** | 万用表 / 电流表 / 温度计（复合模型 `CompositeModel`，3-x 端子电路解析） |
| **EDA 画布 / 面板** | 电子设计画布与面板控制 |

---

## 🛠 技术栈

| 层 | 说明 |
|---|---|
| 语言 | Java（common 纯逻辑） + C（`native/`：JNI 绑定） |
| 平台 | Architectury multi-loader（common + neoforge + fabric 骨架） |
| 数值 | MNA 求解器 + OpenBLAS LAPACK + SuperLU |
| 持久化 | SQLite（网络缓存 / 存档） |
| 网络 | SimHttpServer（HTTP 仿真 API，`RealMnaSolver` / `ComplexMnaSolver` 内核） |

---

## ✅ 支持平台

| 平台 | 游戏版本 |
|------|---------|
| NeoForge | 1.21.1 |

> native 加速目前提供 Windows x86_64 DLL（可交叉扩展其他平台，缺失时自动回退纯 Java）

---

## 🔨 构建

```bash
# 编译 Java
./gradlew build        # Windows: gradlew.bat build

# 编译 native 加速库（可选，约 30 分钟）
cd native && build_dll.bat
# 输出：neoforge/src/main/resources/assets/cryptand/native/libpowergridNative7.dll
```

## 📂 目录结构（速览）

```
common/   → 电路仿真内核 + 模型 + 求解器 + 网络（纯 Java，零 MC）
neoforge/ → 平台实现 + 各 mod 联动模块（powergrid/create/cee/aeronautics/railway/simserver…）
fabric/   → fabric 平台骨架
native/   → JNI 原生求解（OpenBLAS/SuperLU 绑定）
test/     → 求解器/模型自测
```

---

## 📝 许可证

本项目基于 **MIT 许可证** 开源。

---

*Cryptand — 联动拓展玩法，优化提升性能。*

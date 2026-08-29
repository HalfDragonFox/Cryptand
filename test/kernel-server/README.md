# 独立内核仿真服务端（不依赖 Minecraft）

把 Cryptand 自研电力引擎（common 模块 `com.hdf.cryptand.engine`，**纯 Java 无 MC 依赖**）
编译成可独立运行的 HTTP 服务。**不需要启动 Minecraft**，即可用真实内核引擎做仿真对照。

与模组内 `SimHttpServer`（neoforge/simserver/）的协议完全一致，前端（test/）无需改动：
工具栏「内核」→ 选「MC 内核」→ 点「连接」即可（地址默认 `http://127.0.0.1:12787`）。

## 目录结构

```
test/kernel-server/
  lib/gson-2.11.0.jar            # JSON 库（从 Gradle 缓存复制）
  src/com/hdf/cryptand/simserver/
    KernelServer.java            # 独立主程序：HTTP 服务 + /health + /simulate
    KernelSimulator.java         # 网表 → 内核 Network → RealMnaSolver/ComplexMnaSolver → 结果
                                 # （与 neoforge simserver 同源）
  out/                           # 编译输出（javac 生成）
  build.bat                      # 编译：引擎源码(common) + 服务端源码 -> out/
  run.bat                        # 运行：java -cp out;lib/gson ... KernelServer [port]
  README.md
```

引擎源码**不复制**：build.bat 直接从
`common/src/main/java/com/hdf/cryptand/engine/` 编译，保证与模组内引擎一致、无两份副本。

## 用法

```bat
:: 首次：编译（引擎 + 服务端 -> out/）
test\kernel-server\build.bat

:: 运行（默认端口 12787；Ctrl+C 退出）
test\kernel-server\run.bat
:: 或指定端口
test\kernel-server\run.bat 12799
```

`run.bat` 检测到未编译时会自动调用 `build.bat`。

## 接口

| 端点 | 说明 |
| --- | --- |
| `GET /health` | `{ ok, mod: "Cryptand-standalone", kernel, port }` 连接检测 |
| `POST /simulate` | 收网表 JSON，返回仿真结果（与模组版协议一致） |

请求/响应协议与 `test/README.md`「内核桥接」一节完全相同（元素 kinds：
resistor / capacitor / inductor / vsource / acsource / isource / diode / wire；
DC 热稳态、瞬态温度/能量、导线烧毁均由适配器推进）。

## 一键启动

`test\start.bat` 已改为：起前端服务器(12789) + 打开页面 + **自动启动本内核服务端** + 等待就绪
（`CRYPTAND_NO_KERNEL=1` 可跳过，`CRYPTAND_NO_MC=1` 为旧名兼容）。

## 快速自测

```powershell
# 起服务后：
Invoke-RestMethod http://127.0.0.1:12787/health
# POST /simulate 分压电路 → nodeVoltages 中点为 3.333V
```

也可以在 test/ 目录直接 `node mock_kernel.js`（JS 模拟内核）做纯前端联调，两者端口不同注意区分。

# Cryptand 电路模拟器（EDA 风格）

一个纯浏览器端运行的电路原理图编辑器（EDA 风格），位于 `test/` 目录，无需构建工具。

**架构：test/ 只是前端。** 真正的仿真由 **Cryptand Java 内核**完成（common 模块的 `Network` + `RealMnaSolver`/`ComplexMnaSolver`，纯 Java）。内核可两种方式运行：
- **独立内核服务端**（`test/kernel-server/`，**不启动 MC**，一键启动推荐）
- **MC 模组内**（neoforge 的 `SimHttpServer`，随 ServerStarted 开启）

前端把电路序列化为网表元素列表，通过本地 HTTP 接口（`/simulate`）发给内核求解，用于**对照验证内核仿真是否正确**。内置的 JS 引擎仅作为「本地参考」离线对照。

## 仿真核心（SimulationCore，核心与 MC 解耦）

所有仿真操作（网络重建、求解、拆分合并网络等）统一走 **仿真核心** `common/.../engine/core/SimulationCore.java`（纯 Java，零 MC 依赖），参考架构图（`all.drawio`）：

```
任何调用方（MC 主线程 / HTTP 请求 / 独立运行）
   │  提交网络操作（submitSolve / submitRebuild / submitSplitMerge）
   ▼
SimulationCore 门面
   ▼  线程 A：分配器 ThreadDispatchers.get()（全局，必占一线程）
AsyncInteractionManager（线程 B：网络操作记录表，同网络串行锁定）
   ▼  线程 C：网表相关操作类 NetlistOperation（缓冲整合 + 优先级拆合>重建>求解）
CoreNetOpExecutor（对 Network 求解/重建/拆合，结果写入 NetworkRegistry 缓存）
```

- **MC 启动时直接启动内核**：`CryptandNeoForge` 的 ServerStartedEvent → `SimulationCore.get().start()`（ServerStopping → `stop()`），与 SimHttpServer 并列
- **可独立运行**：`./gradlew :common:runSimCoreTest`（或 `java ... SimulationCore`）——不启动 MC 也能跑仿真核心自测（启动/异步求解/同步求解/重建/拆合/结果缓存 6 项）
- 网络注册表 `NetworkRegistry`：key → `Network` + 最近求解结果；`solveNow(key)` 同步求解，`submitXxx(key)` 异步走分配器
- ⚠ DC（f=0）网络必须走 `RealMnaSolver`（`DcVoltageSource.stampComplex` 不注入电压，相量下 DC 电压恒 0）；AC 走 `Solvers.create`（多频/非线性增强/Complex）

## 内核桥接（仿真走 Cryptand Java 内核）

### 架构

```
test/ 前端（原理图编辑 + 结果显示）
   │  电路 → 网表元素列表（节点由前端分配，节点0=地）
   ▼  POST http://127.0.0.1:12787/simulate
SimHttpServer（neoforge/simserver/，随 ServerStarted 启动）
   ▼  KernelSimulator 翻译为内核 Network
Network + RealMnaSolver/ComplexMnaSolver（common 引擎）
   ▼  节点电压 / 元件电流 / 导线温度 / 波形
   返回 JSON → 前端展示（与本地参考同构，便于 A/B 对比）
```

### 使用

内核有两种运行方式（协议完全一致）：

**方式 A（推荐）：独立 Java 内核服务端（不启动 MC）**
1. `test\kernel-server\run.bat`（首次自动编译引擎，默认端口 12787）
2. 前端工具栏「内核」组：选择 **MC 内核**，点 **连接** 检测（/health）
3. 编辑电路 → 直流/瞬态仿真 → 结果来自内核求解器

**方式 B：模组内核（MC 内）**
1. 启动 MC（neoforge 端）→ `SimHttpServer` 随 ServerStarted 自动开启 `http://127.0.0.1:12787`（可用 `-Dcryptand.simport=端口` 改端口）
2. 前端工具栏「内核」组：选择 **MC 内核**，点 **连接** 检测（/health）

> 切「本地参考」可离线对照（JS 引擎，仅参考）。内核未启动时内核模式会优雅提示连接失败；本地参考始终可用。

### 内核状态指示（工具栏「内核」组右侧徽标）

- 页面加载后**每 3 秒自动检测**一次内核（GET /health），无需手动刷新：
  - **● 内核未启动**（红）：内核未运行 → 请运行 `test\start.bat`（自动启动内核）
  - **● 检测中…**（黄）：正在检测
  - **● 内核已连接**（绿）：内核就绪，悬停可看 mod/内核/端口
- 状态切换时状态栏给出对应提示（只提示一次，不刷屏）
- 内核由 `start.bat` 在**独立 cmd 窗口**（标题 `Cryptand-Kernel`）启动并**实时打印内核日志**（`[KernelServer] ...`）；**关闭该窗口即关闭内核**

### 接口

| 端点 | 说明 |
| --- | --- |
| `GET /health` | `{ok, mod, kernel, port}` 连接检测 |
| `POST /simulate` | 收网表 JSON，返回仿真结果（见协议） |

### 请求（POST /simulate）

```json
{
  "mode": "dc" | "tran" | "ac",
  "duration": 0.05, "step": 0.0001, "frequency": 0,
  "nodeCount": 7, "groundNode": 0,
  "elements": [
    {"id":1,"kind":"vsource","a":1,"b":2,"value":5,"series":0.01},
    {"id":2,"kind":"resistor","a":3,"b":4,"value":330,"tempCoef":0,"thermalConductance":0,"heatCapacity":0},
    {"id":3,"kind":"diode","a":5,"b":6,"vth":0.7},
    {"id":5,"kind":"wire","a":1,"b":3,"r":0.075,"type":"copper","name":"铜线","length":100,
       "ratedCurrent":80,"thermalConductance":2,"heatCapacity":5,"maxTemp":200}
  ],
  "probes": [2, 3]
}
```

元素 kinds：`resistor / capacitor / inductor / vsource / acsource / isource / diode / wire`；电感带内阻时前端展开为 `inductor + resistor`（内部节点）。

### 响应

```json
{
  "ok": true, "solver": "RealMnaSolver", "mode": "dc", "converged": true,
  "nodeVoltages": [0, 5.0, 3.333, ...],
  "transient": {"times": [...], "waveforms": [[...]]},   // tran 模式
  "components": [{"id":1,"i":-0.0115,"p":-0.0576,"t":20,"energy":0,"stored":0}],
  "wires": [{"id":1,"r":0.075,"length":100,"i":0.0115,"p":9.9e-6,"t":20,
              "energy":0,"burned":false,"type":"copper","name":"铜线","ratedCurrent":80,"load":0.0001}],
  "burnedWires": []
}
```

- `nodeVoltages` 为内核求解的节点电压（**权威对照值**）；元件/导线电流功率由内核电压推导
- 温度模型：元件 R(T)=R₀(1+α(T−20°C))；导线焦耳热→散热→200°C 烧毁（与前端模型一致，由适配器推进）
- `id` 为数字时对应前端元件/导线 id；电感内部串联电阻部分为字符串 id（前端忽略）

### 桥接自检

```bash
node test/check_kernel.js   # 验证 电路 → 内核网表请求 → 结果归一化
```

## 一键启动（start.bat）

```
test\start.bat
```

流程（test 先启动，内核后启动，自动等待就绪）：
1. 启动前端静态服务器（`node serve.js`，http://127.0.0.1:12789）并打开浏览器
2. **自动启动独立 Java 内核服务端**：新建 `Cryptand-Kernel` 窗口运行 `test\kernel-server\run.bat`（不依赖 Minecraft，引擎直接引用 common 模块源码；若检测到内核已就绪则跳过）
3. **轮询等待内核就绪**（http://127.0.0.1:12787/health），就绪后提示并响铃

> 首次运行内核服务端会先编译引擎（稍慢）。`Cryptand-Kernel` 窗口保留，关闭即退出内核。

> ⚠️ **start.bat 必须保持纯 ASCII**（无中文/全角字符）：cmd 按控制台代码页解析 .bat，GBK 系统下 UTF-8 中文会被拆成乱码命令（`xxx 不是内部或外部命令`、`文件名、目录名或卷标语法不正确`）。所有中文提示都在前端/README 里。

环境变量（测试/自动化）：
- `CRYPTAND_WAIT_TIMEOUT`：等待超时秒数（默认 600）
- `CRYPTAND_KERNEL_PORT` / `CRYPTAND_WEB_PORT`：端口覆盖（例如想用 12800：`set CRYPTAND_KERNEL_PORT=12800` + `set CRYPTAND_WEB_PORT=12800`）
- `CRYPTAND_NO_BROWSER=1`：跳过服务器/浏览器，仅等待内核
- `CRYPTAND_NO_KERNEL=1`：跳过内核自动启动（手动启动内核/模拟内核时）
- `CRYPTAND_NO_MC=1`：同 NO_KERNEL（旧名兼容）
- `CRYPTAND_NO_PAUSE=1`：结束时不停留

> 无内核时可用 `node test/mock_kernel.js 12787` 起一个 JS 模拟内核（返回占位结果），用于联调前端内核模式：先开模拟内核，再 `set CRYPTAND_NO_KERNEL=1` 后运行 start.bat。

## 运行方式

- **推荐**：双击 `test\start.bat` —— 自动起前端服务器(12789) + 打开页面 + 自动启动独立 Java 内核 + 等待就绪
- **仅前端**：直接用浏览器打开 `test/index.html` 即可（双击文件，或拖入浏览器窗口；本地参考仿真不依赖内核）
- 手动起服务器：`node test/serve.js 12789`（**不要用 12788**，该端口曾有残留 http.sys 监听导致空白页）

## 多用户访问（局域网共享，每端独立界面）

架构本就是**纯客户端**：每个浏览器各自独立的画布/电路/结果（无服务器端共享状态），内核为**无状态按请求求解**（每次 /simulate 新建 Network，可并发）。因此**多台设备同时访问即各自独立操作**：

1. **前端服务器监听所有网卡**（serve.js 已改绑 `0.0.0.0`），启动时打印：
   `[serve] 局域网访问: http://192.168.x.x:12789/`
2. **内核服务端监听所有网卡**（`KernelServer`/`SimHttpServer` 默认 `0.0.0.0`），局域网设备可直接 POST /simulate
3. **前端自动指向同主机内核**：页面从局域网地址打开时，内核地址自动变为 `http://<该主机>:12787`（无需手改；工具栏「内核」里可查看/覆盖）
4. start.bat 就绪后打印 `LAN access` 的网页与内核地址，分发给其他设备即可

> 安全提示：内核接口无鉴权，任何局域网设备都能提交仿真请求。若仅限本机，把内核监听改回
> `127.0.0.1`（`-Dcryptand.simport.host=127.0.0.1`，独立服务端可用同属性或改 KernelServer 默认值）。
> 修改内核监听地址后需 `test\kernel-server\build.bat` 重编译（run.bat 只在缺类时自动构建）。

## 移动端适配（手机/平板）

- 窄屏(<820px)下：左侧元件库与右侧面板变为**抽屉**（工具栏「☰」「面板」按钮开关），画布占满全屏
- **触摸**：单指拖动元件/平移画布（空白处拖动=平移）、**双指捏合缩放**、点按放置元件/布线的端点
- 状态栏精简（隐藏坐标/元件数）；弹窗自适应宽度；提示气泡移至下方

## 界面布局

- **顶部工具栏**：文件（新建/打开/保存/导出PNG/示例/帮助）、编辑（撤销/重做/旋转/复制/删除）、工具（选择/导线/探针）、仿真（直流/运行/步进/停止）、视图（缩放/适应）、导线设置（网格吸附/正交/颜色/线宽）
- **左侧元件库**：支持的元件列表，点击后到画布上放置（含「组装器设备」分组）
- **中间画布**：原理图编辑区（滚轮缩放、中键平移、右键菜单）
- **右侧面板**：元件属性编辑、仿真设置、结果表格与波形显示
- **底部状态栏**：当前模式、元件/导线/节点/探针数量、鼠标坐标

### AD 风格交互（参考 Altium Designer）
- **拖动元件时连线跟随（橡皮筋）**：与元件引脚相连的导线端点随元件一起移动，布线不脱开
- **框选多选**：在空白处拖拽拉出矩形框，框内元件/导线全部选中；`Ctrl+点击` 逐个增减选择；可整组拖动/删除
- **悬停高亮**：鼠标悬停元件显示蓝色描边（便于预览命中）
- **布线吸附提示**：布线模式下靠近端子时显示绿色虚线圆环提示可吸附

## 支持的元件

| 元件 | 说明 |
| --- | --- |
| 电阻 | 线性电阻，可配温度系数 α 与热模型（R(T)=R₀(1+α(T−20°C))） |
| 电位器 / 灯泡 | 灯泡为钨丝模型：α=0.0045、散热/热容，随温度升高电阻增大、发光 |
| 电容 | 瞬态仿真（后向欧拉伴随模型），显示储能 ½CV² |
| 电感（绕组） | L 串联 R 的绕组模型，α=0.0039（同模组线圈温度系数），储能 ½LI² |
| 直流电压源 / 交流电压源 / 电流源 | 独立源 |
| 地线 | 电压参考节点（可多个，自动合并） |
| 开关 | 开/关可切换 |
| 二极管 / 发光二极管 | 指数模型 + 牛顿迭代（LED 点亮有发光效果） |
| NPN 三极管 | 简化的 Ebers-Moll 输运模型 |

### 组装器设备（与引擎 composite model / PowerGrid 组装器一一对应）

| 元件 | 电气模型 | 说明 |
| --- | --- | --- |
| 风扇 / 电机 / 电磁铁 | **R-L 绕组**（MotorModel）+ 热模型 | 同款绕组负载：R(T) 温度系数 0.0039 |
| 加热器 / 警铃 / 碳堆 | **纯电阻** + 热模型 | 加热器/警铃 L≈0；碳堆为可变电阻 |
| 盆加热器 | **R-L 绕组** + 热模型 | 同 BasinHeaterAssembler |
| 发电机 | **电枢 R + EMF 源**（GeneratorModel） | 电动势 + 电枢电阻 |
| 电池 | **DC 源 + 内阻**（Thevenin） | 同 BatteryAssembler |
| 变压器 / 自耦调压器 | **理想变压器** 4 端口，变比 ratio | V₂=n·V₁；自耦调压器变比可调 |
| 仪表 | 电阻（低阻安培表 0.05Ω 默认） | 类型可选 电流表/电压表/功率表 |
| 接地棒 | 地（与地线相同） | 参考节点 |
| 连接器 | 极小电阻（短路） | 接触电阻 0.001Ω |
| 保险丝 / 高压开关 / 断路器 / 接触器 | 开关（闭合=0V 理想短接） | 保险丝带额定电流参数 |
| 绘图仪 | 20kΩ 采样电阻 | 同 PlotterAssembler |

### 三相设备（3 相 0/120/240°）

| 元件 | 电气模型 | 说明 |
| --- | --- | --- |
| 三相电源 | 3× 交流源（0/120/240°）→ L1/L2/L3/N | 相幅值、频率可调 |
| 三相电机 / 无刷电机 | 3× R-L 绕组（Y 型，各相对 N） | 含温度模型 |
| 三相变压器 | 3× 理想变压器（pa1..3,n1 ↔ pb1..3,n2） | 变比 ratio |
| 三相整流器 | 3 二极管（相→DC+）+ 滤波电容 + 负载 | 输出 ≈ 1.35×相电压 |
| 变频器 VFD | 3× 交流输出（L 输入 → 3 相输出） | 输出幅值/频率可调 |

### 无线电 / 其他复合（引擎 composite model，可无 BE 绑定）

| 元件 | 电气模型 | 说明 |
| --- | --- | --- |
| 天线 | 辐射电阻 + 损耗电阻 | AntennaModel |
| 无线电发射机 / 振荡器 | 正弦源输出 | RadioTransmitterModel / OscillatorModel |
| 无线电接收机 / 扬声器 | R-L（调谐线圈/音圈） | RadioReceiverModel / SpeakerModel |
| 太阳能板 | 光生电流源 + 串联电阻 | SolarPanelModel |
| 整流器 | 全桥 4 二极管 + 滤波 + 负载 | RectifierModel |
| DC-DC 变换器 / 逆变器 | 压控电压源（Vout=gain·Vin） | DcDcConverterModel / InverterModel |
| 直流电机 / 感应电机 | R-L 绕组 | DcMotorModel / InductionMotorModel |
| 同步电机 | 反电动势 + 内阻 | SynchronousMotorModel |
| 电机/发电机（可切换） | 电动=R-L / 发电=EMF+内阻 | ElectroMachineModel |
| 电子管 | 三极管线性近似 Ia=gm·(Vgk−vcut) | ElectronTubeModel（μ/rp/截止） |
| VFET | 线性近似 Id=gm·(Vgs−vth) | VfetModel（跨导/阈值） |
| 波形源 | DC/SINE/SQUARE/TRIANGLE/SAWTOOTH | WaveformSource（相位/占空比/偏移） |
| 互感 | 耦合电感 BE 伴随（L1/L2/M） | MutualInductor（瞬态耦合） |
| 压控电流源 / 压控电压源 | VCCS：I=gm·Vc；VCVS：Vout=gain·Vin | 4 端子（控制 2 + 输出 2） |

> 求解时复合元件**拆分为基础元件**（与模组一致）：R-L 绕组展开为电阻串联电感；发电机/电池展开为内阻串联 EMF；变压器/三相变压器用理想变压器 MNA 支路；三相源/VFD 为 3 条 120° 相移支路；互感用耦合电感伴随模型。内核桥接对 VCCS/VCVS/电子管/VFET/互感/DC-DC/逆变器 暂跳过（本地参考可算，内核警告）。

## 导线模型（带电阻 + 温度，数据取自 PowerGrid/Cryptand）

导线不再是无电阻的理想连接，而是真实元件：

| 类型 | 电阻率 (Ω/m) | 额定电流 (A) | 散热 (W/K) | 热容 (J/K) | 烧毁温度 |
| --- | --- | --- | --- | --- | --- |
| 铜线 | 0.00075 | 80 | 2.0 | 50 | 200°C |
| 铁线 | 0.0025 | 160 | 2.0 | 50 | 200°C |
| 金线 | 0.0015 | 160 | 2.0 | 50 | 200°C |

- **电阻**：R = 电阻率 × 长度（每段独立建模，导线顶点成为独立节点，可看到沿线压降）
- **温度**：焦耳热 I²R 升温、散热 G·(T−Tₐₘᵦ) 降温，时间常数 τ=C/G=25s
- **烧毁**：温度超过 200°C 导线断开（开路）、电流归零、画布上显示红色虚线 + 🔥，温度保持 200°C
- **可视化**：导线按温度从蓝色渐变到橙红色；工具栏可选 铜/铁/金 类型、可关「线阻」开关
- 选中导线可在属性面板查看：长度、电阻、电流、损耗、温度、负载率（I/I_max）、累计能量

## 温度与能量模型

- **温度模型**：元件（电阻/灯泡/绕组）配 `温度系数 α`、`散热系数 G(W/K)`、`热容 C(J/K)`；
  - 电阻随温度变化 R(T)=R₀(1+α(T−20°C))
  - 瞬态仿真每步推进热演进 T += (P − G·(T−Tₐₘᵦ))·dt/C
  - 直流分析自动迭代到热稳态 T_ss = Tₐₘᵦ + P/G
- **能量模型**：瞬态仿真累计每个元件 ∫P·dt（J），电容/电感显示储能，导线显示损耗能量

## 仿真能力

- **直流工作点分析**：MNA + 高斯消元 + 热稳态迭代，输出元件电流/功率/温度/储能与节点电压
- **瞬态分析**：固定步长，后向欧拉伴随模型（电容/电感），牛顿迭代（二极管/三极管，指数钳位+热启动保证收敛）
- **电压探针**：探针工具点击导线/引脚，瞬态后显示节点电压波形
- 参数支持工程单位：`1k`、`2.2u`、`100m`、`47kΩ`、`50Hz`

## 命令行仿真工具（喂入你的电路数据）

核心算法不依赖浏览器，可直接用 Node 调用。电路数据为 JSON（与编辑器保存格式一致，元件可省略 `id`，参数可省略用默认值）：

```bash
# 直流工作点分析
node test/run_sim.js test/examples/voltage_divider.json

# 瞬态仿真（默认零初始状态），并输出波形 CSV
node test/run_sim.js test/examples/rc_charge.json --tran 0.05 --step 0.0001 --csv out.csv

# 以直流工作点为初始状态
node test/run_sim.js test/examples/rectifier.json --tran 0.04 --init dc

# 打印节点编号对照表（便于定位探针）
node test/run_sim.js test/examples/rectifier.json --nodes
```

选项：`--tran <秒>`、`--step <秒>`、`--probe <x,y|节点号>`（可多次）、`--csv <文件>`、`--nodes`、`--init zero|dc`

## 核心算法 API（Node）

```js
const { solveCircuitJSON, transientJSON } = require('./test/js/simulator.js');

// 直流工作点
const dc = solveCircuitJSON(circuit, {});        // circuit 为 JSON 对象
// dc.V[node] 节点电压, dc.currents.get(id) -> {i, p}, dc.netlist

// 瞬态仿真
const t = transientJSON(circuit, { duration: 0.05, step: 0.0001, probes: ['380,120'] });
// t.times[], t.data[k][]（每个探针一条波形）, t.last 最后一次工作点
```

## 算法自检（推荐先跑）

```bash
node test/verify_api.js
```

覆盖：分压、RC 充电、二极管整流、LED、NPN 共射偏置、电流源、导线电阻/压降、导线过载烧毁，共 21 项断言，全部与理论值对比验证。

## 电路 JSON 格式

```json
{
  "components": [
    { "type": "resistor", "x": 280, "y": 120, "rotation": 0, "params": { "resistance": 1000 } },
    { "type": "vsource",  "x": 80,  "y": 120, "rotation": 180, "params": { "voltage": 5 } }
  ],
  "wires": [ [ { "x": 130, "y": 120 }, { "x": 230, "y": 120 } ] ],
  "probes": [ { "x": 380, "y": 120 } ]
}
```

- 坐标在 20px 网格上；同一坐标的引脚/导线顶点自动归为同一电气节点
- 元件引脚按定义顺序编号：如 `resistor` 两个引脚、`npn` 依次为 B/C/E、`ground` 一个引脚
- 节点 0 为地（参考点），仿真前需放置至少一个地线元件
- 导线可指定类型：`{ type: "copper", points: [...] }`（copper/iron/golden），未指定默认铜线
- 注意：**电源的另一端也要接地**形成回路，否则电路不导通（这是常见接线错误）

## 快捷键

| 按键 | 功能 |
| --- | --- |
| `R` | 旋转选中元件 |
| `Delete` / `Backspace` | 删除选中 |
| `Ctrl+D` | 复制 |
| `Ctrl+Z` / `Ctrl+Y` | 撤销 / 重做 |
| `W` / `P` / `Space` | 导线 / 探针 / 选择工具 |
| `Esc` | 取消布线 / 返回选择 |
| `Ctrl+S` / `Ctrl+O` | 保存 / 打开电路 |
| 滚轮 / 中键拖动 | 缩放 / 平移 |

## 文件结构

```
test/
  index.html          # 入口页面
  css/style.css       # 样式
  js/
    components.js     # 元件定义、参数解析、符号绘制（可被 Node 引入）
    simulator.js      # 本地 JS 参考引擎（网表 + MNA + 瞬态；离线对照用）
    kernel.js         # 前端 ↔ MC 内核 桥接（序列化 / 归一化 / 后端抽象）
    editor.js         # 原理图编辑器（放置/布线/探针/撤销等）
    ui.js             # 工具栏、元件库、面板、仿真控制（后端抽象）
    main.js           # 入口
  run_sim.js          # 命令行本地仿真工具（JS 参考引擎）
  verify_api.js       # 本地引擎自检（21 项断言）
  check_kernel.js     # 内核桥接自检（序列化/归一化）
  mock_kernel.js      # 模拟内核 HTTP 服务（无 MC 时联调内核模式）
  start.bat           # 一键启动：前端服务器(12789) + 打开页面 + 启动独立内核 + 等待就绪
  serve.js            # 前端静态服务器（零依赖 Node，start.bat 调用；勿用 12788）
  kernel-server/      # 独立 Java 内核服务端（不依赖 MC）：
    build.bat         #   编译：引擎源码(common) + 服务端 -> out/
    run.bat           #   运行：java KernelServer [port]（默认 12787，Ctrl+C 退出）
    src/com/hdf/cryptand/simserver/
      KernelServer.java    # 独立主程序（HTTP /health /simulate）
      KernelSimulator.java # 网表 → 内核 Network → RealMnaSolver/ComplexMnaSolver → 结果
    lib/gson-2.11.0.jar    # JSON 库
  examples/           # 示例电路 JSON

模组侧（neoforge/src/main/java/com/hdf/cryptand/neoforge/simserver/，MC 内运行版本）
  SimHttpServer.java      # 本地 HTTP 接口（/health /simulate）
  KernelSimulator.java    # 网表 → 内核 Network → RealMnaSolver/ComplexMnaSolver → 结果
  （CryptandNeoForge 已注册 ServerStarted 启动 / ServerStopping 停止）
```

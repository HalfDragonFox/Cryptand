/**
 * ===== Mixin 配置插件 =====
 *
 * 直接读取 TOML 配置文件（绕过 NeoForge 异步加载），
 * 在类加载阶段决定 Mixin 注入与否。
 */

package com.hdf.cryptand.neoforge.mixin;

import com.electronwill.nightconfig.core.file.FileConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class CryptandMixinPlugin implements IMixinConfigPlugin {

    /** TOML 配置目录（相对运行目录）：config/cryptand/ */
    private static final String CONFIG_DIR = "config/cryptand/";

    /** 是否启用自研求解器替换（enableCryptandSolver，circuit-simulation.toml）。类加载阶段读取，缓存结果。 */
    private static volatile Boolean cryptandSolver;

    /** 是否注入 Create（机械动力）应力上限 Mixin（enableCreateStressLimit，circuit-simulation.toml）。 */
    private static volatile Boolean createStressLimit;

    /** 是否交错电网（PowerGrid）支持/接管（enablePowergridSupport，powergrid.toml）。 */
    private static volatile Boolean powergridSupport;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Aeronautics（Create 飞艇）组：仅在安装 create_aeronautics 时注入。
        // 未装 → 不注入相关 mixin、不启用联动功能（可选联动，compileOnly 依赖）。
        if (mixinClassName.startsWith("com.hdf.cryptand.neoforge.aeronautics.mixin.")) {
            boolean apply = com.hdf.cryptand.neoforge.aeronautics
                    .AeronauticsCompat.isLoaded();
            String sName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            System.out.println("[Cryptand] Mixin " + sName
                    + " → " + (apply ? "INJECTED" : "SKIPPED")
                    + " (aeronautics=" + apply + ")");
            return apply;
        }
        // Sable（航空学物理亚层）组：仅当安装 sable（物理化核心）时注入。
        // 未装 → 跳过（mixin 目标类为字符串，未装时类不存在，跳过安全）。
        // ⚠ 2026-08-23 修复：类加载阶段 net.neoforged.fml.ModList.get() 为 null
        //（PREPARE 早于 FML 初始化）→ 必须用 FMLLoader.getLoadingModList()。
        if (mixinClassName.startsWith("com.hdf.cryptand.neoforge.cee.mixin.sable.")) {
            boolean apply = false;
            try {
                apply = net.neoforged.fml.loading.FMLLoader
                        .getLoadingModList().getModFileById("sable") != null;
            } catch (Throwable ignored) {
            }
            String sName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            System.out.println("[Cryptand] Mixin " + sName
                    + " → " + (apply ? "INJECTED" : "SKIPPED")
                    + " (sable=" + apply + ")");
            return apply;
        }
        // CEE（Create-Electro-Energetics）受电弓算法接管组：仅当 CEE 支持开启 +
        // 仿真核心启用 + CEE mod 已加载时注入（CEE 未装/关闭 → 跳过，目标类不存在安全）。
        if (mixinClassName.startsWith("com.hdf.cryptand.neoforge.cee.mixin.")) {
            boolean apply = com.hdf.cryptand.neoforge.cee.CeeTerminalSupport.ceeEnabled()
                    && com.hdf.cryptand.neoforge.cee.CeeTerminalSupport.ceeModLoaded();
            String sName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            System.out.println("[Cryptand] Mixin " + sName
                    + " → " + (apply ? "INJECTED" : "SKIPPED")
                    + " (cee=" + apply + ")");
            return apply;
        }
        // Create（机械动力）组：受 TOML enableCreateStressLimit 控制
        if (mixinClassName.startsWith("com.hdf.cryptand.neoforge.create.mixin.")) {
            boolean apply = isCreateStressLimit();
            String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            System.out.println("[Cryptand] Mixin " + shortName
                    + " → " + (apply ? "INJECTED" : "SKIPPED")
                    + " (createStressLimit=" + apply + ")");
            return apply;
        }
        // PowerGrid 组：按各自功能/求解器开关决定注入
        //（旧 enablePowergridAsyncNetwork 已废除，2026-08-22）
        if (mixinClassName.startsWith("com.hdf.cryptand.neoforge.powergrid.mixin.")) {
            // 交错电网（PowerGrid）支持/接管开关（enablePowergridSupport，2026-08-22）：
            // 关闭 → 完全原版 PowerGrid，不注入任何替换/接管 Mixin
            //（仿真核心 enableCryptandSolver 仍可单独运行，互不影响）
            if (!isPowergridSupport()) {
                System.out.println("[Cryptand] Mixin "
                        + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)
                        + " → SKIPPED (enablePowergridSupport=false, 完全原版 PowerGrid)");
                return false;
            }
            // 变压器交流化 Mixin：始终注入（与 enableCryptandSolver 无关）
            if (mixinClassName.contains("TransformerBlockEntityMixin")) {
                System.out.println("[Cryptand] Mixin TransformerBlockEntityMixin"
                        + " → INJECTED (ac-transformer, always)");
                return true;
            }
            // 电机相关 Mixin（交流驱动限制 / 线圈发热 / 励磁检测 / 转速配置）：始终注入
            if (mixinClassName.contains("GeneratorCouplingAcMixin")
                    || mixinClassName.contains("GeneratorBlockEntityAcMixin")
                    || mixinClassName.contains("CommutatorBlockEntityAcMixin")
                    || mixinClassName.contains("WindingBlockEntityAcMixin")
                    // 线圈温度算法完全替换（禁用原版失真 wire 功率加热）：始终注入
                    || mixinClassName.contains("ThermalBehaviourWindingMixin")
                    || mixinClassName.contains("RotorBehaviourAcMixin")
                    // 发电机离合器生成转速上限放开：始终注入（与异步网络开关无关）
                    || mixinClassName.contains("GeneratorClutchSpeedMixin")
                    // 风扇/鼓风机冷却（ElectricFan + Create EncasedFan）：始终注入（与异步网络开关无关）
                    || mixinClassName.contains("FanCoolingMixin")
                    // 动态声音 Mixin（机械声 + 电磁嗡鸣）：始终注入（与异步网络开关无关）
                    || mixinClassName.contains("RotorSoundMixin")
                    || mixinClassName.contains("RotorSoundInstanceMixin")) {
                System.out.println("[Cryptand] Mixin "
                        + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)
                        + " → INJECTED (motor-ac, always)");
                return true;
            }
            // 调试 Mixin 始终注入（由 enablePowergridDebug 配置控制输出）
            if (mixinClassName.contains("PowerGridNetworkDebugMixin")) {
                System.out.println("[Cryptand] Mixin PowerGridNetworkDebugMixin"
                        + " → INJECTED (debug, always)");
                return true;
            }
            // ⚠ 电机值【直接写回】Mixin（2026-08-25 启用）：用户要求"禁用原版计算、
            //   直接写应力和转速"。原版 tick 继续跑但结果每 tick 被引擎值覆写
            //   （avgSpeed=ω、generatedSpeed=rpm、load=stress → Create 网络同步）。
            //   内部有 MotorStateStore.get==null → 不干预 兜底，未建模/未自管时无副作用。
            //   ⚠ 此前落入"其余并行求解组"被 enableCryptandSolver=true SKIPPED（归类错误：
            //   它不是多线程 mixin）→ 未生效。现改为始终注入。
            if (mixinClassName.contains("PowerGridMotorTakeoverMixin")
                    || mixinClassName.contains("ConstantSpeedMotorTakeoverMixin")
                    || mixinClassName.contains("ServoMotorTakeoverMixin")) {
                System.out.println("[Cryptand] Mixin "
                        + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)
                        + " → INJECTED (motor-takeover, always)");
                return true;
            }
            // 求解器替换 Mixin 始终注入：是否替换由 enableCryptandSolver 运行时配置决定
            // （独立开关：关闭即完全原版）
            if (mixinClassName.contains("ElectricalNetworkMixin")) {
                System.out.println("[Cryptand] Mixin ElectricalNetworkMixin"
                        + " → INJECTED (solver-replace, always; runtime gated by enableCryptandSolver)");
                return true;
            }
            // 万用表调试 Accessor 始终注入：纯字段读取，无副作用
            // （高级万用表 AdvancedMultimeterItem 直接重写 getText，不再需要 MultimeterItemMixin）
            if (mixinClassName.contains("GeneratorCouplingAccessor")
                    || mixinClassName.contains("ElectricalNetworkAccessor")
                    || mixinClassName.contains("CommutatorBlockEntityAccessor")) {
                System.out.println("[Cryptand] Mixin "
                        + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)
                        + " → INJECTED (multimeter-debug, always)");
                return true;
            }
            // ===== WorldNetworksMixin：始终注入 =====
            // ⚠ 该 Mixin 现为【相量回写】的唯一驱动（cryptand$preTick →
            //   cryptand$doComputeRound → PhasorWriteback.round）。时域求解彻底
            //   禁用后，节点电压完全靠它写回 PowerGrid 网络，原版设备 getValue()
            //   才读得到相量结果。若按旧逻辑在 enableCryptandSolver=true 时跳过，
            //   回写就完全停摆 → 所有设备/万用表读 0（"眼镜不显示数值/测量 0V"
            //   根因，2026-08-10 实测日志 40s 无一条 [Writeback] 行）。
            //   2026-08-20 用户决策：删除原版基础上改造的多线程后台计算死代码
            //   （getPool/cryptand$ensureComputeThread/线程池——时域求解禁用后
            //   无调用点），仅保留主线程 preTick → cryptand$doComputeRound。写入
            //   目标也仅剩自管宿主（TerminalRegistry 端子测试点），原版节点完全
            //   禁止写入。
            if (mixinClassName.contains("WorldNetworksMixin")) {
                System.out.println("[Cryptand] Mixin WorldNetworksMixin"
                        + " → INJECTED (writeback-driver, always)");
                return true;
            }
            // ===== ElectricWireOpenCircuitMixin：始终注入 =====
            // 悬空导线电流保护（任一端孤立节点 → current()=0）。相量模式
            // （enableCryptandSolver=true）正需要它：时域禁用后导线电流基于
            // 回写电压，孤立节点电压保持 0 → (V-0)×G 虚假大电流 → 烧线。
            // 之前落入"其余并行求解组"被 enableCryptandSolver=true 跳过 →
            // 悬空保护从未生效（"依然会烧导线"根因）。它只判 current() 返回值，
            // 不与 PowerGrid 多线程/自研求解冲突。
            if (mixinClassName.contains("ElectricWireOpenCircuitMixin")) {
                System.out.println("[Cryptand] Mixin ElectricWireOpenCircuitMixin"
                        + " → INJECTED (open-circuit, always)");
                return true;
            }
            // ===== Cryptand 求解器模式必需 Mixin：始终注入 =====
            // 以下 Mixin 是 enableCryptandSolver=true（自研相量求解接管）时的
            // 【核心功能】，曾落入"其余并行求解组"被 SKIPPED → 从未生效：
            //   - GoldenWireNoBurnMixin：禁用原版导线烧毁（原版 I²R 温度在时域
            //     禁用下因未写回节点 0V vs 源电压 → 虚假大电流误烧 → 用户反复
            //     遇到"打掉负载烧线"根因之一！isOverheated() 恒 false）
            //   - VoltageSourceCouplingNoWriteMixin / CurrentSourceNodeNoWriteMixin：
            //     禁用原版源 setVoltage/setCurrent 写入（否则端子电压显示原版值
            //     vs Cryptand 未回写端 0V → 虚假大电流烧线）
            //   - FanSpeedMixin：风扇电流驱动（引擎端子电压算真实电流 I=|Va-Vb|/|Z|，
            //     开路等电位 → I=0 不转；被跳过 = 风扇仍按原版电压差驱动 → 开路误转）
            //   - ElectricBlockEntityRemoveMixin：电气方块移除 → 网络级失效重建
            //     （打掉负载后 CACHES 必须 miss，否则旧 ctx 复用 → 悬空端错误
            //     建模 → 烧线/误转）
            //   - FanDiagnosticMixin：诊断（[FanDiag] 日志）；[MotorDiag] 已由
            //     GeneratorCouplingAcMixin 承担；ElectricMotorTickMixin 已废弃移除
            //     （2026-08-26 启动 FATAL "was not found" 根因）
            //   - VirtualDeviceSnapshotMixin：破坏删虚拟快照（setRemoved 注入——
            //     1.21.1 BlockEntity 无 onLoad/onChunkUnloaded，已移除这两个无效
            //     注入；快照保存移到构建时 Assembler.bindAll）
            // 这些 Mixin 内部均有 ENABLE_CRYPTAND_SOLVER 分支：false 时自动回退
            // 原版行为（原版多线程时域模式），注入安全、无冲突。
            if (mixinClassName.contains("GoldenWireNoBurnMixin")
                    || mixinClassName.contains("VoltageSourceCouplingNoWriteMixin")
                    || mixinClassName.contains("CurrentSourceNodeNoWriteMixin")
                    || mixinClassName.contains("FanSpeedMixin")
                    || mixinClassName.contains("FanDiagnosticMixin")
                    || mixinClassName.contains("VirtualDeviceSnapshotMixin")
                    || mixinClassName.contains("ElectricBlockEntityRemoveMixin")) {
                System.out.println("[Cryptand] Mixin "
                        + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)
                        + " → INJECTED (solver-core, always)");
                return true;
            }
            // ===== Cryptand 完全接管 Mixin：始终注入 =====
            // ⚠ 2026-08-13 修复：这三个 Mixin 曾落入"其余并行求解组"被
            // enableCryptandSolver=true 跳过 → 从未注入（用户实测"放置导线
            // 还是原版"/"电压全 0"根因）：
            //   - OwnedFloatingNodeGetVoltageMixin：自管模式接管设备端子电压
            //     （TerminalRegistry → 自管宿主）；非自管自动回退原版
            //   - WireEntityTakeoverMixin：转换后删除实体时拦截级联
            //   - WireItemUseOnMixin：放置导线直接写自管图（不创建原版实体）
            //   - HeaterBlockEntityMixin：消灭原版加热器热模型推进（electricalTick
            //     HEAD cancel——原版 applyPower 功率注入温度禁用，温度由引擎
            //     HeaterAssembler→MotorModel+DeviceThermalStore 相量 I²R 推进）
            //   - ThermometerBlockEntityMixin：方块温度计接管（temperature() 直接读
            //     Cryptand 温度——DeviceThermalStore/TransformerHeatStore/WireThermalStore，
            //     不写回原版 ThermalBehaviour，2026-08-21 用户要求全部接管）
            // 三者内部均有 PowerGridWireConverter.isEnabled() 门控，非自管
            // 模式自动回退原版行为 → 注入安全、无冲突。
            if (mixinClassName.contains("OwnedFloatingNodeGetVoltageMixin")
                    || mixinClassName.contains("WireEntityTakeoverMixin")
                    || mixinClassName.contains("WireItemUseOnMixin")
                    || mixinClassName.contains("ElectricBlockEntityTakeoverMixin")
                    || mixinClassName.contains("MultimeterItemMixin")
                    || mixinClassName.contains("HeaterBlockEntityMixin")
                    || mixinClassName.contains("ThermometerBlockEntityMixin")) {
                System.out.println("[Cryptand] Mixin "
                        + mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)
                        + " → INJECTED (full-takeover, always)");
                return true;
            }
            // 其余并行求解组（NativeMNAMixin / AbstractElectricWireMixin 等）：
            // 开启 enableCryptandSolver（自研求解器）后禁用 —— 自研引擎自带多线程逻辑，
            // 与 PowerGrid 后台线程池并行求解冲突（双重调度/线程重入）。
            // 因此本组注入条件：!enableCryptandSolver（旧 PowerGrid 并行开关
            // enablePowergridAsyncNetwork 已废除，默认始终注入）
            boolean useCryptandSolver = isCryptandSolver();
            String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            if (useCryptandSolver) {
                System.out.println("[Cryptand] Mixin " + shortName
                        + " → SKIPPED (enableCryptandSolver=true, 禁用 PowerGrid 多线程)");
                return false;
            }
            System.out.println("[Cryptand] Mixin " + shortName
                    + " → INJECTED (powergrid-parallel, default)");
            return true;
        }
        // 其余 Mixin 常驻
        String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        System.out.println("[Cryptand] Mixin " + shortName + " → INJECTED (always)");
        return true;
    }

    /**
     * 从 config/cryptand/circuit-simulation.toml 直接读取 enableCryptandSolver（自研求解器替换开关）。
     * 开启后自动禁用 PowerGrid 多线程并行求解（自研引擎自带多线程逻辑，避免冲突）。
     */
    private static boolean isCryptandSolver() {
        if (cryptandSolver != null) return cryptandSolver;
        cryptandSolver = readConfigBool(CONFIG_DIR + "circuit-simulation.toml",
                "enableCryptandSolver", true);
        return cryptandSolver;
    }

    /**
     * 从 config/cryptand/circuit-simulation.toml 读取 enableCreateStressLimit
     * （Create 应力上限 Mixin 注入开关；false 跳过 → 恢复 Create 原版行为）。
     * 公开：供 create 功能 Mixin 子管理（CreateMixinPlugin）复用。
     */
    public static boolean isCreateStressLimit() {
        if (createStressLimit != null) return createStressLimit;
        createStressLimit = readConfigBool(CONFIG_DIR + "circuit-simulation.toml",
                "enableCreateStressLimit", true);
        return createStressLimit;
    }

    /** 从 config/cryptand/powergrid.toml 读取 enablePowergridSupport（交错电网接管开关）。 */
    private static boolean isPowergridSupport() {
        if (powergridSupport != null) return powergridSupport;
        powergridSupport = readConfigBool(CONFIG_DIR + "powergrid.toml",
                "enablePowergridSupport", true);
        return powergridSupport;
    }

    /** 通用：从指定 TOML 读取布尔配置，失败时回退默认值 */
    private static boolean readConfigBool(String path, String key, boolean defaultValue) {
        try {
            Path p = Paths.get(path);
            if (!p.toFile().exists()) {
                System.out.println("[Cryptand] Config file not found, using default "
                        + key + "=" + defaultValue);
                return defaultValue;
            }
            try (FileConfig config = FileConfig.of(p)) {
                config.load();
                boolean val = config.getOrElse(key, defaultValue);
                System.out.println("[Cryptand] Config " + key + "=" + val);
                return val;
            }
        } catch (Exception e) {
            System.out.println("[Cryptand] Failed to read config " + key + ": " + e.getMessage()
                    + " — using default: " + defaultValue);
            return defaultValue;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {}
}
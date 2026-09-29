/**
 * ===== 变压器：交流化改造 + 完全接管（完整算法替换） =====
 *
 * PowerGrid 原版变压器是纯电阻时域模型（primaryStray/mutualInductance 折成电阻，
 * Tr2P2S 静态导纳矩阵），直流也能"变压"（非物理）。原版靠 setRatio 动态改矩阵，
 * 实测在 dev 环境不可靠（5e-9 直连漏电 + 内部 index 节点奇异 → 直流泄漏 + 变压失效）。
 *
 * 本 mixin 直接【替换整个 buildCircuit】，按频率决定拓扑：
 *   - 交流（频率 > 0）→ 原版同款变压器：primaryStray 电阻 + mutualInductance 电阻
 *     + Tr2P2S 静态 ratio=N2/N1 耦合（构建时定死，无需动态 setRatio）
 *   - 直流（频率 = 0）→ 副边完全悬空（只建原边回路，副边端子不连任何东西，
 *     物理上不可能有电压 → 100% 隔直）
 *
 * 频率类别（交流/直流）变化时，tick 触发 rebuildCircuit 切换拓扑。
 *
 * 【完全接管】2026-08-11：禁止原版一切电气/发热/声音更新，全部由 Cryptand 驱动：
 *   - tick() 内 ElectricWire.current() → 重定向为 0：原版 lastCurrent/power 恒 0，
 *     applyTickPower(0) 无发热（温度完全由 TransformerHeatStore 相量铜损+铁损驱动）
 *   - tickAudio() 内 SoundScapes.play(HUM) → 重定向为空：原版嗡鸣不播
 *     （Cryptand SynthHumSound 合成波形接管，音量∝电流、音调∝频率）
 *   - electricalTick() → HEAD 取消：原版 splitting transformer 更新完全禁用
 *   - buildCircuit → HEAD 取消：原版时域电路完全禁用
 *   保留 super.tick()（SmartBlockEntity 行为系统：tick 行为/渲染/同步）与
 *   read/write（NBT 持久化）——这两者不是"电气更新"，必须保留。
 *
 * 注意：mixin 注入的实例字段可能被 Architectury Transformer 破坏（dev 环境），
 * 因此所有状态一律用【静态 ConcurrentHashMap】，字段读写用反射（方法调用不受影响）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.core.sound.TransformerHumSoundInstance;
import com.hdf.cryptand.neoforge.core.sound.TransformerSoundPayload;
import com.hdf.cryptand.neoforge.core.sound.TransformerSoundState;

import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.TransformerCoupling;
import org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity;
import org.patryk3211.powergrid.electricity.transformer.TransformerCoilParameters;
import org.patryk3211.powergrid.utility.sound.SoundScapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import com.hdf.cryptand.neoforge.powergrid.measurement.EngineMeasurements;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.sound.SoundModel;
import com.hdf.cryptand.neoforge.powergrid.sound.SynthHumSound;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerHeatStore;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerReadoutStore;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(targets = "org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity", remap = false)
public abstract class TransformerBlockEntityMixin {

    /** 频率解析刷新间隔（tick） */
    private static final long FREQ_RESOLVE_INTERVAL = 10;

    /** 直流↔交流切换后防抖（tick），避免 rebuild 风暴 */
    private static final int SWITCH_COOLDOWN_TICKS = 10;

    /** 每个变压器方块位置：缓存频率 / 上次解析 tick / 最近构建类别(交流?) / 上次切换 tick。
     *  key 用 BlockPos（客户端/服务端同 JVM 共享同一 key）→ 两端 buildCircuit 拓扑一致 */
    private static final Map<BlockPos, Double> FREQ_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_RESOLVE_TICK = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Boolean> LAST_BUILT_AC = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> LAST_SWITCH_TICK = new ConcurrentHashMap<>();

    /** 每个变压器方块位置 → 客户端合成声音实例（合成波形：音量∝电流、音调∝频率） */
    private static final Map<BlockPos, SynthHumSound> SYNTH_HUM = new ConcurrentHashMap<>();

    /** 服务端相量电压读数写入间隔（tick） */
    private static final long READOUT_INTERVAL = 10;

    // ============ 完全接管：禁止原版一切电气/发热/声音更新 ============

    /**
     * tick() 内 primaryStray/mutualInductance.current() → 恒 0：
     * 原版 lastCurrent 累加 = 0、power(I²R) 累加 = 0 → applyTickPower(0) 无发热。
     * 温度完全由 TransformerHeatStore（相量真实铜损+铁损）驱动，杜绝原版
     * 时域/回写电流导致的虚假 I²R 发热（烧线根源之一）。
     */
    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lorg/patryk3211/powergrid/electricity/sim/ElectricWire;current()D"))
    private static double cryptand$noVanillaCurrent(ElectricWire wire) {
        return 0.0;
    }

    /**
     * tickAudio() 内 SoundScapes.play(HUM, pos, 1, lastCurrent/20) → 空操作：
     * 原版 HUM 声音完全禁用，改由 SynthHumSound 合成波形接管
     * （音量∝电流、音调∝频率，见 cryptand$transformerHum）。
     */
    @Redirect(method = "tickAudio",
            at = @At(value = "INVOKE", target = "Lorg/patryk3211/powergrid/utility/sound/SoundScapes;play(Lorg/patryk3211/powergrid/utility/sound/SoundScapes$AmbienceGroup;Lnet/minecraft/core/BlockPos;FF)V"))
    private static void cryptand$noVanillaHum(SoundScapes.AmbienceGroup group,
            net.minecraft.core.BlockPos pos, float volume, float pitch) {
        // 原版 HUM 禁用（Cryptand 合成波形接管）
    }

    /**
     * electricalTick() → HEAD 取消：原版 splitting transformer 的
     * couplingI1/couplingI2 收敛更新完全禁用（Cryptand 已置 null，
     * 但仍彻底封死该路径，杜绝任何原版电气状态机介入）。
     */
    @Inject(method = "electricalTick", at = @At("HEAD"), cancellable = true)
    private void cryptand$noVanillaElectricalTick(CallbackInfo ci) {
        ci.cancel();
    }

    // ============ buildCircuit 完全替换 ============

    @Inject(method = "buildCircuit", at = @At("HEAD"), cancellable = true)
    private void cryptand$buildCircuit(IElectricEntity.CircuitBuilder builder, CallbackInfo ci) {
        try {
            Object self = this;
            TransformerBlockEntity t = (TransformerBlockEntity) (Object) this;
            TransformerCoilParameters pc = t.getPrimary();
            TransformerCoilParameters sc = t.getSecondary();
            // 线圈未定义（单线圈/空）→ 交给原版处理
            if (pc == null || sc == null || !pc.isDefined() || !sc.isDefined()) return;

            Double f = FREQ_CACHE.get(cryptand$key(self));
            double minHz = ConfigPowerGrid.TRANSFORMER_MIN_FREQUENCY_HZ.get();
            // 默认直流隔离：只有明确检测到 ≥ minHz 的频率才构建交流变压器。
            // （若默认交流，0Hz/未知源在 tick 更新缓存前会短暂变压传输）
            boolean ac = f != null && f >= minHz;
            buildTransformerCircuit(builder, t, pc, sc, ac);
            LAST_BUILT_AC.put(cryptand$key(self), ac);
            ci.cancel();
        } catch (Throwable ignored) {
            // 构建失败 → 不 cancel，原版兜底
        }
    }

    /** 每个变压器方块：上次 buildCircuit 完成后触发重建的时间（节流防风暴） */
    private static final Map<BlockPos, Long> BUILD_CIRCUIT_LAST = new ConcurrentHashMap<>();
    private static final long BUILD_CIRCUIT_THROTTLE_MS = 1000;

    /**
     * buildCircuit 完成后（替换版或原版兜底）→ 节流触发 Cryptand 拓扑重建：
     * 线圈参数变化/初始化（单线圈→双线圈、空→有线圈、匝数/端子变化）→
     * topoVersion++ → 下轮缓存失效重建 → 变压器最新状态【自然建模】。
     * 用户要求（2026-08-12）：变压器两边独立网络、通过互感元件提供能源，
     * 处理天然解决——无需"未就绪标记"，参数就绪后由 rebuildCircuit 驱动重建。
     */
    @Inject(method = "buildCircuit", at = @At("RETURN"))
    private void cryptand$onBuildCircuitReturn(IElectricEntity.CircuitBuilder builder, CallbackInfo ci) {
        try {
            Object self = this;
            BlockPos pos = (BlockPos) reflectField(self, "worldPosition");
            long now = System.currentTimeMillis();
            Long last = BUILD_CIRCUIT_LAST.get(pos);
            if (last == null || now - last >= BUILD_CIRCUIT_THROTTLE_MS) {
                BUILD_CIRCUIT_LAST.put(pos, now);
                CryptandTopologyManager
                        .get().markTopologyChanged();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 构建变压器电路。交流 = 原版同款（Tr2P2S 静态 ratio 耦合）；
     * 直流 = 副边完全悬空（物理隔直）。电磁参数完全复刻原版 buildCircuit。
     */
    @Unique
    private static float buildTransformerCircuit(IElectricEntity.CircuitBuilder builder,
            TransformerBlockEntity t, TransformerCoilParameters pc, TransformerCoilParameters sc,
            boolean ac) {
        builder.setTerminalCount(4);
        int n1 = pc.getTurns();
        int n2 = sc.getTurns();
        double coreAl = t.coreAl();
        double cf = t.couplingFactor();
        double L1 = n1 * (double) n1 * coreAl;
        double L2 = n2 * (double) n2 * coreAl;

        // 少匝数侧当"原边"（与原版一致，ratio 恒 ≥1）
        TransformerCoilParameters coil1, coil2;
        if (n1 > n2) {
            coil1 = sc; coil2 = pc;
            int tn = n1; n1 = n2; n2 = tn;
            double tl = L1; L1 = L2; L2 = tl;
        } else {
            coil1 = pc; coil2 = sc;
        }
        float ratio = n2 / (float) n1;
        double M = cf * L1;
        float secondaryStray = (float) (L2 - ratio * (double) ratio * M);

        FloatingNode internalNode = builder.addInternalNode();
        FloatingNode term1 = builder.terminalNode(coil1.getTerminal1());
        FloatingNode term2 = builder.terminalNode(coil1.getTerminal2());

        // 时域已禁用：primaryStray/mutualInductance 仅保持网络结构与同步包格式。
        // 原版 tick() 的发热（I²R）与声音（lastCurrent/20）都基于它们的 current()
        // ——而 current() 基于【回写相量电压】。若按原版漏感电阻算，I²R 可能巨大
        // → 变压器错误烧毁。这里把电阻放大到 1e9Ω：I²R = V²/R ≈ 0（发热归零），
        // lastCurrent ≈ 0（原版声音不触发，我们用合成波形）。
        // 温度完全由 TransformerHeatStore（相量真实铜损+铁损）驱动。
        ElectricWire ps = builder.connect(1e9f, term1, internalNode);
        ElectricWire mi = builder.connect(1e9f, internalNode, term2);
        setField(t, "primaryStray", ps);
        setField(t, "mutualInductance", mi);

        if (ac) {
            FloatingNode s1 = builder.terminalNode(coil2.getTerminal1());
            FloatingNode s2 = builder.terminalNode(coil2.getTerminal2());
            TransformerCoupling c = builder.couple(ratio, secondaryStray, internalNode, term2, s1, s2);
            setField(t, "coupling", c);
        } else {
            // 直流隔直：副边端子节点仍创建（保持 externalNodes=4，同步包格式不变，
            // 避免 AC/DC 切换时 PowerGrid 同步 Buffer read overrun），
            // 但不连内部任何 wire → 原副边电气隔离（无电流通路，GMin 兜底不奇异）。
            builder.terminalNode(coil2.getTerminal1());
            builder.terminalNode(coil2.getTerminal2());
            setField(t, "coupling", null);
        }
        setField(t, "couplingI1", null);
        setField(t, "couplingI2", null);
        return ac ? ratio : 0f;
    }

    /**
     * 线圈参数懒初始化（客户端适配，2026-08-15）：
     * 原版 primaryCoil/secondaryCoil 只在 buildCircuit() 里懒初始化（服务端构建
     * 电路时触发）。客户端从不调 buildCircuit → primaryCoil 恒 null → isTerminalUsed/
     * hasPrimary/getPrimary NPE（CryptandWirePlacement 客户端分支实测崩溃）。
     * 本架构适配：tick HEAD（客户端/服务端都跑，Create 行为系统驱动）用反射确保
     * 初始化——实例字段注入会被 Architectury Transformer 破坏（dev 环境），
     * 字段读写一律走反射（reflectField/setField 支持父类查找）。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void cryptand$ensureCoilsTick(CallbackInfo ci) {
        try {
            if (reflectField(this, "primaryCoil") == null) {
                setField(this, "primaryCoil", new TransformerCoilParameters());
                setField(this, "secondaryCoil", new TransformerCoilParameters());
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$acTransformerTick(CallbackInfo ci) {
        try {
            Object self = this;
            Object levelObj = reflectField(self, "level");
            if (levelObj == null) return;
            Level level = (Level) levelObj;

            // 客户端/服务端都解析频率（同一逻辑，两端同 JVM 共享静态缓存），
            // 保证 buildCircuit 在两端构建相同拓扑（否则 PowerGrid 同步包不匹配 → Buffer overrun）
            double freq = resolveFrequency(self, level);
            // resolveFrequency 内部已按服务端优先写入共享缓存（BlockPos key）
            FREQ_CACHE.putIfAbsent(cryptand$key(self), freq);
            double minHz = ConfigPowerGrid.TRANSFORMER_MIN_FREQUENCY_HZ.get();
            boolean acNow = freq >= minHz; // 频率 ≥ 最低通过频率才变压（含 0 的低频/直流隔直）

            // 服务端：每 10 tick 计算初/次级【相量】电压（RMS）存入静态表，供 goggle
            // 读取（单机客户端/服务端同 JVM → 跨线程安全读；精确匝数比，无时域失真）。
            if (!level.isClientSide && level.getGameTime() % READOUT_INTERVAL == 0) {
                try {
                    TransformerBlockEntity t = (TransformerBlockEntity) (Object) this;
                    TransformerCoilParameters p2 = t.getPrimary();
                    TransformerCoilParameters s2 = t.getSecondary();
                    if (p2 != null && s2 != null && p2.isDefined() && s2.isDefined()) {
                        BlockPos tpos = (BlockPos) reflectField(self, "worldPosition");
                        double v1 = EngineMeasurements.voltageAcross(level, tpos,
                                p2.getTerminal1(), p2.getTerminal2(), freq, level.getGameTime());
                        double v2 = EngineMeasurements.voltageAcross(level, tpos,
                                s2.getTerminal1(), s2.getTerminal2(), freq, level.getGameTime());
                        TransformerReadoutStore.put(tpos, v1 / Math.sqrt(2.0), v2 / Math.sqrt(2.0));
                        try {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[TfReadout] pos={} freq={} v1={} v2={} hsFreq={} hsI={}",
                                    tpos, String.format("%.1f", freq),
                                    String.format("%.1f", v1 / Math.sqrt(2.0)),
                                    String.format("%.1f", v2 / Math.sqrt(2.0)),
                                    String.format("%.1f", TransformerHeatStore.getFreq(tpos)),
                                    String.format("%.2f", TransformerHeatStore.getCurrent(tpos)));
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 真实变压器发热：每 tick 施加铜损+铁损（PhasorEngine 相量求解写入，
            // 次级悬空 → 铜损≈0 → 无温升）。ThermalBehaviourWindingMixin 统一钳制。
            if (!level.isClientSide) {
                try {
                    BlockPos hpos = (BlockPos) reflectField(self, "worldPosition");
                    double cu = TransformerHeatStore.getCuLoss(hpos);
                    double core = TransformerHeatStore.getCoreLoss(hpos);
                    // ⚠ 2026-09-12 用户："温度不再使用原版路径，全部使用自管"——
                    //   原先这里把 TransformerHeatStore 的铜损+铁损再 tbh.applyTickPower
                    //   注入【原版】容器 = 与自管侧重复加热（EngineThermalCompute
                    //   .computeTransformerHeatOne 已用同一组损耗推进
                    //   TransformerHeatStore 的温度模型）。原版容器已随
                    //   ThermalBehaviour.tick 停用而退役，这里不再注入。
                    // 声音参数：每 READOUT_INTERVAL 发给附近玩家（声音合成放客户端）。
                    // 服务端只传真实值（freq/iP/iS），客户端 TransformerSoundState 驱动
                    // SynthHumSound——多人服务器下客户端不再读服务端静态表。
                    if (level.getGameTime() % READOUT_INTERVAL == 0) {
                        double sf = TransformerHeatStore.getFreq(hpos);
                        double siP = TransformerHeatStore.getCurrent(hpos);
                        double siS = TransformerHeatStore.getSecCurrentRaw(hpos);
                        try {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[TfSoundSrv] pos={} freq={} iP={} iS={}",
                                    hpos, String.format("%.1f", sf),
                                    String.format("%.2f", siP), String.format("%.3f", siS));
                        } catch (Throwable ignored) {
                        }
                        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersNear(
                                    sl, null,
                                    hpos.getX() + 0.5, hpos.getY() + 0.5, hpos.getZ() + 0.5,
                                    48.0,
                                    new TransformerSoundPayload(
                                            hpos, sf, siP, siS));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 频率类别变化（交流↔直流）→ 两端都 rebuild 切换拓扑（防抖）
            Boolean built = LAST_BUILT_AC.get(cryptand$key(self));
            if (built != null && built != acNow) {
                int gameTime = (int) level.getGameTime();
                Integer lastSwitch = LAST_SWITCH_TICK.get(cryptand$key(self));
                if (lastSwitch == null || gameTime - lastSwitch >= SWITCH_COOLDOWN_TICKS) {
                    LAST_SWITCH_TICK.put(cryptand$key(self), gameTime);
                    ElectricBlockEntity ebe = (ElectricBlockEntity) (Object) this;
                    if (ebe.getElectricBehaviour() != null) {
                        if (!level.isClientSide) {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "TransformerMixin SWITCH pos={} freq={} → {}",
                                    reflectField(self, "worldPosition"), freq, acNow ? "AC" : "DC");
                        }
                        ebe.getElectricBehaviour().rebuildCircuit(true);
                    }
                }
            }

        } catch (Throwable ignored) {
        }
    }

    /** 回退播放反射版（2026-08-14 服务端兼容修复）：mixin 方法体不能直接引用
     *  net.minecraft.client.Minecraft（服务端无此类，mixin 应用阶段即 ClassNotFound
     *  → 整个 powergrid mod 加载失败）。改用反射，仅客户端运行时执行。 */
    @Unique
    private static void playHumFallbackReflect(BlockPos pos) {
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            Object sm = mc.getClass().getMethod("getSoundManager").invoke(mc);
            Class<?> siCls = Class.forName(
                    "net.minecraft.client.resources.sounds.SoundInstance");
            Class<?> instCls = Class.forName(
                    "TransformerHumSoundInstance");
            Object sound = instCls.getConstructor(net.minecraft.core.BlockPos.class)
                    .newInstance(pos);
            sm.getClass().getMethod("play", siCls).invoke(sm, sound);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[TfHum] fallback reflect FAILED: {}", String.valueOf(t));
        }
    }

    // ===== 变压器电磁嗡鸣（SoundScapes 原版管道，音量∝电流，音调∝频率） =====

    /** 客户端 tickAudio TAIL：按真实状态合成播放（音量∝电流，音调∝频率） */
    @Inject(method = "tickAudio", at = @At("TAIL"))
    private void cryptand$transformerHum(CallbackInfo ci) {
        try {
            Object self = this;
            Object levelObj = reflectField(self, "level");
            if (!(levelObj instanceof Level level) || !level.isClientSide) return;
            Object posObj = reflectField(self, "worldPosition");
            if (!(posObj instanceof BlockPos pos)) return;

            double freq = TransformerSoundState.getFreq(pos);
            double iP = TransformerSoundState.getCurrent(pos);
            double iS = TransformerSoundState.getSecCurrentRaw(pos); // 瞬时：次级断开立即静音
            // 原版阈值放宽（2026-08-12）：只要有励磁电流（iP>0.02A，空载也有磁致伸缩
            // 嗡鸣）就发声，音量∝初级电流——空载微弱、带载响亮。
            // 不再用 iS 限制（次级悬空时 STATE 已由统一发热处理正确清零）。
            if (freq <= 0 || iP <= 0.02) {
                // 条件不满足 → 停止合成声音 / 标记 SoundManager 实例停止
                try {
                    SynthHumSound sh = SYNTH_HUM.get(pos);
                    if (sh != null) sh.stop();
                } catch (Throwable ignored) {
                }
                TransformerHumSoundInstance.STARTED
                        .put(pos, false);
                if ((iP > 1.0 || iS > 0.05 || freq > 0) && (level.getGameTime() & 0x3F) == 0) {
                    try {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[TfSoundCli] STOP freq={} iP={} iS={}",
                                String.format("%.1f", freq),
                                String.format("%.2f", iP), String.format("%.3f", iS));
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // 声音大小与整体电流有关（初级+次级总电流，统一规则）——空载微弱、带载响亮
            float volume = SoundModel
                    .volumeFromCurrent(Math.abs(iP) + Math.abs(iS));
            // 音调∝输入电频率（50Hz → 1.0，统一规则：声音频率与输入电频率有关）
            float pitch = SoundModel.pitchFromFrequency(freq);
            // ===== 客户端合成（用户要求：根据频率电流合成，在客户端） =====
            // ⚠ 健壮化：SynthHumSound 构造若抛异常（OpenAL 初始化失败）→ sh=null
            //   → 绝不能 NPE（否则 fallback 不触发且无日志）。每步记录 [TfHum] 状态。
            SynthHumSound sh = SYNTH_HUM.get(pos);
            if (sh == null) {
                try {
                    sh = new SynthHumSound(() -> cryptand$transformerAlive(level, pos));
                    SYNTH_HUM.put(pos, sh);
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[TfHum] synth created failed={}", sh.failed);
                } catch (Throwable t) {
                    sh = null;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[TfHum] synth create FAILED: {}", String.valueOf(t));
                }
            }
            if (sh == null || sh.failed) {
                // OpenAL 不可用（无 LWJGL capabilities / 构造异常）→ 回退 MC SoundManager
                try {
                    if (!Boolean.TRUE.equals(
                            TransformerHumSoundInstance.STARTED
                                    .getOrDefault(pos, false))) {
                        TransformerHumSoundInstance.STARTED
                                .put(pos, true);
                        playHumFallbackReflect(pos);
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[TfHum] fallback SoundManager play");
                    }
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[TfHum] fallback FAILED: {}", String.valueOf(t));
                }
            } else {
                // 合成波形播放：音量∝电流（不乘 AMBIENT，防用户环境音量为 0 无声）
                try {
                    sh.setPosition(pos.getCenter().x, pos.getCenter().y, pos.getCenter().z);
                    sh.update(volume, pitch);
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[TfHum] synth update FAILED: {}", String.valueOf(t));
                }
            }
            if ((level.getGameTime() & 0x3F) == 0) {
                try {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[TfSoundCli] PLAY freq={} iP={} iS={} vol={} pitch={}",
                            String.format("%.1f", freq),
                            String.format("%.2f", iP), String.format("%.3f", iS),
                            String.format("%.2f", volume), String.format("%.2f", pitch));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 来源自检：变压器方块仍存在（爆炸/卸载 → false → 合成声音自动关闭） */
    @Unique
    private static boolean cryptand$transformerAlive(Level level, BlockPos pos) {
        try {
            return level != null && level.getBlockEntity(pos) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 解析变压器输入频率：只检测【直接连接本变压器方块】的导线网络
     * （从锚定在本方块上的导线端点 BFS，不跨其他变压器）。
     * 其他网络（隔着其他变压器）的频率不会渗入。返回 0 = 直流 / 无 AC 源。
     *
     * 缓存 key = 方块位置（BlockPos，客户端/服务端同 JVM 共享）→ 两端 buildCircuit 拓扑一致。
     * 客户端 BFS 解析失败（0）时【不覆盖】服务端已解析的有效频率（服务端网络级可靠）。
     */
    @Unique
    private static double resolveFrequency(Object self, Object level) {
        try {
            BlockPos key = cryptand$key(self);
            long tick = ((Level) level).getGameTime();
            Long last = LAST_RESOLVE_TICK.get(key);
            if (last != null && tick - last < FREQ_RESOLVE_INTERVAL) {
                Double c = FREQ_CACHE.get(key);
                return c == null ? 0 : c;
            }
            LAST_RESOLVE_TICK.put(key, tick);

            double freq = 0;
            if (!((Level) level).isClientSide) {
                // 服务端：纯网络级（禁止 BFS）。从本变压器 4 端子节点反查所属
                // ElectricalNetwork，遍历网络节点找 AC 源（频率=网络参数）。
                // 原边/副边各自独立网络：任一网络有 AC 源 → AC（全部直流 → 0）。
                try {
                    ElectricBehaviour beh = ((ElectricBlockEntity) self).getElectricBehaviour();
                    if (beh != null) {
                        for (int t = 0; t < 4; t++) {
                            try {
                                double f = MultimeterDebug.getNetworkFrequencyHz(
                                        (Level) level, beh.getTerminal(t));
                                if (f > 0) { freq = f; break; } // 命中 AC 源
                                // f == 0（该网络直流）或 -1（未挂网络）→ 继续其他端子
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else {
                // 客户端：Dummy 网络不可用（所有电路共享一网络，网络级会串扰）
                // → 仅客户端沿导线拓扑（可靠基准，不影响服务端）
                freq = MultimeterDebug.getTransformerNetworkFrequencyHz((Level) level, key);
                // 客户端 BFS 失败（0）→ 沿用服务端有效频率（服务端网络级可靠）
                if (freq <= 0) {
                    Double serverF = FREQ_CACHE.get(key);
                    if (serverF != null && serverF > 0) {
                        return serverF;
                    }
                }
            }
            FREQ_CACHE.put(key, freq);
            return freq;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 变压器方块位置 key（客户端/服务端同 JVM 共享同一 key → 拓扑一致） */
    @Unique
    private static BlockPos cryptand$key(Object self) {
        try {
            Object p = reflectField(self, "worldPosition");
            return p instanceof BlockPos bp ? bp.immutable() : BlockPos.ZERO;
        } catch (Throwable t) {
            return BlockPos.ZERO;
        }
    }

    /**
     * 修复直流隔离模式的护目镜 NPE：原版 addToGoggleTooltip 在 coupling==null 时
     * 直接访问 couplingI1（假定必为 splitting 模式），直流隔离时两者皆 null → NPE。
     * 此处拦截并显示状态。
     */
    /**
     * 机械动力护目镜显示增强（2026-08-10 修复"无法显示参数"）：
     * 原版 addToGoggleTooltip 要求【潜行】才显示（isPlayerSneaking），且只显示
     * 漏感/互感的电阻值——用户戴眼镜悬停看不到任何参数。
     * 本注入【始终显示】并展示实用参数：
     *   - 匝数比（简化分数）
     *   - 模式（交流变压 / 直流隔离）
     *   - 初级/次级端口电压（各自网络内端子电压差，RMS 量级）
     */
    @Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
    private void cryptand$goggleTooltip(List<net.minecraft.network.chat.Component> tooltip,
            boolean isPlayerSneaking, CallbackInfoReturnable<Boolean> cir) {
        try {
            TransformerBlockEntity t = (TransformerBlockEntity) (Object) this;
            TransformerCoilParameters pc = t.getPrimary();
            TransformerCoilParameters sc = t.getSecondary();
            tooltip.add(net.minecraft.network.chat.Component.literal("变压器 Transformer")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            if (pc != null && sc != null && pc.isDefined() && sc.isDefined()) {
                int n1 = pc.getTurns();
                int n2 = sc.getTurns();
                if (n1 > 0 && n2 > 0) {
                    int g = gcd(n1, n2);
                    tooltip.add(net.minecraft.network.chat.Component.literal(
                                    "匝数比 " + (n1 / g) + ":" + (n2 / g))
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                }
            }
            Object coupling = reflectField(this, "coupling");
            Object couplingI1 = reflectField(this, "couplingI1");
            if (coupling == null && couplingI1 == null) {
                tooltip.add(net.minecraft.network.chat.Component.literal("模式: 直流隔离 (DC ISOLATED)")
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            } else {
                tooltip.add(net.minecraft.network.chat.Component.literal("模式: 交流变压 (AC)")
                        .withStyle(net.minecraft.ChatFormatting.GREEN));
            }
            // 运行状态（新增）：有频率且有初级电流 → 运行中；不处于任何模式 → 停止
            try {
                Object posObj = reflectField(this, "worldPosition");
                if (posObj instanceof BlockPos bpos2) {
                    double sf = TransformerHeatStore.getFreq(bpos2);
                    double si = TransformerHeatStore.getCurrent(bpos2);
                    boolean running = sf > 0 && si > 0.001;
                    tooltip.add(net.minecraft.network.chat.Component.literal(
                                    running ? "状态: 运行中 (RUNNING)" : "状态: 停止 (STOPPED)")
                            .withStyle(running ? net.minecraft.ChatFormatting.GREEN
                                    : net.minecraft.ChatFormatting.RED));
                }
            } catch (Throwable ignored) {
            }
            try {
                Object levelObj = reflectField(this, "level");
                Object posObj = reflectField(this, "worldPosition");
                if (levelObj instanceof Level lvl && posObj instanceof BlockPos ppos
                        && pc != null && sc != null && pc.isDefined() && sc.isDefined()) {
                    double v1 = 0, v2 = 0;
                    // 服务端相量读数表（服务端 tick 网络级相量计算，零 BFS）。
                    // 无读数/过期（多人服务器或未就绪）→ 显示未就绪，绝不做客户端 BFS。
                    TransformerReadoutStore.Readout ro = TransformerReadoutStore.get(ppos);
                    if (ro != null && System.currentTimeMillis() - ro.timeMs < 5000) {
                        v1 = ro.v1;
                        v2 = ro.v2;
                    } else {
                        v1 = Double.NaN;
                        v2 = Double.NaN;
                    }
                    tooltip.add(net.minecraft.network.chat.Component.literal(
                                    Double.isNaN(v1) ? "初级电压 未就绪" : String.format("初级电压 %.1f V", v1))
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                    tooltip.add(net.minecraft.network.chat.Component.literal(
                                    Double.isNaN(v2) ? "次级电压 未就绪" : String.format("次级电压 %.1f V", v2))
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                }
            } catch (Throwable ignored) {
            }
            cir.setReturnValue(true);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static int gcd(int a, int b) {
        while (b != 0) { int tmp = a % b; a = b; b = tmp; }
        return a;
    }

    /** 反射写字段（方法调用，不受 Architectury Transformer 字段赋值破坏影响） */
    @Unique
    private static void setField(Object target, String name, Object value) {
        try {
            Field f = null;
            Class<?> c = target.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) return;
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static Object reflectField(Object target, String name) {
        if (target == null) return null;
        Field f = null;
        Class<?> c = target.getClass();
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }
}

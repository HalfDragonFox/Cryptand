package com.hdf.cryptand.neoforge.cryptandsable.core.chamber;

import com.hdf.cryptand.neoforge.cryptandsable.api.chamber.ChamberBlockProvider;
import com.hdf.cryptand.neoforge.cryptandsable.api.chamber.ChamberData;
import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 气室管理器（ChamberManager）—— 虚拟容积容器（原版无，自实现）。
 *
 * <p><b>模型（模拟现实，简洁省内存/CPU）</b>：
 * 每个刚体一个聚合气室（单 entry Map）。气室 = 虚拟容积容器：
 * <ul>
 *   <li>{@code virtualVolume}：外壳包围体积（排开体积 → 决定浮力/升力）；</li>
 *   <li>{@code solidVolume}：内部实心方块体积（降低可容气空间）；</li>
 *   <li>{@code gasAmount} [mol] 气体量（加压/通入增加；失压泄漏下降）；</li>
 *   <li>{@code gasMolarMass} [g/mol] 气体类型（空气≈29 氢≈2 氦≈4）；</li>
 *   <li>{@code sealLevel} [0..1] 气密性（缺口→下降→失压）；</li>
 *   <li>{@code pressure} = nRT/V（内部气压；加压↔压缩改变）。</li>
 * </ul>
 *
 * <p><b>浮力净力</b>：F = ρ_env · g · V_virtual − m_gas · g（沿重力反向；气体越轻升力越大；
 * 加压→m_gas↑→升力略降，但密封结构按密闭体积算升力）。MVP 做抬升力 = (ρ_env·V − m_gas)·g。
 *
 * <p>本管理器只被核心 worker 线程访问（数据归属矩阵）。对外只暴露注册/操作接口。
 */
public final class ChamberManager {
    /** 内部可变状态（省分配，不用 record 池）。 */
    private static final class ChamberState {
        int chamberId = 1;
        double virtualVolume = 0.0;   // 外壳体积（排开）[m³]
        double solidVolume = 0.0;     // 实心方块体积 [m³]
        double gasAmount = 0.0;       // 气体摩尔数 [mol]
        double gasMolarMass = ChamberData.MOLAR_AIR;
        double sealLevel = 1.0;       // 气密性 0..1
        double temperature = 293.15;  // K
    }

    private static final Map<Integer, ChamberState> BODY_CHAMBERS = new ConcurrentHashMap<>();
    private static final Map<Object, ChamberBlockProvider> PROVIDERS = new ConcurrentHashMap<>();

    /** 理想气体常数 R [J/(mol·K)]。 */
    private static final double R = ChamberData.GAS_R;

    private ChamberManager() {
    }

    /** 注册一个"方块→气室属性"提供者（mod 调用，幂等）。 */
    public static void registerProvider(ChamberBlockProvider provider) {
        if (provider != null) {
            PROVIDERS.putIfAbsent(provider.getClass(), provider);
        }
    }

    /** 可容气空间 [m³]。 */
    private static double freeSpace(ChamberState c) {
        return Math.max(0.0, c.virtualVolume - c.solidVolume);
    }

    /** 当前内部气压 [Pa] = nRT/V。 */
    private static double pressure(ChamberState c) {
        double fs = freeSpace(c);
        return fs > 1e-9 ? c.gasAmount * R * c.temperature / fs : 0.0;
    }

    /**
     * 把一个结构（刚体）的体素聚合为气室并关联。
     * density 为各体素密度（>0 = 实心）。外壳体积 = 包围盒体积（体素数）；
     * 实心体积 = 非空体素数。初始气体 = 常压空气摩尔数（按 freeSpace 平衡到 1 大气压）。
     */
    public static void attachBodyChambers(int bodyId, double[] density, int sizeX, int sizeY, int sizeZ) {
        int total = Math.max(1, sizeX * sizeY * sizeZ);
        int solid = 0;
        double seal = 1.0;
        for (double d : density) {
            if (d > 0) {
                solid++;
            }
        }
        for (ChamberBlockProvider p : PROVIDERS.values()) {
            seal = Math.min(seal, Math.max(0.0, p.sealContribution()));
        }

        ChamberState c = new ChamberState();
        c.virtualVolume = total;
        c.solidVolume = solid;
        c.sealLevel = seal;
        // 常压平衡：P0 = 101325 Pa；初始 gasAmount = P0·freeSpace/(R·T)
        double fs = freeSpace(c);
        c.gasAmount = fs > 1e-9 ? (101325.0 * fs) / (R * c.temperature) : 0.0;
        BODY_CHAMBERS.put(bodyId, c);
    }

    /** 加压：设定目标内部气压 [Pa]，反解所需气体量 n=P·V/(R·T)。 */
    public static void pressurize(int bodyId, double targetPressurePa) {
        ChamberState c = BODY_CHAMBERS.get(bodyId);
        if (c == null) return;
        double fs = freeSpace(c);
        c.gasAmount = fs > 1e-9
                ? (Math.max(0.0, targetPressurePa) * fs) / (R * c.temperature)
                : 0.0;
    }

    /** 通入一定量气体 [mol]（不改压力目标，直接增量）。 */
    public static void addGas(int bodyId, double addedMol) {
        ChamberState c = BODY_CHAMBERS.get(bodyId);
        if (c != null) {
            c.gasAmount += Math.max(0.0, addedMol);
        }
    }

    /** 换气：修改气体类型（摩尔质量 [g/mol]）。 */
    public static void setGasType(int bodyId, double molarMassG) {
        ChamberState c = BODY_CHAMBERS.get(bodyId);
        if (c != null && molarMassG > 0.0) {
            c.gasMolarMass = molarMassG;
        }
    }

    /** 破封/缺口：降低气密性（失压开始）。damage 0..1（0.3=严重破损）。 */
    public static void breakSeal(int bodyId, double sealDecrease) {
        ChamberState c = BODY_CHAMBERS.get(bodyId);
        if (c != null) {
            c.sealLevel = Math.max(0.0, c.sealLevel - Math.max(0.0, sealDecrease));
        }
    }

    /** 撤销刚体气室（结构移除）。 */
    public static void detach(int bodyId) {
        BODY_CHAMBERS.remove(bodyId);
    }

    /**
     * 应用气室净升力到刚体（step 前，模拟器调用）。
     *
     * <p>净力 F = (ρ_env · V_virtual − m_gas) · g，方向沿重力反向（"上"）。
     * 未完全破封且容器内有气体才有升力（密封容器内轻于空气气体 → 抬升）。
     */
    public static void applyBuoyancy(RigidBodyState body, double mediumDensity, Vector3d gravity) {
        ChamberState c = BODY_CHAMBERS.get(body.runtimeId);
        if (c == null) return;

        if (c.sealLevel <= 0.0001) {
            return; // 完全失压 → 无升力
        }
        double g = gravity.length();
        if (g <= 1e-9) {
            return; // 无重力（宇宙）无浮力方向
        }
        Vector3d up = new Vector3d(gravity).normalize().negate();
        double gasMass = c.gasAmount * (c.gasMolarMass / 1000.0);
        double liftN = (mediumDensity * c.virtualVolume - gasMass) * g;
        if (liftN > 1e-9) {
            body.applyForce(up.mul(liftN));
        }
    }

    /** 取气室快照（对外查询；省分配由调用方决定）。 */
    public static ChamberData snapshot(int bodyId) {
        ChamberState c = BODY_CHAMBERS.get(bodyId);
        if (c == null) return null;
        return new ChamberData(c.chamberId, bodyId,
                c.virtualVolume, c.solidVolume, c.gasAmount, c.gasMolarMass,
                c.sealLevel, pressure(c), c.temperature,
                // liftForce 近似（无 env 时返回 0，供诊断；实际力在 applyBuoyancy）
                0.0);
    }

    public static void clear() {
        BODY_CHAMBERS.clear();
        PROVIDERS.clear();
    }
}
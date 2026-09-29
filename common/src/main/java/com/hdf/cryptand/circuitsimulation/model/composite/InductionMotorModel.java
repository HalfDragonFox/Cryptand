package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 单相 AC 感应电机模型（2026-08-28 用户：现实参数体系，普通电机专用）。
 * <p>
 * 现实规格（单相电容运转感应电机，2 极 / 50Hz / 230V AC）：
 *   - 同步转速 3000 RPM（ω_s=314.16）；额定输出 1 kW；效率 η=80%；功率因数 cosφ=0.8
 *   - 额定转速 ~2900 RPM（转差 ~3.3%）→ 额定转矩 T_rated = P/ω = 3.29 N·m
 *   - 应力【单独单位】：σ[SU] = T[N·m] × 16（用户：扭矩强制×16 作为应力）
 *   - 功率【物理】：P = T·ω（输出瓦特；不再 DC 的 P=σ·ω_r 规格映射）
 * <p>
 * 机械 = 【完整异步感应曲线】（重载难启动的真实特性，用户选"完整异步曲线"）：
 *   - 转矩-转速曲线（线性插值表 ×T_rated → {@link #torqueFactor}）：
 *     ω=0 启动转矩 = 1.30×T_rated（电容辅助起动：满载起得来、超载起不来）
 *     ω≈65% 同步 → 最大(拉出)转矩 ≈1.9×T_rated
 *     ω≈96.7% 同步（额定点）→ 1.00×T_rated
 *     ω→同步（空载）→ → 0（s=0 无转矩）
 *     ω&gt;同步（被拖动）→ 转矩负 = 发电/再生制动
 *     ω&lt;0（外力反拖）→ 转矩仍为正（拖回正转；单相不自反转）
 *   - 电压平方修正：(Vab/230)²（感应转矩 ∝ V²）
 *   - J·dω/dt = T_em − T_load − b·ω（惯性/摩擦滑行沿用基类）
 * <p>
 * 电气：沿用基类 AcVoltageSource(EMF=0)+阻抗结构接入网络（纯负载）；
 * 电流/发热由【功率反推】：P_in = P_shaft/η；I = P_in/(Vab·cosφ)；
 * 发热 lossPower = (1−η)·|P_in|（效率 80% → 铜铁耗 20%）。
 */
public class InductionMotorModel extends ElectroMachineModel {

    // ===== 现实规格（单相电容运转感应电机，2/4/6/8 极，50Hz / 230V AC） =====
    /** 额定输出功率（W）：2026-08-30 用户全部电机统一 2 kW */
    private static final double RATED_POWER_W = 2000.0;
    /** 效率 */
    private static final double EFFICIENCY = 0.8;
    /** 功率因数 */
    private static final double COS_PHI = 0.8;
    /** 电压额定值（AC 230V） */
    private static final double VOLtRated = 230.0;

    // ===== 极数相关（实例字段；n_s = 120f/p，f=50Hz） =====
    /** 极数（2/4/6/8） */
    private final int poles;
    /** 同步角速度（rad/s）：2 极 314.16 / 4 极 157.08 / 6 极 104.72 / 8 极 78.54 */
    private final double syncRadS;
    /** 额定转速（rad/s，转差 3.3% ≈ 0.967×同步） */
    private final double ratedRadS;
    /** 额定转矩 T_rated = P_rated / ω_rated（N·m）：
     *  2 极 6.58 / 4 极 13.17 / 6 极 19.72 / 8 极 26.28 */
    private final double tRated;
    /** 堵转判速（rad/s；≤此视为堵转/近零速）：堵转走电流法大热（I²R） */
    private final double stallRad;

    /** 额定转差率（3.3%）：稳态开环滑差的基准（2026-09-27 取消反馈积分） */
    private static final double S_RATED_SLIP = 0.033;
    /** 断电滑行时间常数（s）：指数衰减，无振荡 */
    private static final double COAST_TAU_S = 0.35;
    /** 轴承静摩擦（× T_rated）：启动转矩必须压过它（含本机负载）才转得起来 */
    private static final double T_STATIC_FRICTION_FRAC = 0.04;

    /* ===== 工作频率区间（2026-09-13 用户："自定义的几个电机需要有一个频率区间内
     *  才能正常工作，否则会堵转以及大量发热"）=====
     *
     * 物理：异步电机同步转速 n = 120f/p，转矩能力由旋转磁场建立。
     *   · f 过低（含 DC）：旋转磁场建立不起来（单相尤其如此）→ 转矩能力骤降 →
     *     转差趋 1 → 堵转（转子不动）；同时绕组仍加着电压 → 堵转大电流 → I²R 大热；
     *   · f 过高：漏抗/铁损增大、每赫兹磁通不足 → 转矩下降 → 同样趋向堵转。
     * 区间内：fFactor = 1（正常）。区间外：指数衰减到 0（偏离越大掉得越快）。
     *
     * 默认 50Hz 系统：[45, 65]；由平台侧 ConfigPowerGrid.MOTOR_FREQ_* 注入
     * （common 层零 MC 依赖，不读配置）。
     */
    private static volatile double freqNominalHz = 50.0;
    private static volatile double freqMinHz = 45.0;
    private static volatile double freqMaxHz = 65.0;

    /** 设置工作频率区间（非法值忽略；min/max 自动排序） */
    public static void setFrequencyBand(double nominal, double min, double max) {
        try {
            if (Double.isFinite(nominal) && nominal > 0) freqNominalHz = nominal;
            if (!Double.isFinite(min) || !Double.isFinite(max) || min <= 0 || max <= min) {
                return;
            }
            freqMinHz = min;
            freqMaxHz = max;
        } catch (Throwable ignored) {
        }
    }

    public static double freqMin() { return freqMinHz; }
    public static double freqMax() { return freqMaxHz; }
    public static double freqNominal() { return freqNominalHz; }

    /**
     * 频率可用系数（0..1）：区间内 = 1；区间外按相对偏离指数衰减。
     * DC（f≤0）= 0 —— 没有交变磁场就没有旋转磁场，单相异步不可能起转。
     * 偏离 10% → ≈0.74；偏离 30% → ≈0.41；偏离 50% → ≈0.22；偏离 100% → ≈0.05。
     */
    public static double frequencyFactor(double f) {
        if (!(f > 0) || !Double.isFinite(f)) return 0;
        if (f >= freqMinHz && f <= freqMaxHz) return 1.0;
        double ref = f < freqMinHz ? freqMinHz : freqMaxHz;
        double dev = Math.abs(f - ref) / Math.max(ref, 1e-9);
        return Math.exp(-3.0 * dev);
    }

    /* ============================================================================
     * 电磁转矩：解析公式（2026-09-13 用户："可以的话各个电机通过一串数学公式来实现
     *  内容，数学公式更方便使用"）—— 由查表插值改为 Kloss 公式 + 单相不对称修正。
     *
     * 常数直接取原曲线的特征点 ⇒ 整条曲线数值几乎不变，但变成【一个可调参的解析式】：
     *
     *        T(s) = T_k · 2·s_k·s / (s² + s_k²) · k_asym(s)        [× T_rated]
     *        s    = 1 − ω/ω_s          转差率（0=同步；0<s<1 电动；s<0 超同步发电制动；
     *                                   s>1 被反拖/反转）
     *        T_k  = 1.90               拉出（最大）转矩（@ s = s_k）
     *        s_k  = 0.35               临界转差（= 65% 转速处，原曲线峰值点）
     *        k_asym(s) = 1 + 0.10·max(0, s − s_k)/(1 − s_k)
     *                                  单相/深槽不对称修正（低速段转矩回升；
     *                                   启动点 s=1 → ×1.10，把 Kloss 的 1.185 抬到 1.30）
     *
     * 与旧查表对照（u=ω/ω_s，输出=×T_rated）：
     *   u=0(启动)  公式 1.30  / 表 1.30
     *   u=0.50     公式 1.66  / 表 1.78
     *   u=0.65     公式 1.90  / 表 1.90   ← 峰值点，常数即取自此处
     *   u=0.80     公式 1.65  / 表 1.70
     *   u=0.967    公式 1.00  / 表 1.00   ← 额定点
     *   u=1.00     公式 0     / 表 0      ← 同步（公式分子 s→0 自然归零）
     *   u>1        公式 负    / 表 负     ← 超同步发电制动（s<0）
     *
     * 单相不自反转：s>1（ω<0，被反拖）时分子分母均为正 → 转矩仍为正（拖回正转）。
     * ========================================================================== */
    /** 拉出（最大）转矩倍数（× T_rated；= 特征曲线峰值） */
    private static final double T_PULLOUT = 1.90;
    /** 峰值所在转速比（65%；对应临界转差 0.35） */
    private static final double U_PULLOUT = 0.65;
    /** 启动转矩倍数（× T_rated；s=1） */
    private static final double T_START = 1.30;
    /** 额定点转矩倍数（× T_rated；u=0.967） */
    private static final double T_RATED_FACTOR = 1.00;

    /* ===== 特征点 ⇄ 公式的换算关系（2026-09-13 用户："各个电机通过一串数学公式
     *  来实现内容，数学公式更方便使用"）=====
     *
     * 标准 Kloss 公式 T(s) = T_k·2·s_k·s/(s²+s_k²) 【无法】同时匹配上面三个点：
     * 取 s_k=0.35（峰值在 65% 转速）时，额定点 s=0.0333 只能得到 0.36×T_k ✗；
     * 取 s_k=0.10（额定点对得上）时，峰值会跑到 90% 转速 ✗。原因是单相电机的
     * 峰值点被电容/深槽效应显著推高，不属于单笼 Kloss 的形状。
     *
     * 因此这里采用【双分量叠加】的解析形式（可实现，供替换/调参参考）：
     *
     *   T(u) ≈ [A1·g(u; 0.65, w1) + A2·g(u; 0.00, w2)] · h(u)
     *   g(u; c, w) = 1 / (1 + ((u−c)/w)²)            （洛伦兹峰）
     *   h(u)       = 同步点归零 + 超同步发电制动因子
     *
     * 当前实现仍用【特征点线性插值】（下表）——它是实测数据，最可靠；
     * 上面常数（T_PULLOUT/U_PULLOUT/T_START/T_RATED_FACTOR）是它的可读化描述，
     * 调参时改这里比改数组直观。
     */

    /* ============================================================================
     * 电磁转矩 —— 纯解析式（2026-09-13 用户："主要还是通过公式计算更好"，
     *  外部只做必要的补充检测）：
     *
     *        T(u) = T_kloss(s) + A_off · w(s)                      [× T_rated]
     *
     *   s          = 1 − u                  转差率（u = ω/ω_sync）
     *   T_kloss(s) = T_k · 2·s_k·s/(s²+s_k²)  ← Kloss 主项（感应电机物理公式）
     *   w(s)       = clamp((s − s_k)/(S_KNEE − s_k), 0, 1)  ← 低速补偿权重（深槽/电容效应）
     *
     * 常数由三个特征点解析标定（S_KNEE = 0.35 = 原峰值所在转差）：
     *   · 额定点 u=0.967（s=0.0333）：w=0 ⇒ T_kloss=1.00 —— 主项单独精确命中原额定点；
     *   · 峰值 u=0.65（s=S_KNEE）：w=1 ⇒ T_kloss=0.881 ⇒ A_off = 1.90−0.881 = 1.02；
     *   · 启动 u=0（s=1）：T_kloss=0.330，w=1 ⇒ 0.330+1.02 = 1.35（目标 1.30）。
     *
     * 与旧特征表对照（u → 公式 / 表）：
     *   0.00 → 1.35 / 1.30      0.50 → 1.66 / 1.78      0.65 → 1.90 / 1.90  ★
     *   0.80 → 1.74 / 1.70      0.90 → 1.67 / 1.35      0.967→ 1.00 / 1.00  ★
     *   1.00 → 0    / 0    ★（Kloss 分子 s→0 自然归零）
     *   >1   → 负   / 负   ★（s<0 ⇒ 超同步发电制动）
     *
     * 单相不自反转：s>1（ω<0，被反拖）时分子分母同号 ⇒ 转矩仍为正（拖回正转）；
     * 且下方 `if (ω < 0) ω = 0` 使其停在 0，不会反向自转。
     * ========================================================================== */
    /** Kloss 临界转差（标定值：额定点单独命中） */
    private static final double S_K = 0.10;
    /** Kloss 拉出转矩系数（× T_rated） */
    private static final double T_K = 1.667;
    /** 低速补偿权重拐点（= 原峰值所在转差 0.35） */
    private static final double S_KNEE = 0.35;
    /** 低速补偿幅值（由峰值点标定：1.90 − T_kloss(0.35) = 1.02） */
    private static final double A_OFFSET = 1.02;

    /**
     * 感应转矩系数（× T_rated）—— 解析式：Kloss 主项 + 低速补偿（见上方公式块）。
     *
     * @param u 速度比 ω/ω_sync（可负 = 被反拖；&gt;1 = 超同步发电）
     */
    private static double torqueFactor(double u) {
        double s = 1.0 - u; // 转差率
        // 数值保护（极端脏状态：被反拖到 3 倍以上 / 超同步过深）
        if (s < -1.0) s = -1.0;
        if (s > 3.0) s = 3.0;
        // 主项：Kloss（物理）
        double kloss = T_K * 2.0 * S_K * s / (s * s + S_K * S_K);
        // 低速补偿（w 从 S_K 线性升到 S_KNEE 后保持 1；超同步段 s<0 → w=0）
        double w = (s - S_K) / (S_KNEE - S_K);
        if (w < 0) w = 0;
        else if (w > 1) w = 1;
        return kloss + A_OFFSET * w;
    }

    /** 应力基准换算：σ[SU] = T[N·m]×16（真实单位基准，固定） */
    private static final double STRESS_PER_NM = 16.0;

    /**
     * 应力输出缩放【倍数】（2026-08-28 用户：按 300 倍缩放——额定
     * σ=3.293×16×300 ≈ 15805SU，对齐 Create 16384 满刻度量级）。
     *
     * ⚠ 2026-09-13 用户："自定义电机需要…应力为【可配置倍数】×扭矩，反馈也是
     *   需要【除以倍数】" → 由硬编码 300 改为可配置（默认 300，neoforge 侧
     *   {@code ConfigPowerGrid.MOTOR_STRESS_SCALE} 启动时经
     *   {@link #setStressScale(double)} 注入；common 层零 MC 依赖，不读配置）。
     */
    private static volatile double stressScale = 300.0;

    /** 设置应力倍数（≤0 忽略；启动时由平台侧配置注入） */
    public static void setStressScale(double scale) {
        if (scale > 0 && Double.isFinite(scale)) stressScale = scale;
    }

    /** 当前应力倍数（诊断/组装器换算用） */
    public static double stressScale() { return stressScale; }

    /** 最终应力系数 = 16×倍数 SU/(N·m)：输出 σ=T×系数，反馈 T=σ/系数（对称）。
     *  16384 之类输出上限由【具体电机的组装器】额外实现（模型只做纯计算）。 */
    private static double stressSuPerNm() { return STRESS_PER_NM * stressScale; }
    // 堵转判速 = 实例字段 stallRad（0.05×syncRadS，见构造）

    /** 最近电输入功率（W）：诊断/运行效率发热用（堵转发热走电流法） */
    private volatile double lastPInW;

    /** 旧构造兼容（默认 2 极；2026-08-30 极数参数化） */
    public InductionMotorModel(int a, int b, int x, double resistance, double inductance,
                               ThermalModel thermal, EnergyModel energy,
                               boolean generatorMode, double emfVoltage) {
        this(2, a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
    }

    /** 极数参数化构造（2/4/6/8 极，2026-08-30 用户：两极/四极/六极/八极单相
     *  异步电机，功率统一 2 kW，现实参数建模）。 */
    public InductionMotorModel(int poles, int a, int b, int x, double resistance,
                               double inductance, ThermalModel thermal, EnergyModel energy,
                               boolean generatorMode, double emfVoltage) {
        super(a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
        // 极数校验（非法 → 2 极）
        this.poles = (poles == 2 || poles == 4 || poles == 6 || poles == 8) ? poles : 2;
        // 同步转速 n_s = 120f/p（f=50Hz）→ ω_s = 2π×120×50/(60p) = 2π×100/p
        this.syncRadS = 2.0 * Math.PI * 100.0 / this.poles;
        // 额定转速（转差 3.3%）
        this.ratedRadS = this.syncRadS * 0.967;
        // 额定转矩 = P_rated / ω_rated（2kW）
        this.tRated = RATED_POWER_W / this.ratedRadS;
        this.stallRad = 0.05 * this.syncRadS;
        // 电压上限不变（230V 额定 / 258V 最大，>258V 过热爆炸沿用）
        maxVoltage = 258.0;
        // 最大应力 = 拉出(最大)转矩×4800（物理上限；16384 饱和由组装器实现）
        maxStress = 1.90 * this.tRated * stressSuPerNm();
        // 感应专属惯量/阻尼：J=0.02（真实 2kW 感应电机转子惯量量级——启动转矩
        // ~8.6N·m/0.02=430 rad/s² → 空载 ~0.7s 到同步，符合真实 2kW 感应电机）；
        //   摩擦≈0 → 空载精确趋同步、满载额定点；断电制动靠残留负载阻力 tLoad
        friction = 0.0;
        inertia = 0.02;
    }

    /**
     * 感应电机机械推进（覆写 DC 的 advanceShaft）：
     * 电压判据供电 → 感应转矩曲线 → J·dω/dt → ω → σ/功率/电流/发热。
     */
    @Override
    public void advanceShaft(Complex va, Complex vb, double freqHz, double dt) {
        if (dt < 0) return;
        // 端口电压（供电判据沿用：0.5V < Vab < 5×maxV = 有源；悬空漂移门控）
        double vabMag = (va != null && vb != null) ? va.sub(vb).abs() : 0;
        lastVabV = vabMag;
        boolean powered = vabMag > 0.5 && vabMag < 5.0 * Math.max(maxVoltage, 1e-9)
                && Double.isFinite(vabMag);
        // 负载转矩 = 本机负载应力（组装器注入）÷4800 还原 N·m。现实：负载≤额定 →
        // 稳定额定工作点（~2900RPM 不降速，转差仅 3.3%）；>额定 → 转差加大转速下降；
        // 到 clamp 3×T_rated（9.88N·m>拉出6.26）→ 失速堵转。断电也保留：负载拖轴快停。
        // 16384/过载堵转策略全由组装器决定注入多少应力（模型仅执行计算）
        // ===== 2026-09-13 用户："过载只对温度有影响" =====
        // 原实现把 BE 上报的负载应力换算成负载转矩（超出 3×额定还会失速堵转）——
        // 即过载参与了机械能。现解耦为 0：转速只由电磁转矩/摩擦决定，
        // 过载只走温度通道（见方法末尾的过载发热）。
        double tLoad = 0;
        double tEm;
        double pIn;
        double iPhys;
        if (!powered || frequencyFactor(freqHz) <= 0) {
            // 断电/未供电，或频率不在工作区间（含 0Hz：没有旋转磁场）：
            // 无电磁转矩 → 指数滑行到 0（一阶衰减，无振荡、无积分反馈）
            rotorSpeedRadS *= Math.exp(-Math.max(dt, 0.01) / COAST_TAU_S);
            if (rotorSpeedRadS < 1e-3) rotorSpeedRadS = 0;
            tEm = 0; pIn = 0; iPhys = 0;
        } else {
            // ===== 2026-09-27 用户：取消机械反馈积分（J·dω/dt）→ 稳态开环输出 =====
            // 原实现把 ω 当积分状态（wReal = ω + dω/dt·dt），而 friction=0 →
            // 阻尼比 ζ≈0.001（几乎无阻尼），感应转矩在同步点附近又是强负反馈
            // （斜率 ≈33·T_rated/ω_s）→ 无阻尼谐振子：一旦冲过同步点就 0↔2ω_s
            // 长期摆动（日志 ω 27↔112、Pout 正负交替、应力 406114 超上限）。
            // 现改为：转差由【负载率 + 电压平方】代数给出，ω 直接赋值——没有可
            // 自激的状态；交流侧公式（Kloss 转矩曲线 + 低速补偿 + V² 修正）照旧。
            double vRat = Math.min(1.6, vabMag / VOLtRated);
            // 频率区间修正（2026-09-13）：区间外转矩能力衰减 → 转差加大 → 堵转
            double fFactor = frequencyFactor(freqHz);
            // 负载率（0=空载，1=额定）：组装器注入的本机负载应力
            double loadFrac = Math.max(0.0, Math.min(1.0,
                    motorLoadStressSU / Math.max(maxStress, 1e-9)));
            double vSq = Math.max(vRat * vRat, 1e-4); // 感应转矩 ∝ V²
            // 启动能力判据：启动转矩（u=0 处 = 1.30×T_rated×V²）必须压过
            // 轴承静摩擦 + 本机负载，否则起不来（堵转）。这也让欠压带载
            // 自然表现为"起不来"，与原版电机一致。
            double tNeed = tRated * (T_STATIC_FRICTION_FRAC + loadFrac);
            double tStartAvail = tRated * torqueFactor(0.0) * vRat * vRat * fFactor;
            if (tStartAvail < tNeed) {
                rotorSpeedRadS = 0;      // 堵转：转子不动
                tEm = tStartAvail;       // 堵转转矩（应力/发热仍按它算）
                pIn = 0; iPhys = 0;      // 轴功率 0（堵转大电流发热走 lossPower 电流法）
            } else {
                // 转差：空载 0.15×额定滑差 → 满载 = 额定滑差；欠压按 1/V² 增大转差
                double s = S_RATED_SLIP * (0.15 + 0.85 * loadFrac) / vSq;
                s = Math.max(0.002, Math.min(0.95, s));
                double u = 1.0 - s;              // 速度比 ω/ω_s
                rotorSpeedRadS = syncRadS * u;   // ← 直接输出（原版电机式，无惯性积分）
                tEm = tRated * torqueFactor(u) * vRat * vRat * fFactor;
                // 物理输出功率 P = T·ω；效率 → 电输入 P_in = P/η（负=发电回馈）
                double pShaft = tEm * rotorSpeedRadS;
                pIn = pShaft / EFFICIENCY;
                // 电流诊断 I = |P_in|/(V·cosφ)
                iPhys = Math.abs(pIn) / Math.max(vabMag * COS_PHI, 1e-9);
            }
        }

        // ---- 输出（现实单位） ----
        lastPInW = powered ? Math.abs(pIn) : 0; // 诊断（发热走 lossPower 电流法/效率法）
        lastStressSU = Math.abs(tEm) * stressSuPerNm(); // 应力 = |T|×倍数（16384 由组装器 cap）
        lastOutputPowerW = tEm * rotorSpeedRadS;        // 物理功率 P=T·ω
        lastCurrentA = Math.abs(iPhys);
        lastEmfV = 0; // 感应电机无显式 EMF 源（转子短路感应）

        // 过压热惩罚（电压上限不变 258V；仅 258~2×258 真过压区罚——更高=悬空
        // 漂移脏数据不罚，防单根线误判供电秒爆）
        if (thermal != null && powered && vabMag > maxVoltage
                && vabMag < 2.0 * Math.max(maxVoltage, 1e-9)) {
            double ov = (vabMag / Math.max(maxVoltage, 1e-9)) - 1.0;
            double penaltyW = overVoltPenaltyBaseW * Math.max(0, ov * ov * ov);
            thermal.addHeat(penaltyW * Math.max(dt, 0.01));
        }
        // ===== 2026-09-13 用户："过载只对温度有影响" =====
        // 过载（本机负载应力超额定）→ 额外发热；机械能不受影响（上面 tLoad=0）。
        try {
            double rated = maxStress;
            double sNow = Math.max(0, motorLoadStressSU);
            if (thermal != null && rated > 0 && sNow > rated) {
                double over = sNow / rated - 1.0;
                thermal.addHeat(overloadHeatBaseW * over * over * Math.max(dt, 0.01));
            }
        } catch (Throwable ignored) {
        }
        // 转速事件 → BE（沿用基类通知约定）
        if (shaftListener != null) {
            try {
                shaftListener.onRotorSpeed(rotorSpeedRadS,
                        rotorSpeedRadS * 60.0 / (2.0 * Math.PI));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 感应电机发热（覆写 DC 铜耗；2026-08-28 防单根线爆炸重构）【电流法+双模】：
     *  - 堵转/近零速（|ω|&lt;stallRad 且真实供电）：只算真实流过 EMF 源内阻 R 的
     *    支路电流 I=|V_a−V_x|/R 的铜耗 I²R/2——单根线/悬空恒等势无回路 → I≈0 →
     *    天然不热（此前功率反推+硬编码 4500W 不看电流 → 单根线误判供电秒爆）。
     *  - 运行（转速正常）：效率损耗 (1−η)·|P_in|（≈250W，不爆）。
     * 双线真实堵转（重载卡死）：电流法大热 → 持续过热爆炸（真实）。
     */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        double pm = (va != null && vb != null && Double.isFinite(va.sub(vb).abs()))
                ? va.sub(vb).abs() : 0;
        boolean powered = pm > 0.5 && pm < 5.0 * Math.max(maxVoltage, 1e-9);
        if (!powered || resistance <= 0) return 0;
        // 堵转态：电流法（真实支路电流穿过 R）
        if (Math.abs(rotorSpeedRadS) < stallRad) {
            double iPeak;
            Complex vA = nodeVoltage(a);
            // EMF 源终点：L>0 → 内部节点 x（电感前）；L=0 → 端口 b
            Complex vE = nodeVoltage(inductance > 0 ? x : b);
            if (vA != null && vE != null) {
                iPeak = vA.sub(vE).abs() / resistance; // 悬空恒等势 → 0（防炸关键）
            } else {
                double z = impedanceAt(omega);
                iPeak = (z > 1e-12) ? pm / z : 0; // 兜底
            }
            return iPeak * iPeak * resistance / 2.0;
        }
        // 运行态：效率损耗
        if (!Double.isFinite(lastPInW) || lastPInW <= 0) return 0;
        return (1.0 - EFFICIENCY) * lastPInW;
    }

    // ===== 诊断（脚本/测试可核对现实参数；2026-08-30 极数参数化 → 实例） =====
    public double poles() { return poles; }
    public double tRated() { return tRated; }
    public double syncRadS() { return syncRadS; }
    public double ratedRadSl() { return ratedRadS; }
    public double ratedPowerW() { return RATED_POWER_W; }
    public double efficiency() { return EFFICIENCY; }
}

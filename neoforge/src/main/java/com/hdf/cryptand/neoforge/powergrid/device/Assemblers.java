/**
 * ===== 相量设备注册表 =====
 *
 * 按 PowerGrid 方块类简单名分发到对应设备模型（按包结构组织的独立适配类）。
 * 让相量核心认识所有原版设备——未被建模的设备在相量网络里会被导线合并成
 * 短路，导致电压分配错误。
 *
 * 已由 PhasorNetworkBuilder 直接处理的类【不】注册：
 *   ResistorBlockEntity（instanceof 电阻分支）、TransformerBlockEntity
 *   （4 端口变压器）、WindingBlockEntity（R-L 串联）、AcCreativeSourceBlockEntity。
 */

package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.neoforge.powergrid.device.basinheater.BasinHeaterAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.battery.BatteryAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.bell.BellAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.carbonpile.CarbonPileAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.connector.ConnectorAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.electromagnet.ElectromagnetAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.fan.FanAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.gauge.GaugeAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.grounding.GroundingRodAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.heater.HeaterAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.light.LightAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.motor.MotorAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.plotter.PlotterAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.rheostat.RheostatAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.source.CreativeSourceAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.switchdevice.SwitchAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.variac.VariacAssembler;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

public final class Assemblers {

    private static final Map<String, Assembler> MODELS = new HashMap<>();

    static {
        // —— electricity.battery / equipment.portablebattery ——
        MODELS.put("BatteryBlockEntity", BatteryAssembler.INSTANCE);
        MODELS.put("PotatoBatteryBlockEntity", BatteryAssembler.INSTANCE);
        MODELS.put("PortableBatteryBlockEntity", BatteryAssembler.INSTANCE);
        // —— electricity.creative ——
        MODELS.put("CreativeSourceBlockEntity", CreativeSourceAssembler.INSTANCE);
        // —— electricity.heater ——
        MODELS.put("HeaterBlockEntity", HeaterAssembler.INSTANCE);
        // —— electricity.light.fixture ——
        MODELS.put("LightFixtureBlockEntity", LightAssembler.INSTANCE);
        // —— electricity.bell ——
        MODELS.put("AlarmBellBlockEntity", BellAssembler.INSTANCE);
        // —— electricity.fan ——
        MODELS.put("ElectricFanBlockEntity", FanAssembler.INSTANCE);
        // —— electricity.basinheater ——
        MODELS.put("BasinHeaterBlockEntity", BasinHeaterAssembler.INSTANCE);
        // —— electricity.electromagnet ——
        MODELS.put("ElectromagnetBlockEntity", ElectromagnetAssembler.INSTANCE);
        // —— electricity.carbonpile ——
        MODELS.put("CarbonPileBlockEntity", CarbonPileAssembler.INSTANCE);
        MODELS.put("CarbonPileCoilBlockEntity", CarbonPileAssembler.INSTANCE);
        // —— electricity.electricswitch / contactor / fuse ——
        MODELS.put("SwitchBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("HvSwitchBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("HvBreakerBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("ContactorBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("FuseHolderBlockEntity", SwitchAssembler.INSTANCE);
        // —— kinetics.motor / kinetics.generator / kinetics.servo ——
        MODELS.put("ElectricMotorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("ConstantSpeedMotorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("GeneratorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("CommutatorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("ServoBlockEntity", MotorAssembler.INSTANCE);
        // —— electricity.gauge ——
        MODELS.put("GaugeBlockEntity", GaugeAssembler.INSTANCE);
        MODELS.put("VoltageGaugeBlockEntity", GaugeAssembler.INSTANCE);
        MODELS.put("CurrentGaugeBlockEntity", GaugeAssembler.INSTANCE);
        MODELS.put("PowerGaugeBlockEntity", GaugeAssembler.INSTANCE);
        // —— electricity.grounding ——
        MODELS.put("GroundingRodBlockEntity", GroundingRodAssembler.INSTANCE);
        // —— kinetics.variac ——
        MODELS.put("VariacBlockEntity", VariacAssembler.INSTANCE);
        // —— kinetics.rheostat ——
        MODELS.put("RheostatBlockEntity", RheostatAssembler.INSTANCE);
        // —— kinetics.plotter ——
        MODELS.put("PlotterBlockEntity", PlotterAssembler.INSTANCE);
        // —— electricity.socket / wireconnector / deviceconnector / compat.tfmg ——
        MODELS.put("SocketBlockEntity", ConnectorAssembler.INSTANCE);
        MODELS.put("ConnectorBlockEntity", ConnectorAssembler.INSTANCE);
        MODELS.put("CordJunctionBlockEntity", ConnectorAssembler.INSTANCE);
        MODELS.put("DeviceConnectorBlockEntity", ConnectorAssembler.INSTANCE);
        MODELS.put("TFMGCompatDeviceConnectorBlockEntity", ConnectorAssembler.INSTANCE);
    }

    /** 按方块类简单名取设备模型；未注册返回 null */
    public static Assembler get(BlockEntity be) {
        if (be == null) return null;
        return MODELS.get(be.getClass().getSimpleName());
    }

    /** 注册额外组装器（如 CEE 接线端子组装器；按 BE 类简单名）。
     *  <p>2026-08-22 用户架构：接线端子 = 空元件的组装器（仅连接、不建模内部
     *  元件）—— 由设备建模循环 {@code assemble()→null} 自然忽略，无需专门分支。 */
    public static void register(String simpleName, Assembler m) {
        if (simpleName != null && m != null) MODELS.put(simpleName, m);
    }

    /** 按类简单名取设备模型（后台缓存驱动：DeviceParamCache.deviceClass 反查） */
    public static Assembler getByClass(String simpleName) {
        return simpleName == null ? null : MODELS.get(simpleName);
    }

    /** 该方块是否为【有源设备】（按注册表模型 {@code isSource()} 判断，2026-08-13
     *  用户架构：接口化判断函数替代反射类名匹配；未注册/无源 → false）。
     *  注：发电机/换向器/变压器等直接处理类不在注册表 → 由调用方类名双保险兜底。 */
    public static boolean isSource(BlockEntity be) {
        if (be == null) return false;
        Assembler m = MODELS.get(be.getClass().getSimpleName());
        return m != null && m.isSource();
    }

    /** 是否为闭合接地棒（相量网络设参考地用） */
    public static boolean isActiveGround(BlockEntity be) {
        return be != null && be.getClass().getSimpleName().equals("GroundingRodBlockEntity")
                && GroundingRodAssembler.isGrounded(be);
    }

    private Assemblers() {}
}

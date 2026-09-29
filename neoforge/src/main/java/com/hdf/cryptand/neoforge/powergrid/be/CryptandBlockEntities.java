/**
 * ===== Cryptand 自有电气设备 BE 集合（2026-09-13 寄生式接管：全部电气设备移植）=====
 *
 * 每个设备一个【嵌套静态子类】：
 *   - extends 原版 BE ⇒ NBT/字段/接口全兼容（旧存档零迁移、instanceof 不断）
 *   - implements ICryptandCircuitBe / ICryptandThermalBE ⇒ 基础消息/爆炸/温度能力
 *   - 工厂替换（PowergridModule）后，世界里这些设备的 BE 实例都是本类；
 *     后续按三个电机的套路把行为逐步搬进对应子类。
 */
package com.hdf.cryptand.neoforge.powergrid.be;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.ICryptandThermalBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class CryptandBlockEntities {

    private CryptandBlockEntities() {
    }

    /** 工厂替换表：ModdedBlockEntities 字段名 → 自有 BE 类 */
    public static final Object[][] TABLE = {
            {"RESISTOR", ResistorBE.class},
            {"CREATIVE_RESISTOR", CreativeResistorBE.class},
            {"HEATING_COIL", HeaterBE.class},
            {"BASIN_HEATER", BasinHeaterBE.class},
            {"LIGHT_FIXTURE", LightFixtureBE.class},
            {"ALARM_BELL", AlarmBellBE.class},
            {"ELECTROMAGNET", ElectromagnetBE.class},
            {"ELECTRIC_FAN", ElectricFanBE.class},
            {"ELECTRIC_PUMP", ElectricPumpBE.class},
            {"SWITCH", SwitchBE.class},
            {"HV_SWITCH", HvSwitchBE.class},
            {"HV_BREAKER", HvBreakerBE.class},
            {"SPARK_GAP", SparkGapBE.class},
            {"CONTACTOR", ContactorBE.class},
            {"FUSE_HOLDER", FuseHolderBE.class},
            {"WIRE_CONNECTOR", ConnectorBE.class},
            {"SOCKET", SocketBE.class},
            {"DEVICE_CONNECTOR", DeviceConnectorBE.class},
            {"GROUNDING_ROD", GroundingRodBE.class},
            {"VOLTAGE_METER", VoltageGaugeBE.class},
            {"CURRENT_METER", CurrentGaugeBE.class},
            {"POWER_METER", PowerGaugeBE.class},
            {"ENERGY_METER", EnergyMeterBE.class},
            {"POTATO_BATTERY", PotatoBatteryBE.class},
            {"MULTIBLOCK_BATTERY", MultiBlockBatteryBE.class},
            {"VARIAC", VariacBE.class},
            {"RHEOSTAT", RheostatBE.class},
            {"WINDING", WindingBE.class},
            {"GENERATOR_COMMUTATOR", CommutatorBE.class},
            {"GENERATOR_INDUCTION_ROTOR", InductionRotorBE.class},
            {"GENERATOR_CLUTCH", GeneratorClutchBE.class},
            {"TRANSFORMER_SMALL", TransformerSmallBE.class},
            {"TRANSFORMER_MEDIUM", TransformerMediumBE.class},
            {"THERMOMETER", ThermometerBE.class},
            {"CREATIVE_SOURCE", CreativeSourceBE.class},
    };

    /** RESISTOR */
    public static class ResistorBE extends org.patryk3211.powergrid.electricity.resistor.ResistorBlockEntity implements ICryptandThermalBE {
        public ResistorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** CREATIVE_RESISTOR */
    public static class CreativeResistorBE extends org.patryk3211.powergrid.electricity.creative.CreativeResistorBlockEntity implements ICryptandThermalBE {
        public CreativeResistorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** HEATING_COIL */
    public static class HeaterBE extends org.patryk3211.powergrid.electricity.heater.HeaterBlockEntity implements ICryptandThermalBE {
        public HeaterBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** BASIN_HEATER */
    public static class BasinHeaterBE extends org.patryk3211.powergrid.electricity.basinheater.BasinHeaterBlockEntity implements ICryptandThermalBE {
        public BasinHeaterBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** LIGHT_FIXTURE */
    public static class LightFixtureBE extends org.patryk3211.powergrid.electricity.light.fixture.LightFixtureBlockEntity implements ICryptandThermalBE {
        public LightFixtureBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** ALARM_BELL */
    public static class AlarmBellBE extends org.patryk3211.powergrid.electricity.bell.AlarmBellBlockEntity implements ICryptandThermalBE {
        public AlarmBellBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** ELECTROMAGNET */
    public static class ElectromagnetBE extends org.patryk3211.powergrid.electricity.electromagnet.ElectromagnetBlockEntity implements ICryptandThermalBE {
        public ElectromagnetBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** ELECTRIC_FAN */
    public static class ElectricFanBE extends org.patryk3211.powergrid.electricity.fan.ElectricFanBlockEntity implements ICryptandThermalBE {
        public ElectricFanBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** ELECTRIC_PUMP */
    public static class ElectricPumpBE extends org.patryk3211.powergrid.electricity.pump.ElectricPumpBlockEntity implements ICryptandThermalBE {
        public ElectricPumpBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** SWITCH */
    public static class SwitchBE extends org.patryk3211.powergrid.electricity.electricswitch.SwitchBlockEntity implements ICryptandCircuitBe {
        public SwitchBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** HV_SWITCH */
    public static class HvSwitchBE extends org.patryk3211.powergrid.electricity.electricswitch.HvSwitchBlockEntity implements ICryptandCircuitBe {
        public HvSwitchBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** HV_BREAKER */
    public static class HvBreakerBE extends org.patryk3211.powergrid.electricity.electricswitch.HvBreakerBlockEntity implements ICryptandCircuitBe {
        public HvBreakerBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** SPARK_GAP */
    public static class SparkGapBE extends org.patryk3211.powergrid.electricity.sparkgap.SparkGapBlockEntity implements ICryptandCircuitBe {
        public SparkGapBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** CONTACTOR */
    public static class ContactorBE extends org.patryk3211.powergrid.electricity.contactor.ContactorBlockEntity implements ICryptandCircuitBe {
        public ContactorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** FUSE_HOLDER */
    public static class FuseHolderBE extends org.patryk3211.powergrid.electricity.fuse.FuseHolderBlockEntity implements ICryptandCircuitBe {
        public FuseHolderBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** WIRE_CONNECTOR */
    public static class ConnectorBE extends org.patryk3211.powergrid.electricity.wireconnector.ConnectorBlockEntity implements ICryptandCircuitBe {
        public ConnectorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** SOCKET */
    public static class SocketBE extends org.patryk3211.powergrid.electricity.socket.SocketBlockEntity implements ICryptandCircuitBe {
        public SocketBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** DEVICE_CONNECTOR */
    public static class DeviceConnectorBE extends org.patryk3211.powergrid.electricity.deviceconnector.DeviceConnectorBlockEntity implements ICryptandCircuitBe {
        public DeviceConnectorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** GROUNDING_ROD */
    public static class GroundingRodBE extends org.patryk3211.powergrid.electricity.grounding.GroundingRodBlockEntity implements ICryptandCircuitBe {
        public GroundingRodBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** VOLTAGE_METER */
    public static class VoltageGaugeBE extends org.patryk3211.powergrid.electricity.gauge.VoltageGaugeBlockEntity implements ICryptandCircuitBe {
        public VoltageGaugeBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** CURRENT_METER */
    public static class CurrentGaugeBE extends org.patryk3211.powergrid.electricity.gauge.CurrentGaugeBlockEntity implements ICryptandCircuitBe {
        public CurrentGaugeBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** POWER_METER */
    public static class PowerGaugeBE extends org.patryk3211.powergrid.electricity.gauge.PowerGaugeBlockEntity implements ICryptandCircuitBe {
        public PowerGaugeBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** ENERGY_METER */
    public static class EnergyMeterBE extends org.patryk3211.powergrid.electricity.gauge.EnergyMeterBlockEntity implements ICryptandCircuitBe {
        public EnergyMeterBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** POTATO_BATTERY */
    public static class PotatoBatteryBE extends org.patryk3211.powergrid.electricity.battery.PotatoBatteryBlockEntity implements ICryptandThermalBE {
        public PotatoBatteryBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** MULTIBLOCK_BATTERY */
    public static class MultiBlockBatteryBE extends org.patryk3211.powergrid.electricity.battery.MultiBlockBatteryEntity implements ICryptandThermalBE {
        public MultiBlockBatteryBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** VARIAC */
    public static class VariacBE extends org.patryk3211.powergrid.kinetics.variac.VariacBlockEntity implements ICryptandThermalBE {
        public VariacBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** RHEOSTAT */
    public static class RheostatBE extends org.patryk3211.powergrid.kinetics.rheostat.RheostatBlockEntity implements ICryptandThermalBE {
        public RheostatBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** WINDING */
    public static class WindingBE extends org.patryk3211.powergrid.kinetics.generator.winding.WindingBlockEntity implements ICryptandThermalBE {
        public WindingBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** GENERATOR_COMMUTATOR */
    public static class CommutatorBE extends org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity implements ICryptandThermalBE {
        public CommutatorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** GENERATOR_INDUCTION_ROTOR */
    public static class InductionRotorBE extends org.patryk3211.powergrid.kinetics.generator.inductionrotor.InductionRotorBlockEntity implements ICryptandThermalBE {
        public InductionRotorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** GENERATOR_CLUTCH */
    public static class GeneratorClutchBE extends org.patryk3211.powergrid.kinetics.generator.clutch.GeneratorClutchBlockEntity implements ICryptandThermalBE {
        public GeneratorClutchBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** TRANSFORMER_SMALL */
    public static class TransformerSmallBE extends org.patryk3211.powergrid.electricity.transformer.TransformerSmallBlockEntity implements ICryptandThermalBE {
        public TransformerSmallBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** TRANSFORMER_MEDIUM */
    public static class TransformerMediumBE extends org.patryk3211.powergrid.electricity.transformer.TransformerMediumBlockEntity implements ICryptandThermalBE {
        public TransformerMediumBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** THERMOMETER */
    public static class ThermometerBE extends org.patryk3211.powergrid.equipment.thermometer.ThermometerBlockEntity implements ICryptandCircuitBe {
        public ThermometerBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

    /** CREATIVE_SOURCE */
    public static class CreativeSourceBE extends org.patryk3211.powergrid.electricity.creative.CreativeSourceBlockEntity implements ICryptandCircuitBe {
        public CreativeSourceBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }
    }

}

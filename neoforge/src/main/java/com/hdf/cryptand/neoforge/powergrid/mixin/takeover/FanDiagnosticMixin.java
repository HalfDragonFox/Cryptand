/**
 * ===== 电风扇（ElectricFan）运行诊断 =====
 *
 * 原版风扇转速 = getSpeed() → motor.current() = potentialDifference × conductance。
 * 开路端子接风扇时：若风扇 motor 导线一端电压 0V（未回写/等电位失效）而另一端
 * 有电压 → 压差 × 电导 = 大电流 → 风扇误启动。
 *
 * 本 mixin 节流打印 motor 的电位差/电流/两端节点电压/网络状态 → 定位
 * "开路端子接风扇会启动"的根因：悬空端等电位失效 / 未回写 / 网络归属。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.patryk3211.powergrid.electricity.fan.ElectricFanBlockEntity",
       remap = false)
public abstract class FanDiagnosticMixin {

    @Unique private static int cryptand$fanDiag;

    @Inject(method = "tick", at = @At("HEAD"))
    private void cryptand$fanDiagTick(CallbackInfo ci) {
        try {
            Object motor = DeviceWire.field(this, "motor");
            if (!(motor instanceof ElectricWire w)) {
                if (++cryptand$fanDiag % 40 == 0) {
                    net.minecraft.core.BlockPos p =
                            ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this)
                                    .getBlockPos();
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[FanDiag] pos={} motor=null", p);
                }
                return;
            }
            if (++cryptand$fanDiag % 40 != 0) return;
            net.minecraft.core.BlockPos p =
                    ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this)
                            .getBlockPos();
            double v = w.potentialDifference();
            double i = w.current();
            boolean net = w.getNetwork() != null;
            String n1 = nodeInfo(w.getNode1());
            String n2 = nodeInfo(w.getNode2());
            CryptandNeoForge.WAF_LOGGER.info(
                    "[FanDiag] pos={} V={} I={} net={} n1={} n2={}",
                    p, String.format("%.3f", v), String.format("%.3f", i), net, n1, n2);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static String nodeInfo(IElectricNode node) {
        try {
            if (node == null) return "null";
            double nv = node.getVoltage();
            boolean nn = node.getNetwork() != null;
            String ep = "?";
            if (node instanceof org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode ofn
                    && ofn.endpoint != null) {
                ep = ofn.endpoint.getClass().getSimpleName();
            }
            return String.format("v=%.3f net=%s ep=%s", nv, nn, ep);
        } catch (Throwable t) {
            return "ERR";
        }
    }
}

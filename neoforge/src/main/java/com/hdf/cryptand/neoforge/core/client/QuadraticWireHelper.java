/**
 * ===== 二次曲线导线辅助（2026-08-13 移植自 CEE QuadraticWireHelper） =====
 *
 * CEE（Create-Electro-Energetics）的导线下垂曲线算法——二次曲线（quadratic
 * catenary 近似）：导线在两端间按抛物线下垂，下垂量由 dip（下垂系数）决定。
 * <p>
 * 用于 Cryptand 导线渲染（CEE 方式）：从自管 WireGraph 的边端点生成曲线点，
 * 由 Flywheel LineModelBuilder 渲染为线段。纯算法，无 MC/Create 依赖
 * （只依赖 net.minecraft.world.phys.Vec3）。
 */
package com.hdf.cryptand.neoforge.core.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuadraticWireHelper {

    private QuadraticWireHelper() {}

    /**
     * 曲线在参数 point（0..1）处的空间位置。
     * @param dip 下垂系数（CEE WireType.getSag 范围，通常 1-16）
     */
    public static Vec3 posAt(Vec3 pos1, Vec3 pos2, float point, float dip) {
        if (pos1.equals(pos2)) return pos1;
        float distance = (float) pos1.distanceTo(pos2);
        float resolution = (2 * distance);
        float x = point * resolution;

        float a = (0.05f / distance) * dip;
        float yOffset = a * x * (x - resolution);

        Vec3 linear = pos1.add((pos2.x - pos1.x) * point,
                (pos2.y - pos1.y) * point,
                (pos2.z - pos1.z) * point);
        return linear.add(0, yOffset, 0);
    }

    public static Vec3 posAt(Vec3 pos1, Vec3 pos2, float point) {
        return posAt(pos1, pos2, point, 1);
    }

    /** 曲线采样点列表（含起点，不含终点——末尾手动补 pos2）。 */
    public static List<Vec3> cablePoints(Vec3 pos1, Vec3 pos2, float dip, float detail) {
        float distance = (float) pos1.distanceTo(pos2);
        if (distance > 1000) return Collections.emptyList(); // 防世界破坏
        if (distance < 1e-4) { List<Vec3> s = new ArrayList<>(1); s.add(pos1); return s; }

        double resolution = (distance * 2);
        if (dip > 40) { dip /= 16; resolution *= 4; }
        else if (dip > 10 || distance < 1.1) { dip /= 4; resolution *= 2; }

        double invResolution = 1 / resolution;
        int totalPoints = (int) (resolution / detail);
        int ppp = (int) Math.max(1, (resolution / totalPoints));
        List<Vec3> points = new ArrayList<>(totalPoints);
        float a = (0.05f / distance) * dip;
        for (int x = 0; x < resolution; x++) {
            float particleLevel = (float) (a * x * (x - resolution));
            double pX = (pos2.x - pos1.x) * (invResolution) * x + pos1.x;
            double pY = (pos2.y - pos1.y) * (invResolution) * x + pos1.y + particleLevel;
            double pZ = (pos2.z - pos1.z) * (invResolution) * x + pos1.z;
            if (x % ppp == 0) points.add(new Vec3(pX, pY, pZ));
        }
        return points;
    }

    public static List<Vec3> cablePoints(Vec3 pos1, Vec3 pos2, float dip) {
        return cablePoints(pos1, pos2, dip, 1f);
    }

    /**
     * LOD 降采样版（2026-08-14 CEE 完整移植）：按 position（相机）与曲线的
     * 【最近距离】动态选择 detail 档位——近处密、远处疏：
     *   <100 或导线 <10 格 → detail=1（最密）
     *   <400 或导线 <20 格 → detail=2
     *   <1600 或导线 <30 格 → detail=10
     *   否则 → detail=20（最疏）
     * position 必须与 pos1/pos2 同坐标系（世界坐标对世界坐标）。
     */
    public static List<Vec3> cablePoints(Vec3 pos1, Vec3 pos2, float dip, Vec3 position) {
        float distance = (float) pos1.distanceTo(pos2);
        float wireLength = (float) pos1.distanceTo(pos2);
        if (distance > 1000) return Collections.emptyList(); // 防世界破坏
        if (distance < 1e-4) { List<Vec3> s = new ArrayList<>(1); s.add(pos1); return s; }

        double resolution = (distance * 2);
        double invResolution = 1 / resolution;
        float a = (0.05f / distance) * dip;

        // 扫描曲线全部采样点，求与相机的最近距离平方
        float shortestDistanceSqr = Float.MAX_VALUE;
        for (int x = 0; x < resolution; x++) {
            float particleLevel = (float) (a * x * (x - resolution));
            double pX = (pos2.x - pos1.x) * (invResolution) * x + pos1.x;
            double pY = (pos2.y - pos1.y) * (invResolution) * x + pos1.y + particleLevel;
            double pZ = (pos2.z - pos1.z) * (invResolution) * x + pos1.z;
            shortestDistanceSqr = Math.min(shortestDistanceSqr,
                    (float) position.distanceToSqr(pX, pY, pZ));
        }
        float detail;
        if (shortestDistanceSqr < 100 || wireLength < 10) detail = 1;
        else if (shortestDistanceSqr < 400 || wireLength < 20) detail = 2;
        else if (shortestDistanceSqr < 1600 || wireLength < 30) detail = 10;
        else detail = 20;

        return cablePoints(pos1, pos2, dip, detail);
    }

    /** 直线采样（无下垂，dip 仍用于分辨率）。 */
    public static List<Vec3> cablePointsRaw(Vec3 pos1, Vec3 pos2, float dip, float detail) {
        float distance = (float) pos1.distanceTo(pos2);
        if (distance > 1000) return Collections.emptyList();
        if (distance < 1e-4) { List<Vec3> s = new ArrayList<>(1); s.add(pos1); return s; }

        double resolution = Mth.ceil(distance * 2);
        double invResolution = 1 / resolution;
        int totalPoints = Mth.ceil(resolution / detail);
        int ppp = Math.max(1, Mth.ceil(resolution / totalPoints));
        List<Vec3> points = new ArrayList<>(totalPoints);
        float a = (0.05f / distance) * dip;
        for (int x = 0; x < resolution; x++) {
            float particleLevel = (float) (a * x * (x - resolution));
            double pX = (pos2.x - pos1.x) * (invResolution) * x + pos1.x;
            double pY = (pos2.y - pos1.y) * (invResolution) * x + pos1.y + particleLevel;
            double pZ = (pos2.z - pos1.z) * (invResolution) * x + pos1.z;
            if (x % ppp == 0) points.add(new Vec3(pX, pY, pZ));
        }
        return points;
    }

    public static List<Vec3> cablePointsRaw(Vec3 pos1, Vec3 pos2, float dip) {
        return cablePointsRaw(pos1, pos2, dip, 1f);
    }

    /** 某点处导线切线仰角（度），供附件/朝向。 */
    public static float pointElevationInDegrees(Vec3 pos1, Vec3 pos2, float point, float sag) {
        Vec3 pointAt1 = posAt(pos1, pos2, point, sag);
        Vec3 pointAt2 = posAt(pos1, pos2, point + 0.001f, sag);
        Vec3 directionVector = pointAt1.subtract(pointAt2);
        double horizontalDistance = Math.sqrt(directionVector.x * directionVector.x
                + directionVector.z * directionVector.z);
        return (float) Mth.atan2(directionVector.y, horizontalDistance) * Mth.RAD_TO_DEG;
    }
}

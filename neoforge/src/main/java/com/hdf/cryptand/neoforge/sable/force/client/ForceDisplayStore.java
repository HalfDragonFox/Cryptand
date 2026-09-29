/**
 * ===== 力显示客户端缓存（2026-09-14） =====
 *
 * <p>只保存【服务端被动回复】的最新一帧力数据 + 能力状态；渲染器只读本缓存。
 *
 * <p>能力三态：
 * <ul>
 *   <li>UNKNOWN：尚未探测（或长时间无响应 → 回到 UNKNOWN 重探）</li>
 *   <li>SUPPORTED：服务端接口已开，正常按间隔拉取</li>
 *   <li>UNSUPPORTED：服务端明确回 supported=false（或未装 Cryptand）→ 停止请求，提示一次</li>
 * </ul>
 */
package com.hdf.cryptand.neoforge.sable.force.client;

import com.hdf.cryptand.neoforge.sable.force.net.SableForceResponsePayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ForceDisplayStore {

    public static final ForceDisplayStore INSTANCE = new ForceDisplayStore();

    /** 渲染用样本（解析好 ResourceLocation，避免每帧 parse）。 */
    public static final class Sample {
        public final ResourceLocation id;
        public final int kind;
        public final double cx, cy, cz;
        public final double fx, fy, fz;

        Sample(final ResourceLocation id, final int kind,
               final double cx, final double cy, final double cz,
               final double fx, final double fy, final double fz) {
            this.id = id;
            this.kind = kind;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
        }

        public double magnitude() {
            return Math.sqrt(this.fx * this.fx + this.fy * this.fy + this.fz * this.fz);
        }
    }

    public enum Capability {
        UNKNOWN,
        SUPPORTED,
        UNSUPPORTED
    }

    private final Map<Integer, List<Sample>> frames = new ConcurrentHashMap<>();
    private volatile Capability capability = Capability.UNKNOWN;
    private volatile long lastUpdateMs = 0L;
    private volatile boolean detailed = false;
    /** 一次性提示（"服务器未开启力显示接口"）只打一次。 */
    private volatile boolean noticeLogged = false;

    private ForceDisplayStore() {
    }

    public void onResponse(final SableForceResponsePayload payload) {
        this.lastUpdateMs = System.currentTimeMillis();
        if (!payload.supported()) {
            this.capability = Capability.UNSUPPORTED;
            this.frames.clear();
            return;
        }
        this.capability = Capability.SUPPORTED;
        this.detailed = payload.detailed();

        final Map<Integer, List<Sample>> next = new ConcurrentHashMap<>();
        for (final SableForceResponsePayload.StructureForces sf : payload.structures()) {
            final List<Sample> list = new ArrayList<>(sf.samples().size());
            for (final SableForceResponsePayload.Sample s : sf.samples()) {
                final ResourceLocation rl = ResourceLocation.tryParse(s.forceId());
                if (rl == null) continue;
                list.add(new Sample(rl, s.kind(), s.cx(), s.cy(), s.cz(), s.fx(), s.fy(), s.fz()));
            }
            next.put(sf.structureKey(), list);
        }
        this.frames.clear();
        this.frames.putAll(next);
    }

    /** 断线/切世界：清空并回到 UNKNOWN（重连后重新探测）。 */
    public void reset() {
        this.frames.clear();
        this.capability = Capability.UNKNOWN;
        this.lastUpdateMs = 0L;
        this.noticeLogged = false;
    }

    /** 长时间无响应 → 回到 UNKNOWN（重新探测；服务端可能中途关闭了接口）。 */
    public void markStale() {
        this.capability = Capability.UNKNOWN;
        this.frames.clear();
    }

    public Map<Integer, List<Sample>> frames() {
        return this.frames;
    }

    public Capability capability() {
        return this.capability;
    }

    public boolean hasData() {
        return !this.frames.isEmpty();
    }

    public boolean detailed() {
        return this.detailed;
    }

    public long lastUpdateMs() {
        return this.lastUpdateMs;
    }

    public boolean noticeLogged() {
        return this.noticeLogged;
    }

    public void markNoticeLogged() {
        this.noticeLogged = true;
    }
}

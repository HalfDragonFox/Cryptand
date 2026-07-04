/**
 * ===== PowerGrid - background compute thread with parallel singleTick =====
 *
 * Target: org.patryk3211.powergrid.electricity.WorldNetworks
 * Strategy: @Inject at HEAD + cancellable=true (replaces preTick entirely)
 *
 * Architecture:
 *   1. Main thread preTick(): Phase 1-4 only (topology/islands/transmission lines)
 *      then caches config values to volatile fields for the background thread.
 *   2. Background daemon thread: runs prepare (serial) + singleTick (parallel)
 *      at a fixed interval. Uses FixedThreadPool. NativeMNA is thread-safe now
 *      because our fixed DLL uses JavaVM->GetEnv/AttachCurrentThread for JNI.
 *   3. Timeout via Future.get(deadline) prevents stalled rounds from blocking.
 *   4. Config values (interval, timeout, maxThreads) are cached in volatile
 *      fields so the background thread never touches ModConfigSpec directly.
 */

package com.hdf.cryptand.neoforge.mixin.powergrid;

import com.hdf.cryptand.neoforge.config.ConfigLoad;
import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.patryk3211.powergrid.electricity.WorldNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.PerformanceCounter;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePart;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(value = WorldNetworks.class, remap = false)
public abstract class WorldNetworksMixin {

    @Shadow @Final public net.minecraft.world.level.Level world;
    @Shadow private PerformanceCounter perf;
    @Shadow private boolean runningDiscovery;
    @Shadow private Set<ElectricalNetwork> islandDiscoveryQueue;
    @Shadow protected Set<TransmissionLinePart> deferredRewireEntities;
    @Shadow public List<ElectricalNetwork> subnetworks;
    @Shadow public java.util.Map<Integer, TransmissionLine> transmissionLines;
    @Shadow private void runIslandDiscoveryFor(ElectricalNetwork network) {}

    // ========== background compute ==========

    @Unique
    private static volatile ExecutorService cryptand$pool;

    @Unique
    private static int cryptand$lastThreadCount = -1;

    @Unique
    private static volatile Thread cryptand$computeThread;

    @Unique
    private static volatile Object cryptand$activeInstance;

    @Unique
    private static final AtomicBoolean cryptand$resultsReady = new AtomicBoolean(false);

    @Unique
    private static volatile long cryptand$completedRounds;

    @Unique
    private static volatile int cryptand$cachedMaxThreads;

    @Unique
    private static volatile int cryptand$cachedIntervalMs = 50;

    @Unique
    private static volatile int cryptand$cachedTimeoutMs = 50;

    @Unique
    private static volatile int cryptand$cachedMultiTick = 1;

    @Unique
    private static ExecutorService getPool() {
        int maxThreads = cryptand$cachedMaxThreads;
        int threads = maxThreads > 0 ? maxThreads
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

        if (cryptand$pool == null || threads != cryptand$lastThreadCount) {
            synchronized (WorldNetworksMixin.class) {
                if (cryptand$pool == null || threads != cryptand$lastThreadCount) {
                    if (cryptand$pool != null) cryptand$pool.shutdownNow();
                    cryptand$pool = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "Cryptand-Solver");
                        t.setDaemon(true);
                        return t;
                    });
                    cryptand$lastThreadCount = threads;
                }
            }
        }
        return cryptand$pool;
    }

    @Unique
    private static synchronized void cryptand$ensureComputeThread() {
        if (cryptand$computeThread != null && cryptand$computeThread.isAlive()) return;

        cryptand$computeThread = new Thread(() -> {
            PowerGrid.LOGGER.info("[Cryptand] Background compute thread started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long roundStart = System.currentTimeMillis();
                    Object raw = cryptand$activeInstance;
                    if (raw instanceof WorldNetworksMixin instance) {
                        instance.cryptand$doComputeRound();
                        cryptand$completedRounds++;
                        cryptand$resultsReady.set(true);
                    }

                    long elapsed = System.currentTimeMillis() - roundStart;
                    long sleepMs = cryptand$cachedIntervalMs - elapsed;
                    if (sleepMs > 0) Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            PowerGrid.LOGGER.info("[Cryptand] Background compute thread stopped");
        }, "Cryptand-PowerGrid-Compute");
        cryptand$computeThread.setDaemon(true);
        cryptand$computeThread.start();
    }

    @Unique
    private void cryptand$doComputeRound() {
        ExecutorService pool = getPool();
        int timeoutMs = cryptand$cachedTimeoutMs;
        int multiTick = cryptand$cachedMultiTick;

        this.perf.start();

        Iterator<ElectricalNetwork> netIter = this.subnetworks.iterator();
        while (netIter.hasNext()) {
            ElectricalNetwork network = netIter.next();
            if (network.isEmpty()) {
                netIter.remove();
                network.cleanup();
            } else {
                network.prepare(multiTick);
            }
        }

        if (this.subnetworks.isEmpty()) {
            this.perf.end();
            return;
        }

        List<Future<?>> futures = new ArrayList<>(this.subnetworks.size());
        for (ElectricalNetwork network : this.subnetworks) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < multiTick; i++) {
                    network.singleTick();
                }
            }));
        }

        if (timeoutMs > 0) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            int completed = 0, skipped = 0;
            for (Future<?> f : futures) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) { skipped++; f.cancel(true); continue; }
                try {
                    f.get(remaining, TimeUnit.MILLISECONDS);
                    completed++;
                } catch (TimeoutException e) { skipped++; f.cancel(true); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { PowerGrid.LOGGER.error("[Cryptand] Solve failed", e); }
            }
            if (skipped > 0) {
                PowerGrid.LOGGER.debug("[Cryptand] Round timeout: {} done, {} skipped",
                        completed, skipped);
            }
        } else {
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { PowerGrid.LOGGER.error("[Cryptand] Solve failed", e); }
            }
        }

        this.perf.end();
    }

    // ========== preTick - topology only, compute done by background thread ==========

    @Inject(method = "preTick", at = @At("HEAD"), cancellable = true)
    private void cryptand$preTick(CallbackInfo ci) {
        ci.cancel();

        // Phase 1: deferred rewire cleanup
        this.deferredRewireEntities.removeIf(part -> {
            part.refreshEndpointNodes();
            return true;
        });

        // Phase 2: process new junction nodes
        JunctionWireEndpoint.processNewNodes(this.world);

        // Phase 3: island discovery
        this.runningDiscovery = true;
        for (ElectricalNetwork network : this.islandDiscoveryQueue) {
            this.runIslandDiscoveryFor(network);
        }
        this.islandDiscoveryQueue.clear();
        this.runningDiscovery = false;

        // Phase 4: transmission line tick
        Iterator<TransmissionLine> lineIter = this.transmissionLines.values().iterator();
        ArrayList<TransmissionLine> removed = new ArrayList<>();
        while (lineIter.hasNext()) {
            TransmissionLine line = lineIter.next();
            if (line.segments.isEmpty()) {
                PowerGrid.LOGGER.warn("Empty transmission line {} dropped during tick", line);
                removed.add(line);
                lineIter.remove();
            } else {
                line.tick();
            }
        }
        removed.forEach(TransmissionLine::remove);

        // Cache config values for background thread (volatile => happens-before)
        try {
            cryptand$cachedMaxThreads = ConfigLoad.POWERGRID_MAX_THREADS.get();
            cryptand$cachedIntervalMs = ConfigLoad.POWERGRID_COMPUTE_INTERVAL_MS.get();
            cryptand$cachedTimeoutMs = ConfigLoad.POWERGRID_COMPUTE_TIMEOUT_MS.get();
            cryptand$cachedMultiTick = ModdedConfigs.server().electricity.solver.multiTicks.get();
        } catch (IllegalStateException ignored) {
            // config not loaded yet, use field defaults
        }

        cryptand$activeInstance = this;
        cryptand$ensureComputeThread();
    }
}
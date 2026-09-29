package com.hdf.cryptand.waterphysics;

/**
 * Performance probe: accumulates waterphysics main-thread work per tick and prints a
 * detailed report once per second while debug is on.
 *
 * <p>All log text is **English on purpose** — Chinese written into the log file comes out
 * garbled (encoding), so the report stays ASCII.
 *
 * <p>It answers four questions that used to be guesswork:
 * <ul>
 *   <li>how many cells the main thread actually reads per tick (and how long that takes);</li>
 *   <li>how many write-back entries come out of the queue, how many really call setBlock,
 *       and how many are skipped;</li>
 *   <li>how much of the tick waterphysics occupies in total;</li>
 *   <li>whether events (posts) actually make it through scan → solve → write-back.</li>
 * </ul>
 *
 * <p>Pure Java; offline gate <code>WaterphysicsSelfTest#testProbe</code>.
 */
public final class WaterphysicsProbe {

    private long ticks;
    private long captureCells;
    private long captureNanos;
    private long maxCaptureNanos;
    private long writeConsumed;
    private long writeApplied;
    private long writeSkipped;
    private long writeNanos;
    private long maxWriteNanos;
    private long tickNanos;
    private long maxTickNanos;
    /** Cells posted by events / write-back feedback. */
    private long posts;
    /** Regions submitted to the solver. */
    private long submits;
    private long activeCells;
    private long scannedCells;
    private long unloadedCells;
    /** Level entries produced by the solver. */
    private long changes;
    /** Rounds that finished with nothing left to do (all computed, no change). */
    private long settledRounds;
    /** Batches thrown away because an external change discarded the in-flight snapshot. */
    private long discarded;
    /** How often an external change discarded a region's in-flight batch. */
    private long bumps;

    /** Records one scan frame (cells read, nanos spent). */
    public void addCapture(final int cells, final long nanos) {
        captureCells += cells;
        captureNanos += nanos;
        if (nanos > maxCaptureNanos) {
            maxCaptureNanos = nanos;
        }
    }

    /** Records one write-back (consumed = entries taken, applied = real setBlock, skipped = no-op). */
    public void addWriteBack(final int consumed, final int applied, final int skipped, final long nanos) {
        writeConsumed += consumed;
        writeApplied += applied;
        writeSkipped += skipped;
        writeNanos += nanos;
        if (nanos > maxWriteNanos) {
            maxWriteNanos = nanos;
        }
    }

    /** Records the whole waterphysics main-thread cost of one tick. */
    public void addTick(final long nanos) {
        ticks++;
        tickNanos += nanos;
        if (nanos > maxTickNanos) {
            maxTickNanos = nanos;
        }
    }

    /** One cell was posted for scanning. */
    public void addPost() {
        posts++;
    }

    /** One region was handed to the solver. */
    public void addSubmit(final int active, final int scanned, final int unloaded) {
        submits++;
        activeCells += active;
        scannedCells += scanned;
        unloadedCells += unloaded;
    }

    /** One batch was thrown away (external change discarded the in-flight snapshot). */
    public void addDiscard() {
        discarded++;
    }

    /** An external change hit a region (its in-flight batch, if any, was discarded). */
    public void addBump() {
        bumps++;
    }

    /** How many external changes hit a region. */
    public long totalBumps() {
        return bumps;
    }

    /** The solver finished a round. */
    public void addSolve(final int changed, final boolean settled) {
        changes += changed;
        if (settled) {
            settledRounds++;
        }
    }

    public long ticks() {
        return ticks;
    }

    /** Total cells read from the world (was "sections" before the cell-level rewrite). */
    public long totalCaptureCells() {
        return captureCells;
    }

    public long totalWriteConsumed() {
        return writeConsumed;
    }

    public long totalWriteApplied() {
        return writeApplied;
    }

    public long totalWriteSkipped() {
        return writeSkipped;
    }

    public long totalPosts() {
        return posts;
    }

    /** Rounds discarded because the world changed while they were being computed. */
    public long totalDiscarded() {
        return discarded;
    }

    /** Average waterphysics main-thread cost per tick, in microseconds. */
    public long averageTickMicros() {
        return ticks == 0 ? 0 : tickNanos / ticks / 1000L;
    }

    /** Average number of real setBlock calls per tick. */
    public long averageAppliedPerTick() {
        return ticks == 0 ? 0 : writeApplied / ticks;
    }

    /** One-line ASCII report; safe to write into the log file. */
    public String report() {
        if (ticks == 0) {
            return "waterphysics probe: no samples";
        }
        return "waterphysics/sec ticks=" + ticks
                + " posts=" + posts
                + " submits=" + submits
                + " changes=" + changes
                + " settled=" + settledRounds
                + " discarded=" + discarded
                + " bumps=" + bumps
                + " | scan active=" + activeCells + " scanned=" + scannedCells + " unloaded=" + unloadedCells
                + " | capture avg=" + (captureNanos / ticks / 1000L) + "us peak=" + (maxCaptureNanos / 1000L) + "us"
                + " cells=" + captureCells
                + " | write out=" + writeConsumed + " setBlock=" + writeApplied + " skipped=" + writeSkipped
                + " avg=" + (writeNanos / ticks / 1000L) + "us peak=" + (maxWriteNanos / 1000L) + "us"
                + " | tick avg=" + averageTickMicros() + "us peak=" + (maxTickNanos / 1000L) + "us";
    }

    public void reset() {
        ticks = 0;
        captureCells = 0;
        captureNanos = 0;
        maxCaptureNanos = 0;
        writeConsumed = 0;
        writeApplied = 0;
        writeSkipped = 0;
        writeNanos = 0;
        maxWriteNanos = 0;
        tickNanos = 0;
        maxTickNanos = 0;
        posts = 0;
        submits = 0;
        activeCells = 0;
        scannedCells = 0;
        unloadedCells = 0;
        changes = 0;
        settledRounds = 0;
        discarded = 0;
        bumps = 0;
    }
}

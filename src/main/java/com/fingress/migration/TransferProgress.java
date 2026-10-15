package com.fingress.migration;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

final class TransferProgress {
    volatile String phase = "QUEUED", table = "";
    volatile long rowsRead, rowsSent, rowsCommitted, bytes, totalRows, started = System.currentTimeMillis();
    volatile long finished;
    volatile long tablesDone, tablesTotal;
    void tables(String phase, long total) { this.phase = phase; tablesTotal = total; tablesDone = 0; table = ""; }
    volatile boolean cancel;
    volatile Statement statement;
    private long phaseStarted = started, rateSampleAt = started, rateSampleBytes;
    private double copyRate;
    final Map<String,Double> phaseSeconds = new LinkedHashMap<>();
    synchronized void beginPhase(String next) {
        long now = System.currentTimeMillis();
        phaseSeconds.merge(phase, Math.max(0, now - phaseStarted) / 1000.0, Double::sum);
        phase = next; phaseStarted = now; rateSampleAt = now; rateSampleBytes = bytes; copyRate = 0;
    }
    /** Shown after a failure, e.g. that staged tables were kept for the next attempt. */
    volatile String note;
    /** Parallel readers each count on their own child, so no two threads update the same counters. */
    final TransferProgress parent;
    final List<TransferProgress> children = new CopyOnWriteArrayList<>();
    TransferProgress() { this(null); }
    private TransferProgress(TransferProgress parent) { this.parent = parent; }
    TransferProgress reader() { TransferProgress child = new TransferProgress(this); child.phase = phase; children.add(child); return child; }
    /** Folds a finished reader's counts into this progress. Called by the thread that owns this progress. */
    void release(TransferProgress child) { if (children.remove(child)) { rowsRead += child.rowsRead; bytes += child.bytes; } }
    boolean cancelled() { return cancel || parent != null && parent.cancelled(); }
    void check() { if (cancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException("Cancelled at a safe boundary"); }
    synchronized Model.Progress view() {
        long now = finished == 0 ? System.currentTimeMillis() : finished;
        if (phase.equals("COPYING") && now - rateSampleAt >= 1000) {
            copyRate = Math.max(0, bytes - rateSampleBytes) * 1000.0 / (now - rateSampleAt);
            rateSampleAt = now; rateSampleBytes = bytes;
        }
        long elapsed = Math.max(0, ((finished==0?System.currentTimeMillis():finished) - started)/1000);
        long read = rowsRead, sent = rowsSent, size = bytes, active = 0; String current = table, shownPhase = phase;
        if (!children.isEmpty()) {
            List<String> tables = new java.util.ArrayList<>();
            for (TransferProgress child : children) {
                read += child.rowsRead; size += child.bytes;
                if (child.table != null && !child.table.isBlank()) { tables.add(child.table); active++; }
                if ("RECONNECTING".equals(child.phase)) shownPhase = "RECONNECTING";
            }
            current = String.join(", ", tables);
        }
        long processed = shownPhase.equals("EXTRACTING") || shownPhase.equals("RECONNECTING") ? read : sent;
        double rate = elapsed == 0 ? 0 : (double)processed / elapsed;
        boolean directPhase = DIRECT_PHASES.contains(shownPhase);
        Long eta = !directPhase && rate > 0 && totalRows > processed ? (long)((totalRows-processed)/rate) : null;
        Map<String,Double> timings = new LinkedHashMap<>(phaseSeconds);
        timings.merge(phase, Math.max(0, now - phaseStarted) / 1000.0, Double::sum);
        return new Model.Progress(shownPhase,current,read,sent,rowsCommitted,size,totalRows,elapsed,rate,eta,cancelled(),tablesDone,tablesTotal,active,
                shownPhase.equals("COPYING") && finished == 0 ? copyRate : null, Math.max(0, now-phaseStarted)/1000, Map.copyOf(timings));
    }
    private static final java.util.Set<String> DIRECT_PHASES = java.util.Set.of("COPYING", "FINGERPRINTING", "COMMITTING");
}

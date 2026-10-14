package com.fingress.migration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.fingress.migration.Model.*;

/** Transfer settings worked out from the migration itself: the data drive, the Java heap and each table's
 * size in the source catalog. Used whenever a request carries no explicit options (the browser never does). */
final class AutoTuning {
    /** Source catalog statistics; either value is null when the database has none (e.g. never analysed). */
    record TableStats(Long rows, Long bytes) {}

    static final long MB = 1_000_000L, GB = 1_000_000_000L;
    static final long BATCH_BYTES = 16 * MB;
    static final int DEFAULT_TIMEOUT = 1800, MAX_TIMEOUT = 86_400;
    /** Slowest sustained rate a table operation is allowed before its timeout ends it. */
    static final long BYTES_PER_SECOND = MB / 2;
    /** Staged JSON and LOB files can be several times the source's compressed size. */
    static final long STAGING_FACTOR = 10;

    /** Plan-wide settings. Rows are not capped: free disk and per-table size limits are the real protections. */
    static MigrationOptions global(Path work, Dialect target) throws IOException {
        long total = Files.getFileStore(work).getTotalSpace();
        long reserve = Math.min(Math.max(GB, total / 20), 50 * GB);
        return new MigrationOptions(1_000_000_000L, 1_000_000_000_000L, reserve, 1000, 1000, BATCH_BYTES,
                DEFAULT_TIMEOUT, DEFAULT_TIMEOUT, true, target == Dialect.POSTGRESQL);
    }

    /** Memory one fetch may use: a small share of the heap, so several in-flight copies still fit. */
    static long fetchBudget(long maxHeap) { return Math.min(Math.max(maxHeap / 32, 8 * MB), 64 * MB); }

    /** Rows with documents or long text vary widely around the average, so their fetches allow for this much larger rows. */
    static final long LARGE_VALUE_SPREAD = 8;

    static MigrationOptions forTable(MigrationOptions global, TableStats stats, long maxHeap, boolean largeValues) {
        Long rows = stats == null ? null : stats.rows(), bytes = stats == null ? null : stats.bytes();
        Long rowBytes = rows != null && rows > 0 && bytes != null && bytes > 0 ? Math.max(1, bytes / rows) : null;
        int fetch = rowBytes == null ? (largeValues ? DatabaseGateway.LARGE_VALUE_FETCH : global.fetchSize())
                : (int) clamp(fetchBudget(maxHeap) / (rowBytes * (largeValues ? LARGE_VALUE_SPREAD : 1)), 10, 10_000);
        int batch = rowBytes == null ? global.batchRows() : (int) clamp(BATCH_BYTES / rowBytes, 10, 5_000);
        long maxTable = bytes == null ? 10 * GB : clamp(bytes * STAGING_FACTOR + GB, GB, global.maxTableBytes());
        int timeout = bytes == null ? DEFAULT_TIMEOUT : (int) clamp(bytes / BYTES_PER_SECOND, DEFAULT_TIMEOUT, MAX_TIMEOUT);
        return new MigrationOptions(global.maxRows(), maxTable, global.diskReserveBytes(), fetch, batch, global.batchBytes(),
                timeout, global.readTimeoutSeconds(), global.validateData(), global.useCopy());
    }

    static MigrationOptions withFetchSize(MigrationOptions o, int fetchSize) {
        return new MigrationOptions(o.maxRows(), o.maxTableBytes(), o.diskReserveBytes(), fetchSize, o.batchRows(), o.batchBytes(),
                o.queryTimeoutSeconds(), o.readTimeoutSeconds(), o.validateData(), o.useCopy());
    }

    /** A connection must wait at least as long as its slowest table may take, or the socket gives up first. */
    static MigrationOptions withReadTimeout(MigrationOptions global, Collection<MigrationOptions> tables) {
        int longest = tables.stream().mapToInt(MigrationOptions::queryTimeoutSeconds).max().orElse(global.readTimeoutSeconds());
        return new MigrationOptions(global.maxRows(), global.maxTableBytes(), global.diskReserveBytes(), global.fetchSize(), global.batchRows(),
                global.batchBytes(), global.queryTimeoutSeconds(), Math.max(global.readTimeoutSeconds(), longest), global.validateData(), global.useCopy());
    }

    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private AutoTuning() {}
}

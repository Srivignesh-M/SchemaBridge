package com.fingress.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoTuningTest {
    static final long GB = AutoTuning.GB, MB = AutoTuning.MB, HEAP = 3_800 * MB;
    @TempDir Path directory;

    MigrationOptions global(Dialect target) throws Exception { return AutoTuning.global(directory, target); }

    @Test void planWideSettingsComeFromTheDriveAndTarget() throws Exception {
        MigrationOptions pg = global(Dialect.POSTGRESQL);
        long total = Files.getFileStore(directory).getTotalSpace();
        assertEquals(Math.min(Math.max(GB, total / 20), 50 * GB), pg.diskReserveBytes().longValue());
        assertEquals(1_000_000_000L, pg.maxRows().longValue(), "Rows are not capped; disk and per-table limits protect instead");
        assertTrue(pg.validateData()); assertTrue(pg.useCopy());
        assertFalse(global(Dialect.ORACLE).useCopy(), "COPY only applies to PostgreSQL targets");
    }

    @Test void documentTableGetsSmallFetchesAndALongTimeout() throws Exception {
        // fg_dms_content: 13,809 rows, 5.8 GB including TOAST, roughly 420 KB per row.
        MigrationOptions documents = AutoTuning.forTable(global(Dialect.POSTGRESQL), new AutoTuning.TableStats(13_809L, 5_800 * MB), HEAP, true);
        assertEquals((int) (64 * MB / (5_800 * MB / 13_809 * AutoTuning.LARGE_VALUE_SPREAD)), documents.fetchSize().intValue(), "allows for rows 8x the 420 KB average");
        assertEquals((int) (16 * MB / (5_800 * MB / 13_809)), documents.batchRows().intValue(), "16 MB per insert batch");
        assertEquals(11_600, documents.queryTimeoutSeconds(), "5.8 GB at the 0.5 MB/s floor");
        assertEquals(5_800 * MB * 10 + GB, documents.maxTableBytes().longValue());
    }

    @Test void narrowTableGetsLargeFetchesAndTheDefaultTimeout() throws Exception {
        MigrationOptions codes = AutoTuning.forTable(global(Dialect.POSTGRESQL), new AutoTuning.TableStats(50_000L, 4 * MB), HEAP, false);
        assertEquals(10_000, codes.fetchSize()); assertEquals(5_000, codes.batchRows());
        assertEquals(AutoTuning.DEFAULT_TIMEOUT, codes.queryTimeoutSeconds()); assertEquals(4 * MB * 10 + GB, codes.maxTableBytes().longValue());
    }

    @Test void smallHeapShrinksTheFetchBudget() throws Exception {
        MigrationOptions small = AutoTuning.forTable(global(Dialect.POSTGRESQL), new AutoTuning.TableStats(1_000_000L, 1_000 * MB), 256 * MB, false);
        assertEquals((int) (8 * MB / 1_000), small.fetchSize().intValue(), "256 MB heap: 8 MB budget over 1 KB rows");
    }

    @Test void missingStatisticsFallBackToSafeDefaults() throws Exception {
        MigrationOptions g = global(Dialect.POSTGRESQL);
        for (AutoTuning.TableStats stats : Arrays.asList(null, new AutoTuning.TableStats(null, null), new AutoTuning.TableStats(0L, 8192L))) {
            MigrationOptions t = AutoTuning.forTable(g, stats, HEAP, false);
            assertEquals(1000, t.fetchSize()); assertEquals(AutoTuning.DEFAULT_TIMEOUT, t.queryTimeoutSeconds());
            assertEquals(DatabaseGateway.LARGE_VALUE_FETCH, AutoTuning.forTable(g, stats, HEAP, true).fetchSize(), "unknown large-value tables stay at the cap");
        }
        assertEquals(10 * GB, AutoTuning.forTable(g, null, HEAP, false).maxTableBytes().longValue());
    }

    @Test void textTableWithSmallRowsKeepsLargeFetches() throws Exception {
        // A typical audit table: a text column, but rows average about 1 KB.
        MigrationOptions audit = AutoTuning.forTable(global(Dialect.POSTGRESQL), new AutoTuning.TableStats(614_409L, 614 * MB), HEAP, true);
        assertEquals((int) (64 * MB / (614 * MB / 614_409 * AutoTuning.LARGE_VALUE_SPREAD)), audit.fetchSize().intValue());
        assertTrue(audit.fetchSize() > 1000, "small-row text tables must not be throttled to the 10-row cap");
    }

    @Test void readTimeoutCoversTheSlowestTable() throws Exception {
        MigrationOptions g = global(Dialect.POSTGRESQL);
        var tables = List.of(AutoTuning.forTable(g, new AutoTuning.TableStats(10L, MB), HEAP, false), AutoTuning.forTable(g, new AutoTuning.TableStats(13_809L, 5_800 * MB), HEAP, true));
        assertEquals(11_600, AutoTuning.withReadTimeout(g, tables).readTimeoutSeconds());
        assertEquals(AutoTuning.DEFAULT_TIMEOUT, AutoTuning.withReadTimeout(g, List.of()).readTimeoutSeconds());
    }

    @Test void plansWithoutOptionsAreTunedPerTableAndRun() throws Exception {
        String sourceUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(sourceUrl); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE documents(id INTEGER PRIMARY KEY, body BYTEA); INSERT INTO documents VALUES(1, X'CAFE')");
            s.execute("CREATE TABLE codes(id INTEGER PRIMARY KEY); INSERT INTO codes VALUES(1),(2)");
        }
        var stats = Map.of("documents", new AutoTuning.TableStats(13_809L, 5_800 * MB), "codes", new AutoTuning.TableStats(50_000L, 4 * MB));
        var db = new DatabaseGateway() {
            @Override public Connection connect(ConnectionSpec spec) throws SQLException { return DriverManager.getConnection(spec.host().equals("source") ? sourceUrl : targetUrl); }
            @Override public AutoTuning.TableStats tableStats(Connection c, String schema, String table, Dialect dialect) { return stats.get(table); }
        };
        var source = new ConnectionSpec(Dialect.POSTGRESQL, "source", 5432, "test", "user", "x");
        var target = new ConnectionSpec(Dialect.POSTGRESQL, "target", 5432, "test", "user", "x");
        try (var service = new MigrationService(db, new ObjectMapper(), directory.resolve("work").toString(), 2_000_000, 30)) {
            PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, "public", null, source, "public", List.of("documents", "codes"), true, target, null, Map.of(), false));
            assertTrue(plan.automatic());
            assertEquals(11_600, plan.tableOptions().get("documents").queryTimeoutSeconds());
            assertEquals(10_000, plan.tableOptions().get("codes").fetchSize());
            long rowBytes = 5_800 * MB / 13_809;
            assertEquals((int) Math.max(10, AutoTuning.fetchBudget(Runtime.getRuntime().maxMemory()) / (rowBytes * AutoTuning.LARGE_VALUE_SPREAD)), plan.tableOptions().get("documents").fetchSize(), "documents allow for rows 8x the average");
            assertEquals(11_600, plan.options().readTimeoutSeconds(), "Connections must outlast the slowest table");
            JobView job = service.execute(plan.id(), new ExecuteRequest(target, Map.of("documents", Action.CREATE_AND_LOAD, "codes", Action.CREATE_AND_LOAD)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (Set.of("QUEUED", "RUNNING").contains(job.state()) && System.nanoTime() < end) { Thread.sleep(10); job = service.job(job.id()); }
            assertEquals("SUCCEEDED", job.state(), job.message());
            // Per-table settings survive a restart, so a resumed job keeps them.
            PlanView restored;
            service.close();
            try (var reopened = new MigrationService(db, new ObjectMapper(), directory.resolve("work").toString(), 2_000_000, 30)) { restored = reopened.view(plan.id()); }
            assertTrue(restored.automatic()); assertEquals(plan.tableOptions(), restored.tableOptions());
        }
    }

    @Test void explicitOptionsAreUsedAsGiven() throws Exception {
        String sourceUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(sourceUrl); Statement s = c.createStatement()) { s.execute("CREATE TABLE codes(id INTEGER PRIMARY KEY)"); }
        var db = new DatabaseGateway() { @Override public Connection connect(ConnectionSpec spec) throws SQLException { return DriverManager.getConnection(sourceUrl); } };
        var source = new ConnectionSpec(Dialect.POSTGRESQL, "source", 5432, "test", "user", "x");
        try (var service = new MigrationService(db, new ObjectMapper(), directory.resolve("explicit").toString(), 2_000_000, 30)) {
            PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, "public", null, source, "public", List.of("codes"), true, null, MigrationOptions.defaults(5), Map.of(), false));
            assertFalse(plan.automatic()); assertEquals(5L, plan.options().maxRows().longValue());
            assertEquals(plan.options(), plan.tableOptions().get("codes"), "explicit options apply unchanged to tables without large values");
        }
    }
}

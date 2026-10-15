package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Parallel source readers and continuing an interrupted preparation (H2 fixture). */
class ParallelAndResumeTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final ConnectionSpec source = new ConnectionSpec(Dialect.POSTGRESQL, "source", 5432, "test", "user", "private-secret");
    final ConnectionSpec target = new ConnectionSpec(Dialect.POSTGRESQL, "target", 5432, "test", "user", "private-secret");
    String sourceUrl, targetUrl; MigrationService service;
    /** Export calls per source table, and tables whose next export should fail once with this SQL state. */
    final Map<String, AtomicInteger> exports = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, String> failOnce = new java.util.concurrent.ConcurrentHashMap<>();
    final Set<String> active = java.util.concurrent.ConcurrentHashMap.newKeySet(); final AtomicInteger mostAtOnce = new AtomicInteger();

    DatabaseGateway gateway() {
        return new DatabaseGateway() {
            @Override public Connection connect(ConnectionSpec spec) throws SQLException { return DriverManager.getConnection(spec.host().equals("source") ? sourceUrl : targetUrl); }
            @Override Export exportTyped(Connection c, Table table, Table filterTable, TableSelection selection, String schema, Dialect sourceDialect, Dialect targetDialect,
                                         Path path, AtomicLong budget, MigrationOptions options, TransferProgress progress) throws Exception {
                String name = table.name().in(sourceDialect); exports.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                active.add(name); mostAtOnce.accumulateAndGet(active.size(), Math::max);
                try {
                    Thread.sleep(40); // long enough for readers to overlap
                    Export export = super.exportTyped(c, table, filterTable, selection, schema, sourceDialect, targetDialect, path, budget, options, progress);
                    String state = failOnce.remove(name);
                    if (state != null) throw new SQLException("Simulated failure reading " + name, state);
                    return export;
                } finally { active.remove(name); }
            }
        };
    }
    @BeforeEach void setup() throws Exception {
        sourceUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        targetUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(sourceUrl); Statement s = c.createStatement()) {
            for (int t = 0; t < 9; t++) {
                s.execute("CREATE TABLE t" + t + "(id INTEGER PRIMARY KEY, doc BYTEA, note VARCHAR(20))");
                for (int r = 0; r < t + 1; r++) s.execute("INSERT INTO t" + t + " VALUES(" + r + ", X'" + String.format("%02x", t).repeat(9_000 + r) + "', 'row " + r + "')");
            }
        }
        service = new MigrationService(gateway(), json, directory.toString(), 2_000_000, 30);
    }
    @AfterEach void close() { service.close(); }
    List<String> tables(int count) { List<String> names = new ArrayList<>(); for (int t = 0; t < count; t++) names.add("t" + t); return names; }
    PlanRequest request(List<String> tables) { return new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, "public", null, source, "public", tables, true, target, null, Map.of(), false); }
    PreparationView prepared(PlanRequest request) throws Exception {
        PreparationView p = service.prepare(request); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (Set.of("QUEUED", "PREPARING").contains(p.state()) && System.nanoTime() < end) { Thread.sleep(10); p = service.preparation(p.id()); }
        return p;
    }
    JobView run(PlanView plan) throws Exception {
        Map<String, Action> actions = new LinkedHashMap<>(); plan.tables().forEach(t -> actions.put(t.table(), Action.CREATE_AND_LOAD));
        JobView job = service.execute(plan.id(), new ExecuteRequest(target, actions)); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (Set.of("QUEUED", "RUNNING").contains(job.state()) && System.nanoTime() < end) { Thread.sleep(10); job = service.job(job.id()); }
        return job;
    }
    long lobFiles(String planId) throws Exception { try (var files = Files.list(directory.resolve(planId))) { return files.filter(f -> f.getFileName().toString().startsWith("lob-")).count(); } }

    @Test void parallelReadersStageEveryTableInSelectionOrderAndTheRunVerifiesThem() throws Exception {
        PreparationView p = prepared(request(tables(9)));
        assertEquals("READY", p.state(), p.message());
        assertTrue(mostAtOnce.get() > 1, "tables must be read in parallel");
        assertTrue(mostAtOnce.get() <= SourceExtraction.MAX_READERS);
        PlanView plan = p.plan();
        assertEquals(tables(9), plan.tables().stream().map(TableReport::table).toList(), "plan order follows the selection, not finishing order");
        for (int t = 0; t < 9; t++) assertEquals(t + 1, plan.tables().get(t).rows());
        assertEquals(45, lobFiles(plan.id()), "one LOB file per row, none left over");
        // Each table's LOB files carry its index, so they can be told apart while tables are written in parallel.
        try (var files = Files.list(directory.resolve(plan.id()))) { assertEquals(9, files.filter(f -> f.getFileName().toString().startsWith("lob-00000008-")).count()); }
        assertEquals(45, p.progress().rowsRead(), "reader counts are folded into the preparation's progress");
        assertEquals(9, p.progress().tablesDone());
        JobView job = run(plan);
        assertEquals("SUCCEEDED", job.state(), job.message());
        job.tables().forEach(t -> assertEquals("PASSED", t.validation().outcome(), t.table()));
    }

    @Test void interruptedPreparationContinuesFromTheStagedTables() throws Exception {
        int readers = SourceExtraction.MAX_READERS; SourceExtraction.MAX_READERS = 1;
        try {
            failOnce.put("t5", "42P01");
            PreparationView failed = prepared(request(tables(9)));
            assertEquals("FAILED", failed.state());
            assertTrue(failed.message().contains("The 5 tables staged so far were kept"), failed.message());
            StorageView storage = service.storage();
            PlanStorage interrupted = storage.plans().stream().filter(s -> s.state().equals("INTERRUPTED")).findFirst().orElseThrow();
            assertEquals(5, interrupted.tables()); assertEquals(9, interrupted.tablesTotal()); assertTrue(storage.orphanedDirectories().isEmpty());
            exports.clear();
            PreparationView resumed = prepared(request(tables(9)));
            assertEquals("READY", resumed.state(), resumed.message());
            assertEquals(interrupted.id(), resumed.plan().id(), "the interrupted folder becomes the plan");
            for (int t = 0; t < 5; t++) assertNull(exports.get("t" + t), "t" + t + " was staged before and must not be read again");
            for (int t = 5; t < 9; t++) assertEquals(1, exports.get("t" + t).get());
            assertTrue(resumed.plan().warnings().stream().anyMatch(w -> w.contains("Continued an interrupted preparation") && w.contains("5 tables were reused")), resumed.plan().warnings().toString());
            assertFalse(Files.exists(directory.resolve(resumed.plan().id()).resolve(MigrationService.PARTIAL)));
            assertEquals(45, lobFiles(resumed.plan().id()));
            assertEquals("SUCCEEDED", run(resumed.plan()).state());
        } finally { SourceExtraction.MAX_READERS = readers; }
    }

    @Test void changedStagedFilesAreReadAgainAndOtherSelectionsStartFresh() throws Exception {
        int readers = SourceExtraction.MAX_READERS; SourceExtraction.MAX_READERS = 1;
        try {
            failOnce.put("t3", "42P01");
            assertEquals("FAILED", prepared(request(tables(4))).state());
            String folder = service.storage().plans().stream().filter(s -> s.state().equals("INTERRUPTED")).findFirst().orElseThrow().id();
            // A different table list is a different preparation and does not touch the interrupted one.
            exports.clear();
            PreparationView other = prepared(request(tables(3)));
            assertEquals("READY", other.state()); assertNotEquals(folder, other.plan().id()); assertEquals(1, exports.get("t0").get());
            // Damage one staged table: it is read again, the others are reused.
            Path t1 = directory.resolve(folder).resolve(SourceExtraction.dataFile(1));
            String original = Files.readString(t1);
            Files.writeString(t1, original.replace("row 0", "bad 0")); // same size, different content
            Path lob;
            try (var files = Files.list(directory.resolve(folder))) {
                lob = files.filter(f -> f.getFileName().toString().startsWith("lob-00000002-")).findFirst().orElseThrow();
            }
            byte[] bytes = Files.readAllBytes(lob); bytes[0] ^= 1; Files.write(lob, bytes);
            exports.clear();
            PreparationView resumed = prepared(request(tables(4)));
            assertEquals("READY", resumed.state(), resumed.message()); assertEquals(folder, resumed.plan().id());
            assertNull(exports.get("t0")); assertEquals(1, exports.get("t1").get()); assertEquals(1, exports.get("t2").get()); assertEquals(1, exports.get("t3").get());
            assertEquals(2, resumed.plan().tables().get(1).rows());
        } finally { SourceExtraction.MAX_READERS = readers; }
    }

    @Test void cancelledPreparationIsDiscardedAndInterruptedOnesCanBeDeleted() throws Exception {
        int readers = SourceExtraction.MAX_READERS; SourceExtraction.MAX_READERS = 1;
        try {
            failOnce.put("t2", "42P01");
            assertEquals("FAILED", prepared(request(tables(4))).state());
            String folder = service.storage().plans().stream().filter(s -> s.state().equals("INTERRUPTED")).findFirst().orElseThrow().id();
            service.delete(folder);
            assertFalse(Files.exists(directory.resolve(folder)));
            assertThrows(IllegalArgumentException.class, () -> service.delete(folder));
            // Cancelling keeps nothing.
            PreparationView started = service.prepare(request(tables(9)));
            long started_ = System.nanoTime();
            while (exports.isEmpty() && System.nanoTime() - started_ < TimeUnit.SECONDS.toNanos(30)) Thread.sleep(5);
            service.cancelPreparation(started.id());
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); PreparationView p = started;
            while (Set.of("QUEUED", "PREPARING").contains(p.state()) && System.nanoTime() < end) { Thread.sleep(10); p = service.preparation(p.id()); }
            assertEquals("CANCELLED", p.state(), p.message());
            assertTrue(service.storage().plans().isEmpty(), "a cancelled preparation leaves nothing behind");
        } finally { SourceExtraction.MAX_READERS = readers; }
    }

    @Test void oneFailingReaderStopsTheOthersAndKeepsTheirFinishedTables() throws Exception {
        failOnce.put("t4", "42P01");
        PreparationView failed = prepared(request(tables(9)));
        assertEquals("FAILED", failed.state());
        assertFalse(failed.progress().cancelRequested());
        PlanStorage interrupted = service.storage().plans().stream().filter(s -> s.state().equals("INTERRUPTED")).findFirst().orElseThrow();
        assertTrue(interrupted.tables() >= 1 && interrupted.tables() < 9, "finished tables are kept, the failing one is not: " + interrupted.tables());
        PreparationView resumed = prepared(request(tables(9)));
        assertEquals("READY", resumed.state(), resumed.message());
        assertEquals(9, resumed.plan().tables().size());
        assertEquals("SUCCEEDED", run(resumed.plan()).state());
    }
}

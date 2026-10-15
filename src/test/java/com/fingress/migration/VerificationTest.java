package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

/** How loaded tables are verified: full fingerprints for normal tables, row count plus a sample for large ones. */
class VerificationTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final ConnectionSpec source = new ConnectionSpec(Dialect.POSTGRESQL, "source", 5432, "test", "user", "secret");
    final ConnectionSpec target = new ConnectionSpec(Dialect.POSTGRESQL, "target", 5432, "test", "user", "secret");
    String sourceUrl, targetUrl; MigrationService service; long savedLimit; int savedSamples;

    @BeforeEach void setup() throws Exception {
        sourceUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        targetUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        sql(sourceUrl, "CREATE TABLE keyed(id INTEGER PRIMARY KEY, doc BYTEA, note VARCHAR(20)); INSERT INTO keyed(id) SELECT * FROM SYSTEM_RANGE(1, 1000);"
                + "UPDATE keyed SET doc = CAST(REPEAT('ab', 500) AS BYTEA), note = 'n' || id;"
                // Every sampled row (every 10th) has a document over 8 KB, staged as its own file.
                + "UPDATE keyed SET doc = CAST(REPEAT('cd', 9000) AS BYTEA) WHERE MOD(id, 10) = 1");
        sql(sourceUrl, "CREATE TABLE keyless(id INTEGER, note VARCHAR(20)); INSERT INTO keyless(id) SELECT * FROM SYSTEM_RANGE(1, 40); UPDATE keyless SET note = 'n' || id");
        DatabaseGateway db = new DatabaseGateway() { @Override public Connection connect(ConnectionSpec spec) throws SQLException { return DriverManager.getConnection(spec.host().equals("source") ? sourceUrl : targetUrl); } };
        service = new MigrationService(db, json, directory.toString(), 2_000_000, 30);
        savedLimit = MigrationService.FULL_VERIFY_BYTES; savedSamples = MigrationService.SAMPLE_ROWS;
    }
    @AfterEach void close() { service.close(); MigrationService.FULL_VERIFY_BYTES = savedLimit; MigrationService.SAMPLE_ROWS = savedSamples; }
    void sql(String url, String sql) throws Exception { try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) { s.execute(sql); } }
    PlanView plan() throws Exception { return service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, "public", null, source, "public", List.of("keyed", "keyless"), true, target, null, Map.of(), false)); }
    JobView run(PlanView plan) throws Exception {
        JobView job = service.execute(plan.id(), new ExecuteRequest(target, Map.of("keyed", Action.CREATE_AND_LOAD, "keyless", Action.CREATE_AND_LOAD)));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (Set.of("QUEUED", "RUNNING").contains(job.state()) && System.nanoTime() < end) { Thread.sleep(10); job = service.job(job.id()); }
        assertEquals("SUCCEEDED", job.state(), job.message()); return job;
    }
    ValidationReport validation(JobView job, String table) { return job.tables().stream().filter(t -> t.table().equals(table)).findFirst().orElseThrow().validation(); }

    @Test void normalTablesKeepFullFingerprints() throws Exception {
        JobView job = run(plan());
        assertEquals("FINGERPRINT", validation(job, "keyed").method()); assertEquals(1000, validation(job, "keyed").rowsChecked());
        assertEquals("FINGERPRINT", validation(job, "keyless").method());
    }

    @Test void largeTablesAreVerifiedByRowCountAndASample() throws Exception {
        MigrationService.FULL_VERIFY_BYTES = 0; MigrationService.SAMPLE_ROWS = 100;
        JobView job = run(plan());
        ValidationReport keyed = validation(job, "keyed");
        assertEquals("SAMPLED", keyed.method()); assertEquals("PASSED", keyed.outcome());
        assertEquals(100, keyed.rowsChecked(), "every 10th of 1,000 rows, spread over the whole table");
        ValidationReport keyless = validation(job, "keyless");
        assertEquals("ROW_COUNT", keyless.method()); assertEquals(40, keyless.rowsChecked());
        assertTrue(keyless.skippedReason().contains("no primary key"), keyless.skippedReason());
    }

    @Test void theSampleDetectsChangedContent() throws Exception {
        PlanView plan = plan(); run(plan);
        sql(targetUrl, "UPDATE keyed SET note = 'changed' WHERE id = 21");
        Path staged = directory.resolve(plan.id()).resolve(SourceExtraction.dataFile(0));
        Table table = service.planTables(plan.id()).stream().filter(t -> t.name().in(Dialect.POSTGRESQL).equals("keyed")).findFirst().orElseThrow();
        try (Connection c = DriverManager.getConnection(targetUrl)) {
            var failure = assertThrows(IllegalArgumentException.class, () -> RevertRows.sample(c, table, "public", Dialect.POSTGRESQL, staged, 1000, 100, MigrationOptions.defaults(), new TransferProgress()));
            assertTrue(failure.getMessage().contains("sample check failed for keyed"), failure.getMessage());
            sql(targetUrl, "UPDATE keyed SET note = 'n21' WHERE id = 21");
            assertEquals(100, RevertRows.sample(c, table, "public", Dialect.POSTGRESQL, staged, 1000, 100, MigrationOptions.defaults(), new TransferProgress()));
        }
    }

    @Test void stagedTablesNeedNoSeparateReceiptAndStillRevert() throws Exception {
        PlanView plan = plan(); JobView job = run(plan);
        assertTrue(json.readTree(directory.resolve(plan.id()).resolve("job.json").toFile()).get("createdFingerprints").isEmpty(), "the staged copy is the receipt");
        RevertPreview review = service.previewRevert(job.id(), target);
        JobView reverted = service.revert(job.id(), new RevertRequest(target, review.token()));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Set.of("QUEUED", "RUNNING").contains(reverted.revert().state()) && System.nanoTime() < end) { Thread.sleep(10); reverted = service.job(job.id()); }
        assertEquals("REVERTED", reverted.revert().state(), reverted.revert().message());
    }
}

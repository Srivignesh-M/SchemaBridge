package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationServiceTest {
    @TempDir Path directory;
    MigrationService service; DatabaseGateway gateway; String url;
    final ConnectionSpec target = new ConnectionSpec(Dialect.POSTGRESQL, "localhost", 5432, "test", "user", "secret-password");
    static final String DDL = "CREATE TABLE customers (id NUMBER(10,0) PRIMARY KEY, name VARCHAR2(100) NOT NULL);";
    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        gateway = new DatabaseGateway() { @Override public Connection connect(ConnectionSpec spec) throws SQLException { return DriverManager.getConnection(url); } };
        service = new MigrationService(gateway, new ObjectMapper(), directory.toString(), 100, 30);
    }
    @AfterEach void close() { service.close(); }
    PlanView plan(String sql, boolean connected) throws Exception { return service.create(new PlanRequest(Dialect.ORACLE, Dialect.POSTGRESQL, "public", sql, null, null, List.of(), true, connected ? target : null)); }
    void sql(String sql) throws Exception { try (Connection c = gateway.connect(target); Statement s = c.createStatement()) { s.execute(sql); } }
    long count(String table) throws Exception { try (Connection c = gateway.connect(target); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getLong(1); } }
    JobView await(JobView job) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { job = service.job(job.id()); if (!List.of("RUNNING", "QUEUED").contains(job.state()) && !job.tables().isEmpty()) return job; Thread.sleep(10); }
        fail("Job did not complete: " + job); return job;
    }
    @Test void createsAndLoadsMissingTable() throws Exception {
        PlanView plan = plan(DDL + "INSERT INTO customers VALUES (1,'Anita');", true);
        assertTrue(plan.issues().isEmpty(), plan.issues().toString()); assertEquals(Status.NEW, plan.tables().getFirst().status());
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD))));
        assertEquals("SUCCEEDED", job.state(), job.message()); assertEquals(1, count("customers")); assertEquals(1, job.tables().getFirst().rows());
    }
    @Test void structuredValidationReportDistinguishesMethodScopeAndSkipped() throws Exception {
        PlanView plan = plan(DDL + "CREATE TABLE orders (id NUMBER(8));" + "INSERT INTO customers VALUES (1,'Anita');", true);
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD, "orders", Action.SKIP))));
        assertEquals("SUCCEEDED", job.state(), job.message());
        // This test class only exercises SQL-text input (no source database connection), which is
        // never "typed" staged data, so this is the ROW_COUNT path. See TransferFeaturesTest for the
        // FINGERPRINT path, which requires a real database-to-database (typed) transfer.
        TableResult customers = job.tables().stream().filter(t -> t.table().equals("customers")).findFirst().orElseThrow();
        assertEquals("ROW_COUNT", customers.validation().method()); assertEquals("PASSED", customers.validation().outcome());
        assertEquals(1, customers.validation().rowsChecked()); assertTrue(customers.validation().durationSeconds() >= 0);
        assertNull(customers.validation().skippedReason());
        TableResult orders = job.tables().stream().filter(t -> t.table().equals("orders")).findFirst().orElseThrow();
        assertEquals("SKIPPED", orders.validation().outcome()); assertEquals("NONE", orders.validation().method());
        assertFalse(orders.validation().skippedReason().isBlank());
    }
    @Test void validationDisabledIsReportedAsNoneNotSilentlyOmitted() throws Exception {
        PlanView plan = service.create(new PlanRequest(Dialect.ORACLE, Dialect.POSTGRESQL, "public", DDL + "INSERT INTO customers VALUES (1,'Anita');",
                null, null, List.of(), true, target, new MigrationOptions(null,null,null,null,null,null,null,null,false,null), Map.of(), false));
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD))));
        assertEquals("SUCCEEDED", job.state(), job.message());
        ValidationReport validation = job.tables().getFirst().validation();
        assertEquals("NONE", validation.method()); assertEquals("Validation disabled by user.", validation.skippedReason());
    }
    @Test void existingMatchingTableOffersDmlOnlyAndPreservesExistingRows() throws Exception {
        sql("CREATE TABLE customers (name VARCHAR(100) NOT NULL, id NUMERIC(10,0) PRIMARY KEY)"); sql("INSERT INTO customers VALUES ('Existing',7)");
        PlanView plan = plan(DDL + "INSERT INTO customers (id,name) VALUES (1,'New');", true);
        assertEquals(Status.MATCH, plan.tables().getFirst().status(), plan.tables().getFirst().differences().toString());
        assertEquals(List.of(Action.DML_ONLY, Action.SKIP), plan.tables().getFirst().allowedActions());
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.DML_ONLY))));
        assertEquals("SUCCEEDED", job.state(), job.message()); assertEquals(2, count("customers"));
    }
    @Test void mismatchIsConsolidatedAndCannotBeOverriddenByClient() throws Exception {
        sql("CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(20) NOT NULL)");
        PlanView plan = plan(DDL + "CREATE TABLE orders (id NUMBER(8));", true);
        assertEquals(2, plan.tables().size()); assertEquals(Status.MISMATCH, plan.tables().getFirst().status()); assertEquals(Status.NEW, plan.tables().get(1).status());
        assertThrows(IllegalArgumentException.class, () -> service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.DML_ONLY, "orders", Action.CREATE_AND_LOAD))));
    }
    @Test void mismatchProducesStructuredColumnComparisonWithCorrections() throws Exception {
        sql("CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(20) NOT NULL)");
        PlanView plan = plan(DDL, true);
        TableReport customers = plan.tables().getFirst();
        assertEquals(Status.MISMATCH, customers.status());
        ColumnDifference nameMismatch = customers.columnComparison().stream().filter(d -> "name".equals(d.column())).findFirst().orElseThrow();
        assertEquals("TYPE_MISMATCH", nameMismatch.kind());
        assertTrue(nameMismatch.expected().contains("100")); assertTrue(nameMismatch.actual().contains("20"));
        assertFalse(nameMismatch.correction().isBlank());
    }
    @Test void foreignKeyDependencySummaryListsReferencedTables() throws Exception {
        PlanView plan = plan("CREATE TABLE customers (id NUMBER(10,0) PRIMARY KEY); CREATE TABLE orders (id NUMBER(10,0) PRIMARY KEY, customer_id NUMBER(10,0) REFERENCES customers(id));", false);
        TableReport orders = plan.tables().stream().filter(t -> t.table().equals("orders")).findFirst().orElseThrow();
        assertEquals(List.of("customers"), orders.dependsOn());
        TableReport customers = plan.tables().stream().filter(t -> t.table().equals("customers")).findFirst().orElseThrow();
        assertEquals(List.of(), customers.dependsOn());
    }
    @Test void detectsTargetSchemaDriftBeforeWriting() throws Exception {
        sql("CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        PlanView plan = plan(DDL + "INSERT INTO customers VALUES (1,'New');", true); sql("ALTER TABLE customers ADD unexpected INT");
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.DML_ONLY))));
        assertEquals("FAILED", job.state()); assertTrue(job.message().contains("changed")); assertEquals(0, count("customers"));
    }
    @Test void duplicateKeysRollBackCurrentTableAndReportZeroCommittedRows() throws Exception {
        sql("CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(100) NOT NULL)"); sql("INSERT INTO customers VALUES (1,'Existing')");
        PlanView plan = plan(DDL + "INSERT INTO customers VALUES (2,'First'),(1,'Duplicate');", true);
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.DML_ONLY))));
        assertEquals("FAILED", job.state()); assertEquals(1, count("customers")); assertEquals(0, job.tables().getFirst().rows());
        assertFalse(job.message().contains("Duplicate")); assertFalse(job.message().contains("secret-password"));
    }
    @Test void downloadHonorsDmlOnlyAndMultilineValues() throws Exception {
        sql("CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        PlanView plan = plan(DDL + "INSERT INTO customers VALUES (1,'line1\nline2; Oracle');", true);
        Map<String, String> archive = archive(plan, Map.of("customers", Action.DML_ONLY));
        assertEquals("", archive.get("01-tables.sql")); assertTrue(archive.get("02-data.sql").contains("line1\nline2; Oracle")); assertFalse(archive.get("report.json").contains("secret-password"));
        String tableSql = archive.get("tables/customers.sql");
        assertFalse(tableSql.contains("CREATE TABLE"));
        assertTrue(tableSql.contains("-- DDL:")); assertTrue(tableSql.contains("-- DML:"));
        assertTrue(tableSql.contains(archive.get("02-data.sql")));
        assertTrue(archive.get("migration-info.txt").contains("localhost:5432/test"));
        assertFalse(archive.values().stream().anyMatch(value -> value.contains("secret-password")));
    }
    @Test void perTableFilesAndExecutionSummaryAreIncluded() throws Exception {
        PlanView plan = plan(DDL + "INSERT INTO customers VALUES (1,'Anita');", true);
        Map<String, String> before = archive(plan, Map.of("customers", Action.CREATE_AND_LOAD));
        String tableSql = before.get("tables/customers.sql");
        assertTrue(tableSql.contains("CREATE TABLE")); assertTrue(tableSql.contains("INSERT INTO"));
        assertTrue(tableSql.indexOf("-- DDL:") < tableSql.indexOf("CREATE TABLE"));
        assertTrue(tableSql.indexOf("-- DML:") < tableSql.indexOf("INSERT INTO"));
        assertEquals(1, before.keySet().stream().filter(name -> name.startsWith("tables/")).count());
        assertTrue(before.get("migration-info.txt").contains("Not run by this application"));
        await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD))));
        String info = archive(plan, Map.of("customers", Action.CREATE_AND_LOAD)).get("migration-info.txt");
        assertTrue(info.contains("SUCCEEDED")); assertTrue(info.contains("Committed rows: 1"));
    }
    @Test void unsafeAndCollidingTableNamesHaveDistinctSafeFiles() throws Exception {
        PlanView plan = plan("CREATE TABLE \"a/b\" (id NUMBER(5)); CREATE TABLE \"a?b\" (id NUMBER(5));", false);
        Map<String, String> files = archive(plan, Map.of("a/b", Action.CREATE_AND_LOAD, "a?b", Action.CREATE_AND_LOAD));
        assertTrue(files.containsKey("tables/a_b.sql"));
        assertTrue(files.containsKey("tables/a_b_2.sql"));
    }
    @Test void unsupportedStatementsAreRetainedAsMarkedGapsInOrderedReviewDraft() throws Exception {
        PlanView plan = plan(DDL + "CREATE PACKAGE pkg AS END", false);
        Map<String, String> archive = archive(plan, Map.of("customers", Action.CREATE_AND_LOAD));
        assertTrue(archive.containsKey("converted-script.review.sql")); assertFalse(archive.containsKey("01-tables.sql"));
        assertTrue(archive.get("converted-script.review.sql").contains("MANUAL CORRECTION REQUIRED"));
        assertThrows(IllegalArgumentException.class,()->service.execute(plan.id(),new ExecuteRequest(target,Map.of("customers",Action.CREATE_AND_LOAD))));
    }
    @Test void requiresTargetPreflightAndPreventsPlanReplay() throws Exception {
        PlanView offline = plan(DDL, false);
        assertThrows(IllegalArgumentException.class, () -> service.execute(offline.id(), new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD))));
        PlanView checked = plan(DDL, true); ExecuteRequest request = new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD));
        await(service.execute(checked.id(), request)); assertThrows(IllegalArgumentException.class, () -> service.execute(checked.id(), request));
    }
    @Test void preventsSkippingMissingForeignKeyDependency() throws Exception {
        PlanView plan = plan("CREATE TABLE parent (id NUMBER(5) PRIMARY KEY); CREATE TABLE child (id NUMBER(5) REFERENCES parent(id));", false);
        assertThrows(IllegalArgumentException.class, () -> archive(plan, Map.of("parent", Action.SKIP, "child", Action.CREATE_AND_LOAD)));
    }
    @Test void sourceDatabaseExportProducesDownloadableData() throws Exception {
        sql("CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(100) NOT NULL)"); sql("INSERT INTO customers VALUES (1,'Anita')");
        PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.ORACLE, "APP", null, target, "public", List.of("customers"), true, null));
        assertTrue(plan.issues().isEmpty(), plan.issues().toString()); assertEquals(1, plan.tables().getFirst().rows());
        Map<String, String> archive = archive(plan, Map.of("CUSTOMERS", Action.CREATE_AND_LOAD));
        assertTrue(archive.get("01-tables.sql").contains("NUMBER(10,0)")); assertTrue(archive.get("02-data.sql").contains("'Anita'"));
    }
    @Test void completedTablesRemainVisibleAfterLaterTableFailure() throws Exception {
        PlanView plan = plan(DDL + "INSERT INTO customers VALUES (1,'Good'); CREATE TABLE other (id NUMBER(5) PRIMARY KEY); INSERT INTO other VALUES (1),(1);", true);
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("customers", Action.CREATE_AND_LOAD, "other", Action.CREATE_AND_LOAD))));
        assertEquals("FAILED", job.state()); assertEquals(1, count("customers")); assertEquals(0, count("other"));
        assertEquals(1, job.tables().stream().filter(t -> t.table().equals("customers")).findFirst().orElseThrow().rows());
    }
    @Test void catalogIdentifiersWithUnderscoresAreMatchedLiterally() throws Exception {
        sql("CREATE TABLE custom_ers (id NUMERIC(5,0))"); sql("CREATE TABLE customXers (id VARCHAR(5))");
        try (Connection c = gateway.connect(target)) { assertEquals("DECIMAL", gateway.inspect(c, "public", "custom_ers", Dialect.POSTGRESQL).columns().getFirst().type().kind()); }
    }
    @Test void nameCollisionWithViewIsReportedWithoutOfferingCreate() throws Exception {
        sql("CREATE VIEW customers AS SELECT 1 AS id");
        PlanView plan = plan(DDL, true); assertEquals(Status.MISMATCH, plan.tables().getFirst().status());
        assertEquals(List.of(Action.SKIP), plan.tables().getFirst().allowedActions());
    }
    private Map<String, String> archive(PlanView plan, Map<String, Action> actions) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); service.download(plan.id(), actions, bytes); Map<String, String> output = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            String root = null;
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName(); int slash = path.indexOf('/'); assertTrue(slash > 0);
                String folder = path.substring(0, slash);
                assertTrue(folder.matches("(ORACLE|POSTGRESQL)_to_(ORACLE|POSTGRESQL)_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}Z"), folder);
                if (root == null) root = folder; else assertEquals(root, folder);
                assertFalse(path.contains("../"));
                output.put(path.substring(slash + 1), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return output;
    }
    @Test void storageReportsStagedBytesAndPreviewsReclaimableExpiredPlans() throws Exception {
        PlanView fresh = plan(DDL + "INSERT INTO customers VALUES (1,'Anita');", true);
        StorageView before = service.storage();
        PlanStorage freshEntry = before.plans().stream().filter(p -> p.id().equals(fresh.id())).findFirst().orElseThrow();
        assertTrue(freshEntry.bytes() > 0); assertFalse(freshEntry.reclaimable()); assertEquals("NOT_RUN", freshEntry.state());
        assertTrue(before.stagedBytes() >= freshEntry.bytes()); assertTrue(before.freeDiskBytes() > 0);
        assertEquals(0, before.orphanedDirectories().size());
        // A plan with a real (unexpired) directory on disk but no matching tracked entry is an orphan.
        Files.createDirectories(directory.resolve("orphan-"+UUID.randomUUID()));
        StorageView withOrphan = service.storage();
        assertEquals(1, withOrphan.orphanedDirectories().size());
    }
}

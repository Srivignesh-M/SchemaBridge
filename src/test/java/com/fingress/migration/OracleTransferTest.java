package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in live checks. Admin credentials only provision uniquely named, disposable users. */
@EnabledIfSystemProperty(named="migration.oracle.port", matches="[0-9]+")
class OracleTransferTest {
    @TempDir Path work;
    final DatabaseGateway db = new DatabaseGateway();
    final ObjectMapper json = new ObjectMapper();
    final List<String> createdUsers = new ArrayList<>();
    ConnectionSpec admin, source, target, postgres;
    String sourceSchema, targetSchema, postgresSchema;
    MigrationService service;
    static final String NOTE = "a".repeat(8192) + "\uD83D\uDE80\u0928\u092E\u0938\u094D\u0924\u0947'\n" + "b".repeat(24000);
    static final BigDecimal AMOUNT = new BigDecimal("1234567890123456789012.12345678");
    static final OffsetDateTime INSTANT = OffsetDateTime.parse("2026-10-08T12:34:56.123456+05:30");
    static final LocalDateTime LOCAL = LocalDateTime.parse("2026-10-08T12:34:56.123456");
    static final byte[] PAYLOAD = new byte[40000];
    static { new Random(42).nextBytes(PAYLOAD); }

    static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @BeforeEach void setup() throws Exception {
        admin = new ConnectionSpec(Dialect.ORACLE, setting("MIGRATION_ORACLE_HOST", "127.0.0.1"),
                Integer.getInteger("migration.oracle.port"), setting("MIGRATION_ORACLE_SERVICE", "FREEPDB1"),
                setting("MIGRATION_ORACLE_USERNAME", "system"), setting("MIGRATION_ORACLE_PASSWORD", ""));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        sourceSchema = "FGIT_S_" + suffix;
        targetSchema = "FGIT_T_" + suffix;
        String password = "A" + UUID.randomUUID().toString().replace("-", "") + "z9";
        for (String schema : List.of(sourceSchema, targetSchema)) {
            sql(admin, "CREATE USER " + quote(schema) + " IDENTIFIED BY " + quote(password) + " DEFAULT TABLESPACE USERS QUOTA 2000M ON USERS");
            createdUsers.add(schema);
            sql(admin, "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + quote(schema));
        }
        source = new ConnectionSpec(admin.dialect(), admin.host(), admin.port(), admin.database(), sourceSchema, password);
        target = new ConnectionSpec(admin.dialect(), admin.host(), admin.port(), admin.database(), targetSchema, password);
        service = new MigrationService(db, json, work.toString(), 2_000_000, 30);
    }

    @AfterEach void cleanup() throws Exception {
        if (service != null) service.close();
        Exception failure = null;
        if (postgresSchema != null) {
            try { sql(postgres, "DROP SCHEMA " + quote(postgresSchema) + " CASCADE"); }
            catch (Exception error) { failure = error; }
        }
        for (String schema : createdUsers) {
            if (!schema.matches("FGIT_[ST]_[A-F0-9]{20}")) throw new IllegalStateException("Invalid fixture user");
            try { sql(admin, "DROP USER " + quote(schema) + " CASCADE"); }
            catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }

    void sql(ConnectionSpec connection, String sql) throws Exception {
        try (Connection c = db.connect(connection); Statement s = c.createStatement()) { s.execute(sql); }
    }

    /** Raw JDBC AS SYSDBA, test-only: DatabaseGateway.connect() deliberately has no SYSDBA path (this is a
     * local single-user tool, not a place to expose SYS credentials in production). Only the three
     * SYS.DBA_* grants in enableRevertMetadata() need this; everything else in this fixture uses db.connect(). */
    Connection sysdbaConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", setting("MIGRATION_ORACLE_SYS_USERNAME", "sys"));
        properties.setProperty("password", setting("MIGRATION_ORACLE_SYS_PASSWORD", setting("MIGRATION_ORACLE_PASSWORD", "")));
        properties.setProperty("internal_logon", "sysdba");
        String url = "jdbc:oracle:thin:@//" + admin.host() + ":" + admin.port() + "/" + admin.database();
        return DriverManager.getConnection(url, properties);
    }

    long count(ConnectionSpec connection, String table) throws Exception {
        try (Connection c = db.connect(connection); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(r.next()); return r.getLong(1);
        }
    }

    PlanView plan(ConnectionSpec from, String schema, List<String> tables, ConnectionSpec to, String destination,
                  boolean metadata, Map<String, TableSelection> selections, boolean copy) throws Exception {
        // Newly created Oracle fixtures can report ORA-01466 when a read-only snapshot
        // starts in the same second as DDL. Model a quiescent source before extraction,
        // and retry the snapshot itself (Oracle's own guidance for this error) since the
        // fixed delay alone still flakes occasionally under load, e.g. right after container start.
        if (from.dialect() == Dialect.ORACLE) Thread.sleep(1500);
        for (int attempt = 1; ; attempt++) {
            try {
                return service.create(new PlanRequest(from.dialect(), to.dialect(), destination, null, from, schema, tables,
                        true, to, new MigrationOptions(null, null, null, null, null, null, null, null, true, copy), selections, metadata));
            } catch (SQLException e) {
                if (from.dialect() != Dialect.ORACLE || e.getErrorCode() != 1466 || attempt >= 3) throw e;
                Thread.sleep(1500);
            }
        }
    }

    /** P10: samples a running job's existing progress.phase() on a fixed interval to produce a per-phase
     * duration breakdown (CREATING/LOADING/FINALIZING/CHECKING), without any production code change. */
    static final class PhaseTimer {
        private final List<Object[]> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Thread thread; private volatile boolean running = true;
        PhaseTimer(java.util.function.Supplier<String> phase) {
            thread = new Thread(() -> { while (running) { try { samples.add(new Object[]{System.nanoTime(), phase.get()}); Thread.sleep(5); } catch (Exception ignored) {} } });
            thread.setDaemon(true); thread.start();
        }
        Map<String, Double> stop() throws InterruptedException {
            running = false; thread.join(1000);
            Map<String, Double> totals = new LinkedHashMap<>();
            for (int i = 0; i < samples.size() - 1; i++) {
                String phase = (String) samples.get(i)[1];
                double seconds = ((Long) samples.get(i + 1)[0] - (Long) samples.get(i)[0]) / 1e9;
                totals.merge(phase, seconds, Double::sum);
            }
            return totals;
        }
    }

    JobView await(JobView job) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (Set.of("QUEUED", "RUNNING").contains(job.state()) && System.nanoTime() < deadline) {
            Thread.sleep(25); job = service.job(job.id());
        }
        assertFalse(Set.of("QUEUED", "RUNNING").contains(job.state()), "Migration timed out");
        return job;
    }

    JobView execute(PlanView plan, ConnectionSpec to, Action action) throws Exception {
        assertTrue(plan.issues().isEmpty(), plan.issues().toString());
        Map<String, Action> actions = new LinkedHashMap<>();
        for (TableReport table : plan.tables()) {
            assertTrue(table.allowedActions().contains(action), table.toString()); actions.put(table.table(), action);
        }
        return await(service.execute(plan.id(), new ExecuteRequest(to, actions)));
    }

    void successful(JobView job, long rows) {
        assertEquals("SUCCEEDED", job.state(), job.message() + " " + job.tables());
        assertEquals(rows, job.progress().rowsCommitted());
    }

    void representative(ConnectionSpec connection) throws Exception {
        boolean oracle = connection.dialect() == Dialect.ORACLE;
        String table = oracle ? "ITEMS" : quote(postgresSchema) + ".items";
        sql(connection, "CREATE TABLE " + table + (oracle
                ? " (ID NUMBER(10,0) PRIMARY KEY, LABEL VARCHAR2(100 CHAR), NOTE CLOB, PAYLOAD BLOB, AMOUNT NUMBER(30,8), HAPPENED TIMESTAMP(6), ZONED TIMESTAMP(6) WITH TIME ZONE, FIXED CHAR(8 CHAR), DAY_VALUE DATE)"
                : " (id NUMERIC(10,0) PRIMARY KEY, label VARCHAR(100), note TEXT, payload BYTEA, amount NUMERIC(30,8), happened TIMESTAMP(6), zoned TIMESTAMP(6) WITH TIME ZONE, fixed CHAR(8), day_value TIMESTAMP(0))"));
        try (Connection c = db.connect(connection); PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            s.setInt(1, 1); s.setString(2, "O'Brien \u0928\u092E\u0938\u094D\u0924\u0947 \uD83D\uDE80");
            s.setCharacterStream(3, new java.io.StringReader(NOTE)); s.setBinaryStream(4, new java.io.ByteArrayInputStream(PAYLOAD), PAYLOAD.length);
            s.setBigDecimal(5, AMOUNT); s.setTimestamp(6, Timestamp.valueOf(LOCAL)); s.setObject(7, INSTANT);
            s.setString(8, "abc"); s.setTimestamp(9, Timestamp.valueOf("2026-10-08 13:45:56")); s.executeUpdate();
            s.setInt(1, 2);
            int[] types = { Types.VARCHAR, oracle ? Types.CLOB : Types.VARCHAR, oracle ? Types.BLOB : Types.VARBINARY,
                    Types.NUMERIC, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.CHAR, Types.TIMESTAMP };
            for (int i = 0; i < types.length; i++) s.setNull(i + 2, types[i]);
            s.executeUpdate();
        }
    }

    void assertRepresentative(ConnectionSpec connection, String table) throws Exception {
        try (Connection c = db.connect(connection); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
            assertTrue(r.next()); assertEquals(1, r.getInt(1)); assertEquals("O'Brien \u0928\u092E\u0938\u094D\u0924\u0947 \uD83D\uDE80", r.getString(2));
            assertEquals(NOTE, r.getString(3)); assertArrayEquals(PAYLOAD, r.getBytes(4));
            assertEquals(0, AMOUNT.compareTo(r.getBigDecimal(5))); assertEquals(LOCAL, r.getTimestamp(6).toLocalDateTime());
            assertEquals(INSTANT.toInstant(), r.getObject(7, OffsetDateTime.class).toInstant());
            assertEquals("abc     ", r.getString(8)); assertEquals("2026-10-08 13:45:56.0", r.getTimestamp(9).toString());
            assertTrue(r.next()); assertEquals(2, r.getInt(1));
            for (int i = 2; i <= 9; i++) assertNull(r.getObject(i));
            assertFalse(r.next());
        }
    }

    @Test void oraclePreparedTransferPreservesValuesAndLobs() throws Exception {
        representative(source);
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of(), false);
        JobView job = execute(plan, target, Action.CREATE_AND_LOAD); successful(job, 2);
        assertTrue(job.tables().getFirst().message().contains("SHA-256"));
        assertRepresentative(target, "ITEMS");
        try (Connection c = db.connect(target)) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("checkedAt", Instant.now().toString()); report.put("database", c.getMetaData().getDatabaseProductVersion());
            report.put("driver", c.getMetaData().getDriverVersion()); report.put("java", System.getProperty("java.version"));
            report.put("fixture", "oracle-representative-values"); report.put("rows", 2); report.put("validation", job.tables().getFirst().message());
            Files.createDirectories(Path.of("target/benchmarks"));
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmarks/oracle-representative.json").toFile(), report);
        }
    }

    @Test void oracleStatisticsAndProjection() throws Exception {
        sql(source, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY, LABEL VARCHAR2(100 CHAR))");
        sql(source, "INSERT INTO ITEMS SELECT LEVEL, 'row-' || LEVEL FROM DUAL CONNECT BY LEVEL <= 321");
        sql(source, "BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'ITEMS'); END;");
        PlanView preview = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, true, Map.of(), false);
        assertTrue(preview.issues().isEmpty(), preview.issues().toString());
        assertFalse(preview.prepared()); assertEquals(0, preview.tables().getFirst().rows());
        assertEquals(321L, preview.tables().getFirst().estimatedRows());
        TableSelection selection = new TableSelection(List.of("ID", "LABEL"), Map.of("LABEL", "DISPLAY_NAME"),
                List.of(new RowFilter("ID", ">=", "320")), "RENAMED_ITEMS");
        PlanView prepared = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of("ITEMS", selection), false);
        successful(execute(prepared, target, Action.CREATE_AND_LOAD), 2);
        assertEquals(2, count(target, "RENAMED_ITEMS WHERE ID >= 320 AND DISPLAY_NAME LIKE 'row-%'"));
    }

    @Test void oracleDuplicateRollsBackAndSafelyResumes() throws Exception {
        for (ConnectionSpec c : List.of(source, target)) sql(c, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");
        sql(source, "INSERT INTO ITEMS VALUES (1)"); sql(source, "INSERT INTO ITEMS VALUES (2)");
        sql(target, "INSERT INTO ITEMS VALUES (2)");
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of(), false);
        JobView failed = execute(plan, target, Action.DML_ONLY);
        assertEquals("FAILED", failed.state(), failed.message()); assertTrue(failed.resumable());
        assertEquals(0, failed.progress().rowsCommitted()); assertEquals(1, count(target, "ITEMS"));
        assertEquals(0, count(target, "ITEMS WHERE ID = 1"));
        service.close(); service = new MigrationService(db, json, work.toString(), 2_000_000, 30);
        assertTrue(service.job(failed.id()).resumable());
        sql(target, "DELETE FROM ITEMS WHERE ID = 2");
        successful(await(service.resume(failed.id(), target)), 2);
        assertEquals(2, count(target, "ITEMS"));
    }

    @Test void postgresSqlInputLoadsIntoOracle() throws Exception {
        String sql = "CREATE TABLE items (id NUMERIC(10,0) PRIMARY KEY, label VARCHAR(100), amount NUMERIC(30,8), happened TIMESTAMP(6));"
                + "INSERT INTO items VALUES (1, 'O''Brien', 1234567890123456789012.12345678, TIMESTAMP '2026-10-08 12:34:56.123456');";
        PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.ORACLE, targetSchema, sql, null, null, null, true, target));
        successful(execute(plan, target, Action.CREATE_AND_LOAD), 1);
        try (Connection c = db.connect(target); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT LABEL, AMOUNT, HAPPENED FROM ITEMS")) {
            assertTrue(r.next()); assertEquals("O'Brien", r.getString(1)); assertEquals(0, AMOUNT.compareTo(r.getBigDecimal(2)));
            assertEquals(LOCAL, r.getTimestamp(3).toLocalDateTime());
        }
    }

    @Test void oracleIdentityAndForeignKeyFinalization() throws Exception {
        sql(source, "CREATE TABLE PARENT (ID NUMBER(10,0) GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, LABEL VARCHAR2(20 CHAR))");
        sql(source, "CREATE TABLE CHILD (ID NUMBER(10,0) PRIMARY KEY, PARENT_ID NUMBER(10,0), FOREIGN KEY (PARENT_ID) REFERENCES PARENT(ID))");
        sql(source, "INSERT INTO PARENT (ID,LABEL) VALUES (42,'parent')"); sql(source, "INSERT INTO CHILD VALUES (7,42)");
        PlanView plan = plan(source, sourceSchema, List.of("CHILD", "PARENT"), target, targetSchema, false, Map.of(), false);
        successful(execute(plan, target, Action.CREATE_AND_LOAD), 2);
        sql(target, "INSERT INTO PARENT (LABEL) VALUES ('next')");
        assertEquals(1, count(target, "PARENT WHERE ID > 42"));
        assertThrows(SQLException.class, () -> sql(target, "INSERT INTO CHILD VALUES (8,-1)"));
        assertThrows(SQLException.class, () -> sql(target, "UPDATE PARENT SET ID = 99 WHERE ID = 42"));
    }

    @Test void nonDefaultOracleForeignKeysStayBlocked() throws Exception {
        sql(source, "CREATE TABLE PARENT (ID NUMBER(10,0) PRIMARY KEY)");
        sql(source, "CREATE TABLE CHILD (ID NUMBER(10,0), FOREIGN KEY (ID) REFERENCES PARENT(ID) ON DELETE CASCADE)");
        try (Connection c = db.connect(source)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> db.inspect(c, sourceSchema, "CHILD", Dialect.ORACLE));
            assertTrue(error.getMessage().contains("delete action"), error.getMessage());
        }
    }

    @Test @EnabledIfSystemProperty(named="migration.oracle.benchmarkRows", matches="100000|1000000")
    void oracleNarrowBenchmark() throws Exception {
        int rows = Integer.getInteger("migration.oracle.benchmarkRows");
        sql(source, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY, LABEL VARCHAR2(100 CHAR), AMOUNT NUMBER(18,4))");
        sql(source, "INSERT INTO ITEMS SELECT LEVEL, 'row-' || LEVEL, LEVEL / 100 FROM DUAL CONNECT BY LEVEL <= " + rows);
        Thread.sleep(1500); // Fixture DDL must precede the read-only source snapshot.
        AtomicLong peak = new AtomicLong();
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max), 0, 20, TimeUnit.MILLISECONDS);
        long start = System.nanoTime(), prepared, end;
        PlanView plan; JobView job;
        try {
            plan = service.create(new PlanRequest(Dialect.ORACLE, Dialect.ORACLE, targetSchema, null, source, sourceSchema, List.of("ITEMS"), true, target));
            prepared = System.nanoTime(); job = execute(plan, target, Action.CREATE_AND_LOAD); end = System.nanoTime();
        } finally { sampler.shutdownNow(); }
        successful(job, rows);
        try (Connection c = db.connect(target); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*), SUM(ID), SUM(AMOUNT) FROM ITEMS")) {
            assertTrue(r.next()); assertEquals(rows, r.getLong(1));
            long sum = (long) rows * (rows + 1) / 2;
            assertEquals(sum, r.getLong(2)); assertEquals(0, BigDecimal.valueOf(sum, 2).compareTo(r.getBigDecimal(3)));
        }
        long stagedBytes;
        try (var files = Files.walk(work.resolve(plan.id()))) {
            stagedBytes = 0;
            for (Path path : files.filter(Files::isRegularFile).toList()) stagedBytes += Files.size(path);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkedAt", Instant.now().toString()); report.put("fixture", "oracle-narrow-prepared"); report.put("rows", rows);
        report.put("preparationSeconds", (prepared - start) / 1e9); report.put("loadAndValidationSeconds", (end - prepared) / 1e9);
        report.put("totalSeconds", (end - start) / 1e9); report.put("stagedBytes", stagedBytes);
        report.put("sampledPeakHeapBytes", peak.get()); report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("validation", job.tables().getFirst().message()); report.put("options", plan.options());
        report.put("java", System.getProperty("java.version")); report.put("clientOs", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("timingScope", "Preparation and migration, including built-in validation; excludes fixture provisioning, seed, DDL settling, independent aggregate assertions and cleanup. Heap sampling is not RSS.");
        try (Connection c = db.connect(target)) {
            report.put("database", c.getMetaData().getDatabaseProductVersion()); report.put("driver", c.getMetaData().getDriverVersion());
        }
        Files.createDirectories(Path.of("target/benchmarks"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmarks/oracle-narrow-" + rows + ".json").toFile(), report);
    }

    void enableRevertMetadata()throws Exception {
        try(Connection c=sysdbaConnection();Statement s=c.createStatement()){
            for(String view:List.of("DBA_CONSTRAINTS","DBA_DEPENDENCIES","DBA_SYNONYMS"))s.execute("GRANT SELECT ON SYS."+view+" TO "+quote(targetSchema));
        }
    }
    JobView revertJob(JobView job,ConnectionSpec connection)throws Exception {
        RevertPreview review=service.previewRevert(job.id(),connection);
        JobView result=service.revert(job.id(),new RevertRequest(connection,review.token()));
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
        while(Set.of("QUEUED","RUNNING").contains(result.revert().state())&&System.nanoTime()<deadline){Thread.sleep(25);result=service.job(job.id());}
        assertEquals("REVERTED",result.revert().state(),result.revert().message());return result;
    }
    @Test void oracleRevertDropsOnlyNewTableWithVerifiedLobs()throws Exception {
        enableRevertMetadata();representative(source);
        JobView job=execute(plan(source,sourceSchema,List.of("ITEMS"),target,targetSchema,false,Map.of(),false),target,Action.CREATE_AND_LOAD);successful(job,2);
        revertJob(job,target);assertFalse(db.tables(target,targetSchema).contains("ITEMS"));
    }
    @Test void oracleRevertKeepsExistingParentAndDropsNewChild()throws Exception {
        enableRevertMetadata();
        for(ConnectionSpec c:List.of(source,target))sql(c,"CREATE TABLE PARENT (ID NUMBER(10,0) PRIMARY KEY)");
        sql(source,"CREATE TABLE CHILD (ID NUMBER(10,0) PRIMARY KEY, PID NUMBER(10,0) REFERENCES PARENT(ID))");
        sql(source,"INSERT INTO PARENT VALUES(1)");sql(source,"INSERT INTO CHILD VALUES(7,1)");sql(target,"INSERT INTO PARENT VALUES(99)");
        PlanView p=plan(source,sourceSchema,List.of("CHILD","PARENT"),target,targetSchema,false,Map.of(),false);
        assertTrue(p.issues().isEmpty(),p.issues().toString());
        JobView job=await(service.execute(p.id(),new ExecuteRequest(target,Map.of("CHILD",Action.CREATE_AND_LOAD,"PARENT",Action.DML_ONLY))));successful(job,2);
        sql(target,"INSERT INTO PARENT VALUES(100)");revertJob(job,target);
        assertFalse(db.tables(target,targetSchema).contains("CHILD"));assertEquals(2,count(target,"PARENT"));assertEquals(2,count(target,"PARENT WHERE ID IN (99,100)"));
    }
    @Test void oracleRevertRequiresCompleteDependencyVisibility()throws Exception {
        sql(source,"CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");sql(source,"INSERT INTO ITEMS VALUES(1)");
        JobView job=execute(plan(source,sourceSchema,List.of("ITEMS"),target,targetSchema,false,Map.of(),false),target,Action.CREATE_AND_LOAD);successful(job,1);
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target)).getMessage().contains("DBA_CONSTRAINTS"));
        assertEquals(1,count(target,"ITEMS"));
    }
    @Test void oracleRevertBlocksCrossSchemaCascadeAndRecreatedTables()throws Exception {
        enableRevertMetadata();sql(source,"CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");sql(source,"INSERT INTO ITEMS VALUES(1)");
        JobView job=execute(plan(source,sourceSchema,List.of("ITEMS"),target,targetSchema,false,Map.of(),false),target,Action.CREATE_AND_LOAD);successful(job,1);
        sql(target,"GRANT REFERENCES ON ITEMS TO "+quote(sourceSchema));
        sql(source,"CREATE TABLE OUTSIDER (ID NUMBER(10,0) REFERENCES "+quote(targetSchema)+".ITEMS(ID) ON DELETE CASCADE)");sql(source,"INSERT INTO OUTSIDER VALUES(1)");
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target)).getMessage().contains("references"));assertEquals(1,count(source,"OUTSIDER"));
        sql(source,"DROP TABLE OUTSIDER");sql(target,"DROP TABLE ITEMS");sql(target,"CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");sql(target,"INSERT INTO ITEMS VALUES(1)");
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target)).getMessage().contains("replaced"));assertEquals(1,count(target,"ITEMS"));
    }

    void postgresSchema() throws Exception {
        postgres = new ConnectionSpec(Dialect.POSTGRESQL, setting("MIGRATION_PG_HOST", "127.0.0.1"),
                Integer.getInteger("migration.pg.port"), setting("MIGRATION_PG_DATABASE", "postgres"),
                setting("MIGRATION_PG_USERNAME", "migration_test"), setting("MIGRATION_PG_PASSWORD", ""));
        String name = sourceSchema.toLowerCase(Locale.ROOT);
        sql(postgres, "CREATE SCHEMA " + quote(name)); postgresSchema = name;
    }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    void oracleToPostgresPreservesValues() throws Exception {
        postgresSchema(); representative(source);
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), postgres, postgresSchema, false, Map.of(), false);
        successful(execute(plan, postgres, Action.CREATE_AND_LOAD), 2);
        assertRepresentative(postgres, quote(postgresSchema) + ".items");
    }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    void postgresToOraclePreservesValues() throws Exception {
        postgresSchema(); representative(postgres);
        PlanView plan = plan(postgres, postgresSchema, List.of("items"), target, targetSchema, false, Map.of(), false);
        successful(execute(plan, target, Action.CREATE_AND_LOAD), 2);
        assertRepresentative(target, "ITEMS");
    }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.benchmarkRows", matches="100000|1000000")
    void oracleToPostgresNarrowBenchmark() throws Exception { crossBenchmark(true, false); }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.benchmarkRows", matches="100000|1000000")
    void oracleToPostgresWideBenchmark() throws Exception { crossBenchmark(true, true); }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.benchmarkRows", matches="100000|1000000")
    void postgresToOracleNarrowBenchmark() throws Exception { crossBenchmark(false, false); }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.benchmarkRows", matches="100000|1000000")
    void postgresToOracleWideBenchmark() throws Exception { crossBenchmark(false, true); }

    /**
     * Cross-database (Oracle<->PostgreSQL) scale fixture for improvement-plan P1. Covers a
     * timezone-boundary column (TIMESTAMP WITH TIME ZONE, three varying offsets) and a
     * numeric-boundary column (NUMBER/NUMERIC(30,8)) at narrow/wide row widths, on top of the
     * exact-value LOB/NULL coverage already established by representative()/assertRepresentative().
     */
    void crossBenchmark(boolean oracleSource, boolean wide) throws Exception {
        int rows = Integer.getInteger("migration.cross.benchmarkRows");
        postgresSchema();
        ConnectionSpec from = oracleSource ? source : postgres, to = oracleSource ? postgres : target;
        String fromSchema = oracleSource ? sourceSchema : postgresSchema, toSchema = oracleSource ? postgresSchema : targetSchema;
        String fromTable = oracleSource ? "ITEMS" : "items", toTable = oracleSource ? "items" : "ITEMS";
        int labelWidth = wide ? 900 : 100;
        if (oracleSource) {
            sql(from, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY, LABEL VARCHAR2(" + labelWidth + " CHAR), AMOUNT NUMBER(30,8), HAPPENED TIMESTAMP(6) WITH TIME ZONE)");
            sql(from, "INSERT INTO ITEMS SELECT LEVEL, " + (wide ? "RPAD('row-'||LEVEL||'-',900,'x')" : "'row-'||LEVEL")
                    + ", LEVEL + 0.12345678, FROM_TZ(TIMESTAMP '2026-01-01 00:00:00', CASE MOD(LEVEL,3) WHEN 0 THEN '+00:00' WHEN 1 THEN '+05:30' ELSE '-08:00' END)"
                    + " FROM DUAL CONNECT BY LEVEL <= " + rows);
        } else {
            String table = quote(postgresSchema) + ".items";
            sql(from, "CREATE TABLE " + table + " (id NUMERIC(10,0) PRIMARY KEY, label VARCHAR(" + labelWidth + "), amount NUMERIC(30,8), happened TIMESTAMPTZ)");
            sql(from, "INSERT INTO " + table + " SELECT n, " + (wide ? "rpad('row-'||n||'-',900,'x')" : "'row-'||n")
                    + ", n + 0.12345678, ('2026-01-01 00:00:00' || CASE n%3 WHEN 0 THEN '+00:00' WHEN 1 THEN '+05:30' ELSE '-08:00' END)::timestamptz"
                    + " FROM generate_series(1," + rows + ") n");
        }
        long freeDiskBefore = Files.getFileStore(work).getUsableSpace();
        AtomicLong peak = new AtomicLong();
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max), 0, 20, TimeUnit.MILLISECONDS);
        long start = System.nanoTime(), prepared, end; PlanView plan; JobView job; Map<String,Double> phaseSeconds;
        try {
            plan = plan(from, fromSchema, List.of(fromTable), to, toSchema, false, Map.of(), false);
            prepared = System.nanoTime();
            // P10: break "loadAndValidationSeconds" down into CREATING/LOADING/FINALIZING/CHECKING by
            // sampling the job's own already-reported progress.phase() every 5ms from a background thread —
            // no production code change, reuses the existing progress-reporting the UI already shows.
            assertTrue(plan.issues().isEmpty(), plan.issues().toString());
            Map<String,Action> actions=new LinkedHashMap<>();
            for(TableReport table:plan.tables())actions.put(table.table(),Action.CREATE_AND_LOAD);
            JobView started=service.execute(plan.id(),new ExecuteRequest(to,actions));
            PhaseTimer timer=new PhaseTimer(()->service.job(started.id()).progress().phase());
            job=await(started); end=System.nanoTime(); phaseSeconds=timer.stop();
        } finally { sampler.shutdownNow(); }
        successful(job, rows);
        long freeDiskAfter = Files.getFileStore(work).getUsableSpace();
        String targetTable = oracleSource ? quote(toSchema) + "." + toTable : toTable;
        long sumId = (long) rows * (rows + 1) / 2;
        BigDecimal sumAmount = BigDecimal.valueOf(sumId).add(new BigDecimal("0.12345678").multiply(BigDecimal.valueOf(rows)));
        try (Connection c = db.connect(to); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*), SUM(" + (oracleSource ? "ID" : "id") + "), SUM(" + (oracleSource ? "AMOUNT" : "amount") + ") FROM " + targetTable)) {
            assertTrue(r.next()); assertEquals(rows, r.getLong(1)); assertEquals(sumId, r.getLong(2));
            assertEquals(0, sumAmount.compareTo(r.getBigDecimal(3)));
        }
        try (Connection c = db.connect(to); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT " + (oracleSource ? "HAPPENED" : "happened") + " FROM " + targetTable + " WHERE " + (oracleSource ? "ID" : "id") + "=1")) {
            assertTrue(r.next()); assertNotNull(r.getObject(1, OffsetDateTime.class));
        }
        long stagedBytes;
        try (var files = Files.walk(work.resolve(plan.id()))) { stagedBytes = files.filter(Files::isRegularFile).mapToLong(path -> { try { return Files.size(path); } catch (Exception e) { throw new RuntimeException(e); } }).sum(); }
        long targetBytes = targetSegmentBytes(to, toSchema, toTable, to.dialect());
        Map<String, Object> report = new LinkedHashMap<>();
        String fixture = (oracleSource ? "oracle-to-postgres-" : "postgres-to-oracle-") + (wide ? "wide-" : "narrow-") + rows;
        report.put("checkedAt", Instant.now().toString()); report.put("fixture", fixture); report.put("rows", rows);
        report.put("direction", oracleSource ? "ORACLE_TO_POSTGRESQL" : "POSTGRESQL_TO_ORACLE"); report.put("wide", wide);
        report.put("preparationSeconds", (prepared - start) / 1e9); report.put("loadAndValidationSeconds", (end - prepared) / 1e9); report.put("totalSeconds", (end - start) / 1e9);
        report.put("phaseSecondsBreakdown", phaseSeconds);
        report.put("stagedBytes", stagedBytes); report.put("sampledPeakHeapBytes", peak.get()); report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("workDirDiskUsedBytes", freeDiskBefore - freeDiskAfter); report.put("targetSegmentBytes", targetBytes);
        report.put("validation", job.tables().getFirst().message()); report.put("options", plan.options());
        report.put("java", System.getProperty("java.version")); report.put("clientOs", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("coverage", "Narrow/wide label width; NUMBER/NUMERIC(30,8) numeric-boundary amount column (sum checked exactly); TIMESTAMP(6) WITH TIME ZONE timezone-boundary column cycling three offsets (+00:00/+05:30/-08:00, spot-checked on row 1). Exact per-row LOB/NULL/edge-value equality is covered separately by representative()/assertRepresentative(), not re-checked at this scale.");
        report.put("timingScope", "Preparation and migration including built-in validation; excludes fixture provisioning, seed generation, DDL settling, independent aggregate assertions and cleanup. Heap sampling is periodic, not RSS. workDirDiskUsedBytes is a delta of local work-directory free space (staged artifacts), not target database disk use. targetSegmentBytes is the loaded table's on-disk size (DBA_SEGMENTS/pg_total_relation_size), a proxy for target DB storage load, not a live load/throughput metric.");
        try (Connection oc = db.connect(oracleSource ? source : target); Connection pc = db.connect(postgres)) {
            report.put("oracleDatabase", oc.getMetaData().getDatabaseProductVersion()); report.put("oracleDriver", oc.getMetaData().getDriverVersion());
            report.put("postgresDatabase", pc.getMetaData().getDatabaseProductVersion()); report.put("postgresDriver", pc.getMetaData().getDriverVersion());
        }
        Files.createDirectories(Path.of("target/benchmarks"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmarks/" + fixture + ".json").toFile(), report);
        System.out.println("BENCHMARK " + json.writeValueAsString(report));
    }

    /** USER_SEGMENTS (Oracle) / pg_total_relation_size (PostgreSQL): on-disk size of the loaded table, a target-DB-load proxy.
     * USER_SEGMENTS (not DBA_SEGMENTS) because these fixtures connect as the unprivileged schema owner, not an admin. */
    long targetSegmentBytes(ConnectionSpec connection, String schema, String table, Dialect dialect) throws Exception {
        try (Connection c = db.connect(connection)) {
            if (dialect == Dialect.ORACLE) {
                try (PreparedStatement s = c.prepareStatement("SELECT SUM(bytes) FROM user_segments WHERE segment_name=?")) {
                    s.setString(1, table);
                    try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
                }
            } else {
                try (PreparedStatement s = c.prepareStatement("SELECT pg_total_relation_size(?)")) {
                    s.setString(1, schema + "." + table);
                    try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
                }
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.lobBenchmarkRows", matches="[0-9]+")
    void oracleToPostgresLobBenchmark() throws Exception { crossLobBenchmark(true); }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.lobBenchmarkRows", matches="[0-9]+")
    void postgresToOracleLobBenchmark() throws Exception { crossLobBenchmark(false); }

    /** LOB-at-scale cross-database fixture: a CLOB-equivalent (4000 chars) and BLOB-equivalent (2000 bytes) column per row. */
    void crossLobBenchmark(boolean oracleSource) throws Exception {
        int rows = Integer.getInteger("migration.cross.lobBenchmarkRows");
        postgresSchema();
        ConnectionSpec from = oracleSource ? source : postgres, to = oracleSource ? postgres : target;
        String fromSchema = oracleSource ? sourceSchema : postgresSchema, toSchema = oracleSource ? postgresSchema : targetSchema;
        String fromTable = oracleSource ? "ITEMS" : "items", toTable = oracleSource ? "items" : "ITEMS";
        if (oracleSource) {
            sql(from, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY, NOTE CLOB, PAYLOAD BLOB)");
            sql(from, "INSERT INTO ITEMS SELECT LEVEL, RPAD('clob-'||LEVEL||'-',4000,'y'), UTL_RAW.CAST_TO_RAW(RPAD('b',2000,'b')) FROM DUAL CONNECT BY LEVEL <= " + rows);
        } else {
            String table = quote(postgresSchema) + ".items";
            sql(from, "CREATE TABLE " + table + " (id NUMERIC(10,0) PRIMARY KEY, note TEXT, payload BYTEA)");
            sql(from, "INSERT INTO " + table + " SELECT n, rpad('clob-'||n||'-',4000,'y'), decode(repeat('62',2000),'hex') FROM generate_series(1," + rows + ") n");
        }
        long start = System.nanoTime(), prepared, end; PlanView plan; JobView job;
        plan = plan(from, fromSchema, List.of(fromTable), to, toSchema, false, Map.of(), false);
        prepared = System.nanoTime(); job = execute(plan, to, Action.CREATE_AND_LOAD); end = System.nanoTime();
        successful(job, rows);
        boolean targetOracle = to.dialect() == Dialect.ORACLE;
        String targetTable = targetOracle ? toTable : quote(toSchema) + "." + toTable;
        try (Connection c = db.connect(to); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + targetTable)) {
            assertTrue(r.next()); assertEquals(rows, r.getLong(1));
        }
        String lengthFn = targetOracle ? "SELECT LENGTH(NOTE), LENGTHB(PAYLOAD) FROM " : "SELECT length(note), octet_length(payload) FROM ";
        try (Connection c = db.connect(to); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(lengthFn + targetTable + " WHERE " + (targetOracle ? "ID" : "id") + "=1")) {
            assertTrue(r.next()); assertEquals(4000, r.getLong(1)); assertEquals(2000, r.getLong(2));
        }
        long stagedBytes;
        try (var files = Files.walk(work.resolve(plan.id()))) { stagedBytes = files.filter(Files::isRegularFile).mapToLong(path -> { try { return Files.size(path); } catch (Exception e) { throw new RuntimeException(e); } }).sum(); }
        long targetBytes = targetSegmentBytes(to, toSchema, toTable, to.dialect());
        Map<String, Object> report = new LinkedHashMap<>();
        String fixture = (oracleSource ? "oracle-to-postgres-lob-" : "postgres-to-oracle-lob-") + rows;
        report.put("checkedAt", Instant.now().toString()); report.put("fixture", fixture); report.put("rows", rows);
        report.put("direction", oracleSource ? "ORACLE_TO_POSTGRESQL" : "POSTGRESQL_TO_ORACLE");
        report.put("preparationSeconds", (prepared - start) / 1e9); report.put("loadAndValidationSeconds", (end - prepared) / 1e9); report.put("totalSeconds", (end - start) / 1e9);
        report.put("stagedBytes", stagedBytes); report.put("targetSegmentBytes", targetBytes);
        report.put("validation", job.tables().getFirst().message());
        report.put("coverage", "CLOB-equivalent (4000-char TEXT/CLOB) and BLOB-equivalent (2000-byte BYTEA/BLOB) columns at scale via streamed LOB sidecars; row count and row-1 lengths checked. Exact byte-for-byte LOB equality including Unicode/NULL/max-size edge cases is covered separately by representative()/assertRepresentative() at 2-row scale, not re-checked here.");
        Files.createDirectories(Path.of("target/benchmarks"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmarks/" + fixture + ".json").toFile(), report);
        System.out.println("BENCHMARK " + json.writeValueAsString(report));
    }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.mappingBenchmarkRows", matches="[0-9]+")
    void oracleToPostgresMappingBenchmark() throws Exception { crossMappingBenchmark(true); }

    @Test @EnabledIfSystemProperty(named="migration.pg.port", matches="[0-9]+")
    @EnabledIfSystemProperty(named="migration.cross.mappingBenchmarkRows", matches="[0-9]+")
    void postgresToOracleMappingBenchmark() throws Exception { crossMappingBenchmark(false); }

    /** Table/column rename and foreign-key enforcement at scale, cross-database. */
    void crossMappingBenchmark(boolean oracleSource) throws Exception {
        int rows = Integer.getInteger("migration.cross.mappingBenchmarkRows");
        postgresSchema();
        ConnectionSpec from = oracleSource ? source : postgres, to = oracleSource ? postgres : target;
        String fromSchema = oracleSource ? sourceSchema : postgresSchema, toSchema = oracleSource ? postgresSchema : targetSchema;
        String parentTable = oracleSource ? "PARENT" : "parent", childTable = oracleSource ? "CHILD" : "child";
        if (oracleSource) {
            sql(from, "CREATE TABLE PARENT (ID NUMBER(10,0) PRIMARY KEY, LABEL VARCHAR2(50 CHAR))");
            sql(from, "CREATE TABLE CHILD (ID NUMBER(10,0) PRIMARY KEY, PARENT_ID NUMBER(10,0), FOREIGN KEY (PARENT_ID) REFERENCES PARENT(ID))");
            sql(from, "INSERT INTO PARENT SELECT LEVEL, 'parent-'||LEVEL FROM DUAL CONNECT BY LEVEL <= " + rows);
            sql(from, "INSERT INTO CHILD SELECT LEVEL, LEVEL FROM DUAL CONNECT BY LEVEL <= " + rows);
        } else {
            String parent = quote(postgresSchema) + ".parent", child = quote(postgresSchema) + ".child";
            sql(from, "CREATE TABLE " + parent + " (id NUMERIC(10,0) PRIMARY KEY, label VARCHAR(50))");
            sql(from, "CREATE TABLE " + child + " (id NUMERIC(10,0) PRIMARY KEY, parent_id NUMERIC(10,0) REFERENCES " + parent + "(id))");
            sql(from, "INSERT INTO " + parent + " SELECT n, 'parent-'||n FROM generate_series(1," + rows + ") n");
            sql(from, "INSERT INTO " + child + " SELECT n, n FROM generate_series(1," + rows + ") n");
        }
        boolean targetOracle = to.dialect() == Dialect.ORACLE;
        String renamedParent = targetOracle ? "RENAMED_PARENT" : "renamed_parent", renamedChild = targetOracle ? "RENAMED_CHILD" : "renamed_child";
        String renamedLabel = targetOracle ? "RENAMED_LABEL" : "renamed_label";
        Map<String, TableSelection> selections = oracleSource
                ? Map.of("PARENT", new TableSelection(List.of("ID", "LABEL"), Map.of("LABEL", renamedLabel), List.of(), renamedParent),
                         "CHILD", new TableSelection(List.of("ID", "PARENT_ID"), Map.of(), List.of(), renamedChild))
                : Map.of("parent", new TableSelection(List.of("id", "label"), Map.of("label", renamedLabel), List.of(), renamedParent),
                         "child", new TableSelection(List.of("id", "parent_id"), Map.of(), List.of(), renamedChild));
        long start = System.nanoTime(), prepared, end; PlanView plan; JobView job;
        plan = plan(from, fromSchema, List.of(childTable, parentTable), to, toSchema, false, selections, false);
        prepared = System.nanoTime(); job = execute(plan, to, Action.CREATE_AND_LOAD); end = System.nanoTime();
        successful(job, rows * 2L);
        String renamedParentTable = targetOracle ? renamedParent : quote(toSchema) + "." + renamedParent;
        String renamedChildTable = targetOracle ? renamedChild : quote(toSchema) + "." + renamedChild;
        try (Connection c = db.connect(to); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + renamedParentTable + " WHERE " + renamedLabel + "='parent-1'")) {
            assertTrue(r.next()); assertEquals(1, r.getLong(1));
        }
        try (Connection c = db.connect(to); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO " + renamedChildTable + " VALUES (" + (rows + 999) + ", " + (rows + 999) + ")"));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        String fixture = (oracleSource ? "oracle-to-postgres-mapping-" : "postgres-to-oracle-mapping-") + rows;
        report.put("checkedAt", Instant.now().toString()); report.put("fixture", fixture); report.put("rows", rows * 2);
        report.put("direction", oracleSource ? "ORACLE_TO_POSTGRESQL" : "POSTGRESQL_TO_ORACLE");
        report.put("preparationSeconds", (prepared - start) / 1e9); report.put("loadAndValidationSeconds", (end - prepared) / 1e9); report.put("totalSeconds", (end - start) / 1e9);
        report.put("validation", job.tables().stream().map(TableResult::message).toList());
        report.put("coverage", "Table rename (parent/child) and column rename (label) applied to both tables during a " + (rows * 2) + "-row cross-database transfer; renamed names verified present with correct data; post-load foreign key confirmed enforced on the renamed child table (insert referencing a nonexistent parent id rejected).");
        Files.createDirectories(Path.of("target/benchmarks"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmarks/" + fixture + ".json").toFile(), report);
        System.out.println("BENCHMARK " + json.writeValueAsString(report));
    }

    /**
     * P2 (improvement plan): repeats TransferFeaturesTest's H2 fault-injection techniques (a dynamic
     * Proxy wrapping the real JDBC Connection, throwing at a chosen method call) against a real Oracle
     * connection. H2 proved the recovery LOGIC; this proves the same logic survives a real driver's
     * actual commit/batch/statement semantics, not just H2's. The proxy delegates to the real
     * DatabaseGateway and distinguishes the target connection via spec.database() (the connecting
     * schema name), which is unique per role here — unlike the H2 fixture's synthetic "source"/"target"
     * host strings.
     */
    @Test void oraclePreCommitBatchFailureRollsBackAndSafelyResumes() throws Exception {
        sql(source, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)"); sql(source, "INSERT INTO ITEMS VALUES (1)");
        sql(target, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");
        AtomicBoolean failFirstBatch = new AtomicBoolean();
        DatabaseGateway failing = new DatabaseGateway() {
            @Override public Connection connect(ConnectionSpec spec) throws SQLException {
                Connection raw = db.connect(spec);
                if (!spec.username().equals(targetSchema)) return raw;
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(raw, args);
                        if (method.getName().equals("prepareStatement") && args[0].toString().startsWith("INSERT INTO")) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                                if (m.getName().equals("executeBatch") && failFirstBatch.compareAndSet(true, false)) throw new SQLException("Injected pre-commit disconnect");
                                try { return m.invoke(statement, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        service.close(); service = new MigrationService(failing, json, work.toString(), 2_000_000, 30);
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of(), false);
        failFirstBatch.set(true);
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("ITEMS", Action.DML_ONLY))));
        assertEquals("FAILED", job.state(), job.message()); assertTrue(job.resumable()); assertEquals(0, count(target, "ITEMS"));
        job = await(service.resume(job.id(), target));
        assertEquals("SUCCEEDED", job.state(), job.message()); assertEquals(1, count(target, "ITEMS"));
    }

    @Test void oracleLostCommitResponseRequiresReconciliationAndCannotBeReplayed() throws Exception {
        sql(source, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)"); sql(source, "INSERT INTO ITEMS VALUES (1)");
        sql(target, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");
        AtomicBoolean failCommitResponse = new AtomicBoolean();
        DatabaseGateway uncertain = new DatabaseGateway() {
            @Override public Connection connect(ConnectionSpec spec) throws SQLException {
                Connection raw = db.connect(spec);
                if (!spec.username().equals(targetSchema)) return raw;
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(raw, args);
                        if (method.getName().equals("commit") && failCommitResponse.compareAndSet(true, false)) throw new SQLException("Injected lost commit response");
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        service.close(); service = new MigrationService(uncertain, json, work.toString(), 2_000_000, 30);
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of(), false);
        failCommitResponse.set(true);
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("ITEMS", Action.DML_ONLY))));
        assertEquals("RECOVERY_REQUIRED", job.state(), job.message()); assertFalse(job.resumable());
        assertEquals(1, count(target, "ITEMS"), "the real commit must have succeeded on the server even though the client-side response was lost");
        assertEquals(0, job.progress().rowsCommitted(), "an uncertain outcome must not be reported as a confirmed commit");
        assertThrows(IllegalArgumentException.class, () -> service.resume(job.id(), target));
    }

    @Test void oracleCancellationRollsBackCurrentTableAndAllowsSafeResume() throws Exception {
        sql(source, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");
        sql(source, "INSERT INTO ITEMS SELECT LEVEL FROM DUAL CONNECT BY LEVEL <= 2500");
        sql(target, "CREATE TABLE ITEMS (ID NUMBER(10,0) PRIMARY KEY)");
        CountDownLatch inBatch = new CountDownLatch(1), release = new CountDownLatch(1);
        DatabaseGateway slow = new DatabaseGateway() {
            @Override public Connection connect(ConnectionSpec spec) throws SQLException {
                Connection raw = db.connect(spec);
                if (!spec.username().equals(targetSchema)) return raw;
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(raw, args);
                        if (method.getName().equals("prepareStatement") && args[0].toString().startsWith("INSERT INTO")) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    if (m.getName().equals("executeBatch")) { inBatch.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); }
                                    return m.invoke(statement, a);
                                } catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        service.close(); service = new MigrationService(slow, json, work.toString(), 2_000_000, 30);
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of(), false);
        JobView job = service.execute(plan.id(), new ExecuteRequest(target, Map.of("ITEMS", Action.DML_ONLY)));
        assertTrue(inBatch.await(15, TimeUnit.SECONDS));
        service.cancel(job.id());
        long startNanos = System.nanoTime(); release.countDown();
        // P9 responsiveness: once the worker is unblocked, it must notice the cancellation flag and
        // stop on the very next progress check rather than pressing on to commit more work — this is
        // the real, live-engine-timed half of "exercise real cancel"; correctness (rollback/resume) was
        // already proven in P2, this adds how fast the job actually stops reacting to the request.
        job = await(job);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        assertEquals("CANCELLED", job.state(), job.message());
        assertTrue(elapsedMs < 10000, "Expected cancellation to take effect quickly once unblocked; took " + elapsedMs + "ms");
        assertEquals(0, count(target, "ITEMS")); assertEquals(0, job.progress().rowsCommitted());
        job = await(service.resume(job.id(), target)); assertEquals("SUCCEEDED", job.state(), job.message()); assertEquals(2500, count(target, "ITEMS"));
    }

    @Test void oraclePostLoadDdlFailureIsUncertainAndBlocksResume() throws Exception {
        sql(source, "CREATE TABLE ITEMS (ID NUMBER(10,0) NOT NULL)"); sql(source, "CREATE INDEX SRC_ITEMS_ID ON ITEMS(ID)"); sql(source, "INSERT INTO ITEMS VALUES (1)");
        AtomicBoolean failIndex = new AtomicBoolean();
        DatabaseGateway failedPostLoad = new DatabaseGateway() {
            @Override public Connection connect(ConnectionSpec spec) throws SQLException {
                Connection raw = db.connect(spec);
                if (!spec.username().equals(targetSchema)) return raw;
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(raw, args);
                        if (method.getName().equals("createStatement")) {
                            Statement statement = (Statement) result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Statement.class}, (s, m, a) -> {
                                if (m.getName().equals("execute") && a != null && a.length > 0 && a[0] instanceof String sql && sql.startsWith("CREATE INDEX") && failIndex.compareAndSet(true, false)) throw new SQLException("Injected post-load disconnect");
                                try { return m.invoke(statement, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        service.close(); service = new MigrationService(failedPostLoad, json, work.toString(), 2_000_000, 30);
        PlanView plan = plan(source, sourceSchema, List.of("ITEMS"), target, targetSchema, false, Map.of(), false);
        assertTrue(plan.issues().isEmpty(), plan.issues().toString()); failIndex.set(true);
        JobView job = await(service.execute(plan.id(), new ExecuteRequest(target, Map.of("ITEMS", Action.CREATE_AND_LOAD))));
        // Unlike PostgreSQL's transactional DDL, Oracle DDL commits implicitly: a thrown exception
        // during CREATE INDEX cannot be proven to mean "did not run" versus "ran and committed before
        // the error". The service correctly refuses to guess and reports RECOVERY_REQUIRED rather than
        // a falsely confident FAILED+resumable — this is intentional, documented behavior, not a bug.
        assertEquals("RECOVERY_REQUIRED", job.state(), job.message()); assertFalse(job.resumable());
        assertEquals(1, job.progress().rowsCommitted(), "the row load itself is a real DML commit and stays certain even though the later DDL is not");
        assertEquals(1, count(target, "ITEMS"));
        assertThrows(IllegalArgumentException.class, () -> service.resume(job.id(), target));
        // Deliberately not asserting whether the index itself exists: even though this proxy throws
        // before invoking the real execute() call, Oracle was observed to have created the index
        // anyway (confirmed empirically) — which is exactly the point. A client-side exception on an
        // Oracle DDL statement cannot prove the DDL didn't commit server-side; that is precisely why
        // the service reports RECOVERY_REQUIRED/not-resumable above instead of guessing either way.
    }
    @Test void realSocketReadTimeoutIsEnforcedAgainstALiveSlowQuery()throws Exception {
        // P9: exercise the production readTimeoutSeconds wiring (DatabaseGateway's oracle.jdbc.ReadTimeout
        // property) against a real server that genuinely withholds its response, not a mock. DBMS_SESSION.SLEEP
        // makes the live Oracle server itself go quiet on the wire for 5s; with readTimeoutSeconds=1 the
        // client socket read must time out well before that, not hang. Uses the shared admin connection
        // (not source/target): once the client gives up, Oracle still sees the session as connected
        // server-side for a while, which would otherwise make this test's own fixture schema undroppable
        // (ORA-01940) in @AfterEach cleanup on the next test.
        MigrationOptions tight=new MigrationOptions(null,null,null,null,null,null,null,1,null,null);
        DatabaseGateway.configure(tight);
        Connection c=null;
        try {
            c=db.connect(admin);Statement s=c.createStatement();
            long startNanos=System.nanoTime();
            assertThrows(SQLException.class,()->s.execute("BEGIN DBMS_SESSION.SLEEP(5); END;"));
            long elapsedMs=(System.nanoTime()-startNanos)/1_000_000;
            assertTrue(elapsedMs<4000,"Expected the 1s read timeout to fire well before the 5s server-side sleep completed; took "+elapsedMs+"ms");
        } finally {
            DatabaseGateway.configure(null);
            if(c!=null)try{c.close();}catch(SQLException ignored){}
        }
    }
}

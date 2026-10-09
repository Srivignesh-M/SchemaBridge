package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration tests. Creates and drops only uniquely named test schemas. */
@EnabledIfSystemProperty(named="migration.pg.port",matches="[0-9]+")
class PostgresTransferTest {
    @TempDir Path work;
    ConnectionSpec connection;String sourceSchema,targetSchema;MigrationService service;
    final DatabaseGateway db=new DatabaseGateway();final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup()throws Exception{
        connection=new ConnectionSpec(Dialect.POSTGRESQL,setting("MIGRATION_PG_HOST","127.0.0.1"),Integer.getInteger("migration.pg.port"),setting("MIGRATION_PG_DATABASE","postgres"),setting("MIGRATION_PG_USERNAME","migration_test"),setting("MIGRATION_PG_PASSWORD",""));
        String suffix=UUID.randomUUID().toString().replace("-","");sourceSchema="test_source_"+suffix;targetSchema="test_target_"+suffix;
        sql("CREATE SCHEMA "+quote(sourceSchema));sql("CREATE SCHEMA "+quote(targetSchema));
        service=new MigrationService(db,json,work.toString(),2_000_000,30);
    }
    @AfterEach void close()throws Exception{if(service!=null)service.close();if(sourceSchema!=null)sql("DROP SCHEMA "+quote(sourceSchema)+" CASCADE");if(targetSchema!=null)sql("DROP SCHEMA "+quote(targetSchema)+" CASCADE");}
    void sql(String sql)throws Exception{try(Connection c=db.connect(connection);Statement s=c.createStatement()){s.execute(sql);}}
    private static String setting(String name,String fallback){String value=System.getenv(name);return value==null?fallback:value;}
    String table(String schema){return quote(schema)+".items";}
    PlanView plan(boolean copy)throws Exception{return service.create(new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,targetSchema,null,connection,sourceSchema,List.of("items"),true,connection,new MigrationOptions(null,null,null,null,null,null,null,null,true,copy),Map.of(),false));}
    JobView execute(PlanView plan)throws Exception{
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());JobView job=service.execute(plan.id(),new ExecuteRequest(connection,Map.of("items",Action.CREATE_AND_LOAD)));
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(15);
        while(Set.of("RUNNING","QUEUED").contains(job.state())&&System.nanoTime()<deadline){Thread.sleep(50);job=service.job(job.id());}
        assertEquals("SUCCEEDED",job.state(),job.message());return job;
    }
    void representative(boolean copy)throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY, note TEXT, payload BYTEA, amount NUMERIC(30,8), instant TIMESTAMP(6) WITH TIME ZONE, padded CHAR(8))");
        String text="a".repeat(8191)+"\uD83D\uDE80,\"'\r\n\\N"+"z".repeat(10000);byte[] binary=new byte[26000];new Random(3).nextBytes(binary);
        try(Connection c=db.connect(connection);PreparedStatement s=c.prepareStatement("INSERT INTO "+table(sourceSchema)+" VALUES(?,?,?,?,?,?)")){
            s.setInt(1,1);s.setString(2,text);s.setBytes(3,binary);s.setBigDecimal(4,new java.math.BigDecimal("1234567890123456789012.12345678"));s.setObject(5,java.time.OffsetDateTime.parse("2026-10-06T12:34:56.123456+05:30"));s.setString(6,"abc");s.executeUpdate();
            s.setInt(1,2);s.setString(2,"");s.setBytes(3,new byte[0]);s.setNull(4,Types.NUMERIC);s.setNull(5,Types.TIMESTAMP_WITH_TIMEZONE);s.setNull(6,Types.CHAR);s.executeUpdate();
            s.setInt(1,3);s.setNull(2,Types.VARCHAR);s.setNull(3,Types.BINARY);s.executeUpdate();
        }
        JobView job=execute(plan(copy));assertEquals(3,job.progress().rowsCommitted());
        try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM ((SELECT * FROM "+table(sourceSchema)+" EXCEPT ALL SELECT * FROM "+table(targetSchema)+") UNION ALL (SELECT * FROM "+table(targetSchema)+" EXCEPT ALL SELECT * FROM "+table(sourceSchema)+")) differences")){r.next();assertEquals(0,r.getLong(1));}
    }
    @Test void preparedStatementsPreserveRepresentativeValues()throws Exception{representative(false);}
    @Test void copyPreservesRepresentativeValues()throws Exception{representative(true);}
    @Test void metadataOnlyPlanReportsPostgresRowStatistics()throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY)");sql("INSERT INTO "+table(sourceSchema)+" SELECT n FROM generate_series(1,321) n");sql("ANALYZE "+table(sourceSchema));
        PlanView preview=service.create(new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,targetSchema,null,connection,sourceSchema,List.of("items"),true,connection,MigrationOptions.defaults(),Map.of(),true));
        assertFalse(preview.prepared());assertEquals(0,preview.tables().getFirst().rows());assertEquals(321L,preview.tables().getFirst().estimatedRows());
    }
    JobView reverted(JobView job)throws Exception{
        RevertPreview review=service.previewRevert(job.id(),connection);
        JobView result=service.revert(job.id(),new RevertRequest(connection,review.token()));
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
        while(Set.of("QUEUED","RUNNING").contains(result.revert().state())&&System.nanoTime()<deadline){Thread.sleep(25);result=service.job(job.id());}
        assertEquals("REVERTED",result.revert().state(),result.revert().message());return result;
    }
    @Test void revertsCreatedTableAndPreservesExistingRows()throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY,label VARCHAR(20)); INSERT INTO "+table(sourceSchema)+" VALUES(1,'migrated')");
        sql("CREATE TABLE "+table(targetSchema)+"(id INTEGER PRIMARY KEY,label VARCHAR(20)); INSERT INTO "+table(targetSchema)+" VALUES(99,'before')");
        PlanView p=plan(false);assertTrue(p.issues().isEmpty(),p.issues().toString());
        JobView job=service.execute(p.id(),new ExecuteRequest(connection,Map.of("items",Action.DML_ONLY)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(Set.of("QUEUED","RUNNING").contains(job.state())&&System.nanoTime()<deadline){Thread.sleep(25);job=service.job(job.id());}
        assertEquals("SUCCEEDED",job.state(),job.message());reverted(job);
        try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id FROM "+table(targetSchema))){assertTrue(r.next());assertEquals(99,r.getInt(1));assertFalse(r.next());}
        sql("DROP TABLE "+table(targetSchema));JobView created=execute(plan(true));reverted(created);
        assertFalse(db.tables(connection,targetSchema).contains("items"));
    }
    @Test void revertBlocksDependentViewAndReplacedTable()throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY); INSERT INTO "+table(sourceSchema)+" VALUES(1)");
        JobView job=execute(plan(false));sql("CREATE VIEW "+quote(targetSchema)+".dependent AS SELECT * FROM "+table(targetSchema));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),connection)).getMessage().contains("view"));
        sql("DROP VIEW "+quote(targetSchema)+".dependent");sql("DROP TABLE "+table(targetSchema));sql("CREATE TABLE "+table(targetSchema)+"(id INTEGER PRIMARY KEY); INSERT INTO "+table(targetSchema)+" VALUES(1)");
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),connection)).getMessage().contains("replaced"));
    }
    @Test @EnabledIfSystemProperty(named="migration.benchmark",matches="true") void millionNarrowPrepared()throws Exception{benchmark(false,false);}
    @Test @EnabledIfSystemProperty(named="migration.benchmark",matches="true") void millionWideCopy()throws Exception{benchmark(true,true);}
    void benchmark(boolean wide,boolean copy)throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY, label VARCHAR(1024), amount NUMERIC(18,4))");
        sql("INSERT INTO "+table(sourceSchema)+" SELECT n,"+(wide?"repeat('abcdefghi',80)":"'row-'||n")+",n::numeric/100 FROM generate_series(1,1000000) n");
        java.util.concurrent.atomic.AtomicLong peak=new java.util.concurrent.atomic.AtomicLong();
        ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,20,TimeUnit.MILLISECONDS);
        long start=System.nanoTime();PlanView p;JobView job;long prepared,end;
        try{p=plan(copy);prepared=System.nanoTime();job=execute(p);end=System.nanoTime();}finally{sampler.shutdownNow();}
        assertEquals(1_000_000,job.progress().rowsCommitted());
        try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*),SUM(id),SUM(amount) FROM "+table(targetSchema))){r.next();assertEquals(1000000,r.getLong(1));assertEquals(500000500000L,r.getLong(2));assertEquals(0,new java.math.BigDecimal("5000005000").compareTo(r.getBigDecimal(3)));}
        long bytes;try(var files=Files.walk(work.resolve(p.id()))){bytes=files.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
        if(wide)assertTrue(bytes>100_000_000,"Wide fixture must exceed old 100 MB cap");
        Map<String,Object> report=new LinkedHashMap<>();report.put("fixture",wide?"million-wide-copy":"million-narrow-prepared");report.put("rows",1000000);report.put("preparationSeconds",(prepared-start)/1e9);report.put("loadAndValidationSeconds",(end-prepared)/1e9);report.put("totalSeconds",(end-start)/1e9);report.put("stagedBytes",bytes);report.put("sampledPeakHeapBytes",peak.get());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("validation",job.tables().getFirst().message());
        try(Connection c=db.connect(connection)){report.put("database",c.getMetaData().getDatabaseProductVersion());}report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());report.put("options",p.options());
        Files.createDirectories(Path.of("target/benchmarks"));json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmarks/"+report.get("fixture")+".json").toFile(),report);System.out.println("BENCHMARK "+json.writeValueAsString(report));
    }

    JobView await(JobView job)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){job=service.job(job.id());if(!Set.of("QUEUED","RUNNING").contains(job.state()))return job;Thread.sleep(25);}
        throw new AssertionError("Job timed out: "+job);
    }
    long rows(String table)throws Exception{try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}

    /**
     * P2 (improvement plan): repeats TransferFeaturesTest's H2 fault-injection techniques against a real
     * PostgreSQL connection. Source and target here share one ConnectionSpec (different schemas, same
     * role-agnostic connection), unlike OracleTransferTest's genuinely separate source/target users, so
     * the proxy can't distinguish "target" by ConnectionSpec identity — it matches on the target
     * schema-qualified table name appearing in the SQL text instead, which is unique and known.
     */
    @Test void postgresPreCommitBatchFailureRollsBackAndSafelyResumes() throws Exception {
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY); INSERT INTO "+table(sourceSchema)+" VALUES(1)");
        sql("CREATE TABLE "+table(targetSchema)+"(id INTEGER PRIMARY KEY)");
        AtomicBoolean failFirstBatch=new AtomicBoolean();
        DatabaseGateway failing=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException{
                Connection raw=db.connect(spec);
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{
                        Object result=method.invoke(raw,args);
                        if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO")&&args[0].toString().contains(targetSchema)){
                            PreparedStatement statement=(PreparedStatement)result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                                if(m.getName().equals("executeBatch")&&failFirstBatch.compareAndSet(true,false))throw new SQLException("Injected pre-commit disconnect");
                                try{return m.invoke(statement,a);}catch(InvocationTargetException e){throw e.getCause();}
                            });
                        }
                        return result;
                    }catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(failing,json,work.toString(),2_000_000,30);
        PlanView p=plan(false);failFirstBatch.set(true);
        JobView job=await(service.execute(p.id(),new ExecuteRequest(connection,Map.of("items",Action.DML_ONLY))));
        assertEquals("FAILED",job.state(),job.message());assertTrue(job.resumable());assertEquals(0,rows(table(targetSchema)));
        job=await(service.resume(job.id(),connection));
        assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(1,rows(table(targetSchema)));
    }

    @Test void postgresLostCommitResponseRequiresReconciliationAndCannotBeReplayed() throws Exception {
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY); INSERT INTO "+table(sourceSchema)+" VALUES(1)");
        sql("CREATE TABLE "+table(targetSchema)+"(id INTEGER PRIMARY KEY)");
        AtomicBoolean failCommitResponse=new AtomicBoolean();
        DatabaseGateway uncertain=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException{
                Connection raw=db.connect(spec);
                boolean[] isTargetWriter={false};
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{
                        if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO")&&args[0].toString().contains(targetSchema))isTargetWriter[0]=true;
                        Object result=method.invoke(raw,args);
                        if(method.getName().equals("commit")&&isTargetWriter[0]&&failCommitResponse.compareAndSet(true,false))throw new SQLException("Injected lost commit response");
                        return result;
                    }catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(uncertain,json,work.toString(),2_000_000,30);
        PlanView p=plan(false);failCommitResponse.set(true);
        JobView job=await(service.execute(p.id(),new ExecuteRequest(connection,Map.of("items",Action.DML_ONLY))));
        assertEquals("RECOVERY_REQUIRED",job.state(),job.message());assertFalse(job.resumable());
        assertEquals(1,rows(table(targetSchema)),"the real commit must have succeeded on the server even though the client-side response was lost");
        assertEquals(0,job.progress().rowsCommitted(),"an uncertain outcome must not be reported as a confirmed commit");
        assertThrows(IllegalArgumentException.class,()->service.resume(job.id(),connection));
    }

    @Test void postgresCancellationRollsBackCurrentTableAndAllowsSafeResume() throws Exception {
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER PRIMARY KEY); INSERT INTO "+table(sourceSchema)+" SELECT n FROM generate_series(1,2500) n");
        sql("CREATE TABLE "+table(targetSchema)+"(id INTEGER PRIMARY KEY)");
        CountDownLatch inBatch=new CountDownLatch(1),release=new CountDownLatch(1);
        DatabaseGateway slow=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException{
                Connection raw=db.connect(spec);
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{
                        Object result=method.invoke(raw,args);
                        if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO")&&args[0].toString().contains(targetSchema)){
                            PreparedStatement statement=(PreparedStatement)result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                                try{
                                    if(m.getName().equals("executeBatch")){inBatch.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}
                                    return m.invoke(statement,a);
                                }catch(InvocationTargetException e){throw e.getCause();}
                            });
                        }
                        return result;
                    }catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(slow,json,work.toString(),2_000_000,30);
        PlanView p=plan(false);
        JobView job=service.execute(p.id(),new ExecuteRequest(connection,Map.of("items",Action.DML_ONLY)));
        assertTrue(inBatch.await(15,TimeUnit.SECONDS));
        service.cancel(job.id());
        long startNanos=System.nanoTime();release.countDown();
        // P9 responsiveness: once the worker is unblocked, it must notice the cancellation flag and
        // stop on the very next progress check rather than pressing on to commit more work — this is
        // the real, live-engine-timed half of "exercise real cancel"; correctness (rollback/resume) was
        // already proven in P2, this adds how fast the job actually stops reacting to the request.
        job=await(job);
        long elapsedMs=(System.nanoTime()-startNanos)/1_000_000;
        assertEquals("CANCELLED",job.state(),job.message());
        assertTrue(elapsedMs<10000,"Expected cancellation to take effect quickly once unblocked; took "+elapsedMs+"ms");
        assertEquals(0,rows(table(targetSchema)));assertEquals(0,job.progress().rowsCommitted());
        job=await(service.resume(job.id(),connection));assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(2500,rows(table(targetSchema)));
    }

    @Test void postgresPostLoadIndexFailureResumesWithoutReloadingCommittedRows() throws Exception {
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER NOT NULL); CREATE INDEX src_items_id ON "+table(sourceSchema)+"(id); INSERT INTO "+table(sourceSchema)+" VALUES(1)");
        AtomicBoolean failIndex=new AtomicBoolean();
        DatabaseGateway failedPostLoad=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException{
                Connection raw=db.connect(spec);
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{
                        Object result=method.invoke(raw,args);
                        if(method.getName().equals("createStatement")){
                            Statement statement=(Statement)result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(s,m,a)->{
                                if(m.getName().equals("execute")&&a!=null&&a.length>0&&a[0] instanceof String sql&&sql.startsWith("CREATE INDEX")&&sql.contains(targetSchema)&&failIndex.compareAndSet(true,false))throw new SQLException("Injected post-load disconnect");
                                try{return m.invoke(statement,a);}catch(InvocationTargetException e){throw e.getCause();}
                            });
                        }
                        return result;
                    }catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(failedPostLoad,json,work.toString(),2_000_000,30);
        PlanView p=plan(false);assertTrue(p.issues().isEmpty(),p.issues().toString());failIndex.set(true);
        JobView job=await(service.execute(p.id(),new ExecuteRequest(connection,Map.of("items",Action.CREATE_AND_LOAD))));
        assertEquals("FAILED",job.state(),job.message());assertTrue(job.resumable());assertEquals(1,job.progress().rowsCommitted());assertEquals(1,rows(table(targetSchema)));
        job=await(service.resume(job.id(),connection));assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(1,rows(table(targetSchema)));
        try(Connection c=db.connect(connection);ResultSet indexes=c.getMetaData().getIndexInfo(c.getCatalog(),targetSchema,"items",false,false)){
            assertTrue(indexes.next(),"post-load index should be created by the resumed finalization");
        }
    }
    @Test void realSocketReadTimeoutIsEnforcedAgainstALiveSlowQuery()throws Exception {
        // P9: exercise the production readTimeoutSeconds wiring (DatabaseGateway's "socketTimeout" JDBC
        // property) against a real server that genuinely withholds its response, not a mock or a sleep()
        // in test code. pg_sleep() makes the live PostgreSQL server itself go quiet on the wire for 5s;
        // with readTimeoutSeconds=1 the client socket read must time out well before that, not hang.
        MigrationOptions tight=new MigrationOptions(null,null,null,null,null,null,null,1,null,null);
        DatabaseGateway.configure(tight);
        try(Connection c=db.connect(connection);Statement s=c.createStatement()){
            long startNanos=System.nanoTime();
            assertThrows(SQLException.class,()->s.execute("SELECT pg_sleep(5)"));
            long elapsedMs=(System.nanoTime()-startNanos)/1_000_000;
            assertTrue(elapsedMs<4000,"Expected the 1s read timeout to fire well before the 5s server-side sleep completed; took "+elapsedMs+"ms");
        } finally { DatabaseGateway.configure(null); }
    }
}

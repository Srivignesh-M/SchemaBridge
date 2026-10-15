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
    void nativeTypes(boolean copy)throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id SERIAL PRIMARY KEY, ext UUID, doc JSONB, ratio REAL, score DOUBLE PRECISION, at TIME, note VARCHAR, created TIMESTAMPTZ NOT NULL DEFAULT now(), since DATE DEFAULT '1900-01-01'::date)");
        sql("INSERT INTO "+table(sourceSchema)+"(ext,doc,ratio,score,at,note) VALUES "
                +"('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11','{\"b\":[1,2],\"a\":\"x,\\\"y\"}',0.1,1e-05,'23:59:59.123456',repeat('v',20000)),"
                +"('00000000-0000-0000-0000-000000000000','[]','-Infinity','NaN','24:00:00',''),"
                +"(NULL,NULL,NULL,NULL,NULL,NULL)");
        JobView job=execute(plan(copy));assertEquals(3,job.progress().rowsCommitted());
        assertEquals("PASSED",job.tables().getFirst().validation().outcome());
        try(Connection c=db.connect(connection);Statement s=c.createStatement()){
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM ((SELECT * FROM "+table(sourceSchema)+" EXCEPT ALL SELECT * FROM "+table(targetSchema)+") UNION ALL (SELECT * FROM "+table(targetSchema)+" EXCEPT ALL SELECT * FROM "+table(sourceSchema)+")) differences")){r.next();assertEquals(0,r.getLong(1));}
            // The serial column became an identity that continues after the copied ids.
            try(ResultSet r=s.executeQuery("INSERT INTO "+table(targetSchema)+"(ext) VALUES(NULL) RETURNING id, created IS NOT NULL, since")){r.next();assertEquals(4,r.getInt(1));assertTrue(r.getBoolean(2));assertEquals("1900-01-01",r.getString(3));}
            // Revert refuses a created table holding rows it did not load, so take the probe row out again.
            s.execute("DELETE FROM "+table(targetSchema)+" WHERE id=4");
            try(ResultSet r=s.executeQuery("SELECT data_type, character_maximum_length FROM information_schema.columns WHERE table_schema='"+targetSchema+"' AND table_name='items' AND column_name='note'")){r.next();assertEquals("character varying",r.getString(1));r.getInt(2);assertTrue(r.wasNull(),"note must stay unbounded");}
        }
        reverted(job);assertFalse(db.tables(connection,targetSchema).contains("items"));
    }
    @Test void preparedStatementsCarryPostgresNativeTypes()throws Exception{nativeTypes(false);}
    @Test void copyCarriesPostgresNativeTypes()throws Exception{nativeTypes(true);}
    @Test void serialWithSharedSequenceIsExcluded()throws Exception{
        sql("CREATE SEQUENCE "+quote(sourceSchema)+".shared_seq");
        sql("CREATE TABLE "+table(sourceSchema)+"(id BIGINT PRIMARY KEY DEFAULT nextval('"+sourceSchema+".shared_seq'))");
        PlanView p=plan(false);
        assertTrue(p.tables().isEmpty(),"Table using a sequence it does not own must not be planned");
        assertTrue(p.excluded().getFirst().reason().contains("sequence it does not own"),p.excluded().toString());
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
    // ---- Parallel readers, binary transfer, sampled verification and direct transfer (live PostgreSQL) ----
    String q(String schema,String table){return quote(schema)+"."+quote(table);}
    long count(String schema,String table)throws Exception{try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+q(schema,table))){r.next();return r.getLong(1);}}
    long differences(String table)throws Exception{
        String a=q(sourceSchema,table),b=q(targetSchema,table);
        try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM ((SELECT * FROM "+a+" EXCEPT ALL SELECT * FROM "+b+") UNION ALL (SELECT * FROM "+b+" EXCEPT ALL SELECT * FROM "+a+")) d")){r.next();return r.getLong(1);}
    }
    JobView awaitJob(JobView job)throws Exception{long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);while(Set.of("RUNNING","QUEUED").contains(job.state())&&System.nanoTime()<deadline){Thread.sleep(25);job=service.job(job.id());}return job;}
    PlanRequest analysis(List<String> tables,Map<String,TableSelection> selections,boolean metadataOnly){return new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,targetSchema,null,connection,sourceSchema,tables,true,connection,null,selections,metadataOnly);}
    void documents()throws Exception{
        sql("CREATE TABLE "+q(sourceSchema,"parent")+"(id SERIAL PRIMARY KEY, label VARCHAR, doc BYTEA, meta JSONB, at TIME(6), amount NUMERIC(20,6), ext UUID, created TIMESTAMPTZ DEFAULT now())");
        sql("INSERT INTO "+q(sourceSchema,"parent")+"(label,doc,meta,at,amount,ext) SELECT 'row '||n, decode(repeat(md5(n::text),400),'hex'), jsonb_build_object('n',n,'tags',jsonb_build_array('a','b')), '23:59:59.123456'::time - (n||' seconds')::interval, n*1.000001, gen_random_uuid() FROM generate_series(1,300) n");
        sql("INSERT INTO "+q(sourceSchema,"parent")+"(label,doc,at) VALUES('nulls',NULL,'24:00:00')");
        sql("CREATE TABLE "+q(sourceSchema,"child")+"(id BIGINT PRIMARY KEY, parent_id INTEGER REFERENCES "+q(sourceSchema,"parent")+"(id), note TEXT)");
        sql("INSERT INTO "+q(sourceSchema,"child")+" SELECT n, 1+(n%300), repeat('x',n%50000) FROM generate_series(1,500) n");
        for(int t=0;t<4;t++){sql("CREATE TABLE "+q(sourceSchema,"small"+t)+"(id INTEGER PRIMARY KEY, v VARCHAR(10))");sql("INSERT INTO "+q(sourceSchema,"small"+t)+" SELECT n, 'v'||n FROM generate_series(1,"+(100*(t+1))+") n");}
    }
    List<String> documentTables(){return List.of("parent","child","small0","small1","small2","small3");}
    Map<String,Action> createAll(PlanView plan){Map<String,Action> actions=new LinkedHashMap<>();plan.tables().forEach(t->actions.put(t.table(),Action.CREATE_AND_LOAD));return actions;}

    @Test void exportedSnapshotIsSharedBetweenConnections()throws Exception{
        sql("CREATE TABLE "+table(sourceSchema)+"(id INTEGER)");sql("INSERT INTO "+table(sourceSchema)+" VALUES(1)");
        try(Connection first=db.connect(connection);Connection second=db.connect(connection)){
            db.snapshot(first,Dialect.POSTGRESQL);String snapshot=db.exportSnapshot(first,Dialect.POSTGRESQL);assertNotNull(snapshot);
            sql("INSERT INTO "+table(sourceSchema)+" VALUES(2)"); // committed after the snapshot
            db.snapshot(second,Dialect.POSTGRESQL);db.importSnapshot(second,snapshot);
            try(Statement s=second.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table(sourceSchema))){r.next();assertEquals(1,r.getLong(1),"the importing connection sees the exporter's snapshot");}
            first.rollback();second.rollback();
        }
    }
    @Test void parallelReadersStageEveryTableAndBinaryTransferKeepsValuesExact()throws Exception{
        documents();
        PreparationView p=service.prepare(analysis(documentTables(),Map.of(),false));long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
        while(Set.of("QUEUED","PREPARING").contains(p.state())&&System.nanoTime()<deadline){Thread.sleep(25);p=service.preparation(p.id());}
        assertEquals("READY",p.state(),p.message());
        assertEquals(documentTables(),p.plan().tables().stream().map(TableReport::table).toList());
        JobView job=awaitJob(service.execute(p.plan().id(),new ExecuteRequest(connection,createAll(p.plan()))));
        assertEquals("SUCCEEDED",job.state(),job.message());
        for(String table:documentTables())assertEquals(0,differences(table),table+" must arrive unchanged, including time(6) microseconds and 24:00:00");
        job.tables().forEach(t->assertEquals("FINGERPRINT",t.validation().method(),t.table()));
    }
    @Test void largeTablesAreSampledOnPostgres()throws Exception{
        long saved=MigrationService.FULL_VERIFY_BYTES;MigrationService.FULL_VERIFY_BYTES=0;
        try{
            documents();
            PreparationView p=service.prepare(analysis(List.of("parent"),Map.of(),false));long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
            while(Set.of("QUEUED","PREPARING").contains(p.state())&&System.nanoTime()<deadline){Thread.sleep(25);p=service.preparation(p.id());}
            JobView job=awaitJob(service.execute(p.plan().id(),new ExecuteRequest(connection,createAll(p.plan()))));
            assertEquals("SUCCEEDED",job.state(),job.message());
            ValidationReport v=job.tables().getFirst().validation();assertEquals("SAMPLED",v.method());assertEquals(301,v.rowsChecked());
        }finally{MigrationService.FULL_VERIFY_BYTES=saved;}
    }
    @Test @EnabledIfSystemProperty(named="migration.continuationBenchmark", matches="true")
    void manyTablesStagedAndDirectBenchmark() throws Exception {
        List<String> tables = new ArrayList<>(); long expectedRows = 0;
        for (int i = 0; i < 200; i++) {
            String name = "bench_" + i; tables.add(name);
            int rows = i == 199 ? 600 : 100, width = i == 199 ? 32768 : 160;
            expectedRows += rows;
            sql("CREATE TABLE " + q(sourceSchema, name) + "(id INTEGER PRIMARY KEY, payload BYTEA)");
            sql("INSERT INTO " + q(sourceSchema, name) + " SELECT n, decode(repeat('ab'," + width + "),'hex') FROM generate_series(1," + rows + ") n");
        }
        List<Map<String,Object>> results = new ArrayList<>(); int previousReaders = SourceExtraction.MAX_READERS;
        try {
            for (String mode : List.of("staged-1-reader", "staged-3-readers", "direct")) {
                boolean direct = mode.equals("direct"); SourceExtraction.MAX_READERS = mode.equals("staged-1-reader") ? 1 : 3;
                long start = System.nanoTime();
                PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, targetSchema, null, connection, sourceSchema,
                        tables, true, connection, null, Map.of(), direct));
                double preparation = (System.nanoTime() - start) / 1e9;
                JobView job = awaitJob(service.execute(plan.id(), new ExecuteRequest(connection, createAll(plan), false, direct ? connection : null, direct)));
                double total = (System.nanoTime() - start) / 1e9;
                assertEquals("SUCCEEDED", job.state(), mode + ": " + job.message() + " at " + job.progress().table()); assertEquals(expectedRows, job.progress().rowsCommitted());
                for (String name : tables) {
                    try (Connection c = db.connect(connection); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                            "SELECT COUNT(*) FROM ((SELECT * FROM " + q(sourceSchema,name) + " EXCEPT ALL SELECT * FROM " + q(targetSchema,name)
                            + ") UNION ALL (SELECT * FROM " + q(targetSchema,name) + " EXCEPT ALL SELECT * FROM " + q(sourceSchema,name) + ")) differences")) {
                        r.next(); assertEquals(0, r.getLong(1), name);
                    }
                }
                results.add(Map.of("mode", mode, "tables", tables.size(), "rows", expectedRows, "preparationSeconds", preparation, "totalSeconds", total));
                for (String name : tables) sql("DROP TABLE " + q(targetSchema,name));
                service.delete(plan.id());
            }
        } finally { SourceExtraction.MAX_READERS = previousReaders; }
        Path output = Path.of("target/benchmarks/continuation-many-tables.json"); Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
    }

    @Test void directTransferHonoursDefinitionsOnlyAfterRestart() throws Exception {
        sql("CREATE TABLE " + table(sourceSchema) + "(id INTEGER PRIMARY KEY)");
        sql("INSERT INTO " + table(sourceSchema) + " VALUES (1),(2)");
        PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, targetSchema, null, connection, sourceSchema,
                List.of("items"), false, connection, null, Map.of(), true));
        service.close(); service = new MigrationService(db, json, work.toString(), 2_000_000, 30);
        JobView job = awaitJob(service.execute(plan.id(), new ExecuteRequest(connection, createAll(plan), false, connection, true)));
        assertEquals("SUCCEEDED", job.state(), job.message());
        assertEquals(0, count(targetSchema, "items")); assertEquals(0, job.progress().rowsCommitted());
    }

    @Test void directTransferCountsEarlierCommitsAgainstBudgetOnResume() throws Exception {
        for (String name : List.of("first", "second")) {
            sql("CREATE TABLE " + q(sourceSchema, name) + "(id INTEGER PRIMARY KEY)");
            sql("INSERT INTO " + q(sourceSchema, name) + " VALUES (1),(2),(3)");
        }
        PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, targetSchema, null, connection, sourceSchema,
                List.of("first", "second"), true, connection, MigrationOptions.defaults(4), Map.of(), true));
        JobView job = awaitJob(service.execute(plan.id(), new ExecuteRequest(connection, createAll(plan), false, connection, true)));
        assertEquals("FAILED", job.state()); assertEquals(3, job.progress().rowsCommitted());
        assertEquals(3, count(targetSchema, "first")); assertEquals(0, count(targetSchema, "second"));
        service.close(); service = new MigrationService(db, json, work.toString(), 2_000_000, 30);
        JobView resumed = awaitJob(service.resume(job.id(), connection, connection));
        assertEquals("FAILED", resumed.state()); assertTrue(resumed.message().contains("total row limit"), resumed.message());
        assertEquals(3, count(targetSchema, "first")); assertEquals(0, count(targetSchema, "second"));
    }

    @Test void directTransferEnforcesTotalRowsAndByteLimit() throws Exception {
        sql("CREATE TABLE " + table(sourceSchema) + "(id INTEGER PRIMARY KEY, payload TEXT)");
        sql("INSERT INTO " + table(sourceSchema) + " SELECT n, repeat('x',1000) FROM generate_series(1,3) n");
        for (boolean rowLimit : List.of(true, false)) {
            MigrationOptions options = new MigrationOptions(rowLimit ? 2L : 100L, rowLimit ? 100000L : 100L, 0L, null, null, null, null, null, true, true);
            PlanView plan = service.create(new PlanRequest(Dialect.POSTGRESQL, Dialect.POSTGRESQL, targetSchema, null, connection, sourceSchema,
                    List.of("items"), true, connection, options, Map.of(), true));
            JobView job = awaitJob(service.execute(plan.id(), new ExecuteRequest(connection, createAll(plan), false, connection, true)));
            assertEquals("FAILED", job.state(), job.message());
            assertTrue(job.message().contains(rowLimit ? "total row limit" : "byte limit"), job.message());
            assertEquals(0, count(targetSchema, "items"), "Failed table must roll back");
            sql("DROP TABLE " + table(targetSchema));
        }
    }

    @Test void directTransferStreamsTablesWithoutStaging()throws Exception{
        documents();
        PlanView plan=service.create(analysis(documentTables(),Map.of(),true));
        assertFalse(plan.prepared());
        JobView job=awaitJob(service.execute(plan.id(),new ExecuteRequest(connection,createAll(plan),false,connection,true)));
        assertEquals("SUCCEEDED",job.state(),job.message());assertTrue(job.direct());
        assertTrue(job.progress().phaseSeconds().keySet().containsAll(List.of("COPYING","FINGERPRINTING","COMMITTING")));
        for(String table:documentTables())assertEquals(0,differences(table),table+" must arrive unchanged");
        job.tables().forEach(t->assertEquals("STREAMED",t.validation().method(),t.table()));
        assertEquals(301,job.tables().stream().filter(t->t.table().equals("parent")).findFirst().orElseThrow().rows());
        try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("INSERT INTO "+q(targetSchema,"parent")+"(label) VALUES('next') RETURNING id")){r.next();assertEquals(302,r.getInt(1),"the identity continues after the streamed ids");}
        sql("DELETE FROM "+q(targetSchema,"parent")+" WHERE id=302");
        // Foreign keys are added after loading, so the child must reference the streamed parent.
        try(Connection c=db.connect(connection);Statement s=c.createStatement()){assertThrows(SQLException.class,()->s.execute("INSERT INTO "+q(targetSchema,"child")+" VALUES(9999,99999,'orphan')"));}
        reverted(job);
        for(String table:documentTables())assertFalse(db.tables(connection,targetSchema).contains(table),table+" must be dropped by revert");
    }
    @Test void directTransferHonoursRenamesAndRowLimits()throws Exception{
        documents();
        var selection=new TableSelection(List.of("id","v"),Map.of("v","value"),List.of(),"renamed",50);
        PlanView plan=service.create(analysis(List.of("small3"),Map.of("small3",selection),true));
        JobView job=awaitJob(service.execute(plan.id(),new ExecuteRequest(connection,Map.of("renamed",Action.CREATE_AND_LOAD),false,connection,true)));
        assertEquals("SUCCEEDED",job.state(),job.message());
        assertEquals(50,count(targetSchema,"renamed"));
        try(Connection c=db.connect(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+q(targetSchema,"renamed")+" t JOIN "+q(sourceSchema,"small3")+" s ON s.id=t.id AND s.v=t.value")){r.next();assertEquals(50,r.getLong(1));}
    }
    @Test void directTransferRefusesWhatItCannotDoAndResumesOnlyWithTheSource()throws Exception{
        documents();
        sql("CREATE TABLE "+q(targetSchema,"small0")+"(id INTEGER PRIMARY KEY, v VARCHAR(10))");
        var filtered=new TableSelection(null,null,List.of(new RowFilter("id",">=","10")));
        PlanView plan=service.create(analysis(List.of("small0","small1"),Map.of("small1",filtered),true));
        var refused=assertThrows(IllegalArgumentException.class,()->service.execute(plan.id(),new ExecuteRequest(connection,Map.of("small0",Action.DML_ONLY,"small1",Action.CREATE_AND_LOAD),false,connection,true)));
        assertTrue(refused.getMessage().contains("small0 (existing table)")&&refused.getMessage().contains("small1 (row filters)"),refused.getMessage());
        var other=new ConnectionSpec(Dialect.POSTGRESQL,"localhost",connection.port(),connection.database(),connection.username(),connection.password());
        assertThrows(IllegalArgumentException.class,()->service.execute(plan.id(),new ExecuteRequest(connection,Map.of("small0",Action.SKIP,"small1",Action.SKIP),false,other,true)));
        // A source table dropped after analysis fails the run before any write; resuming needs the source again.
        PlanView fresh=service.create(analysis(List.of("small2"),Map.of(),true));
        sql("ALTER TABLE "+q(sourceSchema,"small2")+" RENAME TO small2_moved");
        JobView failed=awaitJob(service.execute(fresh.id(),new ExecuteRequest(connection,Map.of("small2",Action.CREATE_AND_LOAD),false,connection,true)));
        assertEquals("FAILED",failed.state());assertTrue(failed.message().contains("no longer exists"),failed.message());assertTrue(failed.resumable());
        assertThrows(IllegalArgumentException.class,()->service.resume(failed.id(),connection));
        sql("ALTER TABLE "+q(sourceSchema,"small2_moved")+" RENAME TO small2");
        JobView resumed=awaitJob(service.resume(failed.id(),connection,connection));
        assertEquals("SUCCEEDED",resumed.state(),resumed.message());assertEquals(0,differences("small2"));
    }
}

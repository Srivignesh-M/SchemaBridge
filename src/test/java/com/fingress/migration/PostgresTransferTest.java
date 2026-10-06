package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;
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
}

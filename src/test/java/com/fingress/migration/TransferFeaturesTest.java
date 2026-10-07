package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.*;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferFeaturesTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final ConnectionSpec source=new ConnectionSpec(Dialect.POSTGRESQL,"source",5432,"test","user","private-secret");
    final ConnectionSpec target=new ConnectionSpec(Dialect.POSTGRESQL,"target",5432,"test","user","private-secret");
    String sourceUrl,targetUrl;DatabaseGateway db;MigrationService service;
    @BeforeEach void setup()throws Exception {
        sourceUrl="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        targetUrl="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        db=new DatabaseGateway(){@Override public Connection connect(ConnectionSpec spec)throws SQLException{return DriverManager.getConnection(spec.host().equals("source")?sourceUrl:targetUrl);}@Override public Long estimateRows(Connection connection,String schema,String table,Dialect dialect){return 105_000L;}};
        service=new MigrationService(db,json,directory.toString(),2_000_000,30);
    }
    @AfterEach void close(){service.close();}
    void sql(String url,String sql)throws Exception{try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement()){s.execute(sql);}}
    long count(String table)throws Exception{try(Connection c=DriverManager.getConnection(targetUrl);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    PlanRequest request(boolean metadata,Map<String,TableSelection> selections,MigrationOptions options){return new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,"public",null,source,"public",List.of("items"),true,target,options,selections,metadata);}
    PlanView plan()throws Exception{return service.create(request(false,Map.of(),MigrationOptions.defaults()));}
    JobView await(JobView job)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){job=service.job(job.id());if(!Set.of("QUEUED","RUNNING").contains(job.state())&&!job.tables().isEmpty())return job;Thread.sleep(10);}
        throw new AssertionError("Job timed out: "+job);
    }
    JobView run(PlanView plan,Action action)throws Exception {assertTrue(plan.issues().isEmpty(),plan.issues().toString());return await(service.execute(plan.id(),new ExecuteRequest(target,Map.of("items",action))));}
    @Test void mapsSelectedColumnsAndUsesParameterizedFilters()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY, label VARCHAR(100), omitted VARCHAR(10)); INSERT INTO items VALUES(1,'first','x'),(2,'second','y')");
        var selection=new TableSelection(List.of("id","label"),Map.of("label","display name"),List.of(new RowFilter("id",">=","2")));
        PlanView plan=service.create(request(false,Map.of("items",selection),MigrationOptions.defaults()));
        assertEquals(1,plan.tables().getFirst().rows());assertEquals("SUCCEEDED",run(plan,Action.CREATE_AND_LOAD).state());
        try(Connection c=DriverManager.getConnection(targetUrl);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,\"display name\" FROM items")){assertTrue(r.next());assertEquals(2,r.getInt(1));assertEquals("second",r.getString(2));assertFalse(r.next());}
        var injection=new TableSelection(null,null,List.of(new RowFilter("label","=","x' OR 1=1 --")));
        assertEquals(0,service.create(request(false,Map.of("items",injection),MigrationOptions.defaults())).tables().getFirst().rows());
        var missingKey=new TableSelection(List.of("label"),null,null);
        assertFalse(service.create(request(false,Map.of("items",missingKey),MigrationOptions.defaults())).issues().isEmpty());
    }
    @Test void metadataPreviewDoesNotExtractAndPreparationHasProgress()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items SELECT * FROM SYSTEM_RANGE(1,110000)");
        PlanView preview=service.create(request(true,Map.of(),MigrationOptions.defaults()));
        assertFalse(preview.prepared());assertEquals(0,preview.tables().getFirst().rows());assertEquals(105_000L,preview.tables().getFirst().estimatedRows());
        assertThrows(IllegalArgumentException.class,()->service.execute(preview.id(),new ExecuteRequest(target,Map.of("items",Action.CREATE_AND_LOAD))));
        PreparationView p=service.prepare(request(false,Map.of(),MigrationOptions.defaults()));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(Set.of("QUEUED","PREPARING").contains(p.state())&&System.nanoTime()<end){Thread.sleep(10);p=service.preparation(p.id());}
        assertEquals("READY",p.state(),p.message());assertEquals(110000,p.progress().rowsRead());assertEquals(110000,p.plan().tables().getFirst().rows());
    }
    @Test void migrationOptionsDefaultLegacySettingsToVersionOneAndRejectUnknownVersions()throws Exception {
        MigrationOptions legacy=json.readValue("{\"maxRows\":500}",MigrationOptions.class);
        assertEquals(1,legacy.settingsVersion());assertEquals(1,json.readValue(json.writeValueAsString(legacy),MigrationOptions.class).settingsVersion());
        assertThrows(IllegalArgumentException.class,()->new MigrationOptions(null,null,null,null,null,null,null,null,null,null,2));
    }
    @Test void streamsUnicodeLobsBinaryNullsAndExactDecimals()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY, note CLOB, payload BYTEA, amount NUMERIC(30,8), instant TIMESTAMP WITH TIME ZONE)");
        String text="a".repeat(4095)+"\uD83D\uDE80'\n"+"b".repeat(22000);byte[] binary=new byte[40000];new Random(4).nextBytes(binary);
        try(Connection c=DriverManager.getConnection(sourceUrl);PreparedStatement s=c.prepareStatement("INSERT INTO items VALUES(?,?,?,?,?)")){
            s.setInt(1,1);s.setString(2,text);s.setBytes(3,binary);s.setBigDecimal(4,new java.math.BigDecimal("1234567890123456789012.12345678"));s.setObject(5,java.time.OffsetDateTime.parse("2026-10-06T12:34:56.123456+05:30"));s.executeUpdate();
            s.setInt(1,2);s.setString(2,"");s.setBytes(3,new byte[0]);s.setNull(4,Types.NUMERIC);s.setNull(5,Types.TIMESTAMP_WITH_TIMEZONE);s.executeUpdate();
            s.setInt(1,3);s.setNull(2,Types.CLOB);s.setNull(3,Types.BLOB);s.executeUpdate();
        }
        PlanView p=plan();JobView job=run(p,Action.CREATE_AND_LOAD);assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(3,job.progress().rowsCommitted());
        assertTrue(job.tables().getFirst().message().contains("SHA-256"));
        try(Connection c=DriverManager.getConnection(targetUrl);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM items ORDER BY id")){
            assertTrue(r.next());assertEquals(text,r.getString("note"));assertArrayEquals(binary,r.getBytes("payload"));assertEquals("1234567890123456789012.12345678",r.getBigDecimal("amount").toPlainString());
            assertTrue(r.next());assertEquals("",r.getString("note"));assertEquals(0,r.getBytes("payload").length);assertTrue(r.next());assertNull(r.getString("note"));assertNull(r.getBytes("payload"));
        }
        var out=new java.io.ByteArrayOutputStream();service.download(p.id(),Map.of("items",Action.CREATE_AND_LOAD),out);
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))){java.util.zip.ZipEntry entry;boolean found=false;while((entry=zip.getNextEntry())!=null){if(entry.getName().endsWith("02-data.sql")){assertTrue(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains(text.replace("'","''")));found=true;}}assertTrue(found);}
    }
    @Test void durableResumeSkipsCommittedTablesAndNeverPersistsPasswords()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1),(2)");sql(targetUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1)");
        MigrationOptions options=new MigrationOptions(50L,1_000_000L,0L,25,7,4096L,321,654,true,false);
        PlanView p=service.create(request(false,Map.of(),options));assertEquals(options,p.options());
        JobView job=await(service.execute(p.id(),new ExecuteRequest(target,Map.of("items",Action.DML_ONLY))));assertEquals("FAILED",job.state());assertTrue(job.resumable());assertEquals(options,job.options());assertEquals(1,count("items"));
        service.close();service=new MigrationService(db,json,directory.toString(),2_000_000,30);
        assertEquals(1,service.history().size());assertTrue(service.job(job.id()).resumable());assertEquals(options,service.job(job.id()).options());assertEquals(options,service.view(p.id()).options());
        sql(targetUrl,"DELETE FROM items");job=await(service.resume(job.id(),target));assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(2,count("items"));
        String id=job.id();assertThrows(IllegalArgumentException.class,()->service.resume(id,target));
        for(String file:List.of("plan.json","job.json"))assertFalse(Files.readString(directory.resolve(p.id()).resolve(file)).contains("private-secret"));
    }
    @Test void uncertainRestartCannotBeReplayedAndWorkFolderHasExclusiveOwner()throws Exception {
        assertThrows(java.io.IOException.class,()->new MigrationService(db,json,directory.toString(),100,30));
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1)");PlanView p=plan();JobView job=run(p,Action.CREATE_AND_LOAD);service.close();
        Path manifest=directory.resolve(p.id()).resolve("job.json");var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(manifest));((com.fasterxml.jackson.databind.node.ObjectNode)node.get("view")).put("state","RUNNING");Files.writeString(manifest,json.writeValueAsString(node));
        service=new MigrationService(db,json,directory.toString(),100,30);assertEquals("RECOVERY_REQUIRED",service.job(job.id()).state());assertFalse(service.job(job.id()).resumable());assertThrows(IllegalArgumentException.class,()->service.resume(job.id(),target));assertEquals(1,count("items"));
    }
    @Test void corruptedStagingCannotWriteAndQuotasDoNotTruncate()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1),(2)");
        var options=new MigrationOptions(1L,null,0L,null,null,null,null,null,null,null);
        PlanView limited=service.create(request(false,Map.of(),options));assertFalse(limited.issues().isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.execute(limited.id(),new ExecuteRequest(target,Map.of())));
        PlanView p=plan();Files.writeString(directory.resolve(p.id()).resolve("data-0.rows"),"tampered");JobView job=run(p,Action.CREATE_AND_LOAD);assertEquals("FAILED",job.state());assertTrue(job.message().contains("changed"));assertThrows(SQLException.class,()->count("items"));
        options=new MigrationOptions(null,10L,0L,null,null,null,null,null,null,null);assertFalse(service.create(request(false,Map.of(),options)).issues().isEmpty());
        var disk=new MigrationOptions(null,null,Long.MAX_VALUE,null,null,null,null,null,null,null);assertThrows(java.io.IOException.class,()->service.create(request(false,Map.of(),disk)));
    }
    @Test void cancellationRollsBackCurrentTableAndAllowsSafeResume()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items SELECT * FROM SYSTEM_RANGE(1,2500)");
        CountDownLatch inBatch=new CountDownLatch(1),release=new CountDownLatch(1);
        DatabaseGateway slow=new DatabaseGateway(){@Override public Connection connect(ConnectionSpec spec)throws SQLException{
            Connection raw=db.connect(spec);if(spec.host().equals("source"))return raw;
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try{Object result=method.invoke(raw,args);if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO")){
                    PreparedStatement ps=(PreparedStatement)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{try{if(m.getName().equals("executeBatch")){inBatch.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return m.invoke(ps,a);}catch(InvocationTargetException e){throw e.getCause();}});
                }return result;}catch(InvocationTargetException e){throw e.getCause();}
            });
        }};
        service.close();service=new MigrationService(slow,json,directory.toString(),2_000_000,30);PlanView p=plan();JobView job=service.execute(p.id(),new ExecuteRequest(target,Map.of("items",Action.CREATE_AND_LOAD)));
        assertTrue(inBatch.await(10,TimeUnit.SECONDS));service.cancel(job.id());release.countDown();job=await(job);assertEquals("CANCELLED",job.state(),job.message());assertEquals(0,count("items"));assertEquals(0,job.progress().rowsCommitted());
        job=await(service.resume(job.id(),target));assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(2500,count("items"));
    }
    @Test void commitResponseFailureRequiresReconciliationAndCannotBeReplayed()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1)");sql(targetUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY)");
        AtomicBoolean failCommitResponse=new AtomicBoolean();DatabaseGateway uncertainCommit=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException {
                Connection raw=db.connect(spec);if(!spec.host().equals("target"))return raw;
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{Object result=method.invoke(raw,args);if(method.getName().equals("commit")&&failCommitResponse.compareAndSet(true,false))throw new SQLException("Injected lost commit response");return result;}
                    catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(uncertainCommit,json,directory.toString(),2_000_000,30);
        PlanView plan=plan();failCommitResponse.set(true);
        JobView job=await(service.execute(plan.id(),new ExecuteRequest(target,Map.of("items",Action.DML_ONLY))));
        assertEquals("RECOVERY_REQUIRED",job.state(),job.message());assertFalse(job.resumable());
        assertEquals(1,count("items"));assertEquals(0,job.progress().rowsCommitted());
        assertThrows(IllegalArgumentException.class,()->service.resume(job.id(),target));
    }
    @Test void failedBatchAndRollbackRequireReconciliation()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1)");sql(targetUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY)");
        AtomicBoolean failBatchResponse=new AtomicBoolean(),failRollback=new AtomicBoolean();DatabaseGateway base=db;DatabaseGateway failedRollback=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException {
                Connection raw=base.connect(spec);if(!spec.host().equals("target"))return raw;
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("rollback")&&failRollback.compareAndSet(true,false))throw new SQLException("Injected rollback failure");
                    try{Object result=method.invoke(raw,args);if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO")){
                        PreparedStatement statement=(PreparedStatement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(prepared,operation,parameters)->{
                            try{Object value=operation.invoke(statement,parameters);if(operation.getName().equals("executeBatch")&&failBatchResponse.compareAndSet(true,false))throw new SQLException("Injected lost batch response");return value;}
                            catch(InvocationTargetException failure){throw failure.getCause();}
                        });
                    }return result;}catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(failedRollback,json,directory.toString(),2_000_000,30);
        PlanView plan=plan();failBatchResponse.set(true);failRollback.set(true);
        JobView job=await(service.execute(plan.id(),new ExecuteRequest(target,Map.of("items",Action.DML_ONLY))));
        assertEquals("RECOVERY_REQUIRED",job.state(),job.message());assertFalse(job.resumable());
        assertEquals(0,count("items"));assertEquals(0,job.progress().rowsCommitted());
        assertThrows(IllegalArgumentException.class,()->service.resume(job.id(),target));
    }
    @Test void preCommitBatchFailureRollsBackAndCanSafelyResume()throws Exception {
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1)");sql(targetUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY)");
        AtomicBoolean failFirstBatch=new AtomicBoolean();DatabaseGateway base=db;DatabaseGateway failedBatch=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException {
                Connection raw=base.connect(spec);if(!spec.host().equals("target"))return raw;
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                    try{Object result=method.invoke(raw,args);if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO")){
                        PreparedStatement statement=(PreparedStatement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(prepared,operation,parameters)->{
                            if(operation.getName().equals("executeBatch")&&failFirstBatch.compareAndSet(true,false))throw new SQLException("Injected pre-commit disconnect");
                            try{return operation.invoke(statement,parameters);}catch(InvocationTargetException failure){throw failure.getCause();}
                        });
                    }return result;}catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
        service.close();service=new MigrationService(failedBatch,json,directory.toString(),2_000_000,30);
        PlanView plan=plan();failFirstBatch.set(true);
        JobView job=await(service.execute(plan.id(),new ExecuteRequest(target,Map.of("items",Action.DML_ONLY))));
        assertEquals("FAILED",job.state(),job.message());assertTrue(job.resumable());assertEquals(0,count("items"));
        job=await(service.resume(job.id(),target));assertEquals("SUCCEEDED",job.state(),job.message());assertEquals(1,count("items"));
    }
}

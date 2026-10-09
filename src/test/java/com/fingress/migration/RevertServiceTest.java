package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.*;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class RevertServiceTest {
    @TempDir Path work;
    final ObjectMapper json=new ObjectMapper();
    final ConnectionSpec source=new ConnectionSpec(Dialect.POSTGRESQL,"source",5432,"test","user","private-secret");
    final ConnectionSpec target=new ConnectionSpec(Dialect.POSTGRESQL,"target",5432,"test","user","private-secret");
    String sourceUrl,targetUrl;DatabaseGateway db;MigrationService service;
    @BeforeEach void setup()throws Exception{
        sourceUrl="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        targetUrl="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        db=new DatabaseGateway(){@Override public Connection connect(ConnectionSpec c)throws SQLException{return DriverManager.getConnection(c.host().equals("source")?sourceUrl:targetUrl);}};
        service=new MigrationService(db,json,work.toString(),10000,30);
    }
    @AfterEach void close(){service.close();}
    void sql(String url,String sql)throws Exception{try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement()){s.execute(sql);}}
    long count(String table)throws Exception{try(Connection c=DriverManager.getConnection(targetUrl);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}}
    boolean exists(String table)throws Exception{try(Connection c=DriverManager.getConnection(targetUrl)){return db.tables(c,"public").contains(table);}}
    PlanView plan(String... tables)throws Exception{return service.create(new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,"public",null,source,"public",List.of(tables),true,target));}
    JobView await(JobView job,boolean undo)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){job=service.job(job.id());String state=undo?job.revert().state():job.state();if(!Set.of("QUEUED","RUNNING").contains(state))return job;Thread.sleep(10);}
        throw new AssertionError("Job timed out");
    }
    JobView run(PlanView plan,Map<String,Action> actions)throws Exception{
        assertTrue(plan.issues().isEmpty(),plan.issues().toString());JobView job=await(service.execute(plan.id(),new ExecuteRequest(target,actions)),false);
        assertEquals("SUCCEEDED",job.state(),job.message());return job;
    }
    JobView revert(JobView job)throws Exception{
        RevertPreview preview=service.previewRevert(job.id(),target);
        JobView result=await(service.revert(job.id(),new RevertRequest(target,preview.token())),true);
        assertEquals("REVERTED",result.revert().state(),result.revert().message());return result;
    }
    JobView existing()throws Exception{
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY, label VARCHAR(100)); INSERT INTO items VALUES(1,'one'),(2,'two')");
        sql(targetUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY, label VARCHAR(100)); INSERT INTO items VALUES(99,'before')");
        return run(plan("items"),Map.of("items",Action.DML_ONLY));
    }
    @Test void retainsPreexistingAndLaterUnrelatedRowsAndPersistsRevert()throws Exception{
        JobView job=existing();sql(targetUrl,"INSERT INTO items VALUES(100,'later')");
        RevertPreview preview=service.previewRevert(job.id(),target);assertEquals(2,preview.tables().getFirst().rows());assertEquals(4,count("items"));
        JobView reverted=revert(job);assertEquals(2,reverted.revert().progress().rowsCommitted());assertEquals(2,count("items"));assertEquals(2,count("items WHERE id IN (99,100)"));
        service.close();service=new MigrationService(db,json,work.toString(),10000,30);
        assertEquals("REVERTED",service.job(job.id()).revert().state());assertFalse(service.job(job.id()).resumable());
        assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target));
        assertFalse(Files.readString(work.resolve(job.planId()).resolve("job.json")).contains("private-secret"));
    }
    @Test void removesChildTableBeforeKeepingExistingParent()throws Exception{
        sql(sourceUrl,"CREATE TABLE parent(id INTEGER PRIMARY KEY); CREATE TABLE child(id INTEGER PRIMARY KEY,pid INTEGER REFERENCES parent(id)); INSERT INTO parent VALUES(1); INSERT INTO child VALUES(7,1)");
        sql(targetUrl,"CREATE TABLE parent(id INTEGER PRIMARY KEY); INSERT INTO parent VALUES(99)");
        JobView job=run(plan("child","parent"),Map.of("parent",Action.DML_ONLY,"child",Action.CREATE_AND_LOAD));
        revert(job);assertFalse(exists("child"));assertEquals(1,count("parent WHERE id=99"));assertEquals(1,count("parent"));
    }
    @Test void capturesSqlInputTableReceiptIncludingGeneratedValues()throws Exception{
        PlanView plan=service.create(new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,"public",
                "CREATE TABLE items(id INTEGER PRIMARY KEY, happened TIMESTAMP DEFAULT CURRENT_TIMESTAMP); INSERT INTO items(id) VALUES(1);",null,null,null,true,target));
        JobView job=run(plan,Map.of("items",Action.CREATE_AND_LOAD));
        service.close();service=new MigrationService(db,json,work.toString(),10000,30);
        revert(job);assertFalse(exists("items"));
    }
    @Test void blocksChangedExistingRowsBeforeAndAfterReviewAndRollsBackAllRows()throws Exception{
        JobView job=existing();RevertPreview preview=service.previewRevert(job.id(),target);
        sql(targetUrl,"UPDATE items SET label='changed' WHERE id=2");
        assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target));
        JobView failed=await(service.revert(job.id(),new RevertRequest(target,preview.token())),true);
        assertEquals("FAILED",failed.revert().state());assertEquals(3,count("items"));assertEquals(1,count("items WHERE label='changed'"));
        sql(targetUrl,"UPDATE items SET label='two' WHERE id=2");revert(job);assertEquals(1,count("items"));
    }
    @Test void neverDropsNewTableContainingAdditionalData()throws Exception{
        sql(sourceUrl,"CREATE TABLE items(id INTEGER PRIMARY KEY); INSERT INTO items VALUES(1)");
        JobView job=run(plan("items"),Map.of("items",Action.CREATE_AND_LOAD));RevertPreview preview=service.previewRevert(job.id(),target);
        sql(targetUrl,"INSERT INTO items VALUES(2)");
        JobView failed=await(service.revert(job.id(),new RevertRequest(target,preview.token())),true);
        assertEquals("FAILED",failed.revert().state());assertTrue(exists("items"));assertEquals(2,count("items"));
    }
    @Test void blocksMissingKeysAndSqlDmlReceipts()throws Exception{
        sql(sourceUrl,"CREATE TABLE items(id INTEGER); INSERT INTO items VALUES(1)");sql(targetUrl,"CREATE TABLE items(id INTEGER)");
        JobView job=run(plan("items"),Map.of("items",Action.DML_ONLY));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target)).getMessage().contains("primary key"));
        PlanView sqlPlan=service.create(new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,"public","CREATE TABLE items(id INTEGER); INSERT INTO items VALUES(2);",null,null,null,true,target));
        JobView sqlJob=run(sqlPlan,Map.of("items",Action.DML_ONLY));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(sqlJob.id(),target)).getMessage().contains("receipts"));assertEquals(2,count("items"));
    }
    @Test void blocksWrongTargetMissingConfirmationSchemaDriftAndExternalDependencies()throws Exception{
        JobView job=existing();
        assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),source));
        assertThrows(IllegalArgumentException.class,()->service.revert(job.id(),new RevertRequest(target,null)));
        sql(targetUrl,"CREATE TABLE outsider(id INTEGER REFERENCES items(id)); INSERT INTO outsider VALUES(1)");
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target)).getMessage().contains("references"));
        sql(targetUrl,"DROP TABLE outsider; ALTER TABLE items ADD extra INTEGER");
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target)).getMessage().contains("structure changed"));assertEquals(3,count("items"));
    }
    @Test void treatsLostRevertCommitResponseAsUncertainAndProtectsArtifacts()throws Exception{
        JobView job=existing();service.close();AtomicBoolean fail=new AtomicBoolean(true);
        DatabaseGateway lostCommit=new DatabaseGateway(){@Override public Connection connect(ConnectionSpec spec)throws SQLException{
            Connection raw=db.connect(spec);return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try{Object result=method.invoke(raw,args);if(method.getName().equals("commit")&&fail.getAndSet(false))throw new SQLException("lost response","08006");return result;}
                catch(InvocationTargetException e){throw e.getCause();}
            });
        }};
        service=new MigrationService(lostCommit,json,work.toString(),10000,30);RevertPreview preview=service.previewRevert(job.id(),target);
        JobView result=await(service.revert(job.id(),new RevertRequest(target,preview.token())),true);
        assertEquals("RECOVERY_REQUIRED",result.revert().state());assertEquals(1,count("items"));assertEquals(0,result.revert().progress().rowsCommitted());
        service.close();service=new MigrationService(db,json,work.toString(),10000,30);
        assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target));assertThrows(IllegalArgumentException.class,()->service.delete(job.planId()));
    }
    @Test void restartDuringRevertNeverAutomaticallyReplays()throws Exception{
        JobView job=existing();revert(job);service.close();
        Path journal=work.resolve(job.planId()).resolve("job.json");var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(journal));
        ((com.fasterxml.jackson.databind.node.ObjectNode)saved.get("revert")).put("state","RUNNING");Files.writeString(journal,json.writeValueAsString(saved));
        service=new MigrationService(db,json,work.toString(),10000,30);
        assertEquals("RECOVERY_REQUIRED",service.job(job.id()).revert().state());assertThrows(IllegalArgumentException.class,()->service.previewRevert(job.id(),target));assertEquals(1,count("items"));
    }
}

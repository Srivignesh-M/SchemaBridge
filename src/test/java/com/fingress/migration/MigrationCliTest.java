package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationCliTest {
    @TempDir Path directory;
    String sourceUrl,targetUrl;MigrationCli cli;ByteArrayOutputStream messages;
    @BeforeEach void setup() throws Exception {
        sourceUrl="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        targetUrl="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DatabaseGateway db=new DatabaseGateway(){@Override public Connection connect(ConnectionSpec spec)throws SQLException{return DriverManager.getConnection(spec.host().equals("source")?sourceUrl:targetUrl);}};
        messages=new ByteArrayOutputStream();cli=new MigrationCli(db,name->"test-only-secret",new PrintStream(messages));
        sql(sourceUrl,"CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(50)); INSERT INTO customers VALUES (1,'Alice')");
    }
    void sql(String url,String sql)throws Exception{try(Connection c=DriverManager.getConnection(url);Statement s=c.createStatement()){s.execute(sql);}}
    long rows()throws Exception{try(Connection c=DriverManager.getConnection(targetUrl);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM customers")){rs.next();return rs.getLong(1);}}
    Path config(String existing)throws Exception{
        Path file=directory.resolve("config.json");
        Files.writeString(file,"""
                {"source":{"jdbcUrl":"jdbc:postgresql://source/test","username":"user","passwordEnv":"SOURCE_PASSWORD","schema":"public"},
                 "target":{"jdbcUrl":"jdbc:postgresql://target/test","username":"user","passwordEnv":"TARGET_PASSWORD","schema":"public"},
                 "tables":["customers"],"existingTables":"%s"}
                """.formatted(existing));return file;
    }
    int run(String command,Path file){return cli.run(new String[]{command,"--config",file.toString(),"--output",directory.resolve("reports").toString()});}
    List<Path> reports(String file)throws Exception{try(var paths=Files.walk(directory.resolve("reports"))){return paths.filter(p->p.getFileName().toString().equals(file)).toList();}}
    @Test void helpAndInitNeedNoDatabaseAndNeverOverwriteConfig()throws Exception{
        assertEquals(0,cli.run(new String[]{"help"}));Path file=directory.resolve("example.json");
        assertEquals(0,cli.run(new String[]{"init","--output",file.toString()}));String initial=Files.readString(file);
        assertEquals(1,cli.run(new String[]{"init","--output",file.toString()}));assertEquals(initial,Files.readString(file));
    }
    @Test void planDoesNotWriteAndMigrateCreatesThenLoadsOnlyMatches()throws Exception{
        Path file=config("DML_ONLY");assertEquals(0,run("plan",file),messages.toString());
        assertThrows(SQLException.class,this::rows);
        assertEquals(0,run("migrate",file),messages.toString());assertEquals(1,rows());
        sql(sourceUrl,"DELETE FROM customers; INSERT INTO customers VALUES (2,'Bob')");
        assertEquals(0,run("migrate",file),messages.toString());assertEquals(2,rows());
        assertEquals(2,reports("execution.json").size());assertFalse(messages.toString().contains("test-only-secret"));
        try(var paths=Files.walk(directory.resolve("reports"))){assertTrue(paths.anyMatch(p->p.getFileName().toString().equals(".work")));}
    }
    @Test void mismatchBlocksAndDuplicateExecutionReturnsFailureReport()throws Exception{
        sql(targetUrl,"CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(5))");
        assertEquals(2,run("migrate",config("DML_ONLY")));assertEquals(0,rows());
        sql(targetUrl,"DROP TABLE customers; CREATE TABLE customers (id NUMERIC(10,0) PRIMARY KEY, name VARCHAR(50)); INSERT INTO customers VALUES (1,'Existing')");
        assertEquals(3,run("migrate",config("DML_ONLY")));assertEquals(1,rows());
        assertTrue(Files.readString(reports("execution.json").getFirst()).contains("FAILED"));
    }
    @Test void convertsFolderToOneFilePerTableAndDoesNotNeedPasswords()throws Exception{
        Path input=Files.createDirectory(directory.resolve("input"));
        Files.writeString(input.resolve("01.sql"),"CREATE TABLE customers (id NUMBER(10));");
        Files.writeString(input.resolve("02.sql"),"INSERT INTO customers VALUES (1);");
        Path file=directory.resolve("convert.json");Files.writeString(file,"{\"input\":\"input\",\"sourceDialect\":\"ORACLE\",\"targetDialect\":\"POSTGRESQL\",\"targetSchema\":\"public\"}");
        assertEquals(0,run("convert",file),messages.toString());
        boolean found=false;try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(reports("migration.zip").getFirst()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(entry.getName().endsWith("tables/customers.sql")){String text=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(text.contains("-- DDL:"));assertTrue(text.contains("-- DML:"));assertTrue(text.contains("INSERT INTO"));found=true;}}
        assertTrue(found);
    }
    @Test void invalidConfigDoesNotEchoSecretValues()throws Exception{
        Path file=directory.resolve("bad.json");Files.writeString(file,"{\"unknown\":\"do-not-echo-secret\"}");
        assertEquals(2,run("migrate",file));assertFalse(messages.toString().contains("do-not-echo-secret"));
        assertEquals(2,cli.run(new String[]{"migrate"}));
    }
}

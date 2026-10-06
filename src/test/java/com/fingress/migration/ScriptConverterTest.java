package com.fingress.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.fingress.migration.Model.*;

class ScriptConverterTest {
    ScriptConverter.Result convert(String sql){return new ScriptConverter(Dialect.ORACLE,Dialect.POSTGRESQL,"fg_solutions").convert(sql);}
    String all(ScriptConverter.Result result){return result.statements().stream().map(StatementReport::convertedSql).collect(java.util.stream.Collectors.joining(";\n"));}
    @Test void exactUserFileConvertsAllConstructsAndFindsThreeSourceErrors() throws Exception {
        String input;try(var stream=getClass().getResourceAsStream("/oracle-edge-cases.sql")){input=new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);}
        var result=convert(input);
        Files.writeString(Path.of("target/edge-case-report.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertEquals(63,result.statements().size());
        assertEquals(0,result.statements().stream().filter(s->s.status().equals("UNSUPPORTED")).count(),result.issues().toString());
        assertEquals(3,result.issues().size(),result.issues().toString());
        assertEquals(2,result.issues().stream().filter(s->s.contains("employee.dept_id")).count(),result.issues().toString());
        assertTrue(result.issues().stream().anyMatch(s->s.contains("employee.emp_name")&&s.contains("NOT NULL")));
        assertFalse(result.issues().stream().anyMatch(s->s.contains("Provide CREATE")));
        String sql=all(result);assertTrue(sql.contains("GENERATED ALWAYS AS IDENTITY"));assertTrue(sql.contains("decode('A1B2C3D4', 'hex')"));
        assertTrue(sql.contains("TIMESTAMP WITH TIME ZONE '2026-10-05 14:30:00 +05:30'"));
        assertTrue(sql.contains("விக்னேஷ்"));assertTrue(sql.indexOf("UPDATE ")<sql.indexOf("DELETE "));assertTrue(sql.indexOf("DROP TABLE")>sql.indexOf("MERGE INTO"));
        assertTrue(sql.contains("\"emp_name\" VARCHAR(200) NOT NULL")||sql.contains("ALTER COLUMN \"emp_name\" TYPE VARCHAR(200)"));
    }
    @Test void mapsOracleEmptyStringToNullAndReportsNotNullViolation(){
        var result=convert("CREATE TABLE t (id NUMBER, name VARCHAR2(10) NOT NULL); INSERT INTO t VALUES (1,'');");
        assertEquals("ERROR",result.statements().get(1).status());assertTrue(result.issues().getFirst().contains("NOT NULL"));
    }
    @Test void preservesAlwaysAndUnboundedIdentityMappingWarning(){
        var result=convert("CREATE TABLE t (id NUMBER GENERATED ALWAYS AS IDENTITY);");
        assertTrue(result.issues().isEmpty());assertTrue(all(result).contains("BIGINT GENERATED ALWAYS"));assertTrue(result.statements().getFirst().messages().stream().anyMatch(s->s.contains("64-bit")));
    }
    @Test void validatesUnknownColumnsInIndexesAndPredicates(){
        var result=convert("CREATE TABLE t (id NUMBER); CREATE INDEX idx ON t (missing); UPDATE t SET id=3 WHERE missing=1;");
        assertEquals(2,result.issues().size());assertTrue(result.issues().stream().allMatch(s->s.contains("t.missing")));
    }
    @Test void keepsLiteralSqlKeywordsUntouched(){
        var result=convert("CREATE TABLE t (id NUMBER, v VARCHAR2(100)); INSERT INTO t VALUES (1,'SYSDATE; SYSTIMESTAMP; DROP TABLE');");
        assertTrue(result.issues().isEmpty());assertTrue(all(result).contains("'SYSDATE; SYSTIMESTAMP; DROP TABLE'"));
    }
    @Test void sqlFirstUsesThreeValuedLogicAndOneMaterializedSource(){
        var result=convert("CREATE TABLE t (id NUMBER); INSERT FIRST WHEN v > 3 THEN INTO t(id) VALUES(1) ELSE INTO t(id) VALUES(2) SELECT NULL AS v FROM DUAL;");
        assertTrue(result.issues().isEmpty(),result.issues().toString());assertTrue(all(result).contains("IS NOT TRUE"));assertTrue(all(result).contains("AS MATERIALIZED"));
    }
    @Test void failedAlterDoesNotCorruptFollowingMetadata(){
        var result=convert("CREATE TABLE t (id NUMBER); ALTER TABLE t ADD (v VARCHAR2(10), id NUMBER); INSERT INTO t (v) VALUES ('x');");
        assertEquals(2,result.issues().size());assertTrue(result.issues().get(1).contains("t.v"));
    }
    @Test void firstExecutesEveryIntoInTheFirstMatchingWhenGroup(){
        var result=convert("CREATE TABLE t(id NUMBER); INSERT FIRST WHEN v > 0 THEN INTO t(id) VALUES(1) INTO t(id) VALUES(2) ELSE INTO t(id) VALUES(3) SELECT 1 AS v FROM DUAL;");
        assertTrue(result.issues().isEmpty(),result.issues().toString());
        String sql=result.statements().getLast().convertedSql();
        String second=sql.substring(sql.indexOf("\"__migration_insert_2\""),sql.indexOf("\"__migration_insert_3\""));
        assertFalse(second.contains("IS NOT TRUE"));assertTrue(sql.substring(sql.indexOf("\"__migration_insert_3\"")).contains("IS NOT TRUE"));
    }
    @Test void doesNotMergeMultipleSourceSchemasSilently(){
        var result=convert("CREATE TABLE a.t (id NUMBER); CREATE TABLE b.u (id NUMBER);");
        assertTrue(result.issues().stream().anyMatch(s->s.contains("Multiple source schemas")));
    }
    @Test void checkAndBinaryConstraintsAreActuallyEnforced() throws Exception {
        var result=convert("CREATE TABLE t (id NUMBER(5), amount NUMBER(5) CHECK(amount >= 0), data RAW(2));");
        assertTrue(result.issues().isEmpty(),result.issues().toString());
        try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:check_binary;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");var statement=connection.createStatement()){
            statement.execute("CREATE SCHEMA fg_solutions");statement.execute(all(result));
            assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO fg_solutions.t(id,amount) VALUES(1,-1)"));
            statement.execute("INSERT INTO fg_solutions.t(id,amount,data) VALUES(1,2,X'AABB')");
            assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO fg_solutions.t(id,amount,data) VALUES(2,2,X'AABBCC')"));
        }
    }
}

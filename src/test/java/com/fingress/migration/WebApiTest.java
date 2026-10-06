package com.fingress.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("migration.work-dir", () -> { try { return Files.createTempDirectory("migration-web-test-").toString(); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    @Test void servesUiAndCapabilitiesWithoutCredentials() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("Review every table"))).andExpect(header().exists("Content-Security-Policy"));
        mvc.perform(get("/api/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("$.executionPolicy").value("INSERT_ONLY"));
    }
    @Test void blocksCrossOriginAndMissingWriteHeader() throws Exception {
        mvc.perform(post("/api/plans").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/plans").header("X-Migration-Client", "migration-ui").header("Origin", "https://evil.example").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void convertsThroughHttpAndReportsUnsupportedSql() throws Exception {
        mvc.perform(post("/api/plans").header("X-Migration-Client", "migration-ui").contentType("application/json").content("""
                {"sourceDialect":"ORACLE","targetDialect":"POSTGRESQL","targetSchema":"public","sql":"CREATE TABLE customers (id NUMBER(10,0)); CREATE PACKAGE unsupported AS END"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.tables[0].table").value("customers")).andExpect(jsonPath("$.issues[0]").value(containsString("Statement 2")));
    }
    @Test void malformedPayloadDoesNotEchoSecrets() throws Exception {
        mvc.perform(post("/api/plans").header("X-Migration-Client", "migration-ui").contentType("application/json").content("{\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("secret"))));
    }
    @Test void orderedScriptsExposeCompleteStatementReports() throws Exception {
        mvc.perform(post("/api/plans").header("X-Migration-Client","migration-ui").contentType("application/json").content("""
                {"sourceDialect":"ORACLE","targetDialect":"POSTGRESQL","targetSchema":"public","sql":"CREATE TABLE t (id NUMBER(5)); ALTER TABLE t ADD name VARCHAR2(20); UPDATE t SET id=3; DROP TABLE t;"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderedScript").value(true)).andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.statements.length()").value(4)).andExpect(jsonPath("$.statements[1].convertedSql").value(containsString("ADD COLUMN")))
                .andExpect(jsonPath("$.statements[3].status").value("REVIEW"));
    }
    @Test void missingPostgresDatabaseReturnsSpecificValidationBeforeConnecting() throws Exception {
        mvc.perform(post("/api/schemas").header("X-Migration-Client","migration-ui").contentType("application/json").content("""
                {"dialect":"POSTGRESQL","host":"localhost","port":5432,"database":" ","username":"postgres","password":"test-only"}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("PostgreSQL database name is required")))
                .andExpect(content().string(not(containsString("test-only"))));
    }
    @Test void missingOracleServiceReturnsSpecificValidationBeforeConnecting() throws Exception {
        mvc.perform(post("/api/schemas").header("X-Migration-Client","migration-ui").contentType("application/json").content("""
                {"dialect":"ORACLE","host":"localhost","port":1521,"database":"","username":"app","password":"test-only"}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Oracle service name is required")));
    }
}

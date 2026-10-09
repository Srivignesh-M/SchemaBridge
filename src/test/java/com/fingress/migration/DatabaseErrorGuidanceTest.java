package com.fingress.migration;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseErrorGuidanceTest {
    @Test void neverLeaksTheOriginalDriverMessage() {
        SQLException secretLeak = new SQLException("password authentication failed for user \"admin\": correct-password-is-hunter2", "28P01", 0);
        String result = DatabaseErrorGuidance.describe(secretLeak);
        assertFalse(result.contains("hunter2"));
        assertFalse(result.contains("password authentication failed"));
        assertTrue(result.contains("28P01"));

        SQLException rowDataLeak = new SQLException("duplicate key value violates unique constraint \"items_email_key\" Detail: Key (email)=(someone@example.com) already exists.", "23505", 0);
        String rowResult = DatabaseErrorGuidance.describe(rowDataLeak);
        assertFalse(rowResult.contains("someone@example.com"));
        assertFalse(rowResult.contains("already exists"));
    }

    @Test void mapsKnownOracleCodesToSpecificGuidance() {
        assertTrue(DatabaseErrorGuidance.describe(new SQLException("x", "72000", 1466)).contains("snapshot is stale"));
        assertTrue(DatabaseErrorGuidance.describe(new SQLException("x", "61000", 1654)).contains("tablespace is full"));
        assertTrue(DatabaseErrorGuidance.describe(new SQLException("x", "01000", 1017)).contains("username or password"));
    }

    @Test void mapsKnownPostgresqlCodesToSpecificGuidance() {
        assertTrue(DatabaseErrorGuidance.describe(new SQLException("x", "23505", 0)).contains("unique constraint"));
        assertTrue(DatabaseErrorGuidance.describe(new SQLException("x", "53100", 0)).contains("disk is full"));
        assertTrue(DatabaseErrorGuidance.describe(new SQLException("x", "57014", 0)).contains("cancelled"));
    }

    @Test void unknownCodeFallsBackToGenericSafeMessage() {
        String result = DatabaseErrorGuidance.describe(new SQLException("some unmapped driver text", "HY000", 99999));
        assertFalse(result.contains("some unmapped driver text"));
        assertTrue(result.contains("99999"));
        assertTrue(result.contains("Check connection, permissions and database compatibility"));
    }

    @Test void nullSqlStateDoesNotCrashAndFallsBackToGeneric() {
        String result = DatabaseErrorGuidance.describe(new SQLException("driver gave no SQLState"));
        assertFalse(result.contains("driver gave no SQLState"));
        assertTrue(result.contains("Check connection, permissions and database compatibility"));
    }

    @Test void everyMessageIncludesTheRawIdentityForDebugging() {
        String result = DatabaseErrorGuidance.describe(new SQLException("x", "72000", 1466));
        assertTrue(result.contains("SQLState 72000"));
        assertTrue(result.contains("code 1466"));
    }
}

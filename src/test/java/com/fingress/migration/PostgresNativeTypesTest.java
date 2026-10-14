package com.fingress.migration;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.fingress.migration.Model.*;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL-native column types and defaults: carried over to PostgreSQL, still blocked for Oracle. */
class PostgresNativeTypesTest {
    static Type catalog(String name, int size, int scale) { return SqlParser.catalogType(name, size, scale, Dialect.POSTGRESQL); }

    @Test void mapsPostgresCatalogTypesToTheirOwnSql() {
        assertEquals("REAL", catalog("float4", 8, 8).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        assertEquals("DOUBLE PRECISION", catalog("float8", 17, 17).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        assertEquals("UUID", catalog("uuid", 2147483647, 0).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        assertEquals("JSONB", catalog("jsonb", 2147483647, 0).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        assertEquals("TIME(6)", catalog("time", 15, 6).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        assertEquals("TIME(0)", catalog("time", 8, 0).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
    }

    @Test void serialPseudoTypesMapToTheirIntegerTypes() {
        assertEquals("SMALLINT", catalog("smallserial", 5, 0).kind());
        assertEquals("INTEGER", catalog("serial", 10, 0).kind());
        assertEquals("BIGINT", catalog("bigserial", 19, 0).kind());
        assertEquals("BIGINT", catalog("bigserial", 19, 0).identityTarget(Dialect.POSTGRESQL).kind());
    }

    @Test void varcharWithoutLengthStaysUnboundedOnPostgres() {
        for (int reported : List.of(Integer.MAX_VALUE, 0)) {
            Type type = catalog("varchar", reported, 0);
            assertNull(type.precision());
            assertEquals("VARCHAR", type.target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        }
        assertEquals("VARCHAR(40)", catalog("varchar", 40, 0).target(Dialect.POSTGRESQL).sql(Dialect.POSTGRESQL));
        assertThrows(IllegalArgumentException.class, () -> catalog("varchar", 20_000_000, 0).target(Dialect.POSTGRESQL));
    }

    @Test void oracleTargetsStillRequireAnExplicitMapping() {
        for (Type type : List.of(catalog("float4", 8, 8), catalog("float8", 17, 17), catalog("uuid", 0, 0), catalog("jsonb", 0, 0), catalog("time", 15, 6), catalog("varchar", Integer.MAX_VALUE, 0))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> type.target(Dialect.ORACLE), type.kind());
            assertTrue(failure.getMessage().contains("explicit Oracle mapping"), failure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> type.sql(Dialect.ORACLE), type.kind());
        }
    }

    @Test void identicalNativeTypesCompareAsMatching() {
        Column ratio = new Column(Name.catalog("ratio", Dialect.POSTGRESQL), catalog("float8", 17, 17), true, null, false);
        Column note = new Column(Name.catalog("note", Dialect.POSTGRESQL), catalog("varchar", Integer.MAX_VALUE, 0), true, null, false);
        Table table = new Table(Name.catalog("items", Dialect.POSTGRESQL), List.of(ratio, note), List.of(), List.of());
        assertTrue(Compatibility.differences(table, table, Dialect.POSTGRESQL).isEmpty());
    }

    @Test void rewritesPostgresDefaultsToPortableForms() {
        assertEquals("CURRENT_TIMESTAMP", DatabaseGateway.defaultValue("now()", Dialect.POSTGRESQL).sql());
        assertEquals("DATE '1900-01-01'", DatabaseGateway.defaultValue("'1900-01-01'::date", Dialect.POSTGRESQL).sql());
        assertEquals("TIMESTAMP '2000-01-01 00:00:00'", DatabaseGateway.defaultValue("'2000-01-01 00:00:00'::timestamp without time zone", Dialect.POSTGRESQL).sql());
        assertEquals("'GENERAL'", DatabaseGateway.defaultValue("'GENERAL'::character varying", Dialect.POSTGRESQL).sql());
        // A sequence the table does not own would not exist in the target.
        assertThrows(IllegalArgumentException.class, () -> DatabaseGateway.defaultValue("(nextval('h2h_file_id_seq'::regclass))::character varying", Dialect.POSTGRESQL));
    }

    @Test void tablesWithLargeValuesStartWithSmallFetches() {
        Column id = new Column(Name.catalog("id", Dialect.POSTGRESQL), new Type("BIGINT", null, null), false, null, false);
        Table narrow = new Table(Name.catalog("t", Dialect.POSTGRESQL), List.of(id), List.of(), List.of());
        assertEquals(1000, DatabaseGateway.fetchSize(narrow, MigrationOptions.defaults()));
        for (Type large : List.of(new Type("BINARY", null, null), new Type("TEXT", null, null), new Type("JSONB", null, null), new Type("VARCHAR", null, null))) {
            Table wide = new Table(Name.catalog("t", Dialect.POSTGRESQL), List.of(id, new Column(Name.catalog("v", Dialect.POSTGRESQL), large, true, null, false)), List.of(), List.of());
            assertEquals(DatabaseGateway.LARGE_VALUE_FETCH, DatabaseGateway.fetchSize(wide, MigrationOptions.defaults()), large.kind());
        }
        Table bounded = new Table(Name.catalog("t", Dialect.POSTGRESQL), List.of(id, new Column(Name.catalog("v", Dialect.POSTGRESQL), new Type("VARCHAR", 40, null), true, null, false)), List.of(), List.of());
        assertEquals(1000, DatabaseGateway.fetchSize(bounded, MigrationOptions.defaults()));
    }

    @Test void stagedFloatingPointAndUuidValuesCompareCanonically() {
        assertEquals(DataFingerprint.canonical("DOUBLE", "1e-05"), DataFingerprint.canonical("DOUBLE", Double.toString(1e-05)));
        assertEquals("NaN", DataFingerprint.canonical("DOUBLE", "NaN"));
        assertEquals("-Infinity", DataFingerprint.canonical("REAL", "-Infinity"));
        assertEquals(DataFingerprint.canonical("REAL", "0.1"), DataFingerprint.canonical("REAL", Float.toString(0.1f)));
        assertEquals(DataFingerprint.canonical("UUID", "A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11"), DataFingerprint.canonical("UUID", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"));
    }
}

package com.fingress.migration;

import java.util.*;

public final class Model {
    private Model() {}
    public enum Dialect {
        ORACLE, POSTGRESQL;
        public String fold(String name) { return this == ORACLE ? name.toUpperCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT); }
    }
    public record Name(String value, boolean quoted) {
        public Name { if (value == null || value.isBlank() || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid identifier"); }
        public String in(Dialect dialect) { return quoted ? value : dialect.fold(value); }
        public String sql(Dialect dialect) { return quote(in(dialect)); }
        public static Name catalog(String value, Dialect dialect) { return new Name(value, !value.equals(dialect.fold(value))); }
    }
    public static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    public record Type(String kind, Integer precision, Integer scale) {
        public String sql(Dialect dialect) {
            return switch (kind) {
                case "VARCHAR" -> (dialect == Dialect.ORACLE ? "VARCHAR2" : "VARCHAR") + "(" + precision + (dialect == Dialect.ORACLE ? " CHAR" : "") + ")";
                case "CHAR" -> "CHAR(" + precision + (dialect == Dialect.ORACLE ? " CHAR" : "") + ")";
                case "DECIMAL" -> (dialect == Dialect.ORACLE ? "NUMBER" : "NUMERIC") + (precision == null ? "" : "(" + precision + "," + (scale == null ? 0 : scale) + ")");
                case "SMALLINT" -> dialect == Dialect.ORACLE ? "NUMBER(5,0)" : "SMALLINT";
                case "INTEGER" -> dialect == Dialect.ORACLE ? "NUMBER(10,0)" : "INTEGER";
                case "BIGINT" -> dialect == Dialect.ORACLE ? "NUMBER(19,0)" : "BIGINT";
                case "TEXT" -> dialect == Dialect.ORACLE ? "CLOB" : "TEXT";
                case "BINARY" -> dialect == Dialect.ORACLE ? (precision == null ? "BLOB" : "RAW(" + precision + ")") : "BYTEA";
                case "BOOLEAN" -> { if(dialect==Dialect.ORACLE)throw new IllegalArgumentException("Boolean columns require an explicit Oracle mapping"); yield "BOOLEAN"; }
                case "DATE" -> "DATE";
                case "TIMESTAMP" -> "TIMESTAMP(" + (precision == null ? 6 : precision) + ")";
                case "TIMESTAMPTZ" -> "TIMESTAMP(" + (precision == null ? 6 : precision) + ") WITH TIME ZONE";
                default -> throw new IllegalArgumentException("Unsupported data type: " + kind);
            };
        }
        public Type target(Dialect dialect) {
            if(kind.equals("BOOLEAN") && dialect==Dialect.ORACLE)throw new IllegalArgumentException("Boolean columns require an explicit Oracle mapping");
            if (Set.of("TIMESTAMP", "TIMESTAMPTZ").contains(kind) && precision != null && (precision < 0 || precision > 6)) throw new IllegalArgumentException("Timestamp precision requires an explicit mapping to the supported 0-6 range");
            if (Set.of("VARCHAR", "CHAR").contains(kind) && (precision == null || precision < 1 || precision > 10_485_760)) throw new IllegalArgumentException("Unbounded or oversized character types require an explicit mapping");
            if (dialect == Dialect.POSTGRESQL && kind.equals("DECIMAL") && precision != null && (precision > 1000 || precision < 1 || scale != null && Math.abs((long) scale) > 1000)) throw new IllegalArgumentException("Numeric precision/scale exceeds PostgreSQL limits");
            if (dialect == Dialect.ORACLE && Set.of("SMALLINT", "INTEGER", "BIGINT").contains(kind))
                return new Type("DECIMAL", switch (kind) { case "SMALLINT" -> 5; case "INTEGER" -> 10; default -> 19; }, 0);
            if (dialect == Dialect.ORACLE && kind.equals("DECIMAL") && (precision == null || precision > 38 || (scale != null && (scale < -84 || scale > 127))))
                throw new IllegalArgumentException("PostgreSQL numeric range requires an explicit Oracle precision/scale mapping");
            if (dialect == Dialect.ORACLE && Set.of("VARCHAR", "CHAR").contains(kind) && precision > (kind.equals("CHAR") ? 2000 : 4000))
                throw new IllegalArgumentException("Character length exceeds the conservative Oracle SQL limit; choose a LOB mapping");
            return this;
        }
        public Type identityTarget(Dialect dialect) {
            Type result = target(dialect);
            if (dialect == Dialect.POSTGRESQL && kind.equals("DECIMAL")) {
                if (precision == null) return new Type("BIGINT", null, null);
                if (precision == null || precision > 18 || scale == null || scale != 0) throw new IllegalArgumentException("Numeric identity requires an explicit PostgreSQL integer range mapping");
                return new Type(precision <= 4 ? "SMALLINT" : precision <= 9 ? "INTEGER" : "BIGINT", null, null);
            }
            if (!Set.of("DECIMAL", "INTEGER", "BIGINT", "SMALLINT").contains(result.kind())) throw new IllegalArgumentException("Identity requires an integer-compatible type");
            return result;
        }
    }
    public record Value(String sql) {}
    public record Column(Name name, Type type, boolean nullable, Value defaultValue, boolean generated, boolean always) {
        public Column(Name name, Type type, boolean nullable, Value defaultValue, boolean generated) { this(name, type, nullable, defaultValue, generated, false); }
    }
    public record Key(String kind, List<Name> columns, Name reference, List<Name> referenceColumns, Name name, String expression) {
        public Key(String kind, List<Name> columns, Name reference, List<Name> referenceColumns) { this(kind, columns, reference, referenceColumns, null, null); }
    }
    public record Table(Name name, List<Column> columns, List<Key> keys, List<String> warnings) {}
    public record Insert(Name table, List<Name> columns, List<List<Value>> rows) {}
    public record Parsed(List<Table> tables, List<Insert> inserts, List<String> issues) {}
    public record ConnectionSpec(Dialect dialect, String host, int port, String database, String username, String password, String jdbcUrl) {
        public ConnectionSpec(Dialect dialect, String host, int port, String database, String username, String password) { this(dialect, host, port, database, username, password, null); }
        @Override public String toString() { return "ConnectionSpec[credentials redacted]"; }
    }
    public record PlanRequest(Dialect sourceDialect, Dialect targetDialect, String targetSchema,
                              String sql, ConnectionSpec source, String sourceSchema, List<String> tables,
                              boolean includeData, ConnectionSpec target, MigrationOptions options,
                              Map<String, TableSelection> selections, boolean metadataOnly) {
        public PlanRequest(Dialect s, Dialect t, String schema, String sql, ConnectionSpec source, String ss, List<String> tables, boolean data, ConnectionSpec target) {
            this(s,t,schema,sql,source,ss,tables,data,target,null,Map.of(),false);
        }
    }
    public record RowFilter(String column, String operator, String value) {}
    public record TableSelection(List<String> columns, Map<String,String> rename, List<RowFilter> filters, String tableName, Integer rowLimit) {
        public TableSelection { if(rowLimit!=null && rowLimit<1)throw new IllegalArgumentException("Table row limit must be positive"); }
        public TableSelection(List<String> columns, Map<String,String> rename, List<RowFilter> filters, String tableName) { this(columns,rename,filters,tableName,null); }
        public TableSelection(List<String> columns, Map<String,String> rename, List<RowFilter> filters) { this(columns, rename, filters, null); }
    }
    public enum Status { NEW, MATCH, MISMATCH, UNCHECKED }
    public enum Action { CREATE_AND_LOAD, DML_ONLY, SKIP }
    /** One structured column comparison entry (improvement-plan P3). kind is a short machine-readable
     * category (MISSING_TARGET_COLUMN, EXTRA_TARGET_COLUMN, TYPE_MISMATCH, NULLABILITY_MISMATCH,
     * IDENTITY_MISMATCH, DEFAULT_MISMATCH, KEY_OR_INDEX_MISMATCH); column/expected/actual may be null
     * when the mismatch isn't scoped to one column (e.g. KEY_OR_INDEX_MISMATCH). correction is always
     * present and human-readable. */
    public record ColumnDifference(String column, String kind, String expected, String actual, String correction) {}
    public record TableReport(String table, Status status, List<String> differences, List<String> warnings, long rows, List<Action> allowedActions, Long estimatedRows,
                              List<ColumnDifference> columnComparison, List<String> dependsOn) {}
    public record StatementReport(int number, String kind, String status, String convertedSql, List<String> messages) {}
    public record PlanView(String id, Dialect sourceDialect, Dialect targetDialect, String targetSchema,
                           List<TableReport> tables, List<String> issues, List<String> warnings, boolean targetChecked, String expiresAt,
                           boolean orderedScript, List<StatementReport> statements, boolean prepared, MigrationOptions options) {}
    public record ExecuteRequest(ConnectionSpec target, Map<String, Action> actions) {}
    /** Per-table validation detail (improvement-plan P4). method: FINGERPRINT (typed data: full-table
     * row count + order-independent SHA-256 content aggregate), ROW_COUNT (SQL-input: count only, not
     * fingerprinted), or NONE (validateData=false). rowsChecked is the full target table's row count
     * after load — there is no sampling or key-scoped mode yet; that is future work pending a policy
     * decision, not implemented here. outcome is always PASSED: a failed check throws and rolls back
     * the whole table transaction, so no ValidationReport is attached to a failed/not-run table. */
    public record ValidationReport(String method, long rowsChecked, double durationSeconds, String outcome, String skippedReason) {}
    public record TableResult(String table, Action action, String status, long rows, String message, ValidationReport validation) {}
    /** Storage dashboard (improvement-plan P6). reclaimable is true when this plan is already past
     * expiresAt, inactive and unexecuted — exactly what the existing automatic cleanup() would remove
     * on the next plan creation. This is a read-only preview; nothing is deleted by viewing it.
     * orphanedDirectories are work-directory entries with no matching tracked plan: per README, files
     * left behind by a killed process are not automatically recovered or removed. */
    public record PlanStorage(String id, String state, long bytes, String expiresAt, boolean reclaimable) {}
    public record StorageView(long stagedBytes, long freeDiskBytes, long reclaimableBytes, List<PlanStorage> plans, List<String> orphanedDirectories) {}
    public record Progress(String phase, String table, long rowsRead, long rowsSent, long rowsCommitted, long bytes,
                           long totalRows, long elapsedSeconds, double rowsPerSecond, Long remainingSeconds, boolean cancelRequested) {}
    public record RevertTable(String table, String action, long rows, String status) {}
    public record RevertView(String state, String message, List<RevertTable> tables, Progress progress) {}
    public record RevertPreview(String token, String jobId, String target, List<RevertTable> tables, String expiresAt) {}
    public record RevertRequest(ConnectionSpec target, String token) {}
    public record JobView(String id, String state, List<TableResult> tables, String message, String planId, Progress progress, boolean resumable, MigrationOptions options, RevertView revert) {}
    public record PreparationView(String id, String state, String message, Progress progress, PlanView plan) {}
    public static String qualified(String schema, Name table, Dialect target) { return quote(schema) + "." + table.sql(target); }
}

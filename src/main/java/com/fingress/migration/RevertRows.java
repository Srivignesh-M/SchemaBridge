package com.fingress.migration;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.fingress.migration.Model.*;

/** Guarded row removal. The caller owns transaction, locks and durable audit state. */
final class RevertRows {
    private RevertRows() {}
    static Set<String> indexes(Table table,Dialect dialect){
        Set<String> result=new HashSet<>();table.keys().stream().filter(k->k.kind().equals("INDEX")).forEach(k->result.add(SqlWriter.names(k.columns(),dialect)));return result;
    }

    static Table keyTable(Table table, Dialect dialect) {
        Key primary = table.keys().stream().filter(k -> k.kind().equals("PRIMARY KEY")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Revert needs a primary key on existing table " + table.name().in(dialect)));
        List<Column> columns = primary.columns().stream().map(n -> table.columns().stream()
                .filter(c -> c.name().in(dialect).equals(n.in(dialect))).findFirst().orElseThrow()).toList();
        if (columns.stream().anyMatch(c -> c.nullable() || Set.of("BINARY", "TEXT").contains(c.type().kind())))
            throw new IllegalArgumentException("Revert requires non-null scalar primary keys on " + table.name().in(dialect));
        return new Table(table.name(), columns, List.of(), List.of());
    }

    static void lock(Connection c, String schema, List<Table> tables, Dialect dialect, MigrationOptions options, TransferProgress progress) throws SQLException {
        // Stable order avoids opposite lock acquisition between two jobs.
        for (Table table : tables.stream().sorted(Comparator.comparing(t -> t.name().in(dialect))).toList()) {
            progress.check();
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(options.queryTimeoutSeconds()); progress.statement = s;
                String qualified = qualified(schema, table.name(), dialect);
                if (c.getMetaData().getDatabaseProductName().equals("H2")) {
                    // H2 is only a test fixture; live tests exercise engine table locks.
                    try (ResultSet r = s.executeQuery("SELECT * FROM " + qualified + " FOR UPDATE")) { while (r.next()) progress.check(); }
                } else s.execute("LOCK TABLE " + qualified + (dialect == Dialect.ORACLE ? " IN EXCLUSIVE MODE" : " IN ACCESS EXCLUSIVE MODE"));
            } finally { progress.statement = null; }
        }
    }

    static void dependencies(Connection c, String schema, List<Table> tables, Set<String> dropping, Dialect dialect) throws SQLException {
        Set<String> participating = new HashSet<>(); tables.forEach(t -> participating.add(t.name().in(dialect)));
        for (Table table : tables) {
            String name = table.name().in(dialect);
            boolean oracle = dialect == Dialect.ORACLE && !c.getMetaData().getDatabaseProductName().equals("H2");
            if (oracle) {
                // ALL_* and some JDBC metadata can hide children owned by other users.
                // Fail closed without dictionary visibility: an invisible CASCADE can
                // otherwise delete unrelated rows even when our DELETE is narrowly keyed.
                try (PreparedStatement s = c.prepareStatement("SELECT f.owner, f.table_name, f.delete_rule FROM dba_constraints f JOIN dba_constraints p ON p.owner=f.r_owner AND p.constraint_name=f.r_constraint_name WHERE f.constraint_type='R' AND p.owner=? AND p.table_name=?")) {
                    s.setQueryTimeout(30);s.setString(1,schema);s.setString(2,name);
                    try (ResultSet r=s.executeQuery()) { while(r.next()) {
                        if(!schema.equals(r.getString(1))||!participating.contains(r.getString(2)))throw new IllegalArgumentException("Revert blocked: another table references "+name+". Review its dependent data first.");
                        if(!"NO ACTION".equals(r.getString(3)))throw new IllegalArgumentException("Revert blocked by a cascading or unsupported foreign key on "+name);
                    }}
                } catch(SQLException e) { if(e.getErrorCode()==942||e.getErrorCode()==1031)throw new IllegalArgumentException("Oracle revert needs read access to SYS.DBA_CONSTRAINTS, SYS.DBA_DEPENDENCIES and SYS.DBA_SYNONYMS to check dependencies across schemas");throw e; }
            } else try (ResultSet r = c.getMetaData().getExportedKeys(c.getCatalog(), schema, name)) {
                while (r.next()) {
                    if (!schema.equals(r.getString("FKTABLE_SCHEM")) || !participating.contains(r.getString("FKTABLE_NAME")))
                        throw new IllegalArgumentException("Revert blocked: another table references " + name + ". Review its dependent data first.");
                    short rule = r.getShort("DELETE_RULE");
                    if (r.wasNull() || rule != DatabaseMetaData.importedKeyNoAction && rule != DatabaseMetaData.importedKeyRestrict)
                        throw new IllegalArgumentException("Revert blocked by a cascading or unsupported foreign key on " + name);
                }
            }
            if (oracle && dropping.contains(name)) {
                try (PreparedStatement s = c.prepareStatement("SELECT (SELECT COUNT(*) FROM dba_dependencies WHERE referenced_owner=? AND referenced_name=? AND referenced_type='TABLE') + (SELECT COUNT(*) FROM dba_synonyms WHERE table_owner=? AND table_name=?) FROM dual")) {
                    s.setQueryTimeout(30); s.setString(1, schema); s.setString(2, name);
                    s.setString(3,schema);s.setString(4,name);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next() && r.getLong(1) > 0) throw new IllegalArgumentException("Revert blocked: database objects depend on newly created table " + name);
                    }
                } catch(SQLException e) { if(e.getErrorCode()==942||e.getErrorCode()==1031)throw new IllegalArgumentException("Oracle revert needs read access to SYS.DBA_CONSTRAINTS, SYS.DBA_DEPENDENCIES and SYS.DBA_SYNONYMS to check dependencies across schemas");throw e; }
            }
            if(dialect==Dialect.POSTGRESQL&&dropping.contains(name)&&c.getMetaData().getDatabaseProductName().equals("PostgreSQL")){
                try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM pg_depend d JOIN pg_rewrite r ON r.oid=d.objid AND d.classid='pg_rewrite'::regclass JOIN pg_class t ON t.oid=d.refobjid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE d.refclassid='pg_class'::regclass AND n.nspname=? AND t.relname=? AND r.ev_class<>t.oid")){
                    s.setQueryTimeout(30);s.setString(1,schema);s.setString(2,name);
                    try(ResultSet r=s.executeQuery()){if(r.next()&&r.getLong(1)>0)throw new IllegalArgumentException("Revert blocked: a view depends on newly created table "+name);}
                }
            }
        }
    }

    static List<Table> deletionOrder(List<Table> tables, Dialect dialect) {
        List<Table> pending = new ArrayList<>(tables), result = new ArrayList<>();
        while (!pending.isEmpty()) {
            Table leaf = pending.stream().filter(parent -> pending.stream().noneMatch(child -> child.keys().stream()
                    .anyMatch(k -> k.reference() != null && k.reference().in(dialect).equals(parent.name().in(dialect))))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Revert cannot automatically remove cyclic or self-referencing tables"));
            result.add(leaf); pending.remove(leaf);
        }
        return result;
    }

    static long existing(Connection c, Table table, String schema, Dialect dialect, Path staged,
                         MigrationOptions options, TransferProgress progress, boolean delete) throws Exception {
        Table key = keyTable(table, dialect);
        String where = String.join(" AND ", key.columns().stream().map(column -> column.name().sql(dialect) + "=?").toList());
        String target = qualified(schema, table.name(), dialect);
        String select = "SELECT " + SqlWriter.names(table.columns().stream().map(Column::name).toList(), dialect) + " FROM " + target + " WHERE " + where;
        long rows = 0;
        try (PreparedStatement query = c.prepareStatement(select); PreparedStatement remove = c.prepareStatement("DELETE FROM " + target + " WHERE " + where);
             BufferedReader input = Files.newBufferedReader(staged)) {
            query.setQueryTimeout(options.queryTimeoutSeconds()); remove.setQueryTimeout(options.queryTimeoutSeconds());
            int[] keyIndexes = key.columns().stream().mapToInt(column -> table.columns().indexOf(column)).toArray();
            String line;
            while ((line = input.readLine()) != null) {
                progress.check(); RowStore.Cell[] row = RowStore.JSON.readValue(line, RowStore.Cell[].class);
                if (row.length != table.columns().size()) throw new IOException("Revert row shape differs from the saved table");
                RowStore.Cell[] keys = new RowStore.Cell[keyIndexes.length];
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = row[keyIndexes[i]];
                    if (keys[i].file() != null || keys[i].value() == null) throw new IOException("Invalid revert primary key");
                }
                RowStore.bind(query, keys, key, dialect, staged.getParent(), new ArrayList<>());
                progress.statement = query;
                try (ResultSet r = query.executeQuery()) {
                    if (!r.next() || !matches(r, row, table, staged.getParent()) || r.next())
                        throw new IllegalArgumentException("Revert stopped: transferred rows are missing or changed in " + table.name().in(dialect));
                }
                if (delete) {
                    RowStore.bind(remove, keys, key, dialect, staged.getParent(), new ArrayList<>()); progress.statement = remove;
                    if (remove.executeUpdate() != 1) throw new IllegalArgumentException("Revert row count changed in " + table.name().in(dialect));
                    progress.rowsSent++;
                }
                rows++; progress.rowsRead++;
            }
        } finally { progress.statement = null; }
        return rows;
    }

    private static boolean matches(ResultSet r, RowStore.Cell[] cells, Table table, Path directory) throws Exception {
        for (int i = 0; i < cells.length; i++) {
            RowStore.Cell cell = cells[i]; int index = i + 1;
            boolean expectedNull = cell.value() == null && cell.file() == null;
            String kind = table.columns().get(i).type().kind();
            if (kind.equals("TEXT")) {
                Reader actual = r.getCharacterStream(index);
                if (actual == null) { if (!expectedNull) return false; continue; }
                try (actual) {
                    if (expectedNull) return false;
                    try (Reader expected = cell.file() == null ? new StringReader(cell.value()) : Files.newBufferedReader(RowStore.sidecar(directory, cell.file()))) {
                        Reader bufferedActual = new BufferedReader(actual), bufferedExpected = new BufferedReader(expected);
                        int a; do { a = bufferedActual.read(); if (a != bufferedExpected.read()) return false; } while (a != -1);
                    }
                }
            } else if (kind.equals("BINARY")) {
                InputStream actual = r.getBinaryStream(index);
                if (actual == null) { if (!expectedNull) return false; continue; }
                try (actual) {
                    if (expectedNull) return false;
                    try (InputStream expected = cell.file() == null ? new ByteArrayInputStream(Base64.getDecoder().decode(cell.value())) : Files.newInputStream(RowStore.sidecar(directory, cell.file()))) {
                        byte[] a; do { a = actual.readNBytes(65536); if (!Arrays.equals(a, expected.readNBytes(65536))) return false; } while (a.length != 0);
                    }
                }
            } else {
                if (r.getObject(index) == null) { if (!expectedNull) return false; continue; }
                if (expectedNull) return false;
                String actual = switch (kind) {
                    case "DECIMAL", "INTEGER", "BIGINT", "SMALLINT" -> r.getBigDecimal(index).toPlainString();
                    case "TIMESTAMP" -> r.getTimestamp(index).toLocalDateTime().toString();
                    case "DATE" -> r.getDate(index).toLocalDate().toString();
                    case "TIMESTAMPTZ" -> r.getObject(index, OffsetDateTime.class).toString();
                    default -> r.getString(index);
                };
                if (!DataFingerprint.canonical(kind, actual).equals(DataFingerprint.canonical(kind, cell.value()))) return false;
            }
        }
        return true;
    }
}

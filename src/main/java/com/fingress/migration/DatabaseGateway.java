package com.fingress.migration;

import org.springframework.stereotype.Component;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import static com.fingress.migration.Model.*;

@Component
public class DatabaseGateway {
    private static final ThreadLocal<MigrationOptions> OPTIONS = new ThreadLocal<>();
    static void configure(MigrationOptions options) { if(options==null)OPTIONS.remove();else OPTIONS.set(options); }
    private static final ThreadLocal<Boolean> BACKGROUND = new ThreadLocal<>();
    static void configureBackground(boolean background) { if(background)BACKGROUND.set(true);else BACKGROUND.remove(); }
    private static final ConnectionBudget CONNECTIONS = new ConnectionBudget(4,3);
    public Connection connect(ConnectionSpec spec) throws SQLException {
        if(spec!=null && spec.dialect()!=null && (spec.database()==null || spec.database().isBlank()))
            throw new IllegalArgumentException(spec.dialect()==Dialect.POSTGRESQL
                    ? "PostgreSQL database name is required, for example postgres or your application database. Selecting PostgreSQL only chooses the engine."
                    : "Oracle service name is required, for example XEPDB1. Select the schema after connecting.");
        if (spec == null || spec.dialect() == null || spec.host() == null || !spec.host().matches("[a-zA-Z0-9.-]+") || spec.port() < 1 || spec.port() > 65535
                || spec.database() == null || !spec.database().matches("[a-zA-Z0-9_.-]+") || spec.username() == null || spec.username().isBlank() || spec.password() == null)
            throw new IllegalArgumentException("Provide a valid database type, hostname, port, database/service, username and password");
        Properties properties = new Properties(); properties.setProperty("user", spec.username()); properties.setProperty("password", spec.password());
        properties.setProperty("ApplicationName", "fg-sql-migration");
        properties.setProperty("connectTimeout", "10"); properties.setProperty("socketTimeout", "120");
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000"); properties.setProperty("oracle.jdbc.ReadTimeout", "120000");
        MigrationOptions options=OPTIONS.get();
        if(options!=null){properties.setProperty("socketTimeout",options.readTimeoutSeconds().toString());properties.setProperty("oracle.jdbc.ReadTimeout",Long.toString(options.readTimeoutSeconds()*1000L));}
        String url = spec.dialect() == Dialect.POSTGRESQL ? "jdbc:postgresql://" + spec.host() + ":" + spec.port() + "/" + spec.database()
                : "jdbc:oracle:thin:@//" + spec.host() + ":" + spec.port() + "/" + spec.database();
        if (spec.jdbcUrl() != null && !spec.jdbcUrl().isBlank()) {
            JdbcAddress address = JdbcAddress.parse(spec.jdbcUrl());
            if (address.dialect() != spec.dialect() || !address.host().equals(spec.host()) || address.port() != spec.port() || !address.database().equals(spec.database()))
                throw new IllegalArgumentException("Saved JDBC address differs from connection fields; select the datasource again or use manual entry");
            url = spec.jdbcUrl();
        }
        String jdbcUrl = url;
        ConnectionBudget.Open open=() -> DriverManager.getConnection(jdbcUrl, properties);
        return Boolean.TRUE.equals(BACKGROUND.get())?CONNECTIONS.openBackground(open):CONNECTIONS.open(open);
    }
    public List<String> schemas(ConnectionSpec spec) throws SQLException {
        try (Connection c = connect(spec); ResultSet rs = c.getMetaData().getSchemas()) {
            List<String> result = new ArrayList<>(); while (rs.next()) result.add(rs.getString("TABLE_SCHEM")); return result.stream().distinct().sorted().toList();
        }
    }
    public List<String> tables(ConnectionSpec spec, String schema) throws SQLException {
        try (Connection c = connect(spec)) { return tables(c, schema); }
    }
    public List<String> tables(Connection c, String schema) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), pattern(c, schema), "%", new String[]{"TABLE"})) {
            while (rs.next()) if (schema.equals(rs.getString("TABLE_SCHEM"))) result.add(rs.getString("TABLE_NAME"));
        }
        return result.stream().sorted().toList();
    }
    public String objectIdentity(Connection connection,String schema,String table,Dialect dialect)throws SQLException{
        if(connection.getMetaData().getDatabaseProductName().equals("H2"))return "H2-test-fixture";
        String query=dialect==Dialect.POSTGRESQL
                ?"SELECT c.oid::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind='r'"
                :"SELECT TO_CHAR(object_id) FROM all_objects WHERE owner=? AND object_name=? AND object_type='TABLE'";
        try(PreparedStatement statement=connection.prepareStatement(query)){
            statement.setQueryTimeout(30);statement.setString(1,schema);statement.setString(2,table);
            try(ResultSet result=statement.executeQuery()){if(!result.next())throw new IllegalArgumentException("Cannot verify target table identity: "+table);return result.getString(1);}
        }
    }
    public Long estimateRows(Connection connection,String schema,String table,Dialect dialect) {
        String query=dialect==Dialect.POSTGRESQL
                ?"SELECT c.reltuples::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p')"
                :dialect==Dialect.ORACLE?"SELECT num_rows FROM all_tables WHERE owner=? AND table_name=?":null;
        if(query==null)return null;
        try(PreparedStatement statement=connection.prepareStatement(query)){
            statement.setString(1,schema);statement.setString(2,table);
            try(ResultSet result=statement.executeQuery()){
                if(!result.next())return null;long rows=result.getLong(1);return result.wasNull()||rows<0?null:rows;
            }
        }catch(SQLException ignored){return null;}
    }
    public void requireSchema(Connection c, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) throw new IllegalArgumentException("Select a target schema");
        try (ResultSet rs = c.getMetaData().getSchemas()) {
            while (rs.next()) if (schema.equals(rs.getString("TABLE_SCHEM"))) return;
        }
        throw new IllegalArgumentException("Selected schema does not exist or is not accessible");
    }
    public Table inspect(Connection c, String schema, String name, Dialect dialect) throws SQLException {
        if (!tables(c, schema).contains(name)) {
            try (ResultSet objects = c.getMetaData().getTables(c.getCatalog(), pattern(c, schema), pattern(c, name), null)) {
                while (objects.next()) if (schema.equals(objects.getString("TABLE_SCHEM")) && name.equals(objects.getString("TABLE_NAME")))
                    throw new IllegalArgumentException("Name " + name + " is already used by a non-table object");
            }
            return null;
        }
        rejectAdvancedObjects(c, schema, name, dialect);
        DatabaseMetaData metadata = c.getMetaData(); List<Column> columns = new ArrayList<>(); List<Key> keys = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(c.getCatalog(), pattern(c, schema), pattern(c, name), "%")) {
            while (rs.next()) {
                if (!name.equals(rs.getString("TABLE_NAME")) || !schema.equals(rs.getString("TABLE_SCHEM"))) continue;
                String columnName = rs.getString("COLUMN_NAME");
                boolean generated = "YES".equals(rs.getString("IS_AUTOINCREMENT"));
                if (generated) checkIdentity(c, schema, name, columnName, dialect);
                if ("YES".equals(rs.getString("IS_GENERATEDCOLUMN")) && !generated) throw new IllegalArgumentException("Computed columns require manual mapping: " + columnName);
                Type type = SqlParser.catalogType(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"), dialect);
                String rawDefault = generated ? null : rs.getString("COLUMN_DEF");
                Value defaultValue = defaultValue(rawDefault, dialect);
                columns.add(new Column(Name.catalog(columnName, dialect), type, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, defaultValue, generated));
            }
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("Cannot inspect columns for " + name);
        TreeMap<Integer, Name> primary = new TreeMap<>();
        try (ResultSet rs = metadata.getPrimaryKeys(c.getCatalog(), schema, name)) { while (rs.next()) primary.put(rs.getInt("KEY_SEQ"), Name.catalog(rs.getString("COLUMN_NAME"), dialect)); }
        if (!primary.isEmpty()) keys.add(new Key("PRIMARY KEY", List.copyOf(primary.values()), null, List.of()));
        Map<String, TreeMap<Integer, Name>> indexes = new LinkedHashMap<>(); Map<String, Boolean> unique = new HashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(c.getCatalog(), schema, name, false, false)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String index = rs.getString("INDEX_NAME"), column = rs.getString("COLUMN_NAME");
                if (column == null || rs.getString("FILTER_CONDITION") != null || "D".equals(rs.getString("ASC_OR_DESC"))) throw new IllegalArgumentException("Expression, partial or descending indexes require manual conversion on " + name);
                indexes.computeIfAbsent(index, unused -> new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"), Name.catalog(column, dialect)); unique.put(index, !rs.getBoolean("NON_UNIQUE"));
            }
        }
        indexes.forEach((index, cols) -> { if (!(unique.get(index) && List.copyOf(cols.values()).equals(List.copyOf(primary.values())))) keys.add(new Key(unique.get(index) ? "UNIQUE" : "INDEX", List.copyOf(cols.values()), null, List.of())); });
        Map<String, TreeMap<Integer, Name>> fkColumns = new LinkedHashMap<>(), refColumns = new HashMap<>(); Map<String, Name> references = new HashMap<>();
        try (ResultSet rs = metadata.getImportedKeys(c.getCatalog(), schema, name)) {
            while (rs.next()) {
                if (!schema.equals(rs.getString("PKTABLE_SCHEM"))) throw new IllegalArgumentException("Cross-schema foreign key needs explicit mapping on " + name);
                if (rs.getShort("DELETE_RULE") != DatabaseMetaData.importedKeyNoAction && rs.getShort("DELETE_RULE") != DatabaseMetaData.importedKeyRestrict)
                    throw new IllegalArgumentException("Foreign key delete action needs explicit mapping on " + name);
                short updateRule = rs.getShort("UPDATE_RULE");
                // Oracle JDBC returns SQL NULL here: native Oracle FKs use UPDATE NO ACTION.
                // getShort(NULL) returns zero, which is also JDBC's CASCADE constant.
                if (rs.wasNull() && dialect == Dialect.ORACLE) updateRule = DatabaseMetaData.importedKeyNoAction;
                if (updateRule != DatabaseMetaData.importedKeyNoAction && updateRule != DatabaseMetaData.importedKeyRestrict)
                    throw new IllegalArgumentException("Foreign key update action needs explicit mapping on " + name);
                if (rs.getShort("DEFERRABILITY") != DatabaseMetaData.importedKeyNotDeferrable) throw new IllegalArgumentException("Deferrable constraints need explicit mapping on " + name);
                String key = rs.getString("FK_NAME"); int sequence = rs.getInt("KEY_SEQ");
                fkColumns.computeIfAbsent(key, unused -> new TreeMap<>()).put(sequence, Name.catalog(rs.getString("FKCOLUMN_NAME"), dialect));
                refColumns.computeIfAbsent(key, unused -> new TreeMap<>()).put(sequence, Name.catalog(rs.getString("PKCOLUMN_NAME"), dialect));
                references.put(key, Name.catalog(rs.getString("PKTABLE_NAME"), dialect));
            }
        }
        fkColumns.forEach((key, cols) -> keys.add(new Key("FOREIGN KEY", List.copyOf(cols.values()), references.get(key), List.copyOf(refColumns.get(key).values()))));
        return new Table(Name.catalog(name, dialect), List.copyOf(columns), List.copyOf(keys), List.of());
    }
    private void rejectAdvancedObjects(Connection c, String schema, String table, Dialect dialect) throws SQLException {
        if (c.getMetaData().getDatabaseProductName().equals("H2")) return; // Test fixture only.
        String query = dialect == Dialect.POSTGRESQL
                ? "SELECT count(*) FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=? AND (t.relkind <> 'r' OR t.relrowsecurity OR t.relhastriggers OR EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conrelid=t.oid AND (k.contype='c' OR k.condeferrable OR NOT k.convalidated)))"
                : "SELECT count(*) FROM all_tables t WHERE t.owner=? AND t.table_name=? AND (t.partitioned='YES' OR t.temporary='Y' OR EXISTS (SELECT 1 FROM all_triggers tr WHERE tr.table_owner=t.owner AND tr.table_name=t.table_name AND tr.status='ENABLED') OR EXISTS (SELECT 1 FROM all_constraints k WHERE k.owner=t.owner AND k.table_name=t.table_name AND k.constraint_type IN ('P','U','R') AND (k.status <> 'ENABLED' OR k.validated <> 'VALIDATED' OR k.deferrable <> 'NOT DEFERRABLE')))";
        // PostgreSQL's relhastriggers includes internal FK triggers; use explicit non-internal triggers.
        query = query.replace("t.relhastriggers", "EXISTS (SELECT 1 FROM pg_catalog.pg_trigger tr WHERE tr.tgrelid=t.oid AND NOT tr.tgisinternal)");
        try (PreparedStatement statement = c.prepareStatement(query)) {
            statement.setQueryTimeout(30); statement.setString(1, schema); statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) { if (rs.next() && rs.getInt(1) > 0) throw new IllegalArgumentException("Table " + table + " has checks, triggers, partitioning or policies requiring manual review"); }
        }
        if (dialect == Dialect.ORACLE) {
            try (PreparedStatement statement = c.prepareStatement("SELECT search_condition_vc, status, validated FROM all_constraints WHERE owner=? AND table_name=? AND constraint_type='C'")) {
                statement.setQueryTimeout(30); statement.setString(1, schema); statement.setString(2, table);
                try (ResultSet rs = statement.executeQuery()) { while (rs.next()) {
                    String check = rs.getString(1);
                    if (check == null || !check.trim().matches("(?is)\"(?:[^\"]|\"\")+\"\\s+IS\\s+NOT\\s+NULL") || !"ENABLED".equals(rs.getString(2)) || !"VALIDATED".equals(rs.getString(3)))
                        throw new IllegalArgumentException("CHECK constraint requires manual conversion on " + table);
                } }
            }
        }
    }
    private void checkIdentity(Connection c, String schema, String table, String column, Dialect dialect) throws SQLException {
        if (c.getMetaData().getDatabaseProductName().equals("H2")) return;
        String query = dialect == Dialect.POSTGRESQL
                ? "SELECT identity_generation, identity_increment, identity_cycle FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?"
                : "SELECT generation_type FROM all_tab_identity_cols WHERE owner=? AND table_name=? AND column_name=?";
        try (PreparedStatement statement = c.prepareStatement(query)) {
            statement.setQueryTimeout(30); statement.setString(1, schema); statement.setString(2, table); statement.setString(3, column);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !"BY DEFAULT".equals(rs.getString(1))) throw new IllegalArgumentException("Only BY DEFAULT identity columns are supported; serial or ALWAYS generation needs manual mapping on " + table);
                if (dialect == Dialect.POSTGRESQL && (!"1".equals(rs.getString(2)) || !"NO".equals(rs.getString(3)))) throw new IllegalArgumentException("Custom identity increment/cycle needs manual mapping on " + table);
            }
        }
        if (dialect == Dialect.ORACLE) {
            try (PreparedStatement statement = c.prepareStatement("SELECT s.increment_by, s.cycle_flag FROM all_sequences s JOIN all_tab_identity_cols i ON i.owner=s.sequence_owner AND i.sequence_name=s.sequence_name WHERE i.owner=? AND i.table_name=? AND i.column_name=?")) {
                statement.setQueryTimeout(30); statement.setString(1, schema); statement.setString(2, table); statement.setString(3, column);
                try (ResultSet rs = statement.executeQuery()) { if (!rs.next() || rs.getLong(1) != 1 || !"N".equals(rs.getString(2))) throw new IllegalArgumentException("Cannot verify standard identity sequence options on " + table); }
            }
        }
    }
    private Value defaultValue(String text, Dialect dialect) {
        if (text == null) return null;
        String value = text.trim();
        if (dialect == Dialect.POSTGRESQL) value = value.replaceFirst("::(?:character varying|text|bpchar|numeric|integer|bigint|smallint)$", "");
        Parsed parsed = new SqlParser(dialect, dialect).parse("CREATE TABLE x (v VARCHAR(4000) DEFAULT " + value + ")");
        if (!parsed.issues().isEmpty()) throw new IllegalArgumentException("Default expression requires manual conversion");
        return parsed.tables().getFirst().columns().getFirst().defaultValue();
    }
    public void snapshot(Connection c, Dialect dialect) throws SQLException {
        if (dialect == Dialect.POSTGRESQL) { c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false); }
        else { c.setAutoCommit(false); try (Statement statement = c.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); } }
    }
    public long export(Connection c, Table source, Table target, String sourceSchema, String targetSchema, Dialect sourceDialect, Dialect targetDialect, Path path, long limit) throws SQLException, IOException {
        long rows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(path); Statement statement = c.createStatement()) {
            statement.setFetchSize(500); statement.setQueryTimeout(120);
            try (ResultSet rs = statement.executeQuery("SELECT " + SqlWriter.names(source.columns().stream().map(Column::name).toList(), sourceDialect) + " FROM " + qualified(sourceSchema, source.name(), sourceDialect))) {
                while (rs.next()) {
                    if (++rows > limit) throw new IllegalArgumentException("Data exceeds configured row limit; narrow the selection or raise migration.max-rows");
                    List<Value> values = new ArrayList<>();
                    for (int i = 1; i <= source.columns().size(); i++) values.add(literal(rs, i, source.columns().get(i - 1).type(), targetDialect));
                    // One JSON string per line keeps multiline literals intact for streamed execution.
                    writer.write(JsonLines.encode(SqlWriter.insert(target, List.of(), values, targetSchema, targetDialect))); writer.newLine();
                    if (rows % 500 == 0) { writer.flush(); if (Files.size(path) > 100_000_000) throw new IllegalArgumentException("Export exceeds 100 MB per-table limit"); }
                }
            }
        }
        return rows;
    }
    long exportTyped(Connection c,Table source,Table filterTable,TableSelection selection,String schema,Dialect sourceDialect,Dialect targetDialect,
                     Path path,long limit,MigrationOptions options,TransferProgress progress)throws Exception {
        long rows=0,bytes=0;List<Object> values=new ArrayList<>();
        String query="SELECT "+SqlWriter.names(source.columns().stream().map(Column::name).toList(),sourceDialect)+" FROM "+qualified(schema,source.name(),sourceDialect)+TableProjection.where(filterTable,selection,sourceDialect,values);
        Files.createFile(path);checkDisk(path,options);
        try(PreparedStatement statement=c.prepareStatement(query);BufferedWriter writer=Files.newBufferedWriter(path)){
            progress.statement=statement;statement.setFetchSize(options.fetchSize());statement.setQueryTimeout(options.queryTimeoutSeconds());
            for(int i=0;i<values.size();i++)statement.setObject(i+1,values.get(i));
            try(ResultSet rs=statement.executeQuery()){
                while(rs.next()){
                    progress.check();if(++rows>limit)throw new IllegalArgumentException("Data exceeds configured row limit");
                    RowStore.Cell[] row=RowStore.read(rs,source,targetDialect,path.getParent(),options,progress,diskSpaceProbe());
                    String line=RowStore.JSON.writeValueAsString(row);long length=line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;
                    for(RowStore.Cell cell:row)if(cell.file()!=null)length+=Files.size(RowStore.sidecar(path.getParent(),cell.file()));
                    bytes+=length;if(bytes>options.maxTableBytes())throw new IllegalArgumentException("Export exceeds configured per-table byte limit");
                    writer.write(line);writer.newLine();progress.rowsRead++;progress.bytes+=length;
                    if(rows%options.fetchSize()==0){writer.flush();checkDisk(path,options);}
                }
            }
            writer.flush();checkDisk(path,options);
        }finally{progress.statement=null;}
        return rows;
    }
    protected RowStore.DiskSpaceProbe diskSpaceProbe() { return RowStore.SYSTEM_DISK_SPACE; }
    private void checkDisk(Path path,MigrationOptions options)throws IOException { RowStore.checkDisk(path,options,diskSpaceProbe()); }
    private Value literal(ResultSet rs, int index, Type type, Dialect target) throws SQLException {
        Object object = rs.getObject(index); if (object == null) return new Value("NULL");
        if (Set.of("DECIMAL", "INTEGER", "SMALLINT", "BIGINT").contains(type.kind())) return new Value(rs.getBigDecimal(index).toPlainString());
        if (type.kind().equals("DATE")) return new Value("DATE '" + rs.getDate(index).toLocalDate() + "'");
        if (type.kind().equals("TIMESTAMP")) return new Value("TIMESTAMP '" + rs.getTimestamp(index).toLocalDateTime().toString().replace('T', ' ') + "'");
        if (type.kind().equals("TIMESTAMPTZ")) throw new IllegalArgumentException("Timezone-aware data extraction requires an explicit timezone policy");
        String value = rs.getString(index);
        if (target == Dialect.ORACLE && value.isEmpty()) throw new IllegalArgumentException("Empty string would become NULL in Oracle; choose an explicit mapping");
        if (value.indexOf('\0') >= 0 || value.length() > 2000) throw new IllegalArgumentException("NUL or large text requires a LOB data loader; literal export blocked");
        return new Value("'" + value.replace("'", "''") + "'");
    }
    private String pattern(Connection c, String literal) throws SQLException {
        String escape = c.getMetaData().getSearchStringEscape(); return literal.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}

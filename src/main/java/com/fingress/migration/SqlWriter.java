package com.fingress.migration;

import java.util.*;
import java.util.stream.Collectors;
import static com.fingress.migration.Model.*;

public final class SqlWriter {
    private SqlWriter() {}
    public static String create(Table table, String schema, Dialect dialect) {
        List<String> definitions = new ArrayList<>();
        for (Column c : table.columns()) {
            definitions.add(c.name().sql(dialect) + " " + c.type().target(dialect).sql(dialect)
                    + (c.generated() ? " GENERATED " + (c.always() ? "ALWAYS" : "BY DEFAULT") + " AS IDENTITY" : "")
                    + (c.defaultValue() == null ? "" : " DEFAULT " + c.defaultValue().sql())
                    + (c.nullable() ? "" : " NOT NULL"));
        }
        for (Key k : table.keys()) if (Set.of("PRIMARY KEY", "UNIQUE", "CHECK").contains(k.kind())) definitions.add(constraint(k, dialect));
        return "CREATE TABLE " + qualified(schema, table.name(), dialect) + " (\n  " + String.join(",\n  ", definitions) + "\n)";
    }
    public static String insert(Table table, List<Name> columns, List<Value> values, String schema, Dialect dialect) {
        List<Name> explicit = columns.isEmpty() ? table.columns().stream().map(Column::name).toList() : columns;
        return "INSERT INTO " + qualified(schema, table.name(), dialect) + " (" + names(explicit, dialect) + ") VALUES (" + values.stream().map(Value::sql).collect(Collectors.joining(", ")) + ")";
    }
    public static List<String> afterData(Table table, String schema, Dialect dialect) {
        List<String> statements = new ArrayList<>(); int index = 0;
        for (Key key : table.keys()) {
            if (key.kind().equals("FOREIGN KEY")) statements.add("ALTER TABLE " + qualified(schema, table.name(), dialect) + " ADD FOREIGN KEY (" + names(key.columns(), dialect)
                    + ") REFERENCES " + qualified(schema, key.reference(), dialect) + " (" + names(key.referenceColumns(), dialect) + ")");
            if (key.kind().equals("INDEX")) {
                String indexName = "mig_" + UUID.nameUUIDFromBytes((schema + ":" + table.name().in(dialect) + ":" + index++).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 20);
                statements.add("CREATE INDEX " + quote(indexName) + " ON " + qualified(schema, table.name(), dialect) + " (" + names(key.columns(), dialect) + ")");
            }
        }
        return statements;
    }
    public static String names(List<Name> names, Dialect dialect) { return names.stream().map(n -> n.sql(dialect)).collect(Collectors.joining(", ")); }
    public static String constraint(Key key, Dialect dialect) {
        return (key.name() == null ? "" : "CONSTRAINT " + key.name().sql(dialect) + " ") + key.kind() + " (" + (key.kind().equals("CHECK") ? key.expression() : names(key.columns(), dialect)) + ")";
    }
}

package com.fingress.migration;

import java.util.*;
import static com.fingress.migration.Model.*;

public final class Compatibility {
    private Compatibility() {}
    public static List<String> differences(Table source, Table target, Dialect dialect) {
        List<String> differences = new ArrayList<>();
        Map<String, Column> expected = columns(source, dialect), actual = columns(target, dialect);
        for (Map.Entry<String, Column> entry : expected.entrySet()) {
            String name = entry.getKey(); Column from = entry.getValue(), to = actual.get(name);
            if (to == null) { differences.add("Missing target column: " + name); continue; }
            if (!from.type().target(dialect).equals(to.type().target(dialect))) differences.add(name + ": expected " + from.type().sql(dialect) + ", target " + to.type().sql(dialect));
            if (from.nullable() != to.nullable()) differences.add(name + ": nullability differs");
            if (from.generated() != to.generated() || from.always() != to.always()) differences.add(name + ": identity/generated behavior differs");
            if (!Objects.equals(from.defaultValue(), to.defaultValue())) differences.add(name + ": default value differs");
        }
        for (String name : actual.keySet()) if (!expected.containsKey(name)) differences.add("Extra target column: " + name + "; explicit mapping required");
        if (!keySignatures(source, dialect).equals(keySignatures(target, dialect))) differences.add("Primary, unique, foreign-key or index definitions differ");
        return List.copyOf(differences);
    }
    private static Map<String, Column> columns(Table table, Dialect dialect) {
        Map<String, Column> result = new TreeMap<>(); for (Column c : table.columns()) result.put(c.name().in(dialect), c); return result;
    }
    private static Set<String> keySignatures(Table table, Dialect dialect) {
        Set<String> keys = new TreeSet<>();
        for (Key k : table.keys()) {
            // Nonunique indexes do not affect insert compatibility.
            if (k.kind().equals("INDEX")) continue;
            keys.add(k.kind() + ":" + SqlWriter.names(k.columns(), dialect) + ":" + (k.reference() == null ? "" : k.reference().in(dialect)) + ":" + SqlWriter.names(k.referenceColumns(), dialect) + ":" + k.expression());
        }
        return keys;
    }
}

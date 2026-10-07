package com.fingress.migration;

import java.sql.*;
import java.util.*;
import static com.fingress.migration.Model.*;

final class TableProjection {
    static Table select(Table table,TableSelection selection,Dialect source) {
        if(selection==null || selection.columns()==null || selection.columns().isEmpty())return table;
        Set<String> names=new LinkedHashSet<>(selection.columns());
        if(names.size()!=selection.columns().size())throw new IllegalArgumentException("Duplicate selected column");
        List<Column> columns=table.columns().stream().filter(c->names.contains(c.name().in(source))).toList();
        if(columns.size()!=names.size())throw new IllegalArgumentException("Unknown selected column");
        for(Key key:table.keys()) for(Name name:key.columns()) if(!names.contains(name.in(source)))throw new IllegalArgumentException("Cannot exclude a key/index column: "+name.in(source));
        return new Table(table.name(),columns,table.keys(),table.warnings());
    }
    static Name rename(Name name,TableSelection selection,Dialect source) {
        if(selection==null || selection.rename()==null)return name;
        String mapped=selection.rename().get(name.in(source));
        return mapped==null?name:new Name(mapped,true);
    }
    static Name renameTable(Name name,TableSelection selection,Dialect source) {
        if(selection==null || selection.tableName()==null || selection.tableName().isBlank())return name;
        return new Name(selection.tableName().trim(),true);
    }
    static List<Key> keys(Table table,TableSelection selection,Map<String,TableSelection> all,Dialect source){
        return table.keys().stream().map(k->new Key(k.kind(),k.columns().stream().map(n->rename(n,selection,source)).toList(),k.reference()==null?null:renameTable(k.reference(),all.get(k.reference().in(source)),source),
                k.referenceColumns().stream().map(n->rename(n,k.reference()==null?null:all.get(k.reference().in(source)),source)).toList(),k.name(),k.expression())).toList();
    }
    static String where(Table table,TableSelection selection,Dialect dialect,List<Object> values){
        if(selection==null || selection.filters()==null || selection.filters().isEmpty())return "";
        if(selection.filters().size()>20)throw new IllegalArgumentException("At most 20 row filters per table");
        List<String> conditions=new ArrayList<>();
        for(RowFilter filter:selection.filters()) {
            Column column=table.columns().stream().filter(c->c.name().in(dialect).equals(filter.column())).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown filter column"));
            String operator=Objects.toString(filter.operator(),"").toUpperCase(Locale.ROOT);
            if(!Set.of("=","<>",">",">=","<","<=","IS NULL","IS NOT NULL").contains(operator))throw new IllegalArgumentException("Unsupported filter operator");
            if(Set.of("TEXT","BINARY").contains(column.type().kind()))throw new IllegalArgumentException("LOB filters are unavailable");
            conditions.add(column.name().sql(dialect)+" "+operator+(operator.contains("NULL")?"":" ?"));
            if(!operator.contains("NULL")){
                if(filter.value()==null)throw new IllegalArgumentException("Filter value is required");
                Object value=switch(column.type().kind()) {
                    case "DECIMAL","INTEGER","SMALLINT","BIGINT" -> new java.math.BigDecimal(filter.value());
                    case "DATE" -> java.sql.Date.valueOf(filter.value());
                    case "TIMESTAMP" -> Timestamp.valueOf(filter.value().replace('T',' '));
                    case "TIMESTAMPTZ" -> java.time.OffsetDateTime.parse(filter.value());
                    default -> filter.value();
                }; values.add(value);
            }
        }
        return " WHERE "+String.join(" AND ",conditions);
    }
}

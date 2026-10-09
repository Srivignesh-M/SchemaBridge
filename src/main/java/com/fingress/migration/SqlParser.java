package com.fingress.migration;

import java.util.*;
import static com.fingress.migration.Model.*;

/** Deliberately bounded grammar. Unknown syntax is an error, never passed through to JDBC. */
public final class SqlParser {
    record Token(String text, char kind) {
        boolean is(String value) { return kind == 'w' && text.equalsIgnoreCase(value) || kind == 'p' && text.equals(value); }
    }
    private List<Token> tokens;
    private int position;
    private final Dialect source;
    private final Dialect target;
    private final String targetSchema;
    private final List<String> expressionWarnings = new ArrayList<>();
    private String sourceQualifier;
    public SqlParser(Dialect source, Dialect target) { this(source, target, null); }
    public SqlParser(Dialect source, Dialect target, String targetSchema) { this.source = Objects.requireNonNull(source); this.target = Objects.requireNonNull(target); this.targetSchema=targetSchema; }
    Table definition(List<Token> input) { tokens=input;position=0;expressionWarnings.clear();expect("CREATE");expect("TABLE");Table result=table();end();return result; }
    Insert data(List<Token> input) { tokens=input;position=0;expressionWarnings.clear();expect("INSERT");Insert result=insert();end();return result; }
    List<String> expressionWarnings(){return List.copyOf(expressionWarnings);}

    public Parsed parse(String script) {
        List<Table> tables = new ArrayList<>(); List<Insert> inserts = new ArrayList<>(); List<String> issues = new ArrayList<>();
        List<List<Token>> statements = new ArrayList<>(); List<Token> current = new ArrayList<>();
        try {
            for (Token token : lex(script)) {
                if (token.is(";")) { if (!current.isEmpty()) statements.add(current); current = new ArrayList<>(); }
                else current.add(token);
            }
            if (!current.isEmpty()) statements.add(current);
        } catch (IllegalArgumentException e) { return new Parsed(List.of(), List.of(), List.of(e.getMessage())); }
        int number = 0;
        for (List<Token> statement : statements) {
            number++; tokens = statement; position = 0;expressionWarnings.clear();
            try {
                if (take("CREATE")) { expect("TABLE"); Table table = table(); end(); tables.add(table); }
                else if (take("INSERT")) { Insert insert = insert(); end(); inserts.add(insert); }
                else if (take("COMMIT")) { end(); /* Transaction boundaries are owned by the executor. */ }
                else throw fail("Only CREATE TABLE, INSERT ... VALUES and COMMIT are supported in this release");
            } catch (IllegalArgumentException e) { issues.add("Statement " + number + ": " + e.getMessage()); }
        }
        Set<String> names = new HashSet<>();
        for (Table table : tables) {
            if (!names.add(table.name().in(target))) issues.add("Duplicate mapped table: " + table.name().in(target));
        }
        for (Insert insert : inserts) {
            Table table = tables.stream().filter(t -> t.name().in(target).equals(insert.table().in(target))).findFirst().orElse(null);
            if (table == null) { issues.add("Provide CREATE TABLE metadata for " + insert.table().in(target) + " before converting its data"); continue; }
            List<Name> columns = insert.columns().isEmpty() ? table.columns().stream().map(Column::name).toList() : insert.columns();
            Set<String> seen = new HashSet<>();
            for (Name name : columns) {
                if (!seen.add(name.in(target))) issues.add("Duplicate INSERT column: " + name.in(target));
                if (table.columns().stream().noneMatch(c -> c.name().in(target).equals(name.in(target)))) issues.add("Unknown INSERT column: " + name.in(target));
            }
            for (List<Value> row : insert.rows()) if (columns.size() != row.size()) issues.add("INSERT value count differs from column count for " + table.name().in(target));
        }
        return new Parsed(List.copyOf(tables), List.copyOf(inserts), List.copyOf(new LinkedHashSet<>(issues)));
    }
    private Table table() {
        Name name = qualifiedName(); expect("("); List<Column> columns = new ArrayList<>(); List<Key> keys = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        do {
            Name constraintName = take("CONSTRAINT") ? name() : null;
            if (peek("PRIMARY") || peek("UNIQUE") || peek("FOREIGN") || peek("CHECK")) {
                Key key=key();keys.add(new Key(key.kind(),key.columns(),key.reference(),key.referenceColumns(),constraintName,key.expression()));continue;
            }
            Name columnName = name(); boolean serial = peek("SERIAL") || peek("BIGSERIAL") || peek("SMALLSERIAL");
            boolean localTime = tokens.subList(position,tokens.size()).stream().takeWhile(t -> !t.is(",") && !t.is(")")).anyMatch(t -> t.is("LOCAL"));
            Type type = type(); boolean nullable = true; Value defaultValue = null; boolean generated = serial, always = false;
            if(localTime && target==Dialect.POSTGRESQL) warnings.add("TIMESTAMP WITH LOCAL TIME ZONE maps to TIMESTAMPTZ; verify database/session TimeZone settings.");
            if (source == Dialect.ORACLE && type.kind().equals("VARCHAR")) warnings.add("Verify Oracle character/byte length semantics for " + columnName.in(target));
            while (position < tokens.size() && !peek(",") && !peek(")")) {
                if (take("NOT")) { expect("NULL"); nullable = false; }
                else if (take("NULL")) nullable = true;
                else if (take("DEFAULT")) defaultValue = value();
                else if (take("PRIMARY")) { expect("KEY"); keys.add(new Key("PRIMARY KEY", List.of(columnName), null, List.of())); nullable = false; }
                else if (take("UNIQUE")) keys.add(new Key("UNIQUE", List.of(columnName), null, List.of()));
                else if (take("REFERENCES")) { Name reference = qualifiedName(); keys.add(new Key("FOREIGN KEY", List.of(columnName), reference, nameList())); }
                else if (peek("CHECK")) keys.add(key());
                else if (take("GENERATED")) {
                    always=take("ALWAYS"); if(!always){expect("BY");expect("DEFAULT");} expect("AS"); expect("IDENTITY"); generated = true; nullable = false;
                } else throw fail("Unsupported column clause near " + safeToken());
            }
            if (generated && defaultValue != null) throw fail("Identity columns cannot also have DEFAULT");
            if(generated && type.kind().equals("DECIMAL") && type.precision()==null && target==Dialect.POSTGRESQL) warnings.add("Unbounded NUMBER identity mapped to BIGINT; identity values must fit the signed 64-bit range.");
            if(type.kind().equals("BINARY") && type.precision()!=null && target==Dialect.POSTGRESQL) keys.add(new Key("CHECK",List.of(columnName),null,List.of(),null,"octet_length("+columnName.sql(target)+") <= "+type.precision()));
            columns.add(new Column(columnName, generated ? type.identityTarget(target) : type.target(target), nullable && !generated, defaultValue, generated, always));
        } while (take(","));
        expect(")");
        if (columns.isEmpty()) throw fail("Table has no columns");
        Set<String> columnNames = new HashSet<>();
        for (Column column : columns) if (!columnNames.add(column.name().in(target))) throw fail("Duplicate mapped column");
        if (keys.stream().filter(k -> k.kind().equals("PRIMARY KEY")).count() > 1) throw fail("Multiple primary keys");
        for (Key key : keys) {
            for (Name col : key.columns()) if (!columnNames.contains(col.in(target))) throw fail("Constraint references missing column " + col.in(target));
            if (key.kind().equals("FOREIGN KEY") && key.columns().size() != key.referenceColumns().size()) throw fail("Foreign key column count mismatch");
        }
        List<Column> normalized = columns.stream().map(c -> keys.stream().anyMatch(k -> k.kind().equals("PRIMARY KEY") && k.columns().stream().anyMatch(n -> n.in(target).equals(c.name().in(target))))
                ? new Column(c.name(), c.type(), false, c.defaultValue(), c.generated(), c.always()) : c).toList();
        warnings.addAll(expressionWarnings);
        return new Table(name, normalized, List.copyOf(keys), List.copyOf(warnings));
    }
    private Key key() {
        if(take("CHECK")){expect("(");SqlExpression.Result expr=SqlExpression.parse(tokens,position,source,target,targetSchema);position=expr.next();expressionWarnings.addAll(expr.warnings());expect(")");return new Key("CHECK",expr.columns(),null,List.of(),null,expr.sql());}
        if (take("PRIMARY")) { expect("KEY"); return new Key("PRIMARY KEY", nameList(), null, List.of()); }
        if (take("UNIQUE")) return new Key("UNIQUE", nameList(), null, List.of());
        expect("FOREIGN"); expect("KEY"); List<Name> columns = nameList(); expect("REFERENCES"); Name reference = qualifiedName();
        return new Key("FOREIGN KEY", columns, reference, nameList());
    }
    private Insert insert() {
        expect("INTO"); Name table = qualifiedName(); List<Name> columns = peek("(") ? nameList() : List.of();
        expect("VALUES"); List<List<Value>> rows = new ArrayList<>();
        do { expect("("); List<Value> row = new ArrayList<>(); do { row.add(value()); } while (take(",")); expect(")"); rows.add(List.copyOf(row)); } while (take(","));
        return new Insert(table, columns, List.copyOf(rows));
    }
    private Value value() {
        SqlExpression.Result expr=SqlExpression.parse(tokens,position,source,target,targetSchema);
        if(!expr.columns().isEmpty())throw fail("Column references are not allowed in a literal VALUES/default expression");
        position=expr.next();expressionWarnings.addAll(expr.warnings());return new Value(expr.sql());
    }
    private Type type() {
        Token token = next(); if (token.kind != 'w') throw fail("Expected data type");
        String kind = token.text.toUpperCase(Locale.ROOT);
        if (kind.equals("CHARACTER")) { kind = take("VARYING") ? "VARCHAR" : "CHAR"; }
        Integer precision = null, scale = null;
        if (take("(")) {
            precision = integer(); if (take(",")) { boolean negative = take("-"); scale = integer() * (negative ? -1 : 1); }
            take("CHAR"); take("BYTE"); expect(")");
        }
        if (precision != null && !Set.of("VARCHAR", "VARCHAR2", "CHAR", "NUMBER", "NUMERIC", "DECIMAL", "TIMESTAMP", "TIMESTAMPTZ", "RAW").contains(kind)) throw fail("Unsupported type modifier for " + kind);
        if (scale != null && !Set.of("NUMBER", "NUMERIC", "DECIMAL").contains(kind)) throw fail("Unexpected scale for " + kind);
        Type result = switch (kind) {
            case "VARCHAR", "VARCHAR2", "CHAR" -> {
                if (precision == null || precision < 1) throw fail("Character types require a positive length");
                yield new Type(kind.equals("CHAR") ? "CHAR" : "VARCHAR", precision, null);
            }
            case "NUMBER", "NUMERIC", "DECIMAL" -> {
                if (precision != null && precision < 1) throw fail("Invalid numeric precision");
                yield new Type("DECIMAL", precision, precision == null ? null : (scale == null ? 0 : scale));
            }
            case "INT", "INTEGER", "SERIAL" -> new Type("INTEGER", null, null);
            case "SMALLINT", "SMALLSERIAL" -> new Type("SMALLINT", null, null);
            case "BIGINT", "BIGSERIAL" -> new Type("BIGINT", null, null);
            case "TEXT", "CLOB" -> new Type("TEXT", null, null);
            case "RAW", "BLOB", "BYTEA" -> new Type("BINARY",precision,null);
            case "DATE" -> new Type(source == Dialect.ORACLE ? "TIMESTAMP" : "DATE", source == Dialect.ORACLE ? 0 : null, null);
            case "TIMESTAMP", "TIMESTAMPTZ" -> {
                boolean zone = kind.equals("TIMESTAMPTZ");
                if (take("WITH")) { take("LOCAL"); expect("TIME"); expect("ZONE"); zone = true; }
                else if (take("WITHOUT")) { expect("TIME"); expect("ZONE"); }
                if (precision != null && (precision < 0 || precision > 6)) throw fail("Timestamp precision above 6 needs an explicit mapping");
                yield new Type(zone ? "TIMESTAMPTZ" : "TIMESTAMP", precision == null ? 6 : precision, null);
            }
            default -> throw fail("Unsupported data type " + kind);
        };
        return result;
    }
    public static Type catalogType(String type, int size, int scale, Dialect dialect) {
        String name = type.toUpperCase(Locale.ROOT);
        return switch (name) {
            case "VARCHAR2", "VARCHAR", "CHARACTER VARYING" -> new Type("VARCHAR", size, null);
            case "CHAR", "BPCHAR", "CHARACTER" -> new Type("CHAR", size, null);
            case "NUMBER", "NUMERIC", "DECIMAL" -> new Type("DECIMAL", size <= 0 ? null : size, size <= 0 ? null : scale);
            case "INT2", "SMALLINT" -> new Type("SMALLINT", null, null);
            case "INT4", "INTEGER" -> new Type("INTEGER", null, null);
            case "INT8", "BIGINT" -> new Type("BIGINT", null, null);
            case "TEXT", "CLOB", "CHARACTER LARGE OBJECT" -> new Type("TEXT", null, null);
            case "BYTEA", "BLOB", "BINARY LARGE OBJECT", "BINARY VARYING" -> new Type("BINARY", null, null);
            case "RAW" -> new Type("BINARY", size, null);
            case "DATE" -> new Type(dialect == Dialect.ORACLE ? "TIMESTAMP" : "DATE", dialect == Dialect.ORACLE ? 0 : null, null);
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> new Type("TIMESTAMP", scale, null);
            case "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> new Type("TIMESTAMPTZ", scale, null);
            default -> {
                var timestamp = java.util.regex.Pattern.compile("TIMESTAMP\\(([0-9])\\)( WITH TIME ZONE)?").matcher(name);
                if (timestamp.matches()) yield new Type(timestamp.group(2) == null ? "TIMESTAMP" : "TIMESTAMPTZ", Integer.parseInt(timestamp.group(1)), null);
                throw new IllegalArgumentException("Unsupported catalog type: " + type);
            }
        };
    }
    private Name qualifiedName() {
        Name first = name();
        if (!take(".")) return first;
        String qualifier = first.in(source);
        if (sourceQualifier != null && !sourceQualifier.equals(qualifier)) throw fail("Only one source schema per plan is supported");
        sourceQualifier = qualifier; return name();
    }
    private List<Name> nameList() { expect("("); List<Name> list = new ArrayList<>(); do { list.add(name()); } while (take(",")); expect(")"); return List.copyOf(list); }
    private Name name() { Token token = next(); if (token.kind != 'w' && token.kind != 'i') throw fail("Expected identifier"); return new Name(token.text, token.kind == 'i'); }
    private int integer() { Token token = next(); if (token.kind != 'n' || !token.text.matches("[0-9]+")) throw fail("Expected integer"); return Integer.parseInt(token.text); }
    private Token next() { if (position >= tokens.size()) throw fail("Unexpected end of statement"); return tokens.get(position++); }
    private boolean peek(String value) { return position < tokens.size() && tokens.get(position).is(value); }
    private boolean take(String value) { if (peek(value)) { position++; return true; } return false; }
    private void expect(String value) { if (!take(value)) throw fail("Expected " + value + " near " + safeToken()); }
    private void end() { if (position != tokens.size()) throw fail("Unsupported trailing clause near " + safeToken()); }
    private String safeToken() { return position >= tokens.size() ? "end" : tokens.get(position).kind == 's' ? "[literal]" : tokens.get(position).text; }
    private IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }

    static List<Token> lex(String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL is required");
        List<Token> output = new ArrayList<>(); int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '\ufeff') { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') { while (i < sql.length() && sql.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2; int depth = 1;
                while (i < sql.length() && depth > 0) {
                    if (i + 1 < sql.length() && sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (i + 1 < sql.length() && sql.startsWith("*/", i)) { depth--; i += 2; } else i++;
                }
                if (depth > 0) throw new IllegalArgumentException("Unterminated block comment"); continue;
            }
            if (c == '\'' || c == '"') {
                char delimiter = c; StringBuilder text = new StringBuilder(); i++; boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == delimiter) { if (i < sql.length() && sql.charAt(i) == delimiter) { text.append(delimiter); i++; } else { closed = true; break; } }
                    else { if (next == '\0') throw new IllegalArgumentException("NUL is not supported"); text.append(next); }
                }
                if (!closed) throw new IllegalArgumentException("Unterminated quoted token");
                output.add(new Token(text.toString(), delimiter == '\'' ? 's' : 'i')); continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++; while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || "_$#".indexOf(sql.charAt(i)) >= 0)) i++;
                output.add(new Token(sql.substring(start, i), 'w')); continue;
            }
            if (Character.isDigit(c)) {
                int start = i++; while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
                if (i < sql.length() && sql.charAt(i) == '.') { i++; while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++; }
                if (i < sql.length() && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
                    i++; if (i < sql.length() && (sql.charAt(i) == '-' || sql.charAt(i) == '+')) i++;
                    int digits = i; while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
                    if (digits == i) throw new IllegalArgumentException("Invalid numeric exponent");
                }
                output.add(new Token(sql.substring(start, i), 'n')); continue;
            }
            output.add(new Token(String.valueOf(c), 'p')); i++;
        }
        return output;
    }
}

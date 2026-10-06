package com.fingress.migration;

import java.util.*;
import static com.fingress.migration.Model.*;
import static com.fingress.migration.SqlParser.Token;

/** Bounded expression grammar shared by DDL, DML and index conversion. */
final class SqlExpression {
    record Result(String sql, int next, List<Name> columns, List<String> warnings) {}
    private final List<Token> tokens; private int p; private final Dialect source, target; private final String schema;
    private final List<Name> columns = new ArrayList<>(); private final List<String> warnings = new ArrayList<>();
    private SqlExpression(List<Token> tokens, int start, Dialect source, Dialect target, String schema) { this.tokens=tokens; p=start; this.source=source; this.target=target; this.schema=schema; }
    static Result parse(List<Token> tokens, int start, Dialect source, Dialect target, String schema) {
        SqlExpression parser = new SqlExpression(tokens,start,source,target,schema); String sql=parser.expression(0);
        return new Result(sql,parser.p,List.copyOf(parser.columns),List.copyOf(parser.warnings));
    }
    private String expression(int minimum) {
        String left = atom();
        while (p < tokens.size()) {
            String op=operator(); int precedence=switch(op){case "OR" -> 1; case "AND" -> 2; case "=","<>","!=","<",">","<=",">=","IS","LIKE" -> 3;case "||" -> 4;case "+","-" -> 5;case "*","/" -> 6;default -> -1;};
            if (precedence < minimum) break;
            p += op.length()==2 && !Set.of("OR","IS").contains(op) ? 2 : 1;
            if (op.equals("IS")) { boolean not=take("NOT"); need("NULL"); left="("+left+" IS "+(not?"NOT ":"")+"NULL)"; }
            else left="("+left+" "+op+" "+expression(precedence+1)+")";
        }
        return left;
    }
    private String atom() {
        Token token=next();
        if (token.is("(")) { String sql=expression(0); need(")"); return "("+sql+")"; }
        if (token.is("+")||token.is("-")) return token.text()+expression(7);
        if (token.is("NOT")) return "NOT "+expression(3);
        if (token.kind()=='n') return token.text();
        if (token.kind()=='s') {
            if (token.text().isEmpty() && source==Dialect.ORACLE) return "NULL";
            if (token.text().isEmpty() && target==Dialect.ORACLE) throw error("Empty string would become NULL in Oracle; choose an explicit mapping");
            return literal(token.text());
        }
        if (token.is("NULL")) return "NULL";
        if (token.is("CURRENT_TIMESTAMP")) return "CURRENT_TIMESTAMP";
        if (token.is("SYSDATE") || token.is("SYSTIMESTAMP")) {
            if (target==Dialect.ORACLE) return token.text().toUpperCase(Locale.ROOT);
            warnings.add("Set PostgreSQL TimeZone to the source server timezone for SYSDATE/SYSTIMESTAMP conversions; clock_timestamp uses wall-clock time.");
            return token.is("SYSDATE") ? "date_trunc('second', clock_timestamp()::timestamp)" : "clock_timestamp()";
        }
        if ((token.is("DATE") || token.is("TIMESTAMP")) && p<tokens.size() && tokens.get(p).kind()=='s') {
            String text=next().text(); boolean zone=token.is("TIMESTAMP") && text.matches("(?s).*(?:[+-]\\d{2}:\\d{2}|Z)\\s*$");
            return (zone && target==Dialect.POSTGRESQL ? "TIMESTAMP WITH TIME ZONE" : token.text().toUpperCase(Locale.ROOT))+" "+literal(text);
        }
        if (token.kind()!='w' && token.kind()!='i') throw error("Expected expression");
        if (take("(")) {
            String function=token.text().toUpperCase(Locale.ROOT);
            if (token.kind()=='i' || !Set.of("LENGTH","UPPER","LOWER","COALESCE","NVL","ROUND","TRIM","ABS","HEXTORAW","TO_DATE","TO_TIMESTAMP").contains(function)) throw error("Function needs an explicit conversion rule: "+function);
            List<String> args=new ArrayList<>(); if (!peek(")")) do { args.add(expression(0)); } while(take(",")); need(")");
            if (function.equals("HEXTORAW")) {
                if(args.size()!=1 || !args.getFirst().matches("'(?:[0-9a-fA-F]{2})*'")) throw error("HEXTORAW requires an even-length hexadecimal string literal");
                return target==Dialect.POSTGRESQL ? "decode("+args.getFirst()+", 'hex')" : "HEXTORAW("+args.getFirst()+")";
            }
            if (function.equals("NVL")) { if(args.size()!=2) throw error("NVL requires two arguments"); warnings.add("NVL maps to COALESCE; verify argument types and evaluation behavior."); function=target==Dialect.POSTGRESQL?"COALESCE":"NVL"; }
            if (Set.of("TO_DATE","TO_TIMESTAMP").contains(function) && source!=target) throw error(function+" format/time semantics need an explicit mapping");
            if(Set.of("LENGTH","UPPER","LOWER","TRIM","ABS").contains(function)&&args.size()!=1)throw error(function+" requires one argument");
            if(function.equals("ROUND")&&(args.isEmpty()||args.size()>2))throw error("ROUND requires one or two arguments");
            if(function.equals("COALESCE")&&args.isEmpty())throw error("COALESCE requires arguments");
            return function+"("+String.join(", ",args)+")";
        }
        Name name=new Name(token.text(),token.kind()=='i');
        if (take(".")) {
            Token member=next();
            if (member.is("NEXTVAL") || member.is("CURRVAL")) {
                String seq=schema==null ? name.sql(target) : qualified(schema,name,target);
                return target==Dialect.POSTGRESQL ? member.text().toLowerCase(Locale.ROOT)+"("+literal(seq)+"::regclass)" : seq+"."+member.text().toUpperCase(Locale.ROOT);
            }
            if(member.kind()!='w' && member.kind()!='i') throw error("Expected qualified column");
            Name column=new Name(member.text(),member.kind()=='i'); columns.add(column); return name.sql(target)+"."+column.sql(target);
        }
        columns.add(name); return name.sql(target);
    }
    private String operator() {
        Token token=tokens.get(p); if(token.kind()=='w' && Set.of("OR","AND","IS","LIKE").contains(token.text().toUpperCase(Locale.ROOT))) return token.text().toUpperCase(Locale.ROOT);
        if(token.kind()!='p') return "";
        if(p+1<tokens.size()) { String pair=token.text()+tokens.get(p+1).text(); if(Set.of("<=",">=","<>","!=","||").contains(pair)) return pair; }
        return Set.of("=","<",">","+","-","*","/").contains(token.text())?token.text():"";
    }
    static String literal(String value) { return "'"+value.replace("'","''")+"'"; }
    private boolean peek(String value){return p<tokens.size()&&tokens.get(p).is(value);}
    private boolean take(String value){if(peek(value)){p++;return true;}return false;}
    private void need(String value){if(!take(value))throw error("Expected "+value+" in expression");}
    private Token next(){if(p>=tokens.size())throw error("Unexpected end of expression");return tokens.get(p++);}
    private IllegalArgumentException error(String text){return new IllegalArgumentException(text);}
}

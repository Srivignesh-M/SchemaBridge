package com.fingress.migration;

import java.util.regex.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static com.fingress.migration.Model.*;

/** Bounded JDBC URL support; never returns credentials in parser errors. */
record JdbcAddress(Dialect dialect, String host, int port, String database, String schema, String mode) {
    static JdbcAddress parse(String url) {
        if (url == null) throw new IllegalArgumentException("Missing JDBC URL");
        Matcher pg = Pattern.compile("jdbc:postgresql://([a-zA-Z0-9.-]+)(?::([0-9]{1,5}))?/([a-zA-Z0-9_.-]+)(?:\\?([^#]*))?").matcher(url);
        if (pg.matches()) {
            String schema = null;
            if (pg.group(4) != null) for (String option : pg.group(4).split("&")) {
                String[] pair = option.split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException("Invalid JDBC URL options");
                String key = pair[0], value;
                try { value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid JDBC URL encoding"); }
                if (key.equals("currentSchema") && value.matches("[a-zA-Z0-9_$]+")) schema = value;
                else if (!(key.equals("sslmode") && value.matches("disable|allow|prefer|require|verify-ca|verify-full")))
                    throw new IllegalArgumentException("Unsupported JDBC URL option; supported PostgreSQL options: currentSchema and sslmode");
            }
            return new JdbcAddress(Dialect.POSTGRESQL, pg.group(1), port(pg.group(2),5432),pg.group(3),schema,"Database");
        }
        Matcher oracle = Pattern.compile("jdbc:oracle:thin:@(?://)?([a-zA-Z0-9.-]+):([0-9]{1,5})([:/])([a-zA-Z0-9_.-]+)").matcher(url);
        if (oracle.matches()) return new JdbcAddress(Dialect.ORACLE,oracle.group(1),port(oracle.group(2),1521),oracle.group(4),null,oracle.group(3).equals(":")?"SID":"Service name");
        throw new IllegalArgumentException("Unsupported JDBC URL: select an Oracle SID/service or PostgreSQL datasource");
    }
    private static int port(String value, int fallback) { int port = value == null ? fallback : Integer.parseInt(value); if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid JDBC port"); return port; }
    ConnectionSpec connection(String user, String password, String url) { return new ConnectionSpec(dialect,host,port,database,user,password,url); }
}

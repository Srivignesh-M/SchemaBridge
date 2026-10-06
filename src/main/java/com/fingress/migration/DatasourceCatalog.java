package com.fingress.migration;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;
import static com.fingress.migration.Model.*;

@Service
public class DatasourceCatalog {
    public record Entry(long id, String name, String description, String dialect, boolean supported, String finding) {}
    public record Selection(long id, String name, ConnectionSpec connection, String schema, String mode) {}
    private final DatabaseGateway db;
    private final ObjectMapper json;
    private final String url, username, password, schema;
    public DatasourceCatalog(DatabaseGateway db, ObjectMapper json,
            @Value("${migration.catalog.url:}") String url, @Value("${migration.catalog.username:}") String username,
            @Value("${migration.catalog.password:}") String password, @Value("${migration.catalog.schema:fg_solutions}") String schema) {
        this.db=db; this.json=json; this.url=url; this.username=username; this.password=password; this.schema=schema;
    }
    public boolean configured() { return !url.isBlank() && !username.isBlank() && !password.isBlank(); }
    private Connection open() throws SQLException {
        if (!configured()) throw new IllegalArgumentException("LCNC catalogue is not configured; set migration.catalog connection properties");
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid catalogue schema");
        JdbcAddress address=JdbcAddress.parse(url);
        if (address.dialect()!=Dialect.POSTGRESQL) throw new IllegalArgumentException("The LCNC catalogue connection must be PostgreSQL");
        return db.connect(address.connection(username,password,url));
    }
    private String query() { return "SELECT id, name, description, config FROM " + quote(schema) + ".fg_datasource WHERE code = 'JDBC' AND active_code = 'ACTV' AND status_code = 'APPROVED' AND is_master_version = true"; }
    public synchronized List<Entry> list() throws SQLException {
        // Each request owns exactly one connection and closes it before returning. No datasource pools are created.
        try (Connection connection=open()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement=connection.prepareStatement(query()+" ORDER BY name, id LIMIT 1001")) {
                statement.setQueryTimeout(15);
                try (ResultSet rows=statement.executeQuery()) {
                    List<Entry> entries=new ArrayList<>();
                    while(rows.next()) {
                        try { Selection selection=selection(rows); entries.add(new Entry(selection.id(),selection.name(),rows.getString("description"),selection.connection().dialect().name(),true,"")); }
                        catch (IllegalArgumentException e) { entries.add(new Entry(rows.getLong("id"),rows.getString("name"),rows.getString("description"),"",false,e.getMessage())); }
                    }
                    if(entries.size()>1000) throw new IllegalArgumentException("Catalogue exceeds 1000 active JDBC entries; narrow the catalogue before loading");
                    return List.copyOf(entries);
                }
            }
        }
    }
    public synchronized Selection get(long id) throws SQLException {
        try(Connection connection=open()) {
            connection.setReadOnly(true);
            try(PreparedStatement statement=connection.prepareStatement(query()+" AND id = ?")) {
                statement.setLong(1,id); statement.setQueryTimeout(15);
                try(ResultSet rows=statement.executeQuery()) {
                    if(!rows.next()) throw new IllegalArgumentException("Datasource is missing or is no longer active, approved and a master version");
                    return selection(rows);
                }
            }
        }
    }
    private Selection selection(ResultSet row) throws SQLException {
        JsonNode config;
        try { config=json.readTree(row.getString("config")); } catch(Exception e) { throw new IllegalArgumentException("Datasource config must be valid JSON"); }
        if(config==null || !config.isObject()) throw new IllegalArgumentException("Datasource config must be a JSON object");
        String jdbc=config.path("url").asText(), user=config.path("userName").asText();
        if(user.isBlank() || !config.path("password").isTextual()) throw new IllegalArgumentException("Datasource requires userName and a plain-text password in config");
        JdbcAddress address=JdbcAddress.parse(jdbc);
        String driver=config.path("driver").asText();
        if (!(address.dialect()==Dialect.ORACLE ? Set.of("oracle.jdbc.OracleDriver","oracle.jdbc.driver.OracleDriver").contains(driver) : driver.equals("org.postgresql.Driver")))
            throw new IllegalArgumentException("Datasource driver does not match a supported Oracle/PostgreSQL URL");
        return new Selection(row.getLong("id"),row.getString("name"),address.connection(user,config.path("password").asText(),jdbc),address.schema()==null && address.dialect()==Dialect.ORACLE?user.toUpperCase(Locale.ROOT):address.schema(),address.mode());
    }
}

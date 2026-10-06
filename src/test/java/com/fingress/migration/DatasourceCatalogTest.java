package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.fingress.migration.Model.*;

class DatasourceCatalogTest {
    String url; final AtomicInteger active=new AtomicInteger(); DatabaseGateway gateway; DatasourceCatalog catalog;
    @BeforeEach void setup() throws Exception {
        url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(Connection c=DriverManager.getConnection(url); Statement s=c.createStatement()) {
            s.execute("CREATE SCHEMA fg_solutions");
            s.execute("CREATE TABLE fg_solutions.fg_datasource(id BIGINT,name VARCHAR,description VARCHAR,config VARCHAR,code VARCHAR,active_code VARCHAR,status_code VARCHAR,is_master_version BOOLEAN)");
        }
        gateway=new DatabaseGateway(){ @Override public Connection connect(ConnectionSpec spec) throws SQLException {
            Connection c=DriverManager.getConnection(url);active.incrementAndGet();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                try { return m.invoke(c,a); } catch(InvocationTargetException e) {throw e.getCause();} finally {if(m.getName().equals("close"))active.decrementAndGet();}
            });
        }};
        catalog=new DatasourceCatalog(gateway,new ObjectMapper(),"jdbc:postgresql://localhost:5432/postgres","reader","test-only","fg_solutions");
    }
    void row(long id,String jdbc,String driver,String status,boolean master) throws Exception {
        String config=new ObjectMapper().writeValueAsString(Map.of("url",jdbc,"driver",driver,"userName","APP","password","test-secret"));
        try(Connection c=DriverManager.getConnection(url);PreparedStatement s=c.prepareStatement("INSERT INTO fg_solutions.fg_datasource VALUES (?, 'Example', 'test', ?, 'JDBC', 'ACTV', ?, ?)")) {s.setLong(1,id);s.setString(2,config);s.setString(3,status);s.setBoolean(4,master);s.executeUpdate();}
    }
    @Test void filtersEntriesDoesNotExposePasswordsInListAndClosesRepeatedReads() throws Exception {
        row(1,"jdbc:oracle:thin:@db.example:1524:demo","oracle.jdbc.OracleDriver","APPROVED",true);
        row(2,"jdbc:postgresql://db.example/app?currentSchema=fg_solutions","org.postgresql.Driver","DRAFT",true);
        row(3,"jdbc:postgresql://db.example/app","org.postgresql.Driver","APPROVED",false);
        for(int i=0;i<35;i++) {var entries=catalog.list();assertEquals(1,entries.size());assertFalse(new ObjectMapper().writeValueAsString(entries).contains("test-secret"));assertEquals(0,active.get());}
        var selected=catalog.get(1);assertEquals("SID",selected.mode());assertEquals("demo",selected.connection().database());assertEquals("test-secret",selected.connection().password());assertEquals(0,active.get());
        assertThrows(IllegalArgumentException.class,()->catalog.get(2));assertEquals(0,active.get());
    }
    @Test void closesOnSqlFailureAndReportsUnsupportedEntries() throws Exception {
        row(1,"jdbc:mysql://localhost/test","com.mysql.Driver","APPROVED",true);
        assertFalse(catalog.list().getFirst().supported());assertEquals(0,active.get());
        assertThrows(IllegalArgumentException.class,()->catalog.get(1));assertEquals(0,active.get());
        DatasourceCatalog broken=new DatasourceCatalog(gateway,new ObjectMapper(),"jdbc:postgresql://localhost/test","reader","test-only","missing");
        assertThrows(SQLException.class,broken::list);assertEquals(0,active.get());
    }
    @Test void parsesSupportedAddressesWithoutLosingSidOrSchema() {
        assertEquals("Service name",JdbcAddress.parse("jdbc:oracle:thin:@//db:1521/demo").mode());
        var pg=JdbcAddress.parse("jdbc:postgresql://db:5432/postgres?currentSchema=fg_solutions&sslmode=require");
        assertEquals("fg_solutions",pg.schema());assertEquals(5432,pg.port());
        assertThrows(IllegalArgumentException.class,()->JdbcAddress.parse("jdbc:postgresql://db/app?password=secret"));
    }
    @Test void connectionBudgetReleasesOnCloseAndFailedOpen() throws Exception {
        ConnectionBudget budget=new ConnectionBudget(1);
        Connection first=budget.open(()->DriverManager.getConnection(url));
        assertThrows(SQLException.class,()->budget.open(()->DriverManager.getConnection(url)));
        first.close();first.close();
        assertThrows(SQLException.class,()->budget.open(()->{throw new SQLException("failed");}));
        try(Connection next=budget.open(()->DriverManager.getConnection(url))) {assertFalse(next.isClosed());assertThrows(SQLException.class,()->budget.open(()->DriverManager.getConnection(url)));}
    }
    @Test void backgroundConnectionsLeaveReservedCapacityForForegroundWork() throws Exception {
        ConnectionBudget budget=new ConnectionBudget(4,3);List<Connection> background=new ArrayList<>();
        try {
            for(int i=0;i<3;i++)background.add(budget.openBackground(()->DriverManager.getConnection(url)));
            assertThrows(SQLException.class,()->budget.openBackground(()->DriverManager.getConnection(url)));
            try(Connection foreground=budget.open(()->DriverManager.getConnection(url))){assertFalse(foreground.isClosed());assertThrows(SQLException.class,()->budget.open(()->DriverManager.getConnection(url)));}
        } finally {for(Connection connection:background)connection.close();}
        try(Connection next=budget.openBackground(()->DriverManager.getConnection(url))){assertFalse(next.isClosed());}
    }
}

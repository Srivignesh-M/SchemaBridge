package com.fingress.migration;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** No pool or idle connections. Limits all app-owned catalogue/source/target JDBC sessions. */
final class ConnectionBudget {
    private final Semaphore slots;
    private final Semaphore backgroundSlots;
    ConnectionBudget(int maximum) { this(maximum, maximum); }
    ConnectionBudget(int maximum, int maximumBackground) {
        if(maximum<1||maximumBackground<0||maximumBackground>maximum)throw new IllegalArgumentException("Invalid connection budget");
        slots = new Semaphore(maximum, true);backgroundSlots=new Semaphore(maximumBackground,true);
    }
    interface Open { Connection get() throws SQLException; }
    Connection open(Open factory) throws SQLException {return open(factory,false);}
    Connection openBackground(Open factory) throws SQLException {return open(factory,true);}
    private Connection open(Open factory,boolean background) throws SQLException {
        if(background&&!backgroundSlots.tryAcquire())throw new SQLException("Background connection budget busy", "53300");
        if (!slots.tryAcquire()) {if(background)backgroundSlots.release();throw new SQLException("Connection budget busy", "53300");}
        Connection connection;
        try { connection = factory.get(); } catch (SQLException | RuntimeException | Error e) { slots.release();if(background)backgroundSlots.release();throw e; }
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) {
                if (closed.compareAndSet(false,true)) try { connection.close(); } finally { slots.release();if(background)backgroundSlots.release(); }
                return null;
            }
            if (method.getName().equals("isClosed") && closed.get()) return true;
            if (method.getName().equals("unwrap")) { if (((Class<?>)args[0]).isInstance(proxy)) return proxy; return connection.unwrap((Class<?>)args[0]); }
            if (method.getName().equals("isWrapperFor")) return ((Class<?>)args[0]).isInstance(proxy) || connection.isWrapperFor((Class<?>)args[0]);
            if (closed.get()) throw new SQLException("Connection is closed");
            try { return method.invoke(connection,args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }
}

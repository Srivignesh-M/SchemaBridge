package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.fingress.migration.Model.*;

/** Child JVM used to simulate an OS-level stop after a target commit but before its journal update. */
public final class AbruptTerminationChild {
    private AbruptTerminationChild() {}

    public static void main(String[] args) throws Exception {
        String sourceUrl=args[1],targetUrl=args[2];
        AtomicInteger commits=new AtomicInteger();
        DatabaseGateway gateway=new DatabaseGateway(){
            @Override public Connection connect(ConnectionSpec spec)throws SQLException {
                Connection raw=DriverManager.getConnection(spec.host().equals("source")?sourceUrl:targetUrl);
                if(!spec.host().equals("target"))return raw;
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,parameters)->{
                    try{
                        Object result=method.invoke(raw,parameters);
                        if(method.getName().equals("commit")&&commits.incrementAndGet()==2)Runtime.getRuntime().halt(77);
                        return result;
                    }catch(InvocationTargetException error){throw error.getCause();}
                });
            }
        };
        ConnectionSpec source=new ConnectionSpec(Dialect.POSTGRESQL,"source",5432,"test","user","");
        ConnectionSpec target=new ConnectionSpec(Dialect.POSTGRESQL,"target",5432,"test","user","");
        try(MigrationService service=new MigrationService(gateway,new ObjectMapper(),args[0],100,10)){
            PlanRequest request=new PlanRequest(Dialect.POSTGRESQL,Dialect.POSTGRESQL,"public",null,source,"public",List.of("items"),true,target,MigrationOptions.defaults(),Map.of(),false);
            PlanView plan=service.create(request);
            service.execute(plan.id(),new ExecuteRequest(target,Map.of("items",Action.CREATE_AND_LOAD)));
            Thread.sleep(60_000);
        }
        System.exit(78);
    }
}

package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.fingress.migration.Model.*;

/** Non-interactive entry point: no web server, Spring context or connection pools. */
public final class MigrationCli {
    public record Endpoint(String jdbcUrl, String username, String passwordEnv, Long datasourceId, String schema) {}
    public record Catalog(String jdbcUrl, String username, String passwordEnv, String schema) {}
    public record Config(Endpoint source, Endpoint target, Catalog catalog, List<String> tables, Boolean includeData,
                         Dialect sourceDialect, Dialect targetDialect, String targetSchema, String input,
                         String existingTables, Boolean skipIncompatible, MigrationOptions options, Map<String,TableSelection> selections) {}
    private final DatabaseGateway db;
    private final ObjectMapper json = new ObjectMapper();
    private final Function<String,String> environment;
    private final PrintStream out;
    public MigrationCli(DatabaseGateway db, Function<String,String> environment, PrintStream out) {this.db=db;this.environment=environment;this.out=out;}
    public static int mainRun(String[] args) { return new MigrationCli(new DatabaseGateway(),System::getenv,System.out).run(args); }
    public int run(String[] args) {
        try { return execute(args); }
        catch (IllegalArgumentException e) { out.println("ERROR: "+e.getMessage());return 2; }
        catch (java.sql.SQLException e) { out.println("Database request failed (SQLState "+e.getSQLState()+", code "+e.getErrorCode()+"). Check connection and permissions.");return 1; }
        catch (Exception e) { out.println("Request failed. Check the configuration, input files and output directory. Sensitive details are omitted.");return 1; }
    }
    private int execute(String[] args) throws Exception {
        String command=args.length==0?"help":args[0];
        if (Set.of("help","--help","-h").contains(command)) { help();return 0; }
        if (!Set.of("init","datasources","schemas","tables","plan","convert","migrate","history","resume").contains(command)) throw new IllegalArgumentException("Unknown CLI command. Run: java -jar migration.jar cli help");
        Map<String,String> options=new HashMap<>();
        for(int i=1;i<args.length;i+=2) {
            if(i+1>=args.length || !Set.of("--config","--output","--side","--work-dir","--job").contains(args[i]) || options.put(args[i],args[i+1])!=null) throw new IllegalArgumentException("Use --config PATH, --output PATH or --side source|target once each");
        }
        if(command.equals("init")) {
            Path file=Path.of(options.getOrDefault("--output","migration.json"));
            Files.writeString(file,TEMPLATE,StandardOpenOption.CREATE_NEW);out.println("Created "+file.toAbsolutePath()+". Edit addresses, schemas and tables; supply passwords through environment variables.");return 0;
        }
        if(!options.containsKey("--config")) throw new IllegalArgumentException("--config PATH is required");
        Path configPath=Path.of(options.get("--config")).toAbsolutePath();
        if(Files.size(configPath)>1_000_000) throw new IllegalArgumentException("Configuration exceeds 1 MB");
        Config config;
        try { config=json.readValue(Files.readString(configPath),Config.class); } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalArgumentException("Invalid configuration JSON or unknown field. Use cli init for the template.");}
        if(config==null)throw new IllegalArgumentException("Configuration must be a JSON object");
        DatasourceCatalog catalog=catalog(config.catalog());
        MigrationOptions tuning=config.options()==null?MigrationOptions.defaults():config.options();
        if(Set.of("history","resume").contains(command)){
            if(!options.containsKey("--work-dir"))throw new IllegalArgumentException("--work-dir must identify a previous report folder's .work directory");
            try(MigrationService recovered=new MigrationService(db,json,options.get("--work-dir"),tuning.maxRows(),30)){
                if(command.equals("history")){out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(recovered.history()));return 0;}
                if(!options.containsKey("--job"))throw new IllegalArgumentException("--job is required");
                JobView resumed=recovered.resume(options.get("--job"),resolve(config.target(),catalog));
                while(Set.of("QUEUED","RUNNING").contains(resumed.state())){Thread.sleep(500);resumed=recovered.job(resumed.id());}
                out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(resumed));return resumed.state().equals("SUCCEEDED")?0:3;
            }
        }
        if(command.equals("datasources")) { out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(catalog.list()));return 0; }
        if(Set.of("schemas","tables").contains(command)) {
            String side=options.getOrDefault("--side","source");if(!Set.of("source","target").contains(side))throw new IllegalArgumentException("--side must be source or target");
            Endpoint endpoint=side.equals("source")?config.source():config.target();ConnectionSpec connection=resolve(endpoint,catalog);
            if(connection==null)throw new IllegalArgumentException("Configure the selected connection");
            out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(command.equals("schemas")?db.schemas(connection):db.tables(connection,require(endpoint.schema(),"Connection schema"))));return 0;
        }
        if(config.source()!=null && config.input()!=null) throw new IllegalArgumentException("Choose source connection or input SQL path, not both");
        if(command.equals("convert") && config.input()==null) throw new IllegalArgumentException("convert requires an input SQL file or folder");
        ConnectionSpec source=resolve(config.source(),catalog), target=resolve(config.target(),catalog);
        if(command.equals("migrate") && target==null)throw new IllegalArgumentException("migrate requires a target connection");
        Dialect sourceDialect=source==null?config.sourceDialect():source.dialect(), targetDialect=target==null?config.targetDialect():target.dialect();
        if(source!=null && config.sourceDialect()!=null && sourceDialect!=config.sourceDialect() || target!=null && config.targetDialect()!=null && targetDialect!=config.targetDialect())throw new IllegalArgumentException("Configured dialect differs from the connection URL");
        String targetSchema=target==null?config.targetSchema():config.target().schema();
        String sql=config.input()==null?null:readSql(configPath.getParent().resolve(config.input()).normalize());
        String existing=config.existingTables()==null?"SKIP":config.existingTables();
        if(!Set.of("SKIP","DML_ONLY").contains(existing)) throw new IllegalArgumentException("existingTables must be SKIP or DML_ONLY");
        Path root=Path.of(options.getOrDefault("--output","migration-output")).toAbsolutePath();Files.createDirectories(root);
        Path result=Files.createDirectory(root.resolve("migration-"+java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())+"-"+UUID.randomUUID().toString().substring(0,8)));
        out.println("Reports: "+result);
        Path work=Files.createDirectory(result.resolve(".work"));
        MigrationService service=new MigrationService(db,json,work.toString(),tuning.maxRows(),1);PlanView plan=null;
        try {
            plan=service.create(new PlanRequest(sourceDialect,targetDialect,targetSchema,sql,source,config.source()==null?null:config.source().schema(),config.tables(),!Boolean.FALSE.equals(config.includeData()),target,tuning,config.selections(),false));
            Map<String,Action> actions=new LinkedHashMap<>();boolean mismatch=false;
            for(TableReport table:plan.tables()) {
                Action action=switch(table.status()) {case NEW, UNCHECKED -> Action.CREATE_AND_LOAD;case MATCH -> Action.valueOf(existing);case MISMATCH -> Action.SKIP;};
                actions.put(table.table(),action);mismatch|=table.status()==Status.MISMATCH;
                out.println(table.table()+": "+table.status()+" -> "+action+" ("+table.rows()+" exported rows)");
            }
            writeJson(result.resolve("preflight.json"),plan);
            writeJson(result.resolve("actions.json"),actions);
            for(String issue:plan.issues())out.println("Issue: "+issue);
            try{service.validateDownload(plan.id(),actions);try(OutputStream zip=Files.newOutputStream(result.resolve("migration.zip"),StandardOpenOption.CREATE_NEW)){service.download(plan.id(),actions,zip);}}
            catch(IllegalArgumentException e){if(!command.equals("migrate"))throw e;out.println("SQL download unavailable: "+e.getMessage());}
            boolean blocked=!plan.issues().isEmpty() || mismatch&&!Boolean.TRUE.equals(config.skipIncompatible());
            if(!command.equals("migrate")) {out.println("Conversion/report complete. No target changes performed.");return blocked?2:0;}
            if(blocked || plan.orderedScript()) {out.println("Migration blocked. Review preflight.json and migration.zip; ordered lifecycle scripts are download-only.");return 2;}
            if(actions.values().stream().allMatch(a->a==Action.SKIP)) {out.println("No tables selected for migration; all actions are SKIP.");return 0;}
            JobView job=service.execute(plan.id(),new ExecuteRequest(target,actions));String state="";
            do {
                job=service.job(job.id());
                if(!job.state().equals(state)) {state=job.state();out.println("Migration: "+state);}
                if(Set.of("QUEUED","RUNNING").contains(state))Thread.sleep(200);
            }while(Set.of("QUEUED","RUNNING").contains(state));
            writeJson(result.resolve("execution.json"),job);
            if(Files.exists(result.resolve("migration.zip")))try(OutputStream zip=Files.newOutputStream(result.resolve("migration.zip"))) {service.download(plan.id(),actions,zip);}
            out.println(job.message());return job.state().equals("SUCCEEDED")?0:3;
        } finally {
            service.close();out.println("Durable plan/job data retained at "+work+". Use history/resume with --work-dir; credentials are not retained.");
        }
    }
    private void writeJson(Path file,Object value) throws IOException {Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
    private DatasourceCatalog catalog(Catalog config) {
        return new DatasourceCatalog(db,json,config==null?env("MIGRATION_CATALOG_URL"):require(config.jdbcUrl(),"Catalogue jdbcUrl"),config==null?env("MIGRATION_CATALOG_USERNAME"):require(config.username(),"Catalogue username"),config==null?env("MIGRATION_CATALOG_PASSWORD"):password(config.passwordEnv()),config==null?Optional.ofNullable(environment.apply("MIGRATION_CATALOG_SCHEMA")).orElse("fg_solutions"):config.schema()==null?"fg_solutions":config.schema());
    }
    private String env(String name) {return Optional.ofNullable(environment.apply(name)).orElse("");}
    private ConnectionSpec resolve(Endpoint endpoint,DatasourceCatalog catalog) throws Exception {
        if(endpoint==null)return null;
        if(endpoint.datasourceId()!=null) {
            if(endpoint.jdbcUrl()!=null || endpoint.username()!=null || endpoint.passwordEnv()!=null)throw new IllegalArgumentException("Choose datasourceId or manual JDBC credentials, not both");
            return catalog.get(endpoint.datasourceId()).connection();
        }
        return JdbcAddress.parse(require(endpoint.jdbcUrl(),"Connection jdbcUrl")).connection(require(endpoint.username(),"Connection username"),password(endpoint.passwordEnv()),endpoint.jdbcUrl());
    }
    private String password(String variable) {require(variable,"passwordEnv");String value=environment.apply(variable);if(value==null)throw new IllegalArgumentException("A required password environment variable is not set");return value;}
    private static String require(String value,String label) {if(value==null||value.isBlank())throw new IllegalArgumentException(label+" is required");return value;}
    private String readSql(Path input) throws IOException {
        List<Path> files;
        if(Files.isDirectory(input))try(var paths=Files.walk(input)) {files=paths.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(sql|ddl|dml|txt)")).sorted().toList();}
        else files=List.of(input);
        if(files.isEmpty())throw new IllegalArgumentException("No SQL files found");
        long size=0;for(Path file:files){size+=Files.size(file);if(size>10_000_000)throw new IllegalArgumentException("SQL input exceeds 10 MB");}
        StringBuilder sql=new StringBuilder();for(Path file:files){if(!sql.isEmpty())sql.append("\n;\n");sql.append(Files.readString(file).replaceFirst("^\\uFEFF",""));}
        return sql.toString();
    }
    private void help() {out.println("""
            Fingress SQL Migration CLI (Java 21)
            java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli COMMAND [options]
              init         --output migration.json       Create an editable configuration
              datasources  --config migration.json       List LCNC connections (no credentials)
              schemas      --config migration.json [--side source|target]
              tables       --config migration.json [--side source|target]
              plan         --config migration.json [--output reports]   Inspect/export only
              convert      --config migration.json [--output reports]   Convert SQL file/folder
              migrate      --config migration.json [--output reports]   Execute migration
              history      --config migration.json --work-dir PATH     Inspect saved jobs
              resume       --config migration.json --work-dir PATH --job ID   Resume a safely rolled-back job
            Passwords come from environment variables named in passwordEnv.
            migrate is explicit authorization to write; no interactive approvals.
            Existing tables default to SKIP; set existingTables=DML_ONLY for exact matches.
            Exit codes: 0 success, 1 connection/I/O failure, 2 invalid/blocked, 3 execution failed.
            No arguments starts the browser application. No web server starts in CLI mode.
            """);}
    static final String TEMPLATE="""
            {
              "source": {"jdbcUrl":"jdbc:oracle:thin:@//source-host:1521/SERVICE", "username":"SOURCE_USER", "passwordEnv":"SOURCE_DB_PASSWORD", "schema":"SOURCE_SCHEMA"},
              "target": {"jdbcUrl":"jdbc:postgresql://target-host:5432/database", "username":"TARGET_USER", "passwordEnv":"TARGET_DB_PASSWORD", "schema":"public"},
              "tables": ["CUSTOMERS", "ORDERS"],
              "includeData": true,
              "existingTables": "DML_ONLY",
              "skipIncompatible": false
            }
            """;
}

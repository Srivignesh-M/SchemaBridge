package com.fingress.migration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.fingress.migration.Model.*;

@Service
public class MigrationService implements AutoCloseable {
    static final class Plan {
        PlanView view; final List<Table> tables = new ArrayList<>(); final Map<String, Path> data = new LinkedHashMap<>();
        final Map<String, Table> targetTables = new HashMap<>(); final Map<String, Long> rows = new HashMap<>();
        final Map<String, Long> estimatedRows = new HashMap<>();
        final AtomicBoolean executed = new AtomicBoolean(); String targetIdentity; volatile String jobId; Path directory;
        ScriptConverter.Result script;
        String sourceInfo, targetInfo, sourceSchema, sourceTables;
        MigrationOptions options; boolean prepared=true;
        final Set<String> typed = new HashSet<>(); final Map<String,String> hashes = new LinkedHashMap<>();
    }
    static final class Job {
        String id = UUID.randomUUID().toString(), planId;
        volatile String state = "QUEUED", message = "Waiting";
        final List<TableResult> tables = new CopyOnWriteArrayList<>();
        final TransferProgress progress = new TransferProgress();
        MigrationOptions options;
        Map<String,Action> actions = Map.of();
        final Set<String> created = new HashSet<>(), committed = new HashSet<>(), finalized = new HashSet<>();
        final Map<String,String> validations = new HashMap<>();
        volatile boolean uncertain; int attempt=1;
        JobView view() { return new JobView(id,state,List.copyOf(tables),message,planId,progress.view(),!uncertain&&Set.of("FAILED","CANCELLED").contains(state),options); }
    }
    static final class Preparation {
        final String id=UUID.randomUUID().toString(); final TransferProgress progress=new TransferProgress();
        volatile String state="QUEUED",message="Waiting"; volatile PlanView result;
        PreparationView view(){return new PreparationView(id,state,message,progress.view(),result);}
    }
    private final Map<String,Preparation> preparations=new ConcurrentHashMap<>();
    private final ThreadLocal<TransferProgress> preparing=new ThreadLocal<>();
    private final java.util.concurrent.Semaphore planSlots;
    private final ExecutorService preparer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"migration-preparation");t.setDaemon(true);return t;});
    private final DatabaseGateway db;
    private final Path work;
    private final long maxRows;
    private final int maxPlans;
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> { Thread t = new Thread(r, "migration-worker"); t.setDaemon(true); return t; });
    private final ObjectMapper json;
    private final java.nio.channels.FileChannel lockChannel;
    private final java.nio.channels.FileLock workLock;
    private final ExecutorService canceller=new ThreadPoolExecutor(0,2,10,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"migration-cancel");t.setDaemon(true);return t;});
    public MigrationService(DatabaseGateway db, ObjectMapper json, @Value("${migration.work-dir:./work}") String work,
                            @Value("${migration.max-rows:2000000}") long maxRows, @Value("${migration.max-plans:30}") int maxPlans) throws IOException {
        this.db = db; this.json = json; this.work = Path.of(work).toAbsolutePath().normalize(); this.maxRows = maxRows; this.maxPlans = maxPlans;
        Files.createDirectories(this.work); planSlots=new java.util.concurrent.Semaphore(maxPlans);
        lockChannel=java.nio.channels.FileChannel.open(this.work.resolve(".owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        java.nio.channels.FileLock acquired;
        try{acquired=lockChannel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){acquired=null;}
        if(acquired==null){lockChannel.close();throw new IOException("This work directory is already open in another application. Close it before using history or resume here.");}
        workLock=acquired;
        try{restore();}catch(IOException|RuntimeException e){workLock.release();lockChannel.close();throw e;}
    }
    public PlanView create(PlanRequest request) throws Exception {
        synchronized(plans){
            cleanup();
            if(plans.size()+maxPlans-planSlots.availablePermits()>=maxPlans || !planSlots.tryAcquire())throw new IllegalArgumentException("Plan capacity reached. Delete unused plans.");
        }
        try { return createInternal(request); }
        finally { planSlots.release(); DatabaseGateway.configure(null); }
    }
    private PlanView createInternal(PlanRequest request) throws Exception {
        cleanup();
        if (plans.size() >= maxPlans) throw new IllegalArgumentException("Plan capacity reached. Delete unused plans or wait for their 30-day expiry");
        if (request.sourceDialect() == null || request.targetDialect() == null) throw new IllegalArgumentException("Choose source and target database types");
        if (request.targetSchema() == null || request.targetSchema().isBlank() || request.targetSchema().length() > 128) throw new IllegalArgumentException("Enter the target schema name");
        if (request.source() != null && request.sql() != null && !request.sql().isBlank()) throw new IllegalArgumentException("Choose a database source or SQL input, not both");
        String id = UUID.randomUUID().toString(); Plan plan = new Plan(); plan.directory = Files.createDirectory(work.resolve(id));
        plan.options=request.options()==null?MigrationOptions.defaults(maxRows):request.options();
        plan.prepared=!request.metadataOnly(); DatabaseGateway.configure(plan.options);
        plan.sourceInfo = connectionDescription(request.source(), "SQL input (database name not supplied)");
        plan.targetInfo = connectionDescription(request.target(), "Download only (database name not supplied)");
        plan.sourceSchema = request.sourceSchema() == null ? "SQL input: source qualifiers are mapped to the target schema" : request.sourceSchema();
        plan.sourceTables = request.tables() == null ? "" : String.join(", ", request.tables());
        List<String> issues = new ArrayList<>(), warnings = new ArrayList<>();
        warnings.add("Execution uses insert-only semantics. Existing primary/unique keys can reject rows; no rows are silently skipped or overwritten.");
        warnings.add("Run against a quiescent target: concurrent writers or schema changes during execution are not supported. Identity/sequence changes are not transactional.");
        if(request.source()!=null)warnings.add("Preparation stages a source snapshot locally. If extraction is interrupted or the source snapshot is lost, prepare again from the source; extraction cannot resume from the old snapshot.");
        if (request.targetDialect() == Dialect.ORACLE) warnings.add("Oracle DDL commits implicitly. Failed jobs can leave created objects and previously committed tables.");
        try {
            if (request.source() == null) fromSql(request, plan, issues);
            else fromDatabase(request, plan, issues);
            List<TableReport> reports = new ArrayList<>();
            if (request.target() != null) {
                if (request.target().dialect() != request.targetDialect()) throw new IllegalArgumentException("Target connection type differs from selected target dialect");
                try (Connection connection = db.connect(request.target())) {
                    db.requireSchema(connection, request.targetSchema());
                    for (Table table : plan.tables) {
                        String name = table.name().in(request.targetDialect());
                        try {
                            Table actual = db.inspect(connection, request.targetSchema(), name, request.targetDialect());
                            if (actual == null) reports.add(report(table, request.targetDialect(), Status.NEW, List.of(), plan, List.of(Action.CREATE_AND_LOAD, Action.SKIP)));
                            else {
                                plan.targetTables.put(name, actual);
                                List<String> diff = new ArrayList<>(Compatibility.differences(table, actual, request.targetDialect()));
                                if (request.targetDialect() == Dialect.ORACLE && table.columns().stream().anyMatch(Column::generated) && plan.rows.getOrDefault(name, 0L) > 0)
                                    diff.add("Oracle identity maintenance requires DDL; DML-only mode cannot load explicit identity values");
                                reports.add(report(table, request.targetDialect(), diff.isEmpty() ? Status.MATCH : Status.MISMATCH, diff, plan,
                                        diff.isEmpty() ? List.of(Action.DML_ONLY, Action.SKIP) : List.of(Action.SKIP)));
                            }
                        } catch (IllegalArgumentException e) {
                            reports.add(report(table, request.targetDialect(), Status.MISMATCH, List.of(e.getMessage()), plan, List.of(Action.SKIP)));
                        }
                    }
                    if(plan.script==null || !plan.script.ordered()) dependencies(plan, request, connection, issues);
                }
                plan.targetIdentity = identity(request.target());
            } else {
                for (Table table : plan.tables) reports.add(report(table, request.targetDialect(), Status.UNCHECKED, List.of("Target has not been inspected; download only"), plan, List.of(Action.CREATE_AND_LOAD, Action.SKIP)));
                if(plan.script==null || !plan.script.ordered()) dependencies(plan, request, null, issues);
            }
            if (plan.tables.isEmpty()) issues.add("No supported table definitions were found");
            if (request.sourceDialect() != request.targetDialect()) warnings.add("Cross-database character, collation, timezone and numeric semantics require validation with representative data before production use.");
            boolean ordered=plan.script!=null&&plan.script.ordered();
            if(ordered){warnings.removeIf(w->w.startsWith("Execution uses insert-only"));warnings.add("Ordered script conversion: ALTER, UPDATE, DELETE, MERGE and administrative statements retain their original order. Download the review script; per-table DML-only execution is unavailable for this lifecycle script.");}
            plan.view = new PlanView(id, request.sourceDialect(), request.targetDialect(), request.targetSchema(), List.copyOf(reports), List.copyOf(new LinkedHashSet<>(issues)), List.copyOf(warnings), request.target() != null, Instant.now().plusSeconds(2592000).toString(),ordered,plan.script==null?List.of():plan.script.statements(),plan.prepared,plan.options);
            try(var files=Files.list(plan.directory)){for(Path file:files.filter(Files::isRegularFile).toList())plan.hashes.put(file.getFileName().toString(),RowStore.hash(file));}
            persistPlan(plan); plans.put(id, plan); return plan.view;
        } catch (Exception error) { erase(plan.directory); throw error; }
    }
    private void fromSql(PlanRequest request, Plan plan, List<String> issues) throws IOException {
        if (request.sql() == null || request.sql().length() > 10_000_000) throw new IllegalArgumentException("Provide SQL up to 10 MB");
        plan.script=new ScriptConverter(request.sourceDialect(),request.targetDialect(),request.targetSchema()).convert(request.sql());
        if(plan.script.ordered()){
            issues.addAll(plan.script.issues());plan.tables.addAll(plan.script.tables());return;
        }
        Parsed parsed = new SqlParser(request.sourceDialect(), request.targetDialect(),request.targetSchema()).parse(request.sql());
        issues.addAll(plan.script.issues());if(plan.script.issues().isEmpty())issues.addAll(parsed.issues()); plan.tables.addAll(parsed.tables());
        long total = 0;
        for (Table table : plan.tables) {
            String name = table.name().in(request.targetDialect()); Path path = plan.directory.resolve("data-" + plan.data.size() + ".jsonl"); long rows = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                for (Insert insert : parsed.inserts()) if (insert.table().in(request.targetDialect()).equals(name)) {
                    for (List<Model.Value> values : insert.rows()) {
                        if (++total > plan.options.maxRows()) throw new IllegalArgumentException("SQL input exceeds configured row limit");
                        writer.write(JsonLines.encode(SqlWriter.insert(table, insert.columns(), values, request.targetSchema(), request.targetDialect()))); writer.newLine(); rows++;
                    }
                }
            }
            plan.data.put(name, path); plan.rows.put(name, rows);
        }
    }
    private void fromDatabase(PlanRequest request, Plan plan, List<String> issues) throws Exception {
        if (request.source().dialect() != request.sourceDialect()) throw new IllegalArgumentException("Source connection type differs from selected source dialect");
        if (request.tables() == null || request.tables().isEmpty() || request.tables().size() > 100) throw new IllegalArgumentException("Select between 1 and 100 source tables");
        if (request.sourceSchema() == null || request.sourceSchema().isBlank()) throw new IllegalArgumentException("Select the source schema");
        try (Connection connection = db.connect(request.source())) {
            db.snapshot(connection, request.sourceDialect()); long remaining = plan.options.maxRows();
            TransferProgress progress=preparing.get();if(progress==null)progress=new TransferProgress();
            progress.phase="EXTRACTING";
            Map<String,TableSelection> selections=request.selections()==null?Map.of():request.selections();
            if(!new HashSet<>(request.tables()).containsAll(selections.keySet()))throw new IllegalArgumentException("Selection references an unselected table");
            for (String selected : new LinkedHashSet<>(request.tables())) {
                try {
                    Table source = db.inspect(connection, request.sourceSchema(), selected, request.sourceDialect());
                    if (source == null) throw new IllegalArgumentException("Source table not found: " + selected);
                    progress.check();progress.table=selected;
                    Table filterTable=source;TableSelection selection=selections.get(selected);
                    TableProjection.where(filterTable,selection,request.sourceDialect(),new ArrayList<>());
                    source=TableProjection.select(source,selection,request.sourceDialect());
                    if(selection!=null&&selection.rename()!=null && !source.columns().stream().map(c->c.name().in(request.sourceDialect())).toList().containsAll(selection.rename().keySet()))throw new IllegalArgumentException("Unknown renamed column");
                    List<Column> converted = new ArrayList<>();
                    for (Column c : source.columns()) {
                        if (request.sourceDialect() == Dialect.POSTGRESQL && request.targetDialect() == Dialect.ORACLE && c.defaultValue() != null && c.defaultValue().sql().equals("''"))
                            throw new IllegalArgumentException("Empty-string default would become NULL in Oracle");
                        converted.add(new Column(TableProjection.rename(c.name(),selection,request.sourceDialect()), c.generated() ? c.type().identityTarget(request.targetDialect()) : c.type().target(request.targetDialect()), c.nullable(), c.defaultValue(), c.generated(), c.always()));
                    }
                    if(converted.stream().map(c->c.name().in(request.targetDialect())).distinct().count()!=converted.size())throw new IllegalArgumentException("Mapped column names collide");
                    Table table = new Table(source.name(), converted, TableProjection.keys(source,selection,selections,request.sourceDialect()), source.warnings());
                    String name = table.name().in(request.targetDialect());
                    if (plan.data.containsKey(name)) throw new IllegalArgumentException("Duplicate mapped table name: " + name);
                    Path path = plan.directory.resolve("data-" + plan.data.size() + ".rows");
                    long rows = request.includeData() && plan.prepared ? db.exportTyped(connection,source,filterTable,selection,request.sourceSchema(),request.sourceDialect(),request.targetDialect(),path,remaining,plan.options,progress) : 0;
                    if (!request.includeData() || !plan.prepared) Files.createFile(path);
                    if(!plan.prepared&&(selection==null||selection.filters()==null||selection.filters().isEmpty())){
                        Long estimate=db.estimateRows(connection,request.sourceSchema(),selected,request.sourceDialect());
                        if(estimate!=null)plan.estimatedRows.put(name,estimate);
                    }
                    plan.typed.add(name);
                    remaining -= rows; plan.tables.add(table); plan.data.put(name, path); plan.rows.put(name, rows);
                } catch (IllegalArgumentException e) { issues.add(selected + ": " + e.getMessage()); }
            }
            connection.rollback();
        }
    }
    private TableReport report(Table table, Dialect dialect, Status status, List<String> diff, Plan plan, List<Action> actions) {
        String name=table.name().in(dialect);
        return new TableReport(name, status, List.copyOf(diff), table.warnings(), plan.rows.getOrDefault(name, 0L), actions, plan.estimatedRows.get(name));
    }
    private void dependencies(Plan plan, PlanRequest request, Connection connection, List<String> issues) throws SQLException {
        for (Table table : plan.tables) for (Key key : table.keys()) if (key.kind().equals("FOREIGN KEY")) {
            String reference = key.reference().in(request.targetDialect());
            Table parent = plan.tables.stream().filter(t -> t.name().in(request.targetDialect()).equals(reference)).findFirst().orElse(null);
            if (parent == null && connection != null) {
                try { parent = db.inspect(connection, request.targetSchema(), reference, request.targetDialect()); }
                catch (IllegalArgumentException e) { issues.add("Dependency " + reference + ": " + e.getMessage()); }
            }
            if (parent == null) issues.add(table.name().in(request.targetDialect()) + " references " + reference + "; include its DDL/table in the plan");
        }
    }
    public PlanView view(String id) { return plan(id).view; }
    private Plan plan(String id) {
        Plan plan = plans.get(id);
        if (plan == null) throw new IllegalArgumentException("Plan expired or does not exist; analyse again");
        return plan;
    }
    public String preview(String id) throws IOException {
        Plan plan = plan(id); StringBuilder text = new StringBuilder();
        if(plan.view.orderedScript())return orderedPreview(plan);
        for (Table table : plan.tables) { text.append(SqlWriter.create(table, plan.view.targetSchema(), plan.view.targetDialect())).append(";\n\n"); if (text.length() > 100_000) break; }
        text.append("-- Data statements and post-load constraints are included in the download.\n"); return text.toString();
    }
    public void download(String id, Map<String, Action> actions, OutputStream output) throws IOException {
        download(id, actions, output, downloadName(id));
    }
    void download(String id, Map<String, Action> actions, OutputStream output, String folder) throws IOException {
        validateDownload(id,actions);
        Plan plan = plan(id); Map<String, Action> chosen = plan.view.orderedScript()?Map.of():validateActions(plan, actions);
        try (ZipOutputStream zip = new ZipOutputStream(output) {
            @Override public void putNextEntry(ZipEntry entry) throws IOException { super.putNextEntry(new ZipEntry(folder + "/" + entry.getName())); }
        }) {
            entry(zip, "migration-info.txt", migrationInfo(plan, chosen).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entry(zip, "report.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan.view));
            entry(zip, "actions.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(chosen));
            if(plan.view.orderedScript()){
                entry(zip,"converted-script.review.sql",orderedPreview(plan).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                entry(zip,"README.txt","Ordered review draft. Fix ERROR/UNSUPPORTED statements and review semantic warnings before executing externally. Original statement order is retained; table action selections do not filter this script. Automatic execution is disabled for lifecycle scripts.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));return;
            }
            if (!plan.view.issues().isEmpty()) { entry(zip, "BLOCKED.txt", "No executable scripts generated. Resolve every issue and analyse again.".getBytes(java.nio.charset.StandardCharsets.UTF_8)); return; }
            zip.putNextEntry(new ZipEntry("01-tables.sql"));
            for (Table table : plan.tables) if (chosen.get(table.name().in(plan.view.targetDialect())) == Action.CREATE_AND_LOAD) write(zip, SqlWriter.create(table, plan.view.targetSchema(), plan.view.targetDialect()) + ";\n\n");
            zip.closeEntry(); zip.putNextEntry(new ZipEntry("02-data.sql"));
            for (Table table : ordered(plan, chosen)) {
                String name = table.name().in(plan.view.targetDialect()); if (chosen.get(name) == Action.SKIP) continue;
                writeData(plan,table,zip);
            }
            zip.closeEntry(); zip.putNextEntry(new ZipEntry("03-post-load.sql"));
            for (Table table : plan.tables) {
                Action action = chosen.get(table.name().in(plan.view.targetDialect()));
                if (action == Action.SKIP) continue;
                for (String sql : identitySql(table, plan)) write(zip, sql + ";\n");
                if (action == Action.CREATE_AND_LOAD) for (String sql : SqlWriter.afterData(table, plan.view.targetSchema(), plan.view.targetDialect())) write(zip, sql + ";\n");
            }
            zip.closeEntry();
            Set<String> filenames = new HashSet<>();
            for (Table table : ordered(plan, chosen)) {
                String name = table.name().in(plan.view.targetDialect());
                if (chosen.get(name) == Action.SKIP) continue;
                String base = safeFilename(name), candidate = base;
                for (int suffix = 2; !filenames.add(candidate.toLowerCase(Locale.ROOT)); suffix++) candidate = base + "_" + suffix;
                zip.putNextEntry(new ZipEntry("tables/" + candidate + ".sql"));
                write(zip, "-- DDL: table definition\n");
                if (chosen.get(name) == Action.CREATE_AND_LOAD) write(zip, SqlWriter.create(table, plan.view.targetSchema(), plan.view.targetDialect()) + ";\n");
                else write(zip, "-- DML-only selection: existing table retained; no DDL generated.\n");
                write(zip, "\n-- DML: table data\n");
                writeData(plan,table,zip);
                write(zip, "\n-- Post-load constraints, indexes and identity maintenance: see 03-post-load.sql.\n");
                zip.closeEntry();
            }
            entry(zip, "README.txt", ("Run scripts in numeric order. Insert-only policy: duplicate keys fail. Review report.json and actions.json first.\n"
                    + "01-tables.sql, 02-data.sql and 03-post-load.sql are the complete execution set. tables/ contains one file per table with commented DDL and DML sections: do not execute both sets.\n"
                    + "For per-table files: run all DDL sections first, DML sections in the order listed in migration-info.txt, then 03-post-load.sql for identities, foreign keys and indexes.\n"
                    + "DDL and sequence changes may not roll back. Use a test database first. Credentials are not included.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public String downloadName(String id) {
        Plan plan = plan(id);
        return plan.view.sourceDialect() + "_to_" + plan.view.targetDialect() + "_" + java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss-SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
    }
    private static String safeFilename(String name) {
        String safe = name.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").replaceAll("[. ]+$", "_");
        if (safe.length() > 100) safe = safe.substring(0, 100);
        return safe.isBlank() || safe.equals(".") || safe.equals("..") ? "table" : safe;
    }
    private static String connectionDescription(ConnectionSpec connection, String absent) {
        return connection == null ? absent : connection.host() + ":" + connection.port() + "/" + connection.database();
    }
    private String migrationInfo(Plan plan, Map<String, Action> chosen) {
        StringBuilder info = new StringBuilder("SQL migration information\nGenerated at (UTC): ").append(Instant.now())
                .append("\nPlan: ").append(plan.view.id()).append("\nSource dialect: ").append(plan.view.sourceDialect())
                .append("\nSource database: ").append(plan.sourceInfo).append("\nSource schema: ").append(plan.sourceSchema)
                .append("\nSelected source tables: ").append(plan.sourceTables.isBlank() ? "See converted table list below" : plan.sourceTables)
                .append("\nTarget dialect: ").append(plan.view.targetDialect()).append("\nTarget database: ").append(plan.targetInfo)
                .append("\nTarget schema: ").append(plan.view.targetSchema()).append("\nTarget inspected: ").append(plan.view.targetChecked())
                .append("\n\nDownloading does not create tables. Actions below describe planned changes, not completed changes.\n");
        for (TableReport table : plan.view.tables()) info.append("Table: ").append(table.table()).append(" | Target status: ").append(table.status())
                .append(" | Action: ").append(plan.view.orderedScript() ? "Ordered review script" : chosen.get(table.table()))
                .append(" | Exported rows: ").append(plan.view.orderedScript() ? "See statement report" : table.rows()).append('\n')
                .append("  Findings: ").append(String.join("; ", table.differences())).append(" ").append(String.join("; ", table.warnings())).append('\n');
        if (!plan.view.orderedScript() && plan.view.issues().isEmpty()) info.append("\nDML load order: ").append(String.join(", ", ordered(plan, chosen).stream().filter(t -> chosen.get(t.name().in(plan.view.targetDialect())) != Action.SKIP).map(t -> t.name().in(plan.view.targetDialect())).toList())).append('\n');
        Job job = plan.jobId == null ? null : jobs.get(plan.jobId);
        info.append("\nExecution status: ").append(job == null ? "Not run by this application" : job.state).append('\n');
        if (job != null) {
            info.append(job.message).append('\n');
            for (TableResult result : job.view().tables()) info.append(result.table()).append(" | ").append(result.action()).append(" | ").append(result.status()).append(" | Committed rows: ").append(result.rows()).append(" | ").append(result.message()).append('\n');
        }
        info.append("\nIssues:\n"); plan.view.issues().forEach(issue -> info.append("- ").append(issue).append('\n'));
        info.append("\nWarnings:\n"); plan.view.warnings().forEach(warning -> info.append("- ").append(warning).append('\n'));
        return info.append("\nCredentials are excluded. Read README.txt for execution order. Review report.json for statement-level details.\n").toString();
    }
    public synchronized JobView execute(String id, ExecuteRequest request) {
        Plan plan = plan(id);
        if(!plan.prepared)throw new IllegalArgumentException("Prepare data before execution");
        if(plan.view.orderedScript())throw new IllegalArgumentException("This is an ordered lifecycle script. Download and review it; the table-cloning executor cannot safely reorder its mutations");
        if (!plan.view.targetChecked()) throw new IllegalArgumentException("Inspect the target connection before execution");
        if (!plan.view.issues().isEmpty()) throw new IllegalArgumentException("Resolve plan issues before execution");
        if (request.target() == null || !Objects.equals(identity(request.target()), plan.targetIdentity)) throw new IllegalArgumentException("Target connection differs from the inspected target; analyse again");
        Map<String, Action> actions = validateActions(plan, request.actions()); ordered(plan, actions);
        if (!plan.executed.compareAndSet(false, true)) throw new IllegalArgumentException("This plan has already been submitted. Analyse again to avoid replaying committed data");
        Job job = new Job(); job.planId=id;job.actions=actions;job.options=plan.options; job.progress.totalRows=plan.rows.entrySet().stream().filter(e->actions.get(e.getKey())!=Action.SKIP).mapToLong(Map.Entry::getValue).sum();
        jobs.put(job.id, job); plan.jobId = job.id; checkpoint(plan,job);
        try { executor.execute(() -> run(plan, request.target(), actions, job)); }
        catch (RejectedExecutionException e) { jobs.remove(job.id); plan.jobId=null;plan.executed.set(false);persistPlanUnchecked(plan); throw new IllegalArgumentException("Worker queue full; try again later"); }
        return job.view();
    }
    private Map<String, Action> validateActions(Plan plan, Map<String, Action> actions) {
        if (actions == null) throw new IllegalArgumentException("Choose an action for each table");
        Map<String, Action> result = new LinkedHashMap<>();
        for (TableReport table : plan.view.tables()) {
            Action action = actions.get(table.table());
            if (action == null || !table.allowedActions().contains(action)) throw new IllegalArgumentException("Invalid action for " + table.table());
            result.put(table.table(), action);
        }
        if (actions.size() != result.size()) throw new IllegalArgumentException("Unknown table action");
        for (Table table : plan.tables) {
            String name = table.name().in(plan.view.targetDialect()); if (result.get(name) == Action.SKIP) continue;
            for (Key key : table.keys()) if (key.kind().equals("FOREIGN KEY")) {
                String reference = key.reference().in(plan.view.targetDialect());
                if (result.get(reference) == Action.SKIP && !plan.targetTables.containsKey(reference)) throw new IllegalArgumentException("Cannot skip missing dependency " + reference + " while loading " + name);
            }
        }
        return Map.copyOf(result);
    }
    private List<Table> ordered(Plan plan, Map<String, Action> actions) {
        List<Table> result = new ArrayList<>(), pending = new ArrayList<>(plan.tables); Set<String> complete = new HashSet<>();
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (Table table : List.copyOf(pending)) {
                String name = table.name().in(plan.view.targetDialect());
                boolean ready = actions.get(name) == Action.SKIP || table.keys().stream().filter(k -> k.reference() != null).allMatch(k -> {
                    String ref = k.reference().in(plan.view.targetDialect()); return complete.contains(ref) || actions.get(ref) == null || actions.get(ref) == Action.SKIP;
                });
                if (ready) { result.add(table); pending.remove(table); complete.add(name); progress = true; }
            }
            if (!progress) {
                // New tables have no foreign keys until post-load; they may safely break a cycle.
                Table newlyCreated = pending.stream().filter(t -> actions.get(t.name().in(plan.view.targetDialect())) == Action.CREATE_AND_LOAD).findFirst().orElse(null);
                if (newlyCreated == null) throw new IllegalArgumentException("Existing tables have cyclic/self-referencing foreign keys; DML-only loading requires an explicit strategy");
                pending.remove(newlyCreated); result.add(newlyCreated); complete.add(newlyCreated.name().in(plan.view.targetDialect()));
            }
        }
        return result;
    }
    private void run(Plan plan, ConnectionSpec target, Map<String,Action> actions, Job job) {
        job.state="RUNNING";job.message="Rechecking target and staged data";job.progress.phase="CHECKING";
        DatabaseGateway.configure(plan.options);DatabaseGateway.configureBackground(true);String active=null;boolean risky=false;String outcome="FAILED";
        try {
            checkpoint(plan,job);verifyFiles(plan);
            try(Connection connection=db.connect(target)) {
                db.requireSchema(connection,plan.view.targetSchema());
                for(Table table:plan.tables){
                    String name=table.name().in(plan.view.targetDialect());if(actions.get(name)==Action.SKIP)continue;
                    Table current=db.inspect(connection,plan.view.targetSchema(),name,plan.view.targetDialect());
                    Table previous=plan.targetTables.get(name);
                    if(job.created.contains(name))previous=job.finalized.contains(name)?table:new Table(table.name(),table.columns(),table.keys().stream().filter(k->!Set.of("FOREIGN KEY","INDEX").contains(k.kind())).toList(),table.warnings());
                    if((current==null)!=(previous==null)||current!=null&&!Compatibility.differences(previous,current,plan.view.targetDialect()).isEmpty())throw new IllegalArgumentException("Target structure changed; re-analyse or reconcile before resuming");
                }
                connection.setAutoCommit(false);
                try(Statement statement=connection.createStatement()) {
                    statement.setQueryTimeout(plan.options.queryTimeoutSeconds());
                    try {
                        for(Table table:plan.tables){
                            active=table.name().in(plan.view.targetDialect());job.progress.table=active;job.progress.check();
                            if(actions.get(active)==Action.CREATE_AND_LOAD&&!job.created.contains(active)){
                                job.progress.phase="CREATING";checkpoint(plan,job);risky=true;
                                job.progress.statement=statement;statement.execute(SqlWriter.create(table,plan.view.targetSchema(),plan.view.targetDialect()));connection.commit();
                                job.created.add(active);checkpoint(plan,job);risky=false;
                            }
                        }
                        for(Table table:ordered(plan,actions)){
                            active=table.name().in(plan.view.targetDialect());if(actions.get(active)==Action.SKIP||job.committed.contains(active))continue;
                            job.progress.check();job.progress.table=active;job.progress.phase="LOADING";job.message="Loading "+active;checkpoint(plan,job);
                            String validation=loadTable(connection,plan,table,job);
                            job.progress.check();risky=true;connection.commit();
                            job.committed.add(active);job.validations.put(active,validation);job.progress.rowsCommitted+=plan.rows.getOrDefault(active,0L);
                            checkpoint(plan,job);risky=false;
                        }
                        for(Table table:plan.tables){
                            active=table.name().in(plan.view.targetDialect());if(actions.get(active)==Action.SKIP||job.finalized.contains(active))continue;
                            job.progress.check();job.progress.table=active;job.progress.phase="FINALIZING";checkpoint(plan,job);
                            risky=plan.view.targetDialect()==Dialect.ORACLE||!identitySql(table,plan).isEmpty();job.progress.statement=statement;
                            for(String sql:identitySql(table,plan))statement.execute(sql);
                            if(actions.get(active)==Action.CREATE_AND_LOAD)for(String sql:SqlWriter.afterData(table,plan.view.targetSchema(),plan.view.targetDialect()))statement.execute(sql);
                            risky=true;connection.commit();job.finalized.add(active);checkpoint(plan,job);risky=false;
                        }
                    }catch(Exception failure){try{connection.rollback();}catch(SQLException rollback){risky=true;}throw failure;}
                }
            }
            outcome="SUCCEEDED";job.message="Completed. Validation and committed-row counts are included for every selected table.";
        }catch(Exception failure){
            job.uncertain=risky;
            outcome=risky?"RECOVERY_REQUIRED":job.progress.cancel||failure instanceof CancellationException?"CANCELLED":"FAILED";
            job.message=risky?"A DDL or commit outcome is uncertain. Automatic replay is disabled; inspect the target and reconcile.":failure instanceof SQLException sql?"Database operation failed (SQLState "+sql.getSQLState()+", code "+sql.getErrorCode()+"). Current table rolled back; earlier commits remain.":failure instanceof IllegalArgumentException?failure.getMessage():outcome.equals("CANCELLED")?"Cancelled. Current transaction rolled back; earlier committed tables are retained.":"Operation failed. Check local disk, artifacts and database access. Earlier committed tables remain.";
        }finally{
          synchronized(this){
            job.progress.statement=null;job.progress.finished=System.currentTimeMillis();job.state=outcome;job.progress.phase=outcome;job.tables.clear();
            for(Table table:plan.tables){String name=table.name().in(plan.view.targetDialect());Action action=actions.get(name);
                String status=action==Action.SKIP?"SKIPPED":job.finalized.contains(name)?"SUCCEEDED":job.committed.contains(name)||job.created.contains(name)?"PARTIAL_OR_FAILED":"NOT_RUN";
                job.tables.add(new TableResult(name,action,status,job.committed.contains(name)?plan.rows.getOrDefault(name,0L):0,
                    job.validations.getOrDefault(name,"")+(job.finalized.contains(name)?" Complete.":job.committed.contains(name)?" Data committed; post-load work pending.":" "+job.message)));
            }
            try{checkpoint(plan,job);}catch(Exception failure){job.state="RECOVERY_REQUIRED";job.uncertain=true;job.message="Could not persist final status. Inspect the target before retrying.";}
            DatabaseGateway.configure(null);DatabaseGateway.configureBackground(false);
          }
        }
    }
    private String loadTable(Connection connection,Plan plan,Table table,Job job)throws Exception {
        String name=table.name().in(plan.view.targetDialect());Path file=plan.data.get(name);long rows=0;
        DataFingerprint before=null,expected=null;long beforeCount=0;
        if(plan.options.validateData()){
            job.progress.phase="VALIDATING_BASELINE";
            if(plan.typed.contains(name)){before=DataFingerprint.target(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),plan.options,job.progress);expected=DataFingerprint.staged(file,table,job.progress);}
            else beforeCount=count(connection,plan,table,job.progress);
        }
        job.progress.phase="LOADING";
        if(plan.typed.contains(name)){
            if(plan.options.useCopy()&&plan.view.targetDialect()==Dialect.POSTGRESQL&&connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                {try(Statement timeout=connection.createStatement()){timeout.execute("SET LOCAL statement_timeout = '"+plan.options.queryTimeoutSeconds()+"s'");}rows=CopyLoader.load(connection,file,table,plan.view.targetSchema(),job.progress);}
            else try(PreparedStatement statement=connection.prepareStatement(RowStore.insertSql(table,plan.view.targetSchema(),plan.view.targetDialect()));BufferedReader reader=Files.newBufferedReader(file)){
                statement.setQueryTimeout(plan.options.queryTimeoutSeconds());job.progress.statement=statement;List<Closeable> streams=new ArrayList<>();int batch=0;long bytes=0;
                try{String line;while((line=reader.readLine())!=null){job.progress.check();RowStore.Cell[] row=RowStore.JSON.readValue(line,RowStore.Cell[].class);
                    RowStore.bind(statement,row,table,plan.view.targetDialect(),file.getParent(),streams);statement.addBatch();batch++;rows++;bytes+=line.length()*2L;
                    if(batch>=plan.options.batchRows()||bytes>=plan.options.batchBytes()||!streams.isEmpty()){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;batch=0;bytes=0;closeStreams(streams);}
                }if(batch>0){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;}}
                finally{closeStreams(streams);}
            }
        }else try(Statement statement=connection.createStatement();BufferedReader reader=Files.newBufferedReader(file)){
            statement.setQueryTimeout(plan.options.queryTimeoutSeconds());job.progress.statement=statement;String line;int batch=0;long bytes=0;
            while((line=reader.readLine())!=null){job.progress.check();statement.addBatch(JsonLines.decode(line));batch++;rows++;bytes+=line.length()*2L;
                if(batch>=plan.options.batchRows()||bytes>=plan.options.batchBytes()){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;batch=0;bytes=0;}}
            if(batch>0){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;}
        }
        if(rows!=plan.rows.getOrDefault(name,0L))throw new IOException("Staged row count changed");
        if(!plan.options.validateData())return "Validation disabled by user.";
        job.progress.phase="VALIDATING";
        if(plan.typed.contains(name)){
            DataFingerprint after=DataFingerprint.target(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),plan.options,job.progress);
            if(!after.equals(before.plus(expected)))throw new IllegalArgumentException("Target content validation failed; transaction will roll back");
            return "Validated row-count delta and order-independent SHA-256 aggregate (probabilistic content check).";
        }
        if(count(connection,plan,table,job.progress)!=beforeCount+rows)throw new IllegalArgumentException("Target row-count validation failed; transaction will roll back");
        return "Validated row-count delta; SQL-expression content is not fingerprinted.";
    }
    private static void closeStreams(List<Closeable> streams)throws IOException{IOException failure=null;for(Closeable stream:streams)try{stream.close();}catch(IOException e){failure=e;}streams.clear();if(failure!=null)throw failure;}
    private long count(Connection connection,Plan plan,Table table,TransferProgress progress)throws SQLException{
        try(Statement s=connection.createStatement()){progress.statement=s;s.setQueryTimeout(plan.options.queryTimeoutSeconds());try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM "+qualified(plan.view.targetSchema(),table.name(),plan.view.targetDialect()))){rs.next();return rs.getLong(1);}}
    }
    private List<String> identitySql(Table table, Plan plan) {
        if (plan.rows.getOrDefault(table.name().in(plan.view.targetDialect()), 0L) == 0) return List.of();
        List<String> statements = new ArrayList<>();
        for (Column column : table.columns()) if (column.generated()) {
            String qualified = qualified(plan.view.targetSchema(), table.name(), plan.view.targetDialect());
            if (plan.view.targetDialect() == Dialect.ORACLE) statements.add("ALTER TABLE " + qualified + " MODIFY " + column.name().sql(Dialect.ORACLE) + " GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE)");
            else statements.add("SELECT setval(pg_get_serial_sequence('" + qualified.replace("'", "''") + "','" + column.name().in(Dialect.POSTGRESQL).replace("'", "''")
                    + "'), GREATEST(COALESCE(MAX(" + column.name().sql(Dialect.POSTGRESQL) + "),1),1), MAX(" + column.name().sql(Dialect.POSTGRESQL) + ") IS NOT NULL) FROM " + qualified);
        }
        return statements;
    }
    public synchronized JobView job(String id) { Job job = jobs.get(id); if (job == null) throw new IllegalArgumentException("Job not found"); return job.view(); }
    public synchronized List<JobView> history(){return jobs.values().stream().map(Job::view).sorted(Comparator.comparing(JobView::id)).toList();}
    public JobView cancel(String id){Job job=jobs.get(id);if(job==null)throw new IllegalArgumentException("Job not found");requestCancel(job.progress);return job.view();}
    public synchronized JobView resume(String id,ConnectionSpec target){
        Job job=jobs.get(id);if(job==null||!job.view().resumable())throw new IllegalArgumentException("This job cannot be automatically resumed. Reconcile uncertain target changes first.");
        Plan plan=plan(job.planId);if(target==null||!identity(target).equals(plan.targetIdentity))throw new IllegalArgumentException("Enter the original target connection to resume");
        job.progress.cancel=false;job.progress.finished=0;job.progress.started=System.currentTimeMillis();job.progress.rowsSent=job.progress.rowsCommitted;job.attempt++;job.state="QUEUED";checkpoint(plan,job);
        try{executor.execute(()->run(plan,target,job.actions,job));}catch(RejectedExecutionException e){job.state="FAILED";checkpoint(plan,job);throw new IllegalArgumentException("Worker queue full");}return job.view();
    }
    public void validateDownload(String id,Map<String,Action> actions){
        Plan plan=plan(id);if(!plan.prepared)throw new IllegalArgumentException("Prepare data before downloading SQL");
        if(!plan.view.orderedScript())validateActions(plan,actions);
        if(plan.view.targetDialect()==Dialect.ORACLE)for(String name:plan.typed){
            if(actions.get(name)==Action.SKIP)continue;
            try(BufferedReader reader=Files.newBufferedReader(plan.data.get(name))){String line;while((line=reader.readLine())!=null)for(RowStore.Cell cell:RowStore.JSON.readValue(line,RowStore.Cell[].class)){
                if(cell.file()!=null && Files.size(RowStore.sidecar(plan.directory,cell.file()))>(cell.type().equals("BINARY")?1000:2000)
                    || cell.type().equals("BINARY")&&cell.value()!=null&&Base64.getDecoder().decode(cell.value()).length>1000
                    || cell.type().equals("TEXT")&&cell.value()!=null&&cell.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2000)
                    throw new IllegalArgumentException("Large Oracle LOBs support direct database migration, but not executable SQL-literal downloads. Run the plan to transfer them.");
            }}catch(IOException e){throw new IllegalArgumentException("Staged data is unavailable");}
        }
    }
    public synchronized PreparationView prepare(PlanRequest request){
        if(preparations.size()>=8)preparations.entrySet().removeIf(e->Set.of("READY","FAILED","CANCELLED").contains(e.getValue().state));
        if(preparations.size()>=8)throw new IllegalArgumentException("Preparation queue is full");
        Preparation preparation=new Preparation();preparations.put(preparation.id,preparation);
        preparer.execute(()->{
            DatabaseGateway.configureBackground(true);
            preparing.set(preparation.progress);preparation.state="PREPARING";preparation.message="Reading source and preparing an immutable export";
            try{preparation.progress.check();preparation.result=create(request);preparation.state="READY";preparation.message="Prepared for review";}
            catch(Exception failure){preparation.state=preparation.progress.cancel?"CANCELLED":"FAILED";preparation.message=failure instanceof IllegalArgumentException?failure.getMessage():"Preparation failed. Check source access, limits and disk space.";}
            finally{preparation.progress.finished=System.currentTimeMillis();preparing.remove();DatabaseGateway.configureBackground(false);}
        });return preparation.view();
    }
    public PreparationView preparation(String id){Preparation p=preparations.get(id);if(p==null)throw new IllegalArgumentException("Preparation not found");return p.view();}
    public PreparationView cancelPreparation(String id){Preparation p=preparations.get(id);if(p==null)throw new IllegalArgumentException("Preparation not found");requestCancel(p.progress);return p.view();}
    private void writeData(Plan plan,Table table,OutputStream output)throws IOException{
        String name=table.name().in(plan.view.targetDialect());
        if(!plan.prepared)throw new IllegalArgumentException("Prepare data before downloading SQL");
        if(plan.typed.contains(name)){RowStore.sqlRows(plan.data.get(name),table,plan.view.targetSchema(),plan.view.targetDialect(),output);return;}
        try(BufferedReader reader=Files.newBufferedReader(plan.data.get(name))){String line;while((line=reader.readLine())!=null)write(output,JsonLines.decode(line)+";\n");}
    }
    record PlanManifest(int version,PlanView view,List<Table> tables,Map<String,String> data,Map<String,Table> targetTables,
            Map<String,Long> rows,Set<String> typed,Map<String,String> hashes,MigrationOptions options,String targetIdentity,
            String sourceInfo,String targetInfo,String sourceSchema,String sourceTables,String jobId,boolean executed) {}
    record JobManifest(int version,JobView view,Map<String,Action> actions,Set<String> created,Set<String> committed,Set<String> finalized,
                       Map<String,String> validations,boolean uncertain,int attempt) {}
    private void atomicJson(Path file,Object value)throws IOException{
        Path temp=file.resolveSibling(file.getFileName()+".tmp");byte[] bytes=json.writeValueAsBytes(value);
        try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic job storage is unavailable",e);}
    }
    private void persistPlan(Plan plan)throws IOException{
        Map<String,String> data=new LinkedHashMap<>();plan.data.forEach((key,path)->data.put(key,path.getFileName().toString()));
        atomicJson(plan.directory.resolve("plan.json"),new PlanManifest(1,plan.view,plan.tables,data,plan.targetTables,plan.rows,plan.typed,plan.hashes,plan.options,plan.targetIdentity,
                plan.sourceInfo,plan.targetInfo,plan.sourceSchema,plan.sourceTables,plan.jobId,plan.executed.get()));
    }
    private void persistPlanUnchecked(Plan plan){try{persistPlan(plan);}catch(IOException e){throw new IllegalStateException("Could not save plan state",e);}}
    private void checkpoint(Plan plan,Job job){
        try{
            // Persist submission before writes. A crash between these files fails closed on restore.
            persistPlan(plan);
            atomicJson(plan.directory.resolve("job.json"),new JobManifest(1,job.view(),job.actions,job.created,job.committed,job.finalized,job.validations,job.uncertain,job.attempt));
        }catch(IOException e){throw new IllegalStateException("Could not persist migration state",e);}
    }
    private void verifyFiles(Plan plan)throws IOException{
        for(var entry:plan.hashes.entrySet()){
            Path path=artifact(plan.directory,entry.getKey());
            if(!Files.isRegularFile(path)||!RowStore.hash(path).equals(entry.getValue()))throw new IllegalArgumentException("Staged data is missing or changed. Re-extract before starting any writes.");
        }
    }
    private Path artifact(Path directory,String filename)throws IOException{
        if(filename==null||!filename.matches("(?:data-\\d+\\.(?:rows|jsonl)|lob-[a-f0-9-]{36})"))throw new IOException("Invalid artifact name");
        Path path=directory.resolve(filename);if(Files.isSymbolicLink(path))throw new IOException("Invalid artifact link");return path;
    }
    private void restore()throws IOException{
        try(var directories=Files.list(work)){
            for(Path directory:directories.filter(p->Files.isDirectory(p)&&!Files.isSymbolicLink(p)&&p.getFileName().toString().matches("[a-f0-9-]{36}")).toList()){
                Path manifest=directory.resolve("plan.json");if(!Files.exists(manifest))continue;
                try{
                    PlanManifest saved=json.readValue(Files.readAllBytes(manifest),PlanManifest.class);
                    if(saved.version()!=1||!saved.view().id().equals(directory.getFileName().toString()))continue;
                    Plan plan=new Plan();plan.directory=directory;plan.view=saved.view();plan.prepared=plan.view.prepared();plan.options=saved.options()==null?plan.view.options():saved.options();plan.tables.addAll(saved.tables());
                    for(var entry:saved.data().entrySet())plan.data.put(entry.getKey(),artifact(directory,entry.getValue()));
                    plan.targetTables.putAll(saved.targetTables());plan.rows.putAll(saved.rows());plan.typed.addAll(saved.typed());plan.hashes.putAll(saved.hashes());
                    plan.targetIdentity=saved.targetIdentity();plan.sourceInfo=saved.sourceInfo();plan.targetInfo=saved.targetInfo();plan.sourceSchema=saved.sourceSchema();plan.sourceTables=saved.sourceTables();plan.jobId=saved.jobId();plan.executed.set(saved.executed());
                    plans.put(plan.view.id(),plan);
                    if(plan.jobId!=null){
                        Job job=new Job();job.id=plan.jobId;job.planId=plan.view.id();job.options=plan.options;
                        try{
                            JobManifest snapshot=json.readValue(Files.readAllBytes(directory.resolve("job.json")),JobManifest.class);
                            if(snapshot.version()!=1||!snapshot.view().id().equals(job.id)||!snapshot.view().planId().equals(job.planId))throw new IOException("Job identity changed");
                            job.actions=snapshot.actions();job.created.addAll(snapshot.created());job.committed.addAll(snapshot.committed());job.finalized.addAll(snapshot.finalized());job.validations.putAll(snapshot.validations());job.tables.addAll(snapshot.view().tables());job.attempt=snapshot.attempt();job.state=snapshot.view().state();job.message=snapshot.view().message();job.uncertain=snapshot.uncertain();if(snapshot.view().options()!=null)job.options=snapshot.view().options();
                            job.progress.finished=System.currentTimeMillis();job.progress.started=job.progress.finished-snapshot.view().progress().elapsedSeconds()*1000;job.progress.bytes=snapshot.view().progress().bytes();job.progress.totalRows=snapshot.view().progress().totalRows();job.progress.rowsCommitted=snapshot.view().progress().rowsCommitted();job.progress.rowsSent=job.progress.rowsCommitted;
                            if(Set.of("RUNNING","QUEUED").contains(job.state)){job.uncertain=true;job.state="RECOVERY_REQUIRED";job.message="Application stopped before final status was saved. Reconcile the target before replaying uncertain work.";}
                        }catch(Exception e){job.state="RECOVERY_REQUIRED";job.uncertain=true;job.message="Job journal is missing or damaged. Inspect the target; automatic replay is disabled.";}
                        job.progress.phase=job.state;jobs.put(job.id,job);
                    }
                }catch(Exception error){System.err.println("A saved plan could not be restored: "+directory.getFileName()+". Files retained for inspection.");}
            }
        }
    }
    private static String identity(ConnectionSpec spec) { return spec.dialect() + "|" + spec.host() + "|" + spec.port() + "|" + spec.database() + "|" + Objects.toString(spec.jdbcUrl(), ""); }
    private String orderedPreview(Plan plan){
        StringBuilder sql=new StringBuilder("-- ORDERED CONVERSION REVIEW DRAFT — not an automatically executable migration.\n-- Resolve errors and review warnings before running. Statements keep source order.\n\n");
        for(StatementReport report:plan.view.statements()){
            sql.append("-- Statement ").append(report.number()).append(" [").append(report.status()).append("] ").append(report.kind()).append('\n');
            for(String message:report.messages())sql.append("-- ").append(message.replace("\r"," ").replace("\n"," ")).append('\n');
            if(!report.convertedSql().isBlank())sql.append(report.convertedSql()).append(";\n");
            else sql.append("-- MANUAL CORRECTION REQUIRED: no executable SQL emitted for this statement.\n");
            sql.append('\n');
        }return sql.toString();
    }
    private void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }
    private void write(OutputStream output, String text) throws IOException { output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public synchronized void delete(String id) throws IOException {
        Plan plan = plans.get(id); if (plan == null) throw new IllegalArgumentException("Plan not found");
        if (active(plan)) throw new IllegalArgumentException("Wait for this job before deleting its plan");
        plans.remove(id); if (plan.jobId != null) jobs.remove(plan.jobId); erase(plan.directory);
    }
    private void cleanup() throws IOException {
        for (Plan plan : List.copyOf(plans.values())) if (Instant.parse(plan.view.expiresAt()).isBefore(Instant.now()) && !active(plan) && !plan.executed.get()) { plans.remove(plan.view.id()); if (plan.jobId != null) jobs.remove(plan.jobId); erase(plan.directory); }
    }
    private boolean active(Plan plan) { Job job = plan.jobId == null ? null : jobs.get(plan.jobId); return job != null && (job.state.equals("RUNNING") || job.state.equals("QUEUED")); }
    private void erase(Path directory) throws IOException {
        if (directory == null || !directory.normalize().getParent().equals(work)) throw new IllegalArgumentException("Invalid artifact directory");
        try (var files = Files.walk(directory)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private void requestCancel(TransferProgress progress){
        progress.cancel=true;Statement statement=progress.statement;
        if(statement!=null)try{canceller.execute(()->{try{statement.cancel();}catch(SQLException ignored){}});}catch(RejectedExecutionException ignored){}
    }
    private void releaseLock(){try{if(workLock.isValid())workLock.release();lockChannel.close();}catch(IOException ignored){}}
    @Override @PreDestroy public void close() {
        preparations.values().forEach(p->requestCancel(p.progress));jobs.values().stream().filter(j->Set.of("QUEUED","RUNNING").contains(j.state)).forEach(j->requestCancel(j.progress));
        preparer.shutdown(); executor.shutdown();
        try{executor.awaitTermination(5,TimeUnit.SECONDS);preparer.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        canceller.shutdown();
        if(executor.isTerminated()&&preparer.isTerminated())releaseLock();
        else {Thread release=new Thread(()->{try{while(!executor.awaitTermination(1,TimeUnit.SECONDS)||!preparer.awaitTermination(1,TimeUnit.SECONDS)){}releaseLock();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"migration-lock-release");release.setDaemon(true);release.start();}
    }
}

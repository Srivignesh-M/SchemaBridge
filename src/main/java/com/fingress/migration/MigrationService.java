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
        final Map<String,String> excluded = new LinkedHashMap<>();
        /** Per-table settings when the plan is automatic, keyed by target table name. */
        final Map<String,MigrationOptions> tableOptions = new LinkedHashMap<>(); boolean auto; int reconnects;
        MigrationOptions optionsFor(String table) { return tableOptions.getOrDefault(table, options); }
        /** Per target table: staged size, and the source table and selection it is read from. */
        final Map<String,Long> stagedBytes = new HashMap<>(); final Map<String,String> sources = new HashMap<>();
        final Map<String,TableSelection> selections = new HashMap<>(); String sourceIdentity; Boolean includeData;
        /** Resuming an interrupted preparation: its key, when it started, and the staged tables reused from it. */
        String resumeKey, startedAt; List<SourceExtraction.Outcome> resumed = List.of(); int partialSaved;
    }
    /** Leaves a table out of the plan, discarding anything already staged for it. */
    private static void exclude(Plan plan, String name, String reason, Dialect target) throws IOException {
        plan.excluded.put(name, reason);
        plan.tables.removeIf(table -> table.name().in(target).equals(name));
        Path data = plan.data.remove(name); if (data != null) SourceExtraction.discard(plan.directory, data.getFileName().toString());
        plan.rows.remove(name); plan.typed.remove(name); plan.estimatedRows.remove(name); plan.tableOptions.remove(name);
        plan.stagedBytes.remove(name); plan.sources.remove(name); plan.selections.remove(name);
    }
    static final class Job {
        String id = UUID.randomUUID().toString(), planId;
        volatile String state = "QUEUED", message = "Waiting";
        final List<TableResult> tables = new CopyOnWriteArrayList<>();
        final TransferProgress progress = new TransferProgress();
        MigrationOptions options;
        Map<String,Action> actions = Map.of();
        final Set<String> created = new HashSet<>(), committed = new HashSet<>(), finalized = new HashSet<>();
        final Map<String,ValidationReport> validations = new HashMap<>();
        final Map<String,DataFingerprint> createdFingerprints = new HashMap<>();
        final Map<String,String> targetObjectIds = new HashMap<>();
        volatile Revert revert;
        volatile boolean uncertain; int attempt=1;
        /** A direct transfer streams from the source; its connection (with password) lives only in memory. */
        boolean direct; volatile ConnectionSpec source; final Map<String,Long> loadedRows=new ConcurrentHashMap<>();
        JobView view() { return new JobView(id,state,List.copyOf(tables),message,planId,progress.view(),revert==null&&!uncertain&&Set.of("FAILED","CANCELLED").contains(state),options,revert==null?null:revert.view(),direct); }
    }
    static final class Revert {
        volatile String state="QUEUED",message="Waiting to revert";
        volatile boolean running;
        volatile boolean rowsRemoved;
        final Set<String> dropped=ConcurrentHashMap.newKeySet();
        List<RevertTable> tables=List.of();
        final TransferProgress progress=new TransferProgress();
        RevertView view(){return new RevertView(running&&!Set.of("QUEUED","RUNNING").contains(state)?"RUNNING":state,message,tables.stream().map(t->new RevertTable(t.table(),t.action(),t.rows(),
                dropped.contains(t.table())?"DROPPED":rowsRemoved?"ROWS_REMOVED":state.equals("RECOVERY_REQUIRED")?"UNCERTAIN":"PENDING")).toList(),progress.view());}
    }
    private record RevertTicket(String jobId,String targetIdentity,long expires) {}
    private final Map<String,RevertTicket> revertTickets=new ConcurrentHashMap<>();
    static final class Preparation {
        final String id=UUID.randomUUID().toString(); final TransferProgress progress=new TransferProgress();
        volatile String state="QUEUED",message="Waiting"; volatile PlanView result;
        PreparationView view(){return new PreparationView(id,state,message,progress.view(),result);}
    }
    private final Map<String,Preparation> preparations=new ConcurrentHashMap<>();
    /** Plan folders being written by a running preparation; never resumed into, listed as interrupted or deleted. */
    private final Set<String> activePreparations=ConcurrentHashMap.newKeySet();
    private final ThreadLocal<TransferProgress> preparing=new ThreadLocal<>();
    private final java.util.concurrent.Semaphore planSlots;
    private final ExecutorService preparer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"migration-preparation");t.setDaemon(true);return t;});
    private final DatabaseGateway db;
    private final Path work;
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
        // maxRows is retained for callers; plans without explicit options are tuned automatically (AutoTuning).
        this.db = db; this.json = json; this.work = Path.of(work).toAbsolutePath().normalize(); this.maxPlans = maxPlans;
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
        Plan plan = new Plan(); plan.prepared = !request.metadataOnly();
        // Preparing data again with the same source, tables and selections continues an interrupted preparation.
        plan.resumeKey = plan.prepared && request.source() != null ? resumeKey(request) : null;
        Path resumable = plan.resumeKey == null ? null : findResumable(plan.resumeKey);
        String id;
        if (resumable != null) {
            PartialManifest saved = readPartial(resumable);
            id = resumable.getFileName().toString(); plan.directory = resumable; plan.startedAt = saved.startedAt(); plan.resumed = reusable(resumable, saved); plan.partialSaved = plan.resumed.size();
        } else { id = UUID.randomUUID().toString(); plan.directory = Files.createDirectory(work.resolve(id)); plan.startedAt = Instant.now().toString(); }
        activePreparations.add(id);
        try { return createInternal(request, plan, id); }
        finally { activePreparations.remove(id); }
    }
    private PlanView createInternal(PlanRequest request, Plan plan, String id) throws Exception {
        plan.auto=request.options()==null;
        plan.options=plan.auto?AutoTuning.global(work,request.targetDialect()):request.options();
        DatabaseGateway.configure(plan.options);
        plan.sourceIdentity = request.source() == null ? null : identity(request.source());
        plan.includeData = request.includeData();
        plan.sourceInfo = connectionDescription(request.source(), "SQL input (database name not supplied)");
        plan.targetInfo = connectionDescription(request.target(), "Download only (database name not supplied)");
        plan.sourceSchema = request.sourceSchema() == null ? "SQL input: source qualifiers are mapped to the target schema" : request.sourceSchema();
        plan.sourceTables = request.tables() == null ? "" : String.join(", ", request.tables());
        List<String> issues = new ArrayList<>(), warnings = new ArrayList<>();
        warnings.add("Execution uses insert-only semantics. Existing primary/unique keys can reject rows; no rows are silently skipped or overwritten.");
        warnings.add("Run against a quiescent target: concurrent writers or schema changes during execution are not supported. Identity/sequence changes are not transactional.");
        if(request.source()!=null)warnings.add("Preparation stages a source snapshot locally. A dropped source connection is retried automatically; if preparation stops, preparing again with the same selection continues from the tables already staged.");
        if (request.targetDialect() == Dialect.ORACLE) warnings.add("Oracle DDL commits implicitly. Failed jobs can leave created objects and previously committed tables.");
        try {
            if (request.source() == null) fromSql(request, plan, issues);
            else fromDatabase(request, plan, issues);
            List<TableReport> reports = new ArrayList<>();
            if (request.target() != null) {
                if (request.target().dialect() != request.targetDialect()) throw new IllegalArgumentException("Target connection type differs from selected target dialect");
                TransferProgress progress = preparing.get() == null ? new TransferProgress() : preparing.get();
                try (Connection connection = db.connect(request.target())) {
                    db.requireSchema(connection, request.targetSchema());
                    if (request.source() != null) excludeMissingParents(plan, request, connection);
                    progress.tables("COMPARING_TARGET", plan.tables.size());
                    for (Table table : plan.tables) {
                        String name = table.name().in(request.targetDialect());
                        progress.check(); progress.table = name;
                        try {
                            Table actual = db.inspect(connection, request.targetSchema(), name, request.targetDialect());
                            if (actual == null) reports.add(report(table, request.targetDialect(), Status.NEW, List.of(), plan, List.of(Action.CREATE_AND_LOAD, Action.SKIP)));
                            else {
                                plan.targetTables.put(name, actual);
                                List<ColumnDifference> columnComparison = new ArrayList<>(Compatibility.structuredDifferences(table, actual, request.targetDialect()));
                                List<String> diff = new ArrayList<>(Compatibility.differences(table, actual, request.targetDialect()));
                                if (request.targetDialect() == Dialect.ORACLE && table.columns().stream().anyMatch(Column::generated) && plan.rows.getOrDefault(name, 0L) > 0) {
                                    diff.add("Oracle identity maintenance requires DDL; DML-only mode cannot load explicit identity values");
                                    columnComparison.add(new ColumnDifference(null, "ORACLE_IDENTITY_DML_BLOCKED", null, null,
                                            "Oracle identity maintenance requires DDL; choose Create + load into a new table, or remove identity columns from the DML-only selection."));
                                }
                                reports.add(report(table, request.targetDialect(), diff.isEmpty() ? Status.MATCH : Status.MISMATCH, diff, plan,
                                        diff.isEmpty() ? List.of(Action.DML_ONLY, Action.SKIP) : List.of(Action.SKIP), columnComparison));
                            }
                        } catch (IllegalArgumentException e) {
                            reports.add(report(table, request.targetDialect(), Status.MISMATCH, List.of(e.getMessage()), plan, List.of(Action.SKIP)));
                        }
                        progress.tablesDone++;
                    }
                    if(plan.script==null || !plan.script.ordered()) dependencies(plan, request, connection, issues);
                }
                plan.targetIdentity = identity(request.target());
            } else {
                if (request.source() != null) excludeMissingParents(plan, request, null);
                for (Table table : plan.tables) reports.add(report(table, request.targetDialect(), Status.UNCHECKED, List.of("Target has not been inspected; download only"), plan, List.of(Action.CREATE_AND_LOAD, Action.SKIP)));
                if(plan.script==null || !plan.script.ordered()) dependencies(plan, request, null, issues);
            }
            if (plan.tables.isEmpty()) issues.add(plan.excluded.isEmpty() ? "No supported table definitions were found" : "None of the selected tables can be migrated; see the excluded tables");
            if (request.sourceDialect() != request.targetDialect()) warnings.add("Cross-database character, collation, timezone and numeric semantics require validation with representative data before production use.");
            if (plan.auto) plan.options = AutoTuning.withReadTimeout(plan.options, plan.tableOptions.values());
            if (plan.reconnects > 0) warnings.add("The source connection was lost " + plan.reconnects + " time" + (plan.reconnects == 1 ? "" : "s") + " and re-established. Tables read after a reconnect come from a later moment in the source than earlier tables; prepare again if the source changed meanwhile.");
            if (!plan.resumed.isEmpty()) warnings.add("Continued an interrupted preparation started " + plan.startedAt.substring(0, 16).replace('T', ' ') + " UTC: " + plan.resumed.size() + " table" + (plan.resumed.size() == 1 ? " was" : "s were")
                    + " reused as staged then, so they reflect the source at that time. Delete the interrupted preparation in Storage and prepare again if the source changed since.");
            boolean ordered=plan.script!=null&&plan.script.ordered();
            if(ordered){warnings.removeIf(w->w.startsWith("Execution uses insert-only"));warnings.add("Ordered script conversion: ALTER, UPDATE, DELETE, MERGE and administrative statements retain their original order. Download the review script; per-table DML-only execution is unavailable for this lifecycle script.");}
            plan.view = new PlanView(id, request.sourceDialect(), request.targetDialect(), request.targetSchema(), List.copyOf(reports), List.copyOf(new LinkedHashSet<>(issues)), List.copyOf(warnings), request.target() != null, Instant.now().plusSeconds(2592000).toString(),ordered,plan.script==null?List.of():plan.script.statements(),plan.prepared,plan.options,
                    plan.excluded.entrySet().stream().map(e -> new ExcludedTable(e.getKey(), e.getValue())).toList(), plan.auto, plan.tableOptions);
            // Staged data files only: bookkeeping such as partial.json is not data and is removed below.
            try(var files=Files.list(plan.directory)){for(Path file:files.filter(f->Files.isRegularFile(f)&&ARTIFACT_NAME.matcher(f.getFileName().toString()).matches()).toList())plan.hashes.put(file.getFileName().toString(),RowStore.hash(file));}
            persistPlan(plan); plans.put(id, plan); Files.deleteIfExists(plan.directory.resolve(PARTIAL)); return plan.view;
        } catch (Exception | OutOfMemoryError error) {
            // Staged tables survive a failed preparation (not a cancelled one) so the next attempt can continue.
            TransferProgress progress = preparing.get();
            boolean cancelled = error instanceof CancellationException || progress != null && progress.cancelled();
            if (plan.prepared && plan.partialSaved > 0 && !cancelled && readPartial(plan.directory) != null) {
                if (progress != null) progress.note = "The " + plan.partialSaved + " table" + (plan.partialSaved == 1 ? "" : "s") + " staged so far " + (plan.partialSaved == 1 ? "was" : "were")
                        + " kept: prepare data again with the same selection to continue from there.";
            } else erase(plan.directory);
            throw error;
        }
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
        if (request.tables() == null || request.tables().isEmpty()) throw new IllegalArgumentException("Select at least one source table");
        if (request.sourceSchema() == null || request.sourceSchema().isBlank()) throw new IllegalArgumentException("Select the source schema");
        TransferProgress progress = preparing.get(); if (progress == null) progress = new TransferProgress();
        List<String> tables = List.copyOf(new LinkedHashSet<>(request.tables()));
        Map<String,TableSelection> selections = request.selections() == null ? Map.of() : request.selections();
        if (!tables.containsAll(selections.keySet())) throw new IllegalArgumentException("Selection references an unselected table");
        progress.tables(plan.prepared ? "EXTRACTING" : "READING_DEFINITIONS", tables.size());
        // Tables staged by an interrupted attempt with the same selection are reused as they are.
        List<SourceExtraction.Outcome> outcomes = new ArrayList<>(plan.resumed);
        progress.tablesDone = outcomes.size();
        java.util.concurrent.atomic.AtomicLong budget = new java.util.concurrent.atomic.AtomicLong(plan.options.maxRows() - outcomes.stream().mapToLong(SourceExtraction.Outcome::rows).sum());
        SourceExtraction extraction = new SourceExtraction(db, request, plan.directory, plan.options, plan.auto, plan.prepared, progress, tables, selections, budget);
        List<SourceExtraction.Outcome> staged = new ArrayList<>(outcomes); long[] lastSave = {System.currentTimeMillis()};
        try {
            outcomes.addAll(extraction.run(outcomes.stream().map(SourceExtraction.Outcome::index).collect(java.util.stream.Collectors.toSet()), outcome -> {
                if (outcome.excluded() != null || !plan.prepared) return;
                staged.add(outcome);
                // Saved at most every two seconds, and at once after a large table; the final state is saved below.
                if (System.currentTimeMillis() - lastSave[0] >= 2000 || outcome.stagedBytes() > 50_000_000) { savePartial(plan, tables.size(), staged, extraction.reconnects()); lastSave[0] = System.currentTimeMillis(); }
            }));
        } catch (DatabaseGateway.RowLimitExceeded e) {
            throw new IllegalArgumentException("The selected tables contain more than " + String.format("%,d", plan.options.maxRows()) + " rows in total, the row limit for this plan. Select fewer tables or rows.");
        } finally {
            plan.reconnects += extraction.reconnects();
            if (plan.prepared && !staged.isEmpty() && !progress.cancelled()) try { savePartial(plan, tables.size(), staged, plan.reconnects); } catch (IOException ignored) {}
        }
        outcomes.sort(Comparator.comparingInt(SourceExtraction.Outcome::index));
        for (SourceExtraction.Outcome outcome : outcomes) {
            if (outcome.excluded() != null) { plan.excluded.put(outcome.selected(), outcome.excluded()); continue; }
            String name = outcome.name();
            if (plan.data.containsKey(name)) { SourceExtraction.discard(plan.directory, outcome.index()); plan.excluded.put(outcome.selected(), "Duplicate mapped table name: " + name); continue; }
            plan.typed.add(name); plan.tables.add(outcome.table()); plan.data.put(name, plan.directory.resolve(outcome.dataFile())); plan.rows.put(name, outcome.rows());
            plan.tableOptions.put(name, outcome.options()); plan.stagedBytes.put(name, outcome.stagedBytes()); plan.sources.put(name, outcome.selected());
            TableSelection selection = selections.get(outcome.selected()); if (selection != null) plan.selections.put(name, selection);
            if (outcome.estimatedRows() != null) plan.estimatedRows.put(name, outcome.estimatedRows());
        }
    }
    static final String PARTIAL = "partial.json";
    /** An interrupted preparation: the tables staged so far, kept so preparing again with the same selection continues it. */
    record PartialManifest(int version, String key, String startedAt, String updatedAt, String sourceInfo, String targetInfo, String sourceSchema,
                           int tablesTotal, int reconnects, List<SourceExtraction.Outcome> outcomes) {}
    private void savePartial(Plan plan, int total, List<SourceExtraction.Outcome> staged, int reconnects) throws IOException {
        atomicJson(plan.directory.resolve(PARTIAL), new PartialManifest(1, plan.resumeKey, plan.startedAt, Instant.now().toString(), plan.sourceInfo, plan.targetInfo, plan.sourceSchema, total, reconnects, List.copyOf(staged)));
        plan.partialSaved = staged.size();
    }
    /** An interrupted preparation in this folder, or null. A folder with a finished plan is not one. */
    private PartialManifest readPartial(Path directory) {
        Path file = directory.resolve(PARTIAL);
        if (!Files.isRegularFile(file) || Files.exists(directory.resolve("plan.json"))) return null;
        try { PartialManifest saved = json.readValue(Files.readAllBytes(file), PartialManifest.class); return saved.version() == 1 && saved.outcomes() != null ? saved : null; }
        catch (Exception e) { return null; }
    }
    private List<Path> workFolders() throws IOException {
        try (var folders = Files.list(work)) { return folders.filter(p -> Files.isDirectory(p) && !Files.isSymbolicLink(p) && p.getFileName().toString().matches("[a-f0-9-]{36}")).toList(); }
    }
    /** The newest interrupted preparation with the same source, tables and selections that is not running. */
    private Path findResumable(String key) throws IOException {
        Path best = null; Instant newest = Instant.MIN;
        for (Path folder : workFolders()) {
            PartialManifest saved = readPartial(folder);
            if (saved == null || !key.equals(saved.key()) || activePreparations.contains(folder.getFileName().toString())) continue;
            Instant updated = Instant.parse(saved.updatedAt());
            if (updated.isAfter(newest)) { newest = updated; best = folder; }
        }
        return best;
    }
    /** Staged tables whose files are still exactly as written; anything else is removed and read again. */
    private List<SourceExtraction.Outcome> reusable(Path directory, PartialManifest saved) throws IOException {
        Map<String,long[]> lobs = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("lob-") && name.length() == 40) { long[] counts = lobs.computeIfAbsent(name.substring(4, 12), k -> new long[2]); counts[0]++; counts[1] += Files.size(file); }
            }
        }
        List<SourceExtraction.Outcome> kept = new ArrayList<>();
        for (SourceExtraction.Outcome outcome : saved.outcomes()) {
            if (outcome.excluded() != null) continue;
            Path rows = directory.resolve(SourceExtraction.dataFile(outcome.index()));
            long[] counts = lobs.getOrDefault(SourceExtraction.sidecarPrefix(rows), new long[2]);
            if (Files.isRegularFile(rows) && !Files.isSymbolicLink(rows) && Files.size(rows) == outcome.dataBytes() && counts[0] == outcome.sidecars() && counts[1] == outcome.sidecarBytes()
                    && outcome.hashes() != null && outcome.hashes().equals(SourceExtraction.artifactHashes(directory, rows))) kept.add(outcome);
            else SourceExtraction.discard(directory, outcome.index());
        }
        return kept;
    }
    private static final ObjectMapper CANONICAL = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).build();
    record ResumeKey(Dialect sourceDialect, Dialect targetDialect, String source, String sourceSchema, List<String> tables, boolean includeData,
                     MigrationOptions options, Map<String,TableSelection> selections) {}
    /** Identifies what a preparation stages: the same source, tables, selections and options give the same key. */
    static String resumeKey(PlanRequest request) throws IOException {
        ResumeKey key = new ResumeKey(request.sourceDialect(), request.targetDialect(), identity(request.source()) + "|" + request.source().username(), request.sourceSchema(),
                List.copyOf(new LinkedHashSet<>(request.tables())), request.includeData(), request.options(), request.selections() == null ? Map.of() : new TreeMap<>(request.selections()));
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(key))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private TableReport report(Table table, Dialect dialect, Status status, List<String> diff, Plan plan, List<Action> actions) {
        return report(table, dialect, status, diff, plan, actions, List.of());
    }
    private TableReport report(Table table, Dialect dialect, Status status, List<String> diff, Plan plan, List<Action> actions, List<ColumnDifference> columnComparison) {
        String name=table.name().in(dialect);
        List<String> dependsOn = table.keys().stream().filter(k -> k.kind().equals("FOREIGN KEY")).map(k -> k.reference().in(dialect)).distinct().toList();
        return new TableReport(name, status, List.copyOf(diff), table.warnings(), plan.rows.getOrDefault(name, 0L), actions, plan.estimatedRows.get(name), columnComparison, dependsOn);
    }
    /** Excludes tables whose foreign-key parent is neither planned nor already in the target, repeating
     * until stable because excluding a parent can strand its own children. Database sources only. */
    private void excludeMissingParents(Plan plan, PlanRequest request, Connection connection) throws Exception {
        Dialect target = request.targetDialect(); Map<String, Boolean> inTarget = new HashMap<>();
        boolean changed;
        do {
            changed = false;
            for (Table table : List.copyOf(plan.tables)) {
                String name = table.name().in(target);
                for (Key key : table.keys()) {
                    if (!key.kind().equals("FOREIGN KEY")) continue;
                    String parent = key.reference().in(target);
                    if (plan.tables.stream().anyMatch(t -> t.name().in(target).equals(parent))) continue;
                    if (connection != null) {
                        if (!inTarget.containsKey(parent)) {
                            boolean exists;
                            // A parent the inspector cannot describe still exists, so the foreign key can be created.
                            try { exists = db.inspect(connection, request.targetSchema(), parent, target) != null; } catch (IllegalArgumentException e) { exists = true; }
                            inTarget.put(parent, exists);
                        }
                        if (inTarget.get(parent)) continue;
                    }
                    exclude(plan, name, plan.excluded.containsKey(parent) ? "References " + parent + ", which cannot be migrated"
                            : "References " + parent + ", which is neither selected nor in the target schema", target);
                    changed = true; break;
                }
            }
        } while (changed);
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
    List<Table> planTables(String id) { return List.copyOf(plan(id).tables); }
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
                for (String sql : identitySql(table, plan, null)) write(zip, sql + ";\n");
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
        if (!plan.view.excluded().isEmpty()) { info.append("\nExcluded tables (not in this package):\n"); plan.view.excluded().forEach(t -> info.append("- ").append(t.table()).append(": ").append(t.reason()).append('\n')); }
        info.append("\nWarnings:\n"); plan.view.warnings().forEach(warning -> info.append("- ").append(warning).append('\n'));
        return info.append("\nCredentials are excluded. Read README.txt for execution order. Review report.json for statement-level details.\n").toString();
    }
    public synchronized JobView execute(String id, ExecuteRequest request) {
        Plan plan = plan(id);
        if(!plan.prepared&&!request.direct())throw new IllegalArgumentException("Prepare data before execution, or choose the direct transfer");
        if(plan.view.orderedScript())throw new IllegalArgumentException("This is an ordered lifecycle script. Download and review it; the table-cloning executor cannot safely reorder its mutations");
        if (!plan.view.targetChecked()) throw new IllegalArgumentException("Inspect the target connection before execution");
        if (!plan.view.issues().isEmpty()) throw new IllegalArgumentException("Resolve plan issues before execution");
        int excluded = plan.view.excluded().size();
        if (excluded > 0 && !request.skipExcluded()) throw new IllegalArgumentException(excluded + " selected table" + (excluded == 1 ? "" : "s") + " cannot be migrated. Review the excluded tables and confirm skipping them to migrate the rest.");
        if (request.target() == null || !Objects.equals(identity(request.target()), plan.targetIdentity)) throw new IllegalArgumentException("Target connection differs from the inspected target; analyse again");
        Map<String, Action> actions = validateActions(plan, request.actions()); ordered(plan, actions);
        if (request.direct()) checkDirect(plan, request.source(), actions);
        if (!plan.executed.compareAndSet(false, true)) throw new IllegalArgumentException("This plan has already been submitted. Analyse again to avoid replaying committed data");
        Job job = new Job(); job.planId=id;job.actions=actions;job.options=plan.options;job.direct=request.direct();job.source=request.source();
        job.progress.totalRows=(job.direct?plan.estimatedRows:plan.rows).entrySet().stream().filter(e->actions.get(e.getKey())!=null&&actions.get(e.getKey())!=Action.SKIP).mapToLong(Map.Entry::getValue).sum();
        jobs.put(job.id, job); plan.jobId = job.id; checkpoint(plan,job);
        try { executor.execute(() -> run(plan, request.target(), actions, job)); }
        catch (RejectedExecutionException e) { jobs.remove(job.id); plan.jobId=null;plan.executed.set(false);persistPlanUnchecked(plan); throw new IllegalArgumentException("Worker queue full; try again later"); }
        return job.view();
    }
    /** A direct transfer reads the source again at run time: PostgreSQL to PostgreSQL, the analysed source, new tables
     * only (no staged rows to revert inserts into existing tables by) and no row filters (COPY takes no parameters). */
    private void checkDirect(Plan plan, ConnectionSpec source, Map<String, Action> actions) {
        if (plan.includeData == null) throw new IllegalArgumentException("Analyse again before direct transfer: this older plan has no saved Include data setting.");
        if (plan.view.sourceDialect() != Dialect.POSTGRESQL || plan.view.targetDialect() != Dialect.POSTGRESQL || plan.sourceIdentity == null || plan.sources.isEmpty())
            throw new IllegalArgumentException("Direct transfer is available for PostgreSQL-to-PostgreSQL plans analysed from a database. Analyse again, or use the staged transfer.");
        if (source == null || !identity(source).equals(plan.sourceIdentity)) throw new IllegalArgumentException("Direct transfer needs the analysed source connection. Connect to the same source, or analyse again.");
        List<String> unsupported = new ArrayList<>();
        for (var entry : actions.entrySet()) {
            if (entry.getValue() == Action.SKIP) continue;
            TableSelection selection = plan.selections.get(entry.getKey());
            if (entry.getValue() != Action.CREATE_AND_LOAD) unsupported.add(entry.getKey() + " (existing table)");
            else if (selection != null && selection.filters() != null && !selection.filters().isEmpty()) unsupported.add(entry.getKey() + " (row filters)");
            else if (!plan.sources.containsKey(entry.getKey())) unsupported.add(entry.getKey() + " (analysed before direct transfer existed)");
        }
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("Direct transfer only creates new tables without row filters. Skip these or use the staged transfer: " + String.join(", ", unsupported));
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
        job.state="RUNNING";job.message="Rechecking target and staged data";job.progress.beginPhase("CHECKING");
        DatabaseGateway.configure(plan.options);DatabaseGateway.configureBackground(true);String active=null;boolean risky=false;String outcome="FAILED";
        try {
            checkpoint(plan,job);verifyFiles(plan);
            Map<String,Table> sourceTables=new HashMap<>();
            try(Connection source=job.direct?openDirectSource(plan,job,actions,sourceTables):null;Connection connection=db.connect(target)) {
                db.requireSchema(connection,plan.view.targetSchema());
                for(Table table:plan.tables){
                    String name=table.name().in(plan.view.targetDialect());if(actions.get(name)==Action.SKIP)continue;
                    Table current=db.inspect(connection,plan.view.targetSchema(),name,plan.view.targetDialect());
                    Table previous=plan.targetTables.get(name);
                    if(job.created.contains(name))previous=job.finalized.contains(name)?table:new Table(table.name(),table.columns(),table.keys().stream().filter(k->!Set.of("FOREIGN KEY","INDEX").contains(k.kind())).toList(),table.warnings());
                    if((current==null)!=(previous==null)||current!=null&&!Compatibility.differences(previous,current,plan.view.targetDialect()).isEmpty())throw new IllegalArgumentException("Target structure changed; re-analyse or reconcile before resuming");
                    if(current!=null){String objectId=db.objectIdentity(connection,plan.view.targetSchema(),name,plan.view.targetDialect());
                        if(job.targetObjectIds.containsKey(name)&&!job.targetObjectIds.get(name).equals(objectId))throw new IllegalArgumentException("Target table was replaced; reconcile before resuming");
                        job.targetObjectIds.put(name,objectId);}
                }
                connection.setAutoCommit(false);
                try(Statement statement=connection.createStatement()) {
                    statement.setQueryTimeout(plan.options.queryTimeoutSeconds());
                    try {
                        // Each pass counts its tables from what earlier attempts already finished, so a resumed job counts on.
                        Dialect dialect=plan.view.targetDialect();
                        List<String> creating=plan.tables.stream().map(t->t.name().in(dialect)).filter(n->actions.get(n)==Action.CREATE_AND_LOAD).toList();
                        List<String> selected=plan.tables.stream().map(t->t.name().in(dialect)).filter(n->actions.get(n)!=Action.SKIP).toList();
                        job.progress.tablesTotal=creating.size();job.progress.tablesDone=creating.stream().filter(job.created::contains).count();
                        for(Table table:plan.tables){
                            active=table.name().in(plan.view.targetDialect());job.progress.table=active;job.progress.check();
                            if(actions.get(active)==Action.CREATE_AND_LOAD&&!job.created.contains(active)){
                                job.progress.beginPhase("CREATING");checkpoint(plan,job);risky=true;
                                job.progress.statement=statement;statement.execute(SqlWriter.create(table,plan.view.targetSchema(),plan.view.targetDialect()));connection.commit();
                                job.created.add(active);job.targetObjectIds.put(active,db.objectIdentity(connection,plan.view.targetSchema(),active,plan.view.targetDialect()));job.progress.tablesDone++;checkpoint(plan,job);risky=false;
                            }
                        }
                        job.progress.tablesTotal=selected.size();job.progress.tablesDone=selected.stream().filter(job.committed::contains).count();
                        for(Table table:ordered(plan,actions)){
                            active=table.name().in(plan.view.targetDialect());if(actions.get(active)==Action.SKIP||job.committed.contains(active))continue;
                            job.progress.check();job.progress.table=active;job.progress.beginPhase("LOADING");job.message="Loading "+active;checkpoint(plan,job);
                            ValidationReport validation=job.direct?loadDirect(source,connection,plan,table,sourceTables.get(active),job):loadTable(connection,plan,table,job);
                            // New SQL-input and directly streamed tables have no staged copy. Keep a compact receipt of their
                            // actual values so a later revert can verify the entire table. Staged tables are their own receipt.
                            if(job.created.contains(active)&&!job.direct&&!plan.typed.contains(active))job.createdFingerprints.put(active,DataFingerprint.target(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),plan.optionsFor(active),job.progress));
                            job.progress.beginPhase("COMMITTING");job.progress.check();risky=true;connection.commit();
                            job.committed.add(active);job.validations.put(active,validation);job.progress.rowsCommitted+=rowsFor(plan,job,active);
                            job.progress.tablesDone++;checkpoint(plan,job);risky=false;
                        }
                        job.progress.tablesTotal=selected.size();job.progress.tablesDone=selected.stream().filter(job.finalized::contains).count();
                        for(Table table:plan.tables){
                            active=table.name().in(plan.view.targetDialect());if(actions.get(active)==Action.SKIP||job.finalized.contains(active))continue;
                            job.progress.check();job.progress.table=active;job.progress.beginPhase("FINALIZING");checkpoint(plan,job);
                            // Index and constraint builds scale with the table, so they get its timeout.
                            statement.setQueryTimeout(plan.optionsFor(active).queryTimeoutSeconds());
                            risky=plan.view.targetDialect()==Dialect.ORACLE||!identitySql(table,plan,job).isEmpty();job.progress.statement=statement;
                            for(String sql:identitySql(table,plan,job))statement.execute(sql);
                            if(actions.get(active)==Action.CREATE_AND_LOAD)for(String sql:SqlWriter.afterData(table,plan.view.targetSchema(),plan.view.targetDialect()))statement.execute(sql);
                            risky=true;connection.commit();job.finalized.add(active);job.progress.tablesDone++;checkpoint(plan,job);risky=false;
                        }
                    }catch(Exception|OutOfMemoryError failure){try{connection.rollback();}catch(SQLException rollback){risky=true;}throw failure;}
                }
            }
            outcome="SUCCEEDED";job.message="Completed. Validation and committed-row counts are included for every selected table.";
        }catch(Exception|OutOfMemoryError failure){
            job.uncertain=risky;
            outcome=risky?"RECOVERY_REQUIRED":job.progress.cancel||failure instanceof CancellationException?"CANCELLED":"FAILED";
            job.message=risky?"A DDL or commit outcome is uncertain. Automatic replay is disabled; inspect the target and reconcile.":failure instanceof OutOfMemoryError?outOfMemory(active)+" Current table rolled back; earlier commits remain.":failure instanceof SQLException sql?DatabaseErrorGuidance.describe(sql)+" Current table rolled back; earlier commits remain.":failure instanceof IllegalArgumentException?failure.getMessage():outcome.equals("CANCELLED")?"Cancelled. Current transaction rolled back; earlier committed tables are retained.":"Operation failed. Check local disk, artifacts and database access. Earlier committed tables remain.";
        }finally{
          synchronized(this){
            job.progress.statement=null;job.progress.finished=System.currentTimeMillis();job.state=outcome;job.progress.beginPhase(outcome);job.tables.clear();
            for(Table table:plan.tables){String name=table.name().in(plan.view.targetDialect());Action action=actions.get(name);
                String status=action==Action.SKIP?"SKIPPED":job.finalized.contains(name)?"SUCCEEDED":job.committed.contains(name)||job.created.contains(name)?"PARTIAL_OR_FAILED":"NOT_RUN";
                ValidationReport report=job.validations.get(name);
                if(report==null&&action==Action.SKIP)report=new ValidationReport("NONE",0,0,"SKIPPED","Table skipped by the selected action.");
                else if(report==null&&status.equals("NOT_RUN"))report=new ValidationReport("NONE",0,0,"NOT_RUN","Not yet reached before the job stopped.");
                job.tables.add(new TableResult(name,action,status,job.committed.contains(name)?rowsFor(plan,job,name):0,
                    (report==null?"":describeValidation(report))+(job.finalized.contains(name)?" Complete.":job.committed.contains(name)?" Data committed; post-load work pending.":" "+job.message),report));
            }
            try{checkpoint(plan,job);}catch(Exception failure){job.state="RECOVERY_REQUIRED";job.uncertain=true;job.message="Could not persist final status. Inspect the target before retrying.";}
            DatabaseGateway.configure(null);DatabaseGateway.configureBackground(false);
          }
        }
    }
    private static String describeValidation(ValidationReport r){
        return switch(r.method()){
            case "FINGERPRINT" -> "Validated row-count delta and order-independent SHA-256 aggregate (probabilistic content check).";
            case "ROW_COUNT" -> r.skippedReason()!=null?r.skippedReason():"Validated row-count delta; SQL-expression content is not fingerprinted.";
            case "SAMPLED" -> "Validated exact row-count delta and compared "+r.rowsChecked()+" sampled rows value by value (large table).";
            case "STREAMED" -> "Direct transfer: rows read from the source, rows loaded and the target's row-count delta agree. Content is not fingerprinted (no local copy).";
            case "NONE" -> r.skippedReason();
            default -> r.method();
        };
    }
    private ValidationReport loadTable(Connection connection,Plan plan,Table table,Job job)throws Exception {
        String name=table.name().in(plan.view.targetDialect());Path file=plan.data.get(name);long rows=0;MigrationOptions options=plan.optionsFor(name);
        DataFingerprint before=null,expected=null;long beforeCount=0;
        // Above FULL_VERIFY_BYTES staged, an exact row count and a value-by-value sample replace hashing every value
        // on both sides, which would read the whole table and its staged copy again.
        boolean fingerprint=plan.typed.contains(name)&&plan.stagedBytes.getOrDefault(name,0L)<=FULL_VERIFY_BYTES;
        if(options.validateData()){
            job.progress.beginPhase("VALIDATING_BASELINE");
            if(fingerprint){before=DataFingerprint.target(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),options,job.progress);expected=DataFingerprint.staged(file,table,job.progress);}
            else beforeCount=count(connection,plan,table,job.progress);
        }
        job.progress.beginPhase("LOADING");
        if(plan.typed.contains(name)){
            if(options.useCopy()&&plan.view.targetDialect()==Dialect.POSTGRESQL&&connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))
                {try(Statement timeout=connection.createStatement()){timeout.execute("SET LOCAL statement_timeout = '"+options.queryTimeoutSeconds()+"s'");}rows=CopyLoader.load(connection,file,table,plan.view.targetSchema(),job.progress);}
            else try(PreparedStatement statement=connection.prepareStatement(RowStore.insertSql(table,plan.view.targetSchema(),plan.view.targetDialect()));BufferedReader reader=Files.newBufferedReader(file)){
                statement.setQueryTimeout(options.queryTimeoutSeconds());job.progress.statement=statement;List<Closeable> streams=new ArrayList<>();int batch=0;long bytes=0;
                try{String line;while((line=reader.readLine())!=null){job.progress.check();RowStore.Cell[] row=RowStore.JSON.readValue(line,RowStore.Cell[].class);
                    RowStore.bind(statement,row,table,plan.view.targetDialect(),file.getParent(),streams);statement.addBatch();batch++;rows++;bytes+=line.length()*2L;
                    if(batch>=options.batchRows()||bytes>=options.batchBytes()||!streams.isEmpty()){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;batch=0;bytes=0;closeStreams(streams);}
                }if(batch>0){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;}}
                finally{closeStreams(streams);}
            }
        }else try(Statement statement=connection.createStatement();BufferedReader reader=Files.newBufferedReader(file)){
            statement.setQueryTimeout(options.queryTimeoutSeconds());job.progress.statement=statement;String line;int batch=0;long bytes=0;
            while((line=reader.readLine())!=null){job.progress.check();statement.addBatch(JsonLines.decode(line));batch++;rows++;bytes+=line.length()*2L;
                if(batch>=options.batchRows()||bytes>=options.batchBytes()){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;batch=0;bytes=0;}}
            if(batch>0){statement.executeBatch();statement.clearBatch();job.progress.rowsSent+=batch;job.progress.bytes+=bytes;}
        }
        if(rows!=plan.rows.getOrDefault(name,0L))throw new IOException("Staged row count changed");
        if(!options.validateData())return new ValidationReport("NONE",0,0,"PASSED","Validation disabled by user.");
        job.progress.beginPhase("VALIDATING");
        long validationStart=System.nanoTime();
        if(fingerprint){
            DataFingerprint after=DataFingerprint.target(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),options,job.progress);
            if(!after.equals(before.plus(expected)))throw new IllegalArgumentException("Target content validation failed; transaction will roll back");
            return new ValidationReport("FINGERPRINT",after.count(),(System.nanoTime()-validationStart)/1e9,"PASSED",null);
        }
        long afterCount=count(connection,plan,table,job.progress);
        if(afterCount!=beforeCount+rows)throw new IllegalArgumentException("Target row-count validation failed; transaction will roll back");
        if(plan.typed.contains(name)){
            long checked=RevertRows.sample(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),file,rows,SAMPLE_ROWS,options,job.progress);
            if(checked<0)return new ValidationReport("ROW_COUNT",afterCount,(System.nanoTime()-validationStart)/1e9,"PASSED","Large table: exact row count verified; no primary key to sample rows by.");
            return new ValidationReport("SAMPLED",checked,(System.nanoTime()-validationStart)/1e9,"PASSED",null);
        }
        return new ValidationReport("ROW_COUNT",afterCount,(System.nanoTime()-validationStart)/1e9,"PASSED",null);
    }
    /** Staged size above which a table is verified by exact row count plus a SAMPLE_ROWS sample. Tests lower it. */
    static long FULL_VERIFY_BYTES = 1_000_000_000L;
    static int SAMPLE_ROWS = 1000;
    /** Opens the source for a direct transfer in one read-only snapshot, and checks that every table still to be
     * loaded is as analysed. The projected source tables are returned in sourceTables. */
    private Connection openDirectSource(Plan plan, Job job, Map<String,Action> actions, Map<String,Table> sourceTables) throws Exception {
        if (plan.includeData == null) throw new IllegalArgumentException("Analyse again: this older plan has no saved Include data setting.");
        if (job.source == null) throw new IllegalArgumentException("Enter the source connection to run or resume a direct transfer");
        Connection source = db.connect(job.source);
        try {
            db.snapshot(source, Dialect.POSTGRESQL);
            Map<String,TableSelection> bySource = new HashMap<>(); plan.selections.forEach((name, selection) -> bySource.put(plan.sources.get(name), selection));
            for (Table table : plan.tables) {
                String name = table.name().in(Dialect.POSTGRESQL);
                if (actions.get(name) == Action.SKIP || job.committed.contains(name)) continue;
                String sourceName = plan.sources.get(name); TableSelection selection = plan.selections.get(name);
                Table current = sourceName == null ? null : db.inspect(source, plan.sourceSchema, sourceName, Dialect.POSTGRESQL);
                if (current == null) throw new IllegalArgumentException("Source table " + sourceName + " no longer exists; analyse again");
                Table projected = TableProjection.select(current, selection, Dialect.POSTGRESQL);
                if (!Compatibility.differences(table, SourceExtraction.convert(projected, selection, bySource, Dialect.POSTGRESQL, Dialect.POSTGRESQL), Dialect.POSTGRESQL).isEmpty())
                    throw new IllegalArgumentException("Source table " + sourceName + " changed since analysis; analyse again");
                sourceTables.put(name, projected);
            }
            return source;
        } catch (Exception | Error e) { SourceExtraction.closeQuietly(source); throw e; }
    }
    /** Streams one table from the source into the target with COPY, in the target's open transaction. Binary
     * format (exact, and bytea without hex text) when both sides have identical column types, text otherwise.
     * Verified by row counts: rows read, rows loaded and the target's row-count delta must all agree. */
    private ValidationReport loadDirect(Connection source, Connection target, Plan plan, Table table, Table sourceTable, Job job) throws Exception {
        String name = table.name().in(Dialect.POSTGRESQL); MigrationOptions options = plan.optionsFor(name); long started = System.nanoTime();
        if (!Boolean.TRUE.equals(plan.includeData)) {
            job.loadedRows.put(name, 0L);
            job.createdFingerprints.put(name, DataFingerprint.empty());
            return new ValidationReport("NONE", 0, 0, "PASSED", "Definitions only: Include data was disabled.");
        }
        job.progress.beginPhase("VALIDATING_BASELINE");
        long before = count(target, plan, table, job.progress);
        TableSelection selection = plan.selections.get(name);
        long remaining = plan.options.maxRows() - job.committed.stream().mapToLong(n -> rowsFor(plan, job, n)).sum();
        // Read at most one row beyond the remaining budget; reject before commit instead of scanning twice.
        long limit = Math.max(0, remaining) + 1;
        if (selection != null && selection.rowLimit() != null) limit = Math.min(limit, selection.rowLimit());
        String query = "SELECT " + SqlWriter.names(sourceTable.columns().stream().map(Column::name).toList(), Dialect.POSTGRESQL) + " FROM " + qualified(plan.sourceSchema, sourceTable.name(), Dialect.POSTGRESQL) + " LIMIT " + limit;
        boolean binary = true;
        for (int i = 0; i < table.columns().size(); i++) binary &= table.columns().get(i).type().equals(sourceTable.columns().get(i).type());
        String format = binary ? " (FORMAT binary)" : "";
        for (Connection connection : List.of(source, target)) try (Statement statement = connection.createStatement()) { statement.execute("SET LOCAL statement_timeout = '" + options.queryTimeoutSeconds() + "s'"); }
        job.progress.beginPhase("COPYING");
        org.postgresql.copy.CopyOut copyOut = source.unwrap(org.postgresql.PGConnection.class).getCopyAPI().copyOut("COPY (" + query + ") TO STDOUT" + format);
        org.postgresql.copy.CopyIn copyIn = null; long loaded;
        try {
            copyIn = target.unwrap(org.postgresql.PGConnection.class).getCopyAPI().copyIn("COPY " + qualified(plan.view.targetSchema(), table.name(), Dialect.POSTGRESQL)
                    + " (" + SqlWriter.names(table.columns().stream().map(Column::name).toList(), Dialect.POSTGRESQL) + ") FROM STDIN" + format);
            byte[] chunk; long transferredBytes = 0;
            while ((chunk = copyOut.readFromCopy()) != null) {
                job.progress.check(); transferredBytes += chunk.length;
                if (transferredBytes > options.maxTableBytes()) throw new IllegalArgumentException("Direct transfer exceeds configured per-table byte limit; current table will roll back.");
                copyIn.writeToCopy(chunk, 0, chunk.length); job.progress.bytes += chunk.length;
            }
            loaded = copyIn.endCopy();
        } catch (Exception | Error failure) {
            if (copyIn != null && copyIn.isActive()) try { copyIn.cancelCopy(); } catch (SQLException ignored) {}
            if (copyOut.isActive()) try { copyOut.cancelCopy(); } catch (SQLException ignored) {}
            throw failure;
        }
        long read = copyOut.getHandledRowCount(); job.progress.rowsRead += read; job.progress.rowsSent += loaded;
        if (read != loaded) throw new IllegalArgumentException("Direct transfer of " + name + " read " + read + " rows but loaded " + loaded + "; transaction will roll back");
        if (loaded > remaining) throw new IllegalArgumentException("Direct transfer exceeds configured total row limit; current table will roll back.");
        // One pass supplies both the exact row count and the full receipt required by safe revert.
        job.progress.beginPhase("FINGERPRINTING");
        DataFingerprint receipt = DataFingerprint.target(target, table, plan.view.targetSchema(), Dialect.POSTGRESQL, options, job.progress);
        long after = receipt.count();
        if (after != before + loaded) throw new IllegalArgumentException("Target row-count validation failed for " + name + "; transaction will roll back");
        job.createdFingerprints.put(name, receipt);
        job.loadedRows.put(name, loaded);
        return new ValidationReport("STREAMED", after, (System.nanoTime() - started) / 1e9, "PASSED", null);
    }
    private static void closeStreams(List<Closeable> streams)throws IOException{IOException failure=null;for(Closeable stream:streams)try{stream.close();}catch(IOException e){failure=e;}streams.clear();if(failure!=null)throw failure;}
    private long count(Connection connection,Plan plan,Table table,TransferProgress progress)throws SQLException{
        try(Statement s=connection.createStatement()){progress.statement=s;s.setQueryTimeout(plan.optionsFor(table.name().in(plan.view.targetDialect())).queryTimeoutSeconds());try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM "+qualified(plan.view.targetSchema(),table.name(),plan.view.targetDialect()))){rs.next();return rs.getLong(1);}}
    }
    /** Rows loaded for a table: what a direct transfer streamed, otherwise what was staged. */
    private static long rowsFor(Plan plan, Job job, String name) { return job != null && job.loadedRows.containsKey(name) ? job.loadedRows.get(name) : plan.rows.getOrDefault(name, 0L); }
    private List<String> identitySql(Table table, Plan plan, Job job) {
        if (rowsFor(plan, job, table.name().in(plan.view.targetDialect())) == 0) return List.of();
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
    public JobView cancel(String id){Job job=jobs.get(id);if(job==null)throw new IllegalArgumentException("Job not found");requestCancel(job.revert!=null&&Set.of("QUEUED","RUNNING").contains(job.revert.state)?job.revert.progress:job.progress);return job.view();}
    public JobView resume(String id,ConnectionSpec target){return resume(id,target,null);}
    public synchronized JobView resume(String id,ConnectionSpec target,ConnectionSpec source){
        Job job=jobs.get(id);if(job==null||!job.view().resumable())throw new IllegalArgumentException("This job cannot be automatically resumed. Reconcile uncertain target changes first.");
        Plan plan=plan(job.planId);if(target==null||!identity(target).equals(plan.targetIdentity))throw new IllegalArgumentException("Enter the original target connection to resume");
        if(job.direct){if(source==null||!identity(source).equals(plan.sourceIdentity))throw new IllegalArgumentException("Enter the original source connection to resume this direct transfer");job.source=source;}
        job.progress.cancel=false;job.progress.finished=0;job.progress.started=System.currentTimeMillis();job.progress.rowsSent=job.progress.rowsCommitted;job.attempt++;job.state="QUEUED";checkpoint(plan,job);
        try{executor.execute(()->run(plan,target,job.actions,job));}catch(RejectedExecutionException e){job.state="FAILED";checkpoint(plan,job);throw new IllegalArgumentException("Worker queue full");}return job.view();
    }
    private Job requireRevert(String id,ConnectionSpec target){
        Job job=jobs.get(id);
        if(job==null)throw new IllegalArgumentException("Job not found");
        Plan plan=plan(job.planId);
        if(target==null||!Objects.equals(identity(target),plan.targetIdentity))throw new IllegalArgumentException("Enter the original target connection to review this revert");
        if(job.uncertain||!job.state.equals("SUCCEEDED"))throw new IllegalArgumentException("Automatic revert requires a completed migration with a known commit outcome");
        if(job.revert!=null&&(job.revert.running||!Set.of("FAILED","PARTIAL").contains(job.revert.state)))throw new IllegalArgumentException("Revert is already running, completed, or requires reconciliation");
        return job;
    }
    private List<Table> revertTables(Plan plan,Job job){
        List<Table> tables=plan.tables.stream().filter(t->{String n=t.name().in(plan.view.targetDialect());return job.actions.get(n)!=Action.SKIP&&(job.created.contains(n)||rowsFor(plan,job,n)>0);}).toList();
        if(tables.isEmpty())throw new IllegalArgumentException("This migration has no transferred rows or newly created tables to revert");
        for(Table table:tables){
            String name=table.name().in(plan.view.targetDialect());
            if(!job.committed.contains(name)||!job.finalized.contains(name))throw new IllegalArgumentException("Migration records are incomplete; reconcile before reverting");
            if(!job.targetObjectIds.containsKey(name))throw new IllegalArgumentException("This older migration has no saved target table identity; automatic revert is unavailable");
            if(job.created.contains(name)){
                if(!job.createdFingerprints.containsKey(name)&&!plan.typed.contains(name))throw new IllegalArgumentException("This older SQL-input migration has no saved table receipt; automatic revert is unavailable");
            }else{
                if(!plan.typed.contains(name))throw new IllegalArgumentException("SQL-input inserts into existing tables do not have reliable row receipts; automatic revert is unavailable");
                RevertRows.keyTable(table,plan.view.targetDialect());
            }
        }
        return RevertRows.deletionOrder(tables,plan.view.targetDialect());
    }
    private void checkRevertTarget(Connection connection,Plan plan,Job job,List<Table> tables,TransferProgress progress,boolean data)throws Exception{
        Dialect dialect=plan.view.targetDialect();String schema=plan.view.targetSchema();Revert r=job.revert;
        db.requireSchema(connection,schema);
        Set<String> dropping=new HashSet<>(job.created);if(r!=null)dropping.removeAll(r.dropped);
        for(Table table:tables){
            progress.check();String name=table.name().in(dialect);progress.table=name;
            Table current=db.inspect(connection,schema,name,dialect),expected=job.created.contains(name)?table:plan.targetTables.get(name);
            if(current==null||expected==null||!Compatibility.differences(expected,current,dialect).isEmpty())throw new IllegalArgumentException("Revert blocked: target structure changed for "+name);
            if(!job.targetObjectIds.get(name).equals(db.objectIdentity(connection,schema,name,dialect)))throw new IllegalArgumentException("Revert blocked: target table was replaced: "+name);
            if(!RevertRows.indexes(expected,dialect).equals(RevertRows.indexes(current,dialect)))throw new IllegalArgumentException("Revert blocked: target indexes changed for "+name);
            if(!data)continue;
            if(job.created.contains(name)){
                DataFingerprint saved=r!=null&&r.rowsRemoved?DataFingerprint.empty():job.createdFingerprints.get(name);
                if(saved==null)saved=DataFingerprint.staged(plan.data.get(name),table,progress);
                if((r==null||!r.rowsRemoved)&&saved.count()!=rowsFor(plan,job,name))throw new IllegalArgumentException("Revert receipt row count differs for "+name);
                if(!saved.equals(DataFingerprint.target(connection,table,schema,dialect,plan.optionsFor(name),progress)))throw new IllegalArgumentException("Revert blocked: newly created table "+name+" contains changed or additional data");
            }else if(r==null||!r.rowsRemoved){
                if(RevertRows.existing(connection,table,schema,dialect,plan.data.get(name),plan.optionsFor(name),progress,false)!=plan.rows.getOrDefault(name,0L))throw new IllegalArgumentException("Revert row receipt changed for "+name);
            }
        }
        RevertRows.dependencies(connection,schema,tables,dropping,dialect);
    }
    public RevertPreview previewRevert(String id,ConnectionSpec target)throws Exception{
        Job job;Plan plan;Revert previous;
        synchronized(this){job=requireRevert(id,target);plan=plan(job.planId);previous=job.revert;}
        List<Table> tables=revertTables(plan,job);verifyFiles(plan);
        List<Table> remaining=tables.stream().filter(t->previous==null||!previous.dropped.contains(t.name().in(plan.view.targetDialect()))).toList();
        DatabaseGateway.configure(plan.options);
        try(Connection connection=db.connect(target)){
            connection.setAutoCommit(false);
            try{checkRevertTarget(connection,plan,job,remaining,new TransferProgress(),true);}finally{connection.rollback();}
        }finally{DatabaseGateway.configure(null);}
        synchronized(this){
            requireRevert(id,target);if(job.revert!=previous)throw new IllegalArgumentException("Job changed while reviewing revert; review again");
            long now=System.currentTimeMillis();revertTickets.entrySet().removeIf(e->e.getValue().expires()<now);
            if(revertTickets.size()>=64)throw new IllegalArgumentException("Too many pending revert reviews");
            String token=UUID.randomUUID().toString();long expires=now+300000;
            revertTickets.put(token,new RevertTicket(id,identity(target),expires));
            return new RevertPreview(token,id,target.dialect()+" "+target.host()+":"+target.port()+"/"+target.database()+" / "+plan.view.targetSchema(),
                    tables.stream().map(t->{String n=t.name().in(plan.view.targetDialect());return new RevertTable(n,job.created.contains(n)?"DROP_TABLE":"DELETE_ROWS",rowsFor(plan,job,n),
                            previous!=null&&previous.dropped.contains(n)?"DROPPED":previous!=null&&previous.rowsRemoved?"ROWS_REMOVED":"PENDING");}).toList(),Instant.ofEpochMilli(expires).toString());
        }
    }
    public synchronized JobView revert(String id,RevertRequest request){
        Job job=requireRevert(id,request.target());Plan plan=plan(job.planId);
        RevertTicket ticket=request.token()==null?null:revertTickets.remove(request.token());
        if(ticket==null||!ticket.jobId().equals(id)||!ticket.targetIdentity().equals(identity(request.target()))||ticket.expires()<System.currentTimeMillis())throw new IllegalArgumentException("Revert review expired or does not match this job. Review again before confirming.");
        List<Table> tables=revertTables(plan,job);Revert previous=job.revert;Revert r=new Revert();
        r.tables=tables.stream().map(t->{String n=t.name().in(plan.view.targetDialect());return new RevertTable(n,job.created.contains(n)?"DROP_TABLE":"DELETE_ROWS",rowsFor(plan,job,n),"PENDING");}).toList();
        r.progress.totalRows=r.tables.stream().mapToLong(RevertTable::rows).sum();
        if(previous!=null){r.rowsRemoved=previous.rowsRemoved;r.dropped.addAll(previous.dropped);r.progress.rowsCommitted=previous.progress.rowsCommitted;r.progress.rowsSent=r.progress.rowsCommitted;}
        job.revert=r;r.running=true;
        try{checkpoint(plan,job);}catch(RuntimeException error){job.revert=previous;throw error;}
        try{executor.execute(()->runRevert(plan,job,request.target(),tables));}
        catch(RejectedExecutionException error){r.running=false;r.state=r.rowsRemoved?"PARTIAL":"FAILED";r.message="Worker queue full; review and retry revert.";checkpoint(plan,job);throw new IllegalArgumentException(r.message);}
        return job.view();
    }
    private void runRevert(Plan plan,Job job,ConnectionSpec target,List<Table> tables){
        Revert r=job.revert;boolean risky=false;Connection connection=null;
        r.state="RUNNING";r.progress.phase="CHECKING";r.message="Rechecking target rows and dependencies";
        DatabaseGateway.configure(plan.options);DatabaseGateway.configureBackground(true);
        try{
            checkpoint(plan,job);verifyFiles(plan);r.progress.check();
            connection=db.connect(target);connection.setAutoCommit(false);
            List<Table> remaining=tables.stream().filter(t->!r.dropped.contains(t.name().in(plan.view.targetDialect()))).toList();
            RevertRows.lock(connection,plan.view.targetSchema(),remaining,plan.view.targetDialect(),plan.options,r.progress);
            checkRevertTarget(connection,plan,job,remaining,r.progress,true);
            if(!r.rowsRemoved){
                r.progress.phase="REMOVING_ROWS";r.message="Removing only this migration's rows";checkpoint(plan,job);
                for(Table table:tables){
                    r.progress.check();String name=table.name().in(plan.view.targetDialect());r.progress.table=name;long rows;
                    if(job.created.contains(name)){
                        try(Statement s=connection.createStatement()){
                            s.setQueryTimeout(plan.optionsFor(name).queryTimeoutSeconds());r.progress.statement=s;
                            rows=s.executeLargeUpdate("DELETE FROM "+qualified(plan.view.targetSchema(),table.name(),plan.view.targetDialect()));r.progress.rowsSent+=rows;
                        }
                    }else rows=RevertRows.existing(connection,table,plan.view.targetSchema(),plan.view.targetDialect(),plan.data.get(name),plan.optionsFor(name),r.progress,true);
                    if(rows!=rowsFor(plan,job,name))throw new IllegalArgumentException("Revert row count changed for "+name);
                }
                r.progress.check();risky=true;connection.commit();r.rowsRemoved=true;r.progress.rowsCommitted=r.progress.totalRows;checkpoint(plan,job);risky=false;
            }else connection.rollback();
            r.progress.phase="DROPPING_TABLES";r.message="Removing tables created by this migration";
            for(Table table:tables){
                String name=table.name().in(plan.view.targetDialect());if(!job.created.contains(name)||r.dropped.contains(name))continue;
                r.progress.check();r.progress.table=name;
                RevertRows.lock(connection,plan.view.targetSchema(),List.of(table),plan.view.targetDialect(),plan.options,r.progress);
                // Validate all remaining dependencies again after the data commit. Never CASCADE.
                remaining=tables.stream().filter(t->!r.dropped.contains(t.name().in(plan.view.targetDialect()))).toList();
                checkRevertTarget(connection,plan,job,remaining,r.progress,true);checkpoint(plan,job);
                try(Statement s=connection.createStatement()){
                    s.setQueryTimeout(plan.options.queryTimeoutSeconds());r.progress.statement=s;risky=true;
                    s.execute("DROP TABLE "+qualified(plan.view.targetSchema(),table.name(),plan.view.targetDialect()));connection.commit();
                }
                r.dropped.add(name);checkpoint(plan,job);risky=false;
            }
            r.state="REVERTED";r.message="Transferred rows removed and migration-created tables dropped. Existing tables and unrelated rows retained; identity/sequence values were not reset.";
        }catch(Exception failure){
            if(connection!=null)try{connection.rollback();}catch(SQLException rollback){risky=true;}
            r.state=risky?"RECOVERY_REQUIRED":r.rowsRemoved?"PARTIAL":"FAILED";
            r.message=risky?"A revert commit or DROP outcome is uncertain. Keep the saved files and reconcile the target; automatic retry is disabled.":
                    failure instanceof IllegalArgumentException?failure.getMessage():failure instanceof CancellationException?"Revert cancelled at a safe boundary.":
                    failure instanceof SQLException sql?"Revert: "+DatabaseErrorGuidance.describe(sql):"Revert failed. Check saved data, disk and database access.";
            if(!risky)r.message+=r.rowsRemoved?" Row removal is committed; some new tables may remain. Review before retrying.":" Row removal was rolled back; no revert changes were committed.";
        }finally{
            r.progress.statement=null;
            if(connection!=null)try{connection.close();}catch(SQLException ignored){}
            r.progress.finished=System.currentTimeMillis();r.progress.phase=r.state;
            synchronized(this){try{checkpoint(plan,job);}catch(Exception failure){r.state="RECOVERY_REQUIRED";r.message="Could not save the final revert result. Reconcile the target before further changes.";}finally{r.running=false;}}
            DatabaseGateway.configure(null);DatabaseGateway.configureBackground(false);
        }
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
            catch(Exception failure){preparation.state=preparation.progress.cancel?"CANCELLED":"FAILED";preparation.message=failure instanceof IllegalArgumentException?failure.getMessage():failure instanceof SQLException sql?DatabaseErrorGuidance.describe(sql):"Preparation failed. Check source access, limits and disk space.";}
            // The staged rows are released by now, so record the outcome instead of leaving the preparation stuck.
            catch(OutOfMemoryError failure){preparation.state="FAILED";preparation.message=outOfMemory(preparation.progress.table);}
            finally{
                if("FAILED".equals(preparation.state)&&preparation.progress.note!=null)preparation.message=preparation.message+" "+preparation.progress.note;
                preparation.progress.finished=System.currentTimeMillis();preparing.remove();DatabaseGateway.configureBackground(false);
            }
        });return preparation.view();
    }
    static String outOfMemory(String table){
        return "Ran out of memory"+(table==null||table.isBlank()?"":" on "+table)+". Start SchemaBridge with more memory (-Xmx) and try again.";
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
            String sourceInfo,String targetInfo,String sourceSchema,String sourceTables,String jobId,boolean executed,
            Map<String,Long> stagedBytes,Map<String,String> sources,Map<String,TableSelection> selections,String sourceIdentity,Boolean includeData) {}
    record JobManifest(int version,JobView view,Map<String,Action> actions,Set<String> created,Set<String> committed,Set<String> finalized,
                       Map<String,ValidationReport> validations,boolean uncertain,int attempt,Map<String,DataFingerprint> createdFingerprints,Map<String,String> targetObjectIds,RevertManifest revert,
                       boolean direct,Map<String,Long> loadedRows) {}
    record RevertManifest(String state,String message,boolean rowsRemoved,Set<String> dropped,List<RevertTable> tables,Progress progress) {}
    private void atomicJson(Path file,Object value)throws IOException{
        Path temp=file.resolveSibling(file.getFileName()+".tmp");byte[] bytes=json.writeValueAsBytes(value);
        try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        try{replaceJournal(() -> Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));}
        catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic job storage is unavailable",e);}
    }
    @FunctionalInterface interface JournalMove { void move() throws IOException; }
    /** Windows scanners may briefly hold a journal open. Retry only the atomic rename; never replay DB writes
     * or fall back to deleting the previous journal. Persistent errors still require recovery. */
    static void replaceJournal(JournalMove move) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try { move.move(); return; }
            catch (AccessDeniedException denied) {
                if (attempt >= 9) throw denied;
                try { Thread.sleep(Math.min(250, 25L << Math.min(attempt, 4))); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while saving migration journal", interrupted);
                }
            }
        }
    }
    private void persistPlan(Plan plan)throws IOException{
        Map<String,String> data=new LinkedHashMap<>();plan.data.forEach((key,path)->data.put(key,path.getFileName().toString()));
        atomicJson(plan.directory.resolve("plan.json"),new PlanManifest(1,plan.view,plan.tables,data,plan.targetTables,plan.rows,plan.typed,plan.hashes,plan.options,plan.targetIdentity,
                plan.sourceInfo,plan.targetInfo,plan.sourceSchema,plan.sourceTables,plan.jobId,plan.executed.get(),plan.stagedBytes,plan.sources,plan.selections,plan.sourceIdentity,plan.includeData));
    }
    private void persistPlanUnchecked(Plan plan){try{persistPlan(plan);}catch(IOException e){throw new IllegalStateException("Could not save plan state",e);}}
    private void checkpoint(Plan plan,Job job){
        try{
            // Persist submission before writes. A crash between these files fails closed on restore.
            persistPlan(plan);
            Revert r=job.revert;
            atomicJson(plan.directory.resolve("job.json"),new JobManifest(1,job.view(),job.actions,job.created,job.committed,job.finalized,job.validations,job.uncertain,job.attempt,job.createdFingerprints,job.targetObjectIds,
                    r==null?null:new RevertManifest(r.state,r.message,r.rowsRemoved,r.dropped,r.tables,r.progress.view()),job.direct,job.loadedRows));
        }catch(IOException e){throw new IllegalStateException("Could not persist migration state",e);}
    }
    private void verifyFiles(Plan plan)throws IOException{
        for(var entry:plan.hashes.entrySet()){
            Path path=artifact(plan.directory,entry.getKey());
            if(!Files.isRegularFile(path)||!RowStore.hash(path).equals(entry.getValue()))throw new IllegalArgumentException("Staged data is missing or changed. Re-extract before starting any writes.");
        }
    }
    private static final java.util.regex.Pattern ARTIFACT_NAME=java.util.regex.Pattern.compile("(?:data-\\d+\\.(?:rows|jsonl)|lob-[a-f0-9-]{36})");
    private Path artifact(Path directory,String filename)throws IOException{
        if(filename==null||!ARTIFACT_NAME.matcher(filename).matches())throw new IOException("Invalid artifact name");
        Path path=directory.resolve(filename);if(Files.isSymbolicLink(path))throw new IOException("Invalid artifact link");return path;
    }
    private void restore()throws IOException{
        try(var directories=Files.list(work)){
            for(Path directory:directories.filter(p->Files.isDirectory(p)&&!Files.isSymbolicLink(p)&&p.getFileName().toString().matches("[a-f0-9-]{36}")).toList()){
                Path manifest=directory.resolve("plan.json");if(!Files.exists(manifest))continue;
                try{
                    PlanManifest saved=json.readValue(Files.readAllBytes(manifest),PlanManifest.class);
                    if(saved.version()!=1||!saved.view().id().equals(directory.getFileName().toString()))continue;
                    Plan plan=new Plan();plan.directory=directory;plan.view=saved.view();plan.prepared=plan.view.prepared();plan.options=saved.options()==null?plan.view.options():saved.options();plan.auto=plan.view.automatic();plan.tableOptions.putAll(plan.view.tableOptions());plan.tables.addAll(saved.tables());
                    for(var entry:saved.data().entrySet())plan.data.put(entry.getKey(),artifact(directory,entry.getValue()));
                    plan.targetTables.putAll(saved.targetTables());plan.rows.putAll(saved.rows());plan.typed.addAll(saved.typed());plan.hashes.putAll(saved.hashes());
                    plan.targetIdentity=saved.targetIdentity();plan.sourceInfo=saved.sourceInfo();plan.targetInfo=saved.targetInfo();plan.sourceSchema=saved.sourceSchema();plan.sourceTables=saved.sourceTables();plan.jobId=saved.jobId();plan.executed.set(saved.executed());
                    if(saved.stagedBytes()!=null)plan.stagedBytes.putAll(saved.stagedBytes());if(saved.sources()!=null)plan.sources.putAll(saved.sources());
                    if(saved.selections()!=null)plan.selections.putAll(saved.selections());plan.sourceIdentity=saved.sourceIdentity();plan.includeData=saved.includeData();
                    plans.put(plan.view.id(),plan);
                    if(plan.jobId!=null){
                        Job job=new Job();job.id=plan.jobId;job.planId=plan.view.id();job.options=plan.options;
                        try{
                            JobManifest snapshot=json.readValue(Files.readAllBytes(directory.resolve("job.json")),JobManifest.class);
                            if(snapshot.version()!=1||!snapshot.view().id().equals(job.id)||!snapshot.view().planId().equals(job.planId))throw new IOException("Job identity changed");
                            job.actions=snapshot.actions();job.created.addAll(snapshot.created());job.committed.addAll(snapshot.committed());job.finalized.addAll(snapshot.finalized());job.validations.putAll(snapshot.validations());job.tables.addAll(snapshot.view().tables());job.attempt=snapshot.attempt();job.state=snapshot.view().state();job.message=snapshot.view().message();job.uncertain=snapshot.uncertain();if(snapshot.view().options()!=null)job.options=snapshot.view().options();
                            if(snapshot.createdFingerprints()!=null)job.createdFingerprints.putAll(snapshot.createdFingerprints());
                            if(snapshot.targetObjectIds()!=null)job.targetObjectIds.putAll(snapshot.targetObjectIds());
                            job.direct=snapshot.direct();if(snapshot.loadedRows()!=null)job.loadedRows.putAll(snapshot.loadedRows());
                            if(snapshot.view().progress().phaseSeconds()!=null)job.progress.phaseSeconds.putAll(snapshot.view().progress().phaseSeconds());
                            if(snapshot.revert()!=null){
                                RevertManifest savedRevert=snapshot.revert();Revert r=new Revert();job.revert=r;
                                r.state=savedRevert.state();r.message=savedRevert.message();r.rowsRemoved=savedRevert.rowsRemoved();r.dropped.addAll(savedRevert.dropped());r.tables=List.copyOf(savedRevert.tables());
                                r.progress.totalRows=savedRevert.progress().totalRows();r.progress.rowsCommitted=savedRevert.progress().rowsCommitted();r.progress.rowsSent=r.progress.rowsCommitted;
                                r.progress.finished=System.currentTimeMillis();r.progress.started=r.progress.finished-savedRevert.progress().elapsedSeconds()*1000;
                                if(Set.of("QUEUED","RUNNING").contains(r.state)){r.state="RECOVERY_REQUIRED";r.message="Revert was interrupted. Reconcile target rows and dropped tables before further changes.";}
                                r.progress.phase=r.state;
                            }
                            job.progress.finished=System.currentTimeMillis();job.progress.started=job.progress.finished-snapshot.view().progress().elapsedSeconds()*1000;job.progress.bytes=snapshot.view().progress().bytes();job.progress.totalRows=snapshot.view().progress().totalRows();job.progress.rowsCommitted=snapshot.view().progress().rowsCommitted();job.progress.rowsSent=job.progress.rowsCommitted;
                            if(Set.of("RUNNING","QUEUED").contains(job.state)){job.uncertain=true;job.state="RECOVERY_REQUIRED";job.message="Application stopped before final status was saved. Reconcile the target before replaying uncertain work.";}
                        }catch(Exception e){job.state="RECOVERY_REQUIRED";job.uncertain=true;job.message="Job journal is missing or damaged. Inspect the target; automatic replay is disabled.";}
                        job.progress.beginPhase(job.state);
                        jobs.put(job.id,job);
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
    public void delete(String id) throws IOException { delete(id, false); }
    /** acknowledgeUncertain bypasses the RECOVERY_REQUIRED/PARTIAL protection below for a deliberate,
     * reviewed discard (P5 "allow reviewed discard") — never the default. Ordinary deletion (the
     * false path) must keep failing for uncertain jobs: that evidence is exactly what a human needs
     * to reconcile the target per RECOVERY-RUNBOOK.md before it is gone forever. */
    public synchronized void delete(String id, boolean acknowledgeUncertain) throws IOException {
        Plan plan = plans.get(id);
        if (plan == null) {
            // An interrupted preparation has no plan yet; it can be deleted unless it is being prepared right now.
            Path folder = id != null && id.matches("[a-f0-9-]{36}") ? work.resolve(id) : null;
            if (folder == null || readPartial(folder) == null) throw new IllegalArgumentException("Plan not found");
            if (activePreparations.contains(id)) throw new IllegalArgumentException("This preparation is running. Cancel it before deleting its files.");
            erase(folder); return;
        }
        if (active(plan)) throw new IllegalArgumentException("Wait for this job before deleting its plan");
        Job savedJob=plan.jobId==null?null:jobs.get(plan.jobId);
        if(!acknowledgeUncertain&&savedJob!=null&&savedJob.state.equals("RECOVERY_REQUIRED"))throw new IllegalArgumentException("This job's commit outcome is uncertain. Reconcile the target first, or confirm you have reviewed it before discarding its evidence.");
        if(!acknowledgeUncertain&&uncertainRevert(savedJob))throw new IllegalArgumentException("Retain this job's files until its revert has been reconciled, or confirm you have reviewed it before discarding its evidence.");
        plans.remove(id); if (plan.jobId != null) jobs.remove(plan.jobId); erase(plan.directory);
    }
    private static boolean uncertainRevert(Job job){return job!=null&&job.revert!=null&&Set.of("PARTIAL","RECOVERY_REQUIRED").contains(job.revert.state);}
    /** Mirrors delete(): files that ordinary deletion refuses to discard without acknowledgement. */
    private static boolean uncertain(Job job){return job!=null&&(job.state.equals("RECOVERY_REQUIRED")||uncertainRevert(job));}
    public synchronized StorageView storage() throws IOException {
        List<PlanStorage> details = new ArrayList<>(); long totalBytes = 0, reclaimable = 0;
        Set<String> known = new HashSet<>();
        for (Plan plan : plans.values()) {
            long bytes = directoryBytes(plan.directory); totalBytes += bytes;
            if (plan.directory != null) known.add(plan.directory.getFileName().toString());
            boolean expiredInactive = Instant.parse(plan.view.expiresAt()).isBefore(Instant.now()) && !active(plan) && !plan.executed.get();
            if (expiredInactive) reclaimable += bytes;
            Job job = plan.jobId == null ? null : jobs.get(plan.jobId);
            details.add(new PlanStorage(plan.view.id(), job == null ? "NOT_RUN" : job.state, bytes, plan.view.expiresAt(), expiredInactive,
                    plan.sourceInfo, plan.targetInfo, plan.view.tables().size(), plan.prepared, active(plan), uncertain(job), 0));
        }
        // Interrupted preparations: their staged tables wait for the next attempt with the same selection.
        for (Path folder : workFolders()) {
            PartialManifest saved = readPartial(folder); if (saved == null) continue;
            String name = folder.getFileName().toString(); known.add(name);
            long bytes = directoryBytes(folder); totalBytes += bytes;
            details.add(new PlanStorage(name, "INTERRUPTED", bytes, Instant.parse(saved.updatedAt()).plus(Duration.ofDays(30)).toString(), false,
                    saved.sourceInfo(), saved.targetInfo(), saved.outcomes().size(), false, activePreparations.contains(name), false, saved.tablesTotal()));
        }
        details.sort(Comparator.comparing(PlanStorage::expiresAt).reversed());
        List<String> orphans = new ArrayList<>();
        if (Files.isDirectory(work)) try (var entries = Files.list(work)) {
            for (Path entry : entries.filter(Files::isDirectory).toList()) if (!known.contains(entry.getFileName().toString())) orphans.add(entry.getFileName().toString());
        }
        long freeDisk = Files.exists(work) ? Files.getFileStore(work).getUsableSpace() : 0;
        return new StorageView(totalBytes, freeDisk, reclaimable, List.copyOf(details), List.copyOf(orphans));
    }
    private long directoryBytes(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) return 0;
        try (var files = Files.walk(directory)) { return files.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum(); }
    }
    private void cleanup() throws IOException {
        for (Plan plan : List.copyOf(plans.values())) if (Instant.parse(plan.view.expiresAt()).isBefore(Instant.now()) && !active(plan) && !plan.executed.get()) { plans.remove(plan.view.id()); if (plan.jobId != null) jobs.remove(plan.jobId); erase(plan.directory); }
        // Interrupted preparations expire like plans, 30 days after their last staged table.
        for (Path folder : workFolders()) {
            PartialManifest saved = readPartial(folder);
            if (saved != null && !activePreparations.contains(folder.getFileName().toString()) && Instant.parse(saved.updatedAt()).plus(Duration.ofDays(30)).isBefore(Instant.now())) erase(folder);
        }
    }
    private boolean active(Plan plan) { Job job = plan.jobId == null ? null : jobs.get(plan.jobId); return job != null && (job.state.equals("RUNNING") || job.state.equals("QUEUED") || job.revert!=null&&(job.revert.running||Set.of("RUNNING","QUEUED").contains(job.revert.state))); }
    private void erase(Path directory) throws IOException {
        if (directory == null || !directory.normalize().getParent().equals(work)) throw new IllegalArgumentException("Invalid artifact directory");
        try (var files = Files.walk(directory)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private void requestCancel(TransferProgress progress){
        progress.cancel=true;
        // Parallel readers run their own statements; each is cancelled too.
        List<Statement> statements=new ArrayList<>();if(progress.statement!=null)statements.add(progress.statement);
        for(TransferProgress reader:progress.children)if(reader.statement!=null)statements.add(reader.statement);
        for(Statement statement:statements)try{canceller.execute(()->{try{statement.cancel();}catch(SQLException ignored){}});}catch(RejectedExecutionException ignored){}
    }
    private void releaseLock(){try{if(workLock.isValid())workLock.release();lockChannel.close();}catch(IOException ignored){}}
    @Override @PreDestroy public void close() {
        preparations.values().forEach(p->requestCancel(p.progress));jobs.values().stream().filter(j->Set.of("QUEUED","RUNNING").contains(j.state)).forEach(j->requestCancel(j.progress));
        jobs.values().stream().filter(j->j.revert!=null&&Set.of("QUEUED","RUNNING").contains(j.revert.state)).forEach(j->requestCancel(j.revert.progress));
        preparer.shutdown(); executor.shutdown();
        try{executor.awaitTermination(5,TimeUnit.SECONDS);preparer.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        canceller.shutdown();
        if(executor.isTerminated()&&preparer.isTerminated())releaseLock();
        else {Thread release=new Thread(()->{try{while(!executor.awaitTermination(1,TimeUnit.SECONDS)||!preparer.awaitTermination(1,TimeUnit.SECONDS)){}releaseLock();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"migration-lock-release");release.setDaemon(true);release.start();}
    }
}

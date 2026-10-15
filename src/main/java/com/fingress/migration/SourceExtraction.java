package com.fingress.migration;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.fingress.migration.Model.*;

/** Reads the selected source tables of a plan: definitions always, data when preparing. On PostgreSQL up to
 * MAX_READERS tables are read at once on separate connections that all start from one exported snapshot, so the
 * staged copy is as consistent as a single reader's. Other sources use one reader. Every finished table is passed
 * to a listener (which saves it so an interrupted preparation can continue), and a dropped connection costs only
 * the table being read on it: that reader reconnects and reads the table again. */
final class SourceExtraction {
    /** One selected table. excluded holds the reason when the table cannot be migrated (table is then null).
     * Byte/file counts and hashes let a later attempt verify the staged files before reusing them. */
    record Outcome(int index, String selected, Table table, String name, String dataFile, long rows, MigrationOptions options,
                   Long estimatedRows, long dataBytes, int sidecars, long sidecarBytes, String excluded, Map<String,String> hashes) {
        long stagedBytes() { return dataBytes + sidecarBytes; }
    }
    interface Listener { void completed(Outcome outcome) throws IOException; }

    /** Parallel readers. The connection budget leaves three background connections, and a busy budget means fewer.
     * Start with -Dmigration.readers=1 or 2 to put less load on a busy source. */
    static int MAX_READERS = Math.max(1, Math.min(3, Integer.getInteger("migration.readers", 3)));
    /** Waits between attempts to reach the source again after a dropped connection: about six and a half minutes. */
    static long[] RECONNECT_DELAYS_MS = {5_000, 10_000, 15_000, 30_000, 30_000, 60_000, 60_000, 60_000, 60_000, 60_000};

    private final DatabaseGateway db; private final PlanRequest request; private final Path directory;
    private final MigrationOptions planOptions; private final boolean auto, prepared;
    private final TransferProgress progress; private final List<String> tables; private final Map<String, TableSelection> selections;
    private final AtomicLong budget;
    private final AtomicInteger reconnects = new AtomicInteger();
    private final ConcurrentLinkedQueue<Integer> pending = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<Outcome> finished = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<TransferProgress> readers = new CopyOnWriteArrayList<>();
    private volatile boolean sharedSnapshot;

    SourceExtraction(DatabaseGateway db, PlanRequest request, Path directory, MigrationOptions planOptions, boolean auto, boolean prepared,
                     TransferProgress progress, List<String> tables, Map<String, TableSelection> selections, AtomicLong budget) {
        this.db = db; this.request = request; this.directory = directory; this.planOptions = planOptions; this.auto = auto; this.prepared = prepared;
        this.progress = progress; this.tables = tables; this.selections = selections; this.budget = budget;
    }
    int reconnects() { return reconnects.get(); }
    boolean sharedSnapshot() { return sharedSnapshot; }

    /** Reads every table whose index is not in done. Outcomes are returned (and passed to the listener) in the
     * order the tables finish; on failure the tables that did finish are still passed to the listener first. */
    List<Outcome> run(Set<Integer> done, Listener listener) throws Exception {
        for (int i = 0; i < tables.size(); i++) if (!done.contains(i)) pending.add(i);
        int count = pending.size(); List<Outcome> outcomes = new ArrayList<>();
        if (count == 0) return outcomes;
        List<Connection> connections = open(Math.min(MAX_READERS, count));
        ExecutorService pool = Executors.newFixedThreadPool(connections.size(), new ThreadFactory() {
            final AtomicInteger number = new AtomicInteger();
            public Thread newThread(Runnable r) { Thread t = new Thread(r, "migration-reader-" + number.incrementAndGet()); t.setDaemon(true); return t; }
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Connection connection : connections) {
                TransferProgress reader = progress.reader(); readers.add(reader);
                futures.add(pool.submit(() -> { read(connection, reader); return null; }));
            }
            while (outcomes.size() < count) {
                Outcome outcome = finished.poll(200, TimeUnit.MILLISECONDS);
                if (outcome != null) { record(outcome, outcomes, listener); continue; }
                if (futures.stream().allMatch(Future::isDone)) break;
            }
            for (Future<?> future : futures) try { future.get(); } catch (ExecutionException e) { fail(e.getCause()); }
            Outcome late;
            while ((late = finished.poll()) != null) record(late, outcomes, listener);
        } finally {
            pool.shutdownNow();
            for (TransferProgress reader : readers) progress.release(reader);
        }
        Throwable cause = failure.get();
        if (cause instanceof Error error) throw error;
        if (cause != null) throw (Exception) cause;
        if (outcomes.size() < count) throw new IllegalStateException("Source readers stopped before every table was read");
        return outcomes;
    }
    private void record(Outcome outcome, List<Outcome> outcomes, Listener listener) {
        outcomes.add(outcome); progress.tablesDone++;
        try { listener.completed(outcome); } catch (IOException e) { fail(e); }
    }
    /** The first failure wins; the other readers are stopped and their running statements cancelled. */
    private void fail(Throwable cause) {
        if (cause == null || !failure.compareAndSet(null, cause)) return;
        for (TransferProgress reader : readers) {
            reader.cancel = true; Statement statement = reader.statement;
            if (statement != null) try { statement.cancel(); } catch (SQLException ignored) {}
        }
    }

    /** Opens the readers. The first exports its snapshot and the others start on it; a source that cannot share
     * a snapshot, or a connection budget in use elsewhere, means fewer readers rather than a failure. */
    private List<Connection> open(int wanted) throws Exception {
        List<Connection> connections = new ArrayList<>();
        Connection first = db.connect(request.source());
        try {
            db.snapshot(first, request.sourceDialect());
            String snapshot = null;
            if (wanted > 1 && db.parallelReads(first, request.sourceDialect())) {
                try { snapshot = db.exportSnapshot(first, request.sourceDialect()); sharedSnapshot = snapshot != null; }
                catch (SQLException e) { first.rollback(); wanted = 1; } // e.g. a standby server cannot export snapshots
            } else wanted = 1;
            connections.add(first);
            for (int i = 1; i < wanted; i++) {
                Connection extra = null;
                try { extra = db.connect(request.source()); db.snapshot(extra, request.sourceDialect()); db.importSnapshot(extra, snapshot); connections.add(extra); }
                catch (SQLException e) { closeQuietly(extra); break; }
            }
            return connections;
        } catch (Exception | Error e) {
            closeQuietly(first); for (Connection connection : connections) closeQuietly(connection);
            throw e;
        }
    }

    private void read(Connection opened, TransferProgress reader) throws Exception {
        DatabaseGateway.configure(planOptions); DatabaseGateway.configureBackground(true);
        Connection connection = opened; String phase = reader.phase;
        try {
            Integer index;
            while (failure.get() == null && (index = pending.poll()) != null) {
                String selected = tables.get(index);
                int attemptsLeft = RECONNECT_DELAYS_MS.length;
                while (true) {
                    reader.check(); reader.table = selected;
                    long rowsBefore = reader.rowsRead, bytesBefore = reader.bytes;
                    try { finished.put(readTable(connection, reader, index, selected)); break; }
                    catch (IllegalArgumentException e) {
                        discard(directory, index); reader.rowsRead = rowsBefore; reader.bytes = bytesBefore;
                        if (e instanceof DatabaseGateway.RowsTooLarge || e instanceof DatabaseGateway.RowLimitExceeded) throw e;
                        finished.put(new Outcome(index, selected, null, null, null, 0, null, null, 0, 0, 0, e.getMessage(), Map.of())); break;
                    } catch (SQLException e) {
                        if (!lostConnection(e, connection) || attemptsLeft == 0) throw e;
                        discard(directory, index); reader.rowsRead = rowsBefore; reader.bytes = bytesBefore;
                        closeQuietly(connection); connection = null;
                        int[] left = {attemptsLeft};
                        connection = reconnect(reader, selected, left, e);
                        attemptsLeft = left[0]; reader.phase = phase; reconnects.incrementAndGet();
                    }
                }
                reader.table = "";
            }
            if (connection != null) connection.rollback();
        } catch (Exception | Error e) {
            if (failure.get() == null && reader.table != null && !reader.table.isBlank()) progress.table = reader.table; // named in the failure message
            fail(e); throw e;
        }
        finally { closeQuietly(connection); reader.table = ""; DatabaseGateway.configure(null); DatabaseGateway.configureBackground(false); }
    }

    private Outcome readTable(Connection connection, TransferProgress reader, int index, String selected) throws Exception {
        Dialect sourceDialect = request.sourceDialect(), targetDialect = request.targetDialect();
        discard(directory, index); // leftovers from an earlier attempt at this table
        Table source = db.inspect(connection, request.sourceSchema(), selected, sourceDialect);
        if (source == null) throw new IllegalArgumentException("Source table not found: " + selected);
        reader.check();
        Table filterTable = source; TableSelection selection = selections.get(selected);
        TableProjection.where(filterTable, selection, sourceDialect, new ArrayList<>());
        source = TableProjection.select(source, selection, sourceDialect);
        Table table = convert(source, selection, selections, sourceDialect, targetDialect);
        Path path = directory.resolve(dataFile(index));
        // Each table's settings are fixed here and reused for reading, loading, verifying and reverting it.
        MigrationOptions options = auto
                ? AutoTuning.forTable(planOptions, db.tableStats(connection, request.sourceSchema(), selected, sourceDialect), Runtime.getRuntime().maxMemory(), DatabaseGateway.largeValues(source))
                : AutoTuning.withFetchSize(planOptions, DatabaseGateway.fetchSize(source, planOptions));
        DatabaseGateway.Export export;
        if (request.includeData() && prepared) export = db.exportTyped(connection, source, filterTable, selection, request.sourceSchema(), sourceDialect, targetDialect, path, budget, options, reader);
        else { Files.createFile(path); export = new DatabaseGateway.Export(0, 0, 0, 0); }
        Long estimate = !prepared && (selection == null || selection.filters() == null || selection.filters().isEmpty())
                ? db.estimateRows(connection, request.sourceSchema(), selected, sourceDialect) : null;
        return new Outcome(index, selected, table, table.name().in(targetDialect), path.getFileName().toString(), export.rows(), options, estimate,
                export.dataBytes(), export.sidecars(), export.sidecarBytes(), null, artifactHashes(directory, path));
    }

    static Map<String,String> artifactHashes(Path directory, Path rows) throws IOException {
        Map<String,String> hashes = new TreeMap<>();
        hashes.put(rows.getFileName().toString(), RowStore.hash(rows));
        String prefix = "lob-" + sidecarPrefix(rows) + "-";
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Invalid artifact link");
                hashes.put(file.getFileName().toString(), RowStore.hash(file));
            }
        }
        return Map.copyOf(hashes);
    }

    /** The target definition of a projected source table. */
    static Table convert(Table source, TableSelection selection, Map<String, TableSelection> selections, Dialect sourceDialect, Dialect targetDialect) {
        if (selection != null && selection.rename() != null && !source.columns().stream().map(c -> c.name().in(sourceDialect)).toList().containsAll(selection.rename().keySet()))
            throw new IllegalArgumentException("Unknown renamed column");
        List<Column> converted = new ArrayList<>();
        for (Column c : source.columns()) {
            if (sourceDialect == Dialect.POSTGRESQL && targetDialect == Dialect.ORACLE && c.defaultValue() != null && c.defaultValue().sql().equals("''"))
                throw new IllegalArgumentException("Empty-string default would become NULL in Oracle");
            converted.add(new Column(TableProjection.rename(c.name(), selection, sourceDialect), c.generated() ? c.type().identityTarget(targetDialect) : c.type().target(targetDialect), c.nullable(), c.defaultValue(), c.generated(), c.always()));
        }
        if (converted.stream().map(c -> c.name().in(targetDialect)).distinct().count() != converted.size()) throw new IllegalArgumentException("Mapped column names collide");
        return new Table(TableProjection.renameTable(source.name(), selection, sourceDialect), converted, TableProjection.keys(source, selection, selections, sourceDialect), source.warnings());
    }

    /** Waits and reconnects after a dropped connection, using this reader's remaining attempts. */
    private Connection reconnect(TransferProgress reader, String table, int[] attemptsLeft, SQLException cause) throws Exception {
        SQLException last = cause;
        while (attemptsLeft[0] > 0) {
            int attempt = RECONNECT_DELAYS_MS.length - attemptsLeft[0]; long delay = RECONNECT_DELAYS_MS[attempt]; attemptsLeft[0]--;
            reader.phase = "RECONNECTING";
            reader.table = table + " (connection lost; attempt " + (attempt + 1) + " of " + RECONNECT_DELAYS_MS.length + " in " + Math.max(1, delay / 1000) + "s)";
            for (long waited = 0; waited < delay; waited += 250) { reader.check(); Thread.sleep(Math.min(250, delay - waited)); }
            Connection connection = null;
            try { connection = db.connect(request.source()); db.snapshot(connection, request.sourceDialect()); reader.table = table; return connection; }
            catch (SQLException e) { last = e; closeQuietly(connection); }
        }
        throw last;
    }

    static String dataFile(int index) { return "data-" + index + ".rows"; }
    private static final java.util.regex.Pattern DATA_FILE = java.util.regex.Pattern.compile("data-(\\d+)\\.rows");
    /** The 8 hex digits that start the names of one table's LOB files, derived from its row file's index. */
    static String sidecarPrefix(Path dataFile) {
        var match = DATA_FILE.matcher(dataFile.getFileName().toString());
        return match.matches() ? String.format("%08x", Integer.parseInt(match.group(1))) : UUID.randomUUID().toString().substring(0, 8);
    }
    /** Removes one table's staged files: its row file and the LOB files named after it. Other tables, possibly
     * being written at the same time, are untouched. Cheap when the table has no row file yet. */
    static void discard(Path directory, int index) throws IOException {
        Path rows = directory.resolve(dataFile(index));
        if (!Files.exists(rows)) return;
        String prefix = "lob-" + sidecarPrefix(rows) + "-";
        try (var files = Files.list(directory)) { for (Path file : files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(rows);
    }
    /** As discard, for a table known by its row file name (older plans use names this cannot attribute). */
    static void discard(Path directory, String dataFile) throws IOException {
        var match = DATA_FILE.matcher(dataFile);
        if (match.matches()) discard(directory, Integer.parseInt(match.group(1))); else Files.deleteIfExists(directory.resolve(dataFile));
    }

    static boolean lostConnection(SQLException failure, Connection connection) {
        for (Throwable t = failure; t != null; t = t.getCause())
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) return true;
        try { return connection == null || connection.isClosed() || !connection.isValid(5); } catch (SQLException e) { return true; }
    }
    static void closeQuietly(Connection connection) { if (connection != null) try { connection.close(); } catch (SQLException ignored) {} }
}

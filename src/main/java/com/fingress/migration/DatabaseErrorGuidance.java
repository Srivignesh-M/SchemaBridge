package com.fingress.migration;

import java.sql.SQLException;
import java.util.Map;

/**
 * Maps known, safe database error codes to specific actionable guidance for users.
 * Never includes the original driver message: some vendor messages embed connection
 * secrets (driver-specific URL echoes) or literal row/column values (for example
 * PostgreSQL's unique-violation "Detail:" line quotes the conflicting value). Unknown
 * codes fall back to the pre-existing generic, code-only message, which is itself safe.
 *
 * Oracle's JDBC driver always sets a nonzero vendor error code (the ORA-NNNNN number);
 * PostgreSQL's driver always sets it to zero and relies on the five-character SQLState
 * instead. That split is used to pick which table to consult, with no dialect parameter
 * needed and no risk of the two vendors' codes colliding.
 */
final class DatabaseErrorGuidance {
    private DatabaseErrorGuidance() {}

    private static final Map<Integer, String> ORACLE = Map.ofEntries(
            Map.entry(1466, "The source table changed during extraction (ORA-01466). The staged snapshot is stale — do not resume this job. Start a fresh plan once the source is quiescent."),
            Map.entry(1654, "The target tablespace is full (ORA-01654). Free space or extend the tablespace, then retry; tables already committed are unaffected."),
            Map.entry(1536, "The target quota was exceeded (ORA-01536). Raise the target user's tablespace quota, then retry; tables already committed are unaffected."),
            Map.entry(1, "A unique constraint was violated on the target (ORA-00001). The conflicting row's values are not shown; check the target table for a pre-existing conflicting row before retrying."),
            Map.entry(2291, "A foreign key reference was missing on the target (ORA-02291). Load the referenced parent table first, or check the target for a missing parent row."),
            Map.entry(1017, "Oracle rejected the configured username or password (ORA-01017). Re-check the saved credentials; this tool never retries with different credentials automatically."),
            Map.entry(1031, "Oracle reported insufficient privileges (ORA-01031). The configured account needs additional grants for this operation; see README.md/CLAUDE.md for what this feature requires."),
            Map.entry(12170, "The Oracle connection timed out before completing the operation (ORA-12170). Check network reachability and listener status, then retry."),
            Map.entry(12541, "No Oracle listener was reachable at the configured host and port (ORA-12541). Check the host, port, and that the listener is running."),
            Map.entry(12514, "The Oracle listener does not recognize the configured service name (ORA-12514). Check the configured service name.")
    );

    private static final Map<String, String> POSTGRESQL = Map.ofEntries(
            Map.entry("23505", "A unique constraint was violated on the target (unique_violation). The conflicting row's values are not shown; check the target table for a pre-existing conflicting row before retrying."),
            Map.entry("23503", "A foreign key reference was missing on the target (foreign_key_violation). Load the referenced parent table first, or check the target for a missing parent row."),
            Map.entry("53100", "The target's disk is full (disk_full). Free space on the target's storage, then retry; tables already committed are unaffected."),
            Map.entry("57014", "The operation was cancelled or exceeded its configured query timeout (query_canceled). If this was not a deliberate cancellation, raise the timeout and retry."),
            Map.entry("08006", "The database connection was lost (connection_failure). Check network reachability and that the database is still running, then retry or resume."),
            Map.entry("08001", "Could not establish a connection to the database (sqlclient_unable_to_establish_sqlconnection). Check the host, port, and that the database is reachable."),
            Map.entry("28P01", "PostgreSQL rejected the configured username or password (invalid_password). Re-check the saved credentials."),
            Map.entry("42501", "PostgreSQL reported insufficient privileges (insufficient_privilege). The configured account needs additional grants for this operation."),
            Map.entry("40P01", "A deadlock was detected on the target (deadlock_detected). This is usually transient contention from another session; retry."),
            Map.entry("55P03", "Could not acquire a lock on the target within the configured timeout (lock_not_available). Retry once any other session using the target has finished.")
    );

    /** A safe, human-actionable message for this error. Always includes the SQLState/code identity;
     * only ever adds guidance text from the fixed tables above, never anything from {@code error.getMessage()}. */
    static String describe(SQLException error) {
        String identity = "SQLState " + error.getSQLState() + ", code " + error.getErrorCode();
        String specific = error.getErrorCode() != 0 ? ORACLE.get(error.getErrorCode())
                : error.getSQLState() != null ? POSTGRESQL.get(error.getSQLState()) : null;
        return specific != null ? specific + " (" + identity + ")" : "Database request failed (" + identity + "). Check connection, permissions and database compatibility.";
    }
}

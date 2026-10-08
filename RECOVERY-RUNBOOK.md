# Migration recovery runbook

Use this procedure when a migration reports `RECOVERY_REQUIRED`, the application stops during a job, or a connection is lost around a commit. It helps preserve evidence and reconcile target state; it does not make uncertain work safe to replay.

## Stop and preserve evidence

1. Do not start the same migration again, delete its saved history, remove its work directory, or change the target to make the job appear complete.
2. Keep the application data directory and logs intact. The default Windows location is `%LOCALAPPDATA%\Fingress SQL Migration`; the application log is under `logs\application.log`. The desktop host log is `logs\desktop.log`.
3. In **Saved migration history**, open the job and save its report. Record the job ID, plan ID, target dialect/schema, affected tables, final state, table messages, and rows the job reports as committed. Protect reports and staged files as database data.
4. If the application restarted, treat any job changed from `RUNNING` or `QUEUED` to `RECOVERY_REQUIRED` as uncertain. A missing or damaged job journal also requires reconciliation.

## Reconcile with the database owner

Use a DBA or target owner with read-only access first. Confirm the target server, database, schema and table names against the report. For each affected table, compare target rows with the migration's known committed tables and stable keys. Where counts alone cannot distinguish pre-existing rows from this migration, compare key sets and relevant values or use an independently retained source snapshot. Check constraints, indexes, identity state and any table creation reported by the job separately.

The application reports committed rows recorded by its journal; this value is not proof of what the target committed when the connection failed during commit. A database may have committed the transaction even though the application never received the response. Oracle DDL can also commit independently of later table work. Do not infer that a table is empty or safe to reload from a failed status or a partial report.

Do not delete target rows, drop tables, reset identities, or rerun SQL as a recovery shortcut. Agree with the database owner on any corrective change, record it outside the application, and retain the before/after evidence.

## Resume only when the application allows it

The app exposes resume only for a `FAILED` or `CANCELLED` job whose current table transaction is known to have rolled back. Earlier committed tables remain committed. Re-enter the original target connection and use the job's **Resume** action; the application checks the target identity and rechecks schema and staged data before continuing.

`RECOVERY_REQUIRED` jobs are deliberately not resumable. The application has no control for clearing that state after manual reconciliation. Once the target owner has resolved the uncertain work, create a new reviewed plan from the reconciled target state and choose table actions accordingly. Do not use a new plan to replay rows until duplicate-key and data-validation consequences are understood.

## Escalate when state cannot be proven

If keys, values, DDL outcomes, or the commit result cannot be established, leave the job in `RECOVERY_REQUIRED`, preserve its files, and ask the database owner to decide how to proceed. Do not retry automatically. This release has no target-side transaction ledger and cannot prove or replay an uncertain commit across a lost connection.

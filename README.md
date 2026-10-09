# SchemaBridge

For the Windows desktop application and setup EXE build instructions, see [WINDOWS-README.md](WINDOWS-README.md).

For the smaller Windows package using an employee's installed Java 21+, see [SYSTEM-JAVA-README.txt](SYSTEM-JAVA-README.txt). Build it with `scripts/package-system-java.ps1`; this edition omits the bundled Java runtime.

The private local transfer artifacts are described in [STAGING-FORMAT.md](STAGING-FORMAT.md); they are versioned with the saved plan format, not a public interchange format.

For interrupted or uncertain jobs, follow the [migration recovery runbook](RECOVERY-RUNBOOK.md) before attempting any further writes.

A standalone Java 21 / Spring Boot application for Oracle ↔ PostgreSQL table and data migration. Open the browser UI at **http://localhost:8098**. This module builds independently and is intentionally not added to the platform aggregator.

## Run

For a terminal-only migration using the same executable JAR, see [CLI-README.md](CLI-README.md). Users need Java 21, not Maven. Start with `java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli init --output migration.json`, then use `cli plan` or `cli migrate --config migration.json`. The JAR includes both JDBC drivers; no HTTP server starts in CLI mode.

Install **JDK 21** and **Maven 3.6.3 or newer**, then check `java -version` and `mvn -version`. Maven must use Java 21. Run the commands below in PowerShell from the repository root (the directory containing `fg-sql-migration`).

From the repository root:

```powershell
mvn -f fg-sql-migration/pom.xml spring-boot:run
```

Or build and run a portable executable JAR:

```powershell
mvn -f fg-sql-migration/pom.xml clean verify
java -jar fg-sql-migration/target/fg-sql-migration-1.0.0-SNAPSHOT.jar --migration.work-dir=fg-sql-migration/work
```

Requires Java 21, Maven, and initial access to Maven Central. Oracle and PostgreSQL JDBC drivers are included. No Node build is needed. Port 8098 and loopback binding are configured in `application.properties`.

Once the startup log says the application has started, open **http://localhost:8098**. Keep the terminal running; press **Ctrl+C** to stop. If port 8098 is occupied, stop the previous instance or append `--server.port=8099` to the JAR command and open that port. Stop a running JAR before rebuilding it on Windows.

For a first test without a database, choose SQL to download, click **Load example**, analyse and download. For a connected flow, PostgreSQL needs a database name (for example `postgres`); Oracle needs a service name. Connect, choose a schema and source tables, then analyse. The target schema must already exist.

## Download filenames and information

The ZIP and its enclosing folder use `SOURCE_to_TARGET_yyyy-MM-dd_HH-mm-ss-SSSZ`, with the current download timestamp in UTC. Example:

```text
ORACLE_to_POSTGRESQL_2026-10-05_13-00-00-123Z/
  migration-info.txt
  README.txt
  report.json
  actions.json
  01-tables.sql
  02-data.sql
  03-post-load.sql
  tables/
    customers.sql
```

Each selected table has one file named after the table, with `-- DDL: table definition` and `-- DML: table data` comment headings. DML-only selections have an explanatory comment instead of table-creation SQL; skipped tables have no file. Invalid filename characters are replaced with underscores and colliding names receive numeric suffixes. SQL identifiers inside the files remain unchanged.

Execute the three numbered scripts in order, **or** use the table files: all DDL sections first, DML sections in the load order listed in `migration-info.txt`, then `03-post-load.sql` to finish identities, foreign keys and indexes. Do not run both copies of the same SQL. The per-table DDL contains the initial table definition; post-load constraints and indexes remain in the shared final script so cross-table dependencies are preserved.

`migration-info.txt` includes source and target dialects, connection host/port/database when supplied, source and target schemas, selected tables, target compatibility, chosen actions, exported row counts, issues and warnings. It excludes credentials. Downloading only generates files; it does not create tables. Download again after execution to include the recorded job status, per-table completion/partial-failure details and committed row counts. For uploaded SQL, the original database name is unavailable and is labelled accordingly; source qualifiers map to the selected target schema.

Ordered lifecycle scripts retain `converted-script.review.sql` instead of per-table files because splitting interleaved changes would alter execution order. They receive the same timestamped folder and migration information. Blocked ordinary plans contain reports and `BLOCKED.txt` instead of executable SQL.

## Workflows

### Bulk folder conversion

In SQL to download or SQL to database, use **Or choose a folder** to select a directory containing your scripts. Subfolders are included. The application reads `.sql`, `.ddl`, `.dml` and `.txt` files, ignores other extensions, and displays the file count and relative paths in processing order. You can also select multiple individual files using **Upload SQL files**.

Files are combined into one conversion plan in natural filename/path order (1, 2, ... 10). All files must use the selected source dialect and describe one consistent source model; separate scripts redefining the same table are not independent jobs. Put definitions before data using filenames such as `01-schema.sql` and `02-data.sql`. Review the combined editor, choose the target dialect/schema, analyse, and download one ZIP containing the per-table files and reports. The combined UTF-8 input limit remains 10 MB. Selecting another folder or file batch replaces the previous input. Unsupported statements and ordered lifecycle scripts follow the existing reporting rules described below.

1. **SQL → download:** select dialects and target schema, upload UTF-8 DDL/DML or paste SQL, analyse, select table actions, download ZIP.
2. **SQL → database:** also connect to the target and select its schema. Analyse checks existing tables. Run the selected plan and download the final execution report.
3. **Database → download:** connect to the source, select schema/tables, optionally include data, choose target dialect/schema, analyse and download.
4. **Database → database:** configure both connections, select source tables, analyse conflicts, select actions, run and download the final report.

Use `examples/oracle-ddl.sql` and `examples/oracle-dml.sql` together for an offline demonstration, or the built-in **Load example** button. `examples/postgresql.sql` demonstrates the reverse direction. Target schemas must already exist for execution. Oracle's connection database field is a **service name**, not a SID.

For PostgreSQL, selecting the dialect fills the visible database-name field with `postgres`. Replace this with the actual database containing your schemas. The database name is separate from the engine selection, username and schema name. Blank names are blocked before a connection request, and the API returns a specific validation error if called directly without one. Custom database names are remembered separately per dialect while the page remains open.

### Ordered scripts versus table cloning

Uploads containing ALTER statements, UPDATE/DELETE/MERGE, standalone indexes/sequences, multi-table inserts, renames, drops, comments or permissions now enter **ordered script review**. The report shows every statement with a number, conversion status and findings. The preview and `converted-script.review.sql` download keep source order. Source errors and unsupported statements are marked in place, never silently removed. Review drafts are available even when individual statements have errors.

The ordered Oracle → PostgreSQL converter supports ADD/MODIFY/RENAME/DROP COLUMN, ADD constraints, regular/unique/expression indexes, sequences and NEXTVAL/CURRVAL, unconditional and conditional INSERT ALL, INSERT FIRST, basic UPDATE/DELETE, native MERGE with a constant SELECT source (PostgreSQL 15+), DROP, RENAME, TRUNCATE, COMMENT and table GRANT/REVOKE. It is a bounded grammar, not a universal SQL implementation.

Automatic execution and table action filtering remain available for ordinary table-cloning plans. They are **disabled for ordered lifecycle scripts**: regrouping an interleaved create/update/drop script into per-table insert-only jobs would change its behavior. Review and run such downloads externally after fixing source errors, checking warnings and validating the target version. Source comments are not copied, but literal strings and Unicode data are preserved.

The supplied 63-statement Oracle edge-case file is a regression fixture at `src/test/resources/oracle-edge-cases.sql`. Its conversion reports three genuine source problems: an index and an UPDATE reference `EMPLOYEE.DEPT_ID`, which is never declared, and an INSERT supplies an Oracle empty string (NULL) for required `EMPLOYEE.EMP_NAME`. The converter does not invent that column or substitute a fabricated name. Other statements receive converted SQL with semantic-review notes where applicable.

## Existing target tables

### LCNC datasource selection

Both source and target connection panels retain **Manual entry** and add **Load / refresh LCNC datasources**. Load the list, choose a saved connection, then click **Connect & load schemas**. Saved host, port, database, username and masked password populate the form; choose Manual entry to edit them. PostgreSQL `currentSchema` or the Oracle username is preselected if that schema is accessible.

The catalogue reads `fg_solutions.fg_datasource` from a separately configured PostgreSQL database. It includes only `code=JDBC`, `active_code=ACTV`, `status_code=APPROVED`, `is_master_version=true` rows. Unsupported configurations remain visible with a reason. Selecting a row rechecks these filters. The list does not include credentials; only the explicitly selected entry returns its credentials to this local browser form, with caching disabled. Credentials are not added to exported reports.

Configure `MIGRATION_CATALOG_URL`, `MIGRATION_CATALOG_USERNAME`, `MIGRATION_CATALOG_PASSWORD` and optionally `MIGRATION_CATALOG_SCHEMA` before starting. Alternatively put `migration.catalog.url`, `.username`, `.password` and `.schema` properties in **catalog-local.properties**, which is ignored by Git. The application loads that file from its working directory. To use the module's local file, start from the module directory:

```powershell
cd fg-sql-migration
mvn package -DskipTests
java -jar target/fg-sql-migration-1.0.0-SNAPSHOT.jar
```

From the repository root, pass `--spring.config.additional-location=optional:file:fg-sql-migration/catalog-local.properties` to the JAR command instead. Local secrets are not packaged into the JAR. The configured catalogue user needs SELECT access to `fg_datasource`; catalogue reads never modify it or connect to every listed environment.

Supported saved URLs: PostgreSQL host/port/database (optional `currentSchema` and `sslmode`), Oracle `@host:port:SID`, `@host:port/service`, and `@//host:port/service`. Other JDBC options/address forms are explicitly reported as unsupported. Driver classes must match Oracle or PostgreSQL. Encrypted/external-secret credentials are not resolved by this standalone application.

**Connections:** this app does not create Hikari pools or keep idle JDBC sessions. Catalogue requests are serialized and use one short-lived read-only connection with a 15-second query timeout. Connections, statements and result sets close using try-with-resources, including error paths. A shared four-connection ceiling covers catalogue, source and target JDBC sessions in this application process; busy requests fail instead of opening more connections. Existing LCNC platform pools are unaffected. PostgreSQL sessions use application name `fg-sql-migration` for monitoring. Migration jobs retain their connection while actively executing and close it when the job finishes or fails.

The preflight report includes all supported tables in a single view:

Metadata-only analysis does not extract rows. For unfiltered tables it may show an approximate source row count from PostgreSQL or Oracle catalog statistics; the estimate can be unavailable or stale and is neither an exact count nor an estimate of filtered rows. Data-size estimates are not currently provided.

| Status | Actions |
| --- | --- |
| New | Create structure and load supplied data; skip |
| Already exists, exact compatible definition | DML only; skip |
| Definition mismatch or unsupported target metadata | Skip; resolve externally and analyse again |
| Target not connected | Download only |

Existing tables default to **Skip**. **DML only for all matches** selects eligible tables together. Client requests cannot override the allowed action list. For DML-only tables, no table-creation SQL is generated or executed.

Comparison uses mapped names, converted types, length, numeric precision/scale, timestamp precision, nullability, defaults, identity behavior, primary/unique keys and foreign keys. Column order can differ because inserts name every column. Additional target columns currently require explicit mapping and are treated as mismatches. Nonunique index differences do not block DML-only loading.

The target connection is bound to the analysed host, port, database, dialect and schema. Metadata is checked again before any writes. Changes after this recheck and concurrent target writers are **not supported**: use a maintenance window or an isolated target. Required missing parent tables cannot be skipped while their children load. Cyclic/self-referencing foreign keys on existing tables are blocked for DML-only execution.

## Conversion coverage

This release uses a bounded, tokenized grammar. It does not claim universal SQL translation.

Supported file statements:

- `CREATE TABLE` with columns, literal/default `CURRENT_TIMESTAMP`, `SYSDATE`, `SYSTIMESTAMP`, `NOT NULL`, primary keys, unique/check constraints, foreign keys with default actions, and `GENERATED BY DEFAULT` / `GENERATED ALWAYS AS IDENTITY` without custom options.
- `INSERT ... VALUES` with one or multiple rows, optional explicit column lists, quoted string and numeric literals, `NULL`, SQL date/time literals and supported bounded expressions.
- `COMMIT` is accepted; the execution service owns transaction boundaries.
- Quoted identifiers, escaped quotes, multiline strings, line comments, nested block comments, and semicolons inside strings.

Common type mappings:

| Source | Target |
| --- | --- |
| Oracle `NUMBER(p,s)` | PostgreSQL `NUMERIC(p,s)` |
| Oracle `VARCHAR2(n)` | PostgreSQL `VARCHAR(n)` |
| Oracle `DATE` | PostgreSQL `TIMESTAMP(0)` (retains time) |
| PostgreSQL integers | Oracle numeric types with explicit precision |
| PostgreSQL `VARCHAR(n)` | Oracle `VARCHAR2(n CHAR)` within conservative length limits |
| `TEXT` / `CLOB` | Other dialect's large character type (large data loading is limited below) |
| Oracle `RAW(n)` / `BLOB` | PostgreSQL `BYTEA`; RAW length is preserved with an octet-length CHECK |
| Oracle `TIMESTAMP WITH LOCAL TIME ZONE` | PostgreSQL `TIMESTAMPTZ`, with a timezone-policy review note |
| Oracle `NUMBER` identity without explicit precision | PostgreSQL `BIGINT`, with an explicit 64-bit range warning |

Unsupported statements create explicit issues and block automatic execution. Ordinary clone-plan downloads contain the report and `BLOCKED.txt` when blocked; ordered scripts provide a clearly marked review draft with numbered gaps. Nothing unsupported is passed through to the database. DML-only uploads need supporting `CREATE TABLE` metadata. Source schema qualifiers map to the selected target schema; multiple source schemas in one script are rejected.

Not yet supported: general `INSERT SELECT`, arbitrary expressions/functions, PL/SQL or PL/pgSQL routines, packages, triggers, views, partitioning, arbitrary sequence options, arrays, JSON, SQL*Plus/psql commands, dump archives, or compressed uploads. Multi-table insert and MERGE source queries currently accept constant SELECT expressions from DUAL; general table/query sources require further rules. Simple indexes can be extracted from a connected database and are rebuilt after data loading. Ordered file conversion preserves explicit constraint/index names; database-cloning exports do not preserve every original object name.

Unbounded PostgreSQL numeric → Oracle and PostgreSQL empty-string → Oracle are blocked rather than silently narrowing values or changing them to NULL. Oracle empty strings translate to NULL, and known NOT NULL violations are reported. Oracle byte/character length semantics require review. Explicit oversized identity ranges/custom options are rejected; unbounded Oracle identity receives a BIGINT range warning. Oracle DML-only loads with explicit identity values are blocked because sequence maintenance would require DDL. SQL `SERIAL` declarations normalize to standard identity, but catalog extraction of PostgreSQL serial/ALWAYS identity requires manual mapping.

`SYSDATE` uses `date_trunc('second', clock_timestamp()::timestamp)` and `SYSTIMESTAMP` uses `clock_timestamp()` in PostgreSQL. These use wall-clock time rather than transaction-start time. Match the target session timezone to the source server timezone and validate casts/default behavior. Zoned timestamp literals retain their offsets. Oracle HEXTORAW literal values use PostgreSQL decode(hex, 'hex').

Database extraction also blocks detected triggers, check constraints, partitioning, row security, computed columns, cross-schema foreign keys, non-default FK actions and advanced indexes. This is a table/data cloning tool, not a full schema backup: grants, storage attributes, collations, policies, sequence history and every vendor-specific feature are not reproduced. Validate business behavior on real target versions before production use.

## Execution and reports

Execution policy is **insert only**. Duplicate keys fail instead of silently overwriting or skipping rows. Tables are created first; typed data is loaded with parameterized JDBC batches bounded by configured row and byte limits, with optional PostgreSQL `COPY`; each table is one transaction. New foreign keys and indexes are created after data. Existing constraints determine loading order.

Oracle DDL commits implicitly. Created tables and completed tables can remain after a later failure. Sequence updates are not transactional. Reports distinguish complete, skipped, not-run and partial/failed tables, and show **rows committed by this job**, not total target counts. SQLState/vendor error codes are returned; raw database errors are withheld because they can contain credentials or data values.

Plans can only be submitted once. There is no automatic retry of uncertain writes or destructive rollback. Safe failed or cancelled jobs can be resumed when the current table transaction is known to have rolled back; uncertain commit outcomes require target reconciliation. Downloads contain ordered scripts (`01-tables.sql`, `02-data.sql`, `03-post-load.sql`), a compatibility report and selected actions. Identity maintenance and dependencies are included where supported.

Jobs run asynchronously on two workers with a bounded queue. The four-connection application budget permits up to three background preparation/migration connections and preserves one slot for foreground catalogue and analysis requests. Plans and job journals are saved in the configured work directory, survive application restart, and include staged-artifact integrity checks. Incomplete jobs with uncertain outcomes enter recovery-required state instead of replaying writes. Inactive plans expire after 30 days; job history and artifacts remain until deleted. Preparation and migration support cancellation, subject to driver responsiveness and already committed tables. Source extraction uses a read-only snapshot and streams typed rows and large values to local artifacts. PostgreSQL uses repeatable-read; Oracle uses a read-only transaction.

Default limits: 500 source tables, 2,000,000 rows, 10 GB of staged data per table, 256 MB reserved free disk, 10 MB combined uploaded SQL, and 30 retained plans. Transfer row/byte limits, fetch size, batch size, timeouts, validation and PostgreSQL COPY can be changed in the desktop transfer settings or the CLI JSON `options` object. Options use `settingsVersion: 1`; omitted versions are treated as version 1 for backward compatibility, and unsupported versions are rejected. Effective settings are included in preflight and execution reports. `migration.max-rows`, `migration.max-plans` and `migration.work-dir` set application defaults and storage limits. NUL text and values that cannot preserve source/target semantics, including empty text sent to Oracle, are rejected. SQL literal export of large Oracle LOBs is limited; database transfers use streaming LOB sidecars. Uploaded SQL is parsed in memory; connected database data is streamed.

Artifact files contain migrated data. Keep the work directory private. Delete plans through `DELETE /api/plans/{id}` after downloading reports; expired inactive plans are cleaned when new plans are created. Orphaned files after restart are not automatically recovered or removed. Use a dedicated work directory and manage its retention.

## API

All write requests require `Content-Type: application/json` and `X-Migration-Client: migration-ui`.

| Route | Purpose |
| --- | --- |
| `GET /api/capabilities` | Supported dialects and execution policy |
| `POST /api/schemas` | Test connection and list schemas; body is ConnectionSpec |
| `POST /api/tables` | List tables; body `{connection, schema}` |
| `POST /api/plans` | Analyse source SQL or selected database tables |
| `GET /api/plans/{id}` | Consolidated compatibility report |
| `GET /api/plans/{id}/preview` | Converted table DDL preview |
| `POST /api/plans/{id}/download` | ZIP; body is table-to-action map |
| `POST /api/plans/{id}/execute` | Submit background job; body `{target, actions}` |
| `GET /api/jobs/{id}` | Progress and final per-table execution report |
| `DELETE /api/plans/{id}` | Delete inactive plan and its local data artifacts |

ConnectionSpec fields: `dialect`, `host`, `port`, `database`, `username`, `password`. Plan fields are illustrated by the browser request; a minimal download plan is:

```json
{
  "sourceDialect": "ORACLE",
  "targetDialect": "POSTGRESQL",
  "targetSchema": "public",
  "sql": "CREATE TABLE t (id NUMBER(10,0)); INSERT INTO t VALUES (1);"
}
```

This is a **local single-user application**, not an internet-facing multi-tenant service. Browser cross-origin writes and non-local hostnames are rejected. Authentication, TLS/database certificate configuration, per-user plan ownership, audit persistence, IPv6 hosts, Oracle SID/TNS wallets and production deployment hardening are not implemented.

## Verification

```powershell
mvn -B -ntp -f fg-sql-migration/pom.xml verify
node --check fg-sql-migration/src/main/resources/static/app.js
# With the application already running:
node fg-sql-migration/scripts/smoke-test.mjs
```

Tests exercise parsing, type mapping, matching/mismatched target schemas, DML-only loading, preserved existing rows, duplicate rollback, drift detection, source export, dependency protection, ZIP contents, HTTP controls. H2 PostgreSQL mode is a test fixture, **not proof of Oracle/PostgreSQL compatibility**. Live verification needs disposable Oracle and PostgreSQL databases; no production database credentials are assumed. Docker's engine was unavailable during initial implementation.

The optional independent grammar check uses PostgreSQL's parser through pglast against the regression output. It does not execute SQL:

```powershell
python -m pip install --target fg-sql-migration/target/pg-parser pglast==8.5
python fg-sql-migration/scripts/validate-postgres-output.py
```

Before a production migration, test both directions against the actual database versions, validate row values and constraints, and test intentional partial failures. No unsupported script is eligible for automatic execution.

Reference behavior: [Oracle ALTER TABLE identity options](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/ALTER-TABLE.html), [PostgreSQL sequence functions](https://www.postgresql.org/docs/18/functions-sequence.html).

### Optional table settings

Select up to 500 source tables. Open a selected table in the picker to load its settings automatically; unopened tables keep all columns and rows. Add or remove up to 20 filters per table, combined with AND. Boolean columns use true/false values, other supported scalar columns use text input, and NULL checks do not need a value. Native boolean database transfers are supported for PostgreSQL targets; Oracle boolean mappings remain blocked pending an explicit version-aware mapping.

Each table can have an optional positive row limit. This selects up to that many matching rows without guaranteed ordering; the overall transfer row limit remains a safety ceiling. Leave the table limit blank to include all matching rows.

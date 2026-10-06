# SQL Migration CLI — one executable JAR

Give users `fg-sql-migration-1.0.0-SNAPSHOT.jar`. It contains the application, Oracle driver, PostgreSQL driver and dependencies. Users need **Java 21 or newer**, network access to the selected databases, and database permissions. They do not need Maven, source code, a browser, or a separately installed application server. Credentials and your local catalogue configuration are **not** included in the JAR.

## First migration

Run in a terminal from the directory containing the JAR:

```powershell
java -version
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli init --output migration.json
```

Edit the generated `migration.json` with your source/target JDBC URLs, usernames, schemas, and selected source tables. The template enables DML-only for matching existing tables. Define the password environment variables in that terminal (or inject them through your automation's secret manager):

```powershell
$env:SOURCE_DB_PASSWORD = 'your-source-password'
$env:TARGET_DB_PASSWORD = 'your-target-password'
```

On Linux/macOS use `export SOURCE_DB_PASSWORD='...'` and `export TARGET_DB_PASSWORD='...'` instead. `passwordEnv` contains the **environment variable name**, not the password. Do not put real passwords in the JSON file or JDBC URL.

Optional preflight, then execution:

```powershell
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli plan --config migration.json --output reports
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli migrate --config migration.json --output reports
```

`plan` reads source data, inspects the target, and creates reports/SQL without target writes. `migrate` performs a fresh preflight and executes; it does not ask for approval. A previously generated plan is not replayed. Each invocation creates a unique output directory.

## Configuration

```json
{
  "source": {
    "jdbcUrl": "jdbc:oracle:thin:@//source-host:1521/SERVICE",
    "username": "SOURCE_USER",
    "passwordEnv": "SOURCE_DB_PASSWORD",
    "schema": "SOURCE_SCHEMA"
  },
  "target": {
    "jdbcUrl": "jdbc:postgresql://target-host:5432/database",
    "username": "TARGET_USER",
    "passwordEnv": "TARGET_DB_PASSWORD",
    "schema": "public"
  },
  "tables": ["CUSTOMERS", "ORDERS"],
  "includeData": true,
  "existingTables": "DML_ONLY",
  "skipIncompatible": false
}
```

- Source table names and schemas must match the database catalogue. Use the discovery commands below to list them.
- Oracle SID connections use `jdbc:oracle:thin:@host:1521:SID`. Service connections use `@//host:1521/service`.
- PostgreSQL URLs may include `currentSchema` and `sslmode`. Set the endpoint's `schema` explicitly for the migration.
- New tables are created and loaded. Existing compatible tables use `existingTables`: `SKIP` (default) or `DML_ONLY`.
- Definition mismatches block migration by default. `skipIncompatible: true` explicitly allows skipping incompatible tables while migrating eligible ones; missing dependencies and other plan errors still block execution.
- `includeData: false` exports/creates table definitions without loading source rows. Default is true.
- The target schema must already exist. Only Oracle and PostgreSQL are supported, including same-dialect table cloning within supported features.
- Database-to-database migrations require `source`, `target`, and explicit `tables`. There is no implicit migrate-everything option.

## LCNC datasource IDs

Use a configuration like this instead of manual endpoint URLs:

```json
{
  "catalog": {
    "jdbcUrl": "jdbc:postgresql://catalog-host:5432/postgres",
    "username": "catalog_reader",
    "passwordEnv": "CATALOG_DB_PASSWORD",
    "schema": "fg_solutions"
  },
  "source": {"datasourceId": 100, "schema": "SOURCE_SCHEMA"},
  "target": {"datasourceId": 125, "schema": "TARGET_SCHEMA"},
  "tables": ["CUSTOMERS"],
  "existingTables": "DML_ONLY"
}
```

IDs are examples: choose IDs from your own catalogue. Set `CATALOG_DB_PASSWORD` in your environment. The CLI resolves active, approved master JDBC entries from `fg_datasource`; it does not connect to every listed environment. Each selected entry supplies its URL and credentials. Do not combine `datasourceId` with manual credentials in one endpoint.

Alternatively omit `catalog` and set `MIGRATION_CATALOG_URL`, `MIGRATION_CATALOG_USERNAME`, `MIGRATION_CATALOG_PASSWORD` and optionally `MIGRATION_CATALOG_SCHEMA`. CLI mode reads this JSON/environment configuration directly; it does **not** load the web application's `catalog-local.properties` automatically.

## Discovery commands

```powershell
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli datasources --config migration.json
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli schemas --config migration.json --side source
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli tables --config migration.json --side source
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli schemas --config migration.json --side target
```

`datasources` lists names, IDs and supported/unsupported status, without passwords. `tables` requires a schema for that endpoint.

## SQL files or a folder

Create `convert.json`:

```json
{
  "input": "input-scripts",
  "sourceDialect": "ORACLE",
  "targetDialect": "POSTGRESQL",
  "targetSchema": "public"
}
```

```powershell
java -jar fg-sql-migration-1.0.0-SNAPSHOT.jar cli convert --config convert.json --output reports
```

`input` can be one UTF-8 SQL file or a folder, resolved relative to the config file. Folders include `.sql`, `.ddl`, `.dml` and `.txt` files recursively in path order; use zero-padded prefixes such as `01-schema.sql`, `02-data.sql`, `10-extra.sql`. Files form one consistent source model. Default limits are 10 MB combined SQL, 100 selected database tables, and 100,000 exported rows. For SQL-to-database execution, add a `target` endpoint with its schema and run `migrate` instead. Ordered lifecycle scripts remain review/download-only.

## Reports and exit codes

The output directory contains `preflight.json`, `actions.json`, `migration.zip` and, after execution, `execution.json`. The ZIP includes the migration information text file, per-table `table_name.sql` files with commented DDL/DML sections, and the complete numbered execution scripts. Execute only one copy of the SQL, following the ZIP README. After migration the ZIP is refreshed with execution details; downloading/exporting does not itself create tables.

| Exit code | Meaning |
| --- | --- |
| 0 | Completed successfully (possibly all tables explicitly skipped) |
| 1 | Connection, input/output or unexpected failure |
| 2 | Invalid configuration, unsupported conversion or blocked preflight |
| 3 | Execution failed; inspect the report for committed rows and partial changes |

The executor inserts rows; it does not overwrite existing rows or ignore duplicate keys. It commits per table, and Oracle DDL/sequence operations can persist after failure. Do not automatically retry a failed or interrupted migration: inspect target state and reports first. CLI mode creates no HTTP server and no Hikari pools. Connections close after use and are capped at four simultaneous sessions **per JVM process**. Launching many CLI processes multiplies that limit.

Run without `cli` to retain the existing browser interface. Run `cli help` for command syntax. Live migrations should be validated against disposable instances of your actual Oracle/PostgreSQL versions; automated migration tests use H2 fixtures.

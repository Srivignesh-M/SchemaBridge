# Live Oracle/PostgreSQL checks

These opt-in JUnit fixtures exercise the real migration service and JDBC drivers. Default Maven runs skip database tests whose opt-in properties are absent. H2 coverage is not real-engine compatibility evidence.

## Configuration

Provide passwords in the Maven process environment, not in source, POM, Maven command-line properties or tracked configuration. Fixtures do not read `catalog-local.properties`.

| Environment variable | Default / purpose |
| --- | --- |
| `MIGRATION_ORACLE_HOST` | `127.0.0.1` |
| `MIGRATION_ORACLE_SERVICE` | `FREEPDB1` |
| `MIGRATION_ORACLE_USERNAME` | `system`; fixture provisioner only |
| `MIGRATION_ORACLE_PASSWORD` | No usable default; supply through environment |
| `MIGRATION_PG_HOST` | `127.0.0.1` |
| `MIGRATION_PG_DATABASE` | `postgres` |
| `MIGRATION_PG_USERNAME` | `migration_test` |
| `MIGRATION_PG_PASSWORD` | Empty unless supplied |

Oracle needs the `USERS` tablespace and an account allowed to create/drop users and grant CREATE SESSION, CREATE TABLE and CREATE SEQUENCE. Each test creates two random `FGIT_S_<20 hex>` / `FGIT_T_<20 hex>` users with random passwords and 2000 MB quotas (raised from 256 MB to fit the wide 1,000,000-row cross-database benchmarks). Transfers connect as these limited fixture users. PostgreSQL needs a disposable account allowed to create schemas.

**Revert tests only:** `enableRevertMetadata()` in `OracleTransferTest` grants `SELECT` on `SYS.DBA_CONSTRAINTS`/`SYS.DBA_DEPENDENCIES`/`SYS.DBA_SYNONYMS` to the target fixture schema, using the same admin connection (`MIGRATION_ORACLE_USERNAME`, default `system`). Confirmed by live testing: this fails with `ORA-01031: insufficient privileges` unless that admin account is a true `SYS AS SYSDBA` connection — a `DBA`-role account like `SYSTEM` cannot grant on SYS-owned dictionary objects, even with `GRANT ANY OBJECT PRIVILEGE`, and there is no `O7_DICTIONARY_ACCESSIBILITY`-style override in Oracle 23ai. To run the Oracle revert tests, either pre-grant those three views (as SYS) to the exact fixture schemas before each run, or connect the admin fixture as SYS. `oracleRevertRequiresCompleteDependencyVisibility` deliberately runs without this grant and needs it absent to pass.

Normal teardown removes only users/schemas successfully created by that test, including after assertion failures. A killed JVM may leave fixture objects; inspect ownership and logs before cleanup, and never bulk-drop by prefix alone. Use disposable databases.

## Functional checks

Run from the project root after configuring the environment.

Oracle only:

```powershell
mvn -B -ntp test '-Dtest=OracleTransferTest' '-Dmigration.oracle.port=1521'
```

Both engines and both cross-database directions:

```powershell
mvn -B -ntp test '-Dtest=OracleTransferTest,PostgresTransferTest' '-Dmigration.oracle.port=1521' '-Dmigration.pg.port=5432' '-DargLine=-Xmx1g'
```

Full regression suite with live functional checks:

```powershell
mvn -B -ntp test '-Dmigration.oracle.port=1521' '-Dmigration.pg.port=5432' '-DargLine=-Xmx1g'
```

Oracle coverage includes Unicode, quotes/newlines, exact decimals, fixed CHAR, DATE time-of-day, microsecond timestamps, zoned instants, NULLs, a CLOB over 32K characters and a 40,000-byte BLOB. Values are checked independently on the target. Other fixtures check statistics, mappings/filters, duplicate rollback/restart/resume, identity advancement, default FK enforcement, rejection of cascading deletes, and PostgreSQL SQL input loaded into Oracle.

Oracle fixtures wait 1.5 seconds after setup before opening a read-only source snapshot because immediately reading freshly created tables produced ORA-01466. This adds no production retry mechanism. Oracle catalog WITH LOCAL TIME ZONE columns remain outside these support claims.

## Benchmarks

Oracle narrow 100K baseline:

```powershell
mvn -B -ntp test '-Dtest=OracleTransferTest#oracleNarrowBenchmark' '-Dmigration.oracle.port=1521' '-Dmigration.oracle.benchmarkRows=100000' '-DargLine=-Xmx1g'
```

For one million rows, use `'-Dmigration.oracle.benchmarkRows=1000000'`. Only these two sizes enable the fixture. It transfers Oracle to Oracle with prepared statements and checks count, ID/amount sums and application fingerprints.

Existing PostgreSQL narrow JDBC and wide COPY benchmarks:

```powershell
mvn -B -ntp test '-Dtest=PostgresTransferTest' '-Dmigration.pg.port=5432' '-Dmigration.benchmark=true' '-DargLine=-Xmx1g'
```

Results are written to:

- `target/surefire-reports/`: assertions and per-class results; later runs overwrite the same class's report.
- `target/benchmarks/oracle-representative.json`: successful Oracle value fixture metadata and database/driver versions.
- `target/benchmarks/oracle-narrow-100000.json` and `oracle-narrow-1000000.json`: timings, staged bytes, sampled heap, versions and options.
- `target/benchmarks/million-narrow-prepared.json` and `million-wide-copy.json`: PostgreSQL benchmark records.

Oracle timing includes preparation and migration with application validation; it excludes setup, seed generation, settling delay, independent aggregate assertions and cleanup. Heap sampling is periodic and does not measure process RSS or database memory. Two-row cross-database checks do not establish million-row cross-database performance. Local single-run timings are not duration guarantees.

## Observed results on 2026-10-08

- Full regression with Oracle and its 100K benchmark: 86 discovered, 79 passed, 7 skipped, zero failures/errors. PostgreSQL-dependent tests were disabled in that run.
- Subsequent live functional run with both engines: 14 discovered, 11 passed, 3 optional benchmarks skipped, zero failures/errors.
- Separate Oracle 1M run: one passed test, 19.64 seconds including application validation. Oracle 100K: 4.05 seconds.
- Oracle server: Oracle AI Database 26ai Free 23.26.3.0.0; JDBC 23.3.0.23.09. Client: Java 21.0.9, Windows 11 amd64, 1 GiB heap for measured runs.
- The original-output `mvn verify` reached packaging after its tests passed, then failed to rename the JAR. An isolated output build packaged successfully. Managed-Windows desktop checks remain outstanding.

## Observed results on 2026-10-09 and two fixture fixes (P8)

Re-ran the full "integration-job-style" invocation (`mvn test` against both live engines, the same command the CI workflow below runs) three times while building the CI job, local Docker (`oracle-db`, `pg-dev`). This surfaced and fixed two real test-fixture bugs, neither of which is a production code change:

1. **`ORA-01031: insufficient privileges` on all three `enableRevertMetadata()`-dependent Oracle revert tests.** `enableRevertMetadata()` granted `SELECT` on `SYS.DBA_CONSTRAINTS`/`DBA_DEPENDENCIES`/`DBA_SYNONYMS` through the fixture's ordinary admin connection (`db.connect(admin)`, username `system`) — exactly the connection path documented above as unable to grant on SYS-owned objects without a true `SYS AS SYSDBA` session. `DatabaseGateway.connect()` (production code) intentionally has no SYSDBA support — this is a local single-user tool, not a place to accept SYS credentials from users. Fixed in `OracleTransferTest.java` only: a new test-only `sysdbaConnection()` helper opens a raw JDBC connection with `user=sys`, `internal_logon=sysdba`, and a new `MIGRATION_ORACLE_SYS_PASSWORD` environment variable (falling back to `MIGRATION_ORACLE_PASSWORD` — the same value works out of the box against `gvenzl/oracle-free`, which gives SYS and SYSTEM the same `ORACLE_PASSWORD`). `enableRevertMetadata()` now issues the three grants over this connection instead of `db.connect(admin)`. All three tests pass unmodified otherwise.
2. **Intermittent `ORA-01466: unable to read data - table definition has changed`**, hitting a different test each full-suite run (seen on `oracleRevertBlocksCrossSchemaCascadeAndRecreatedTables` once, `oraclePreCommitBatchFailureRollsBackAndSafelyResumes` once) — both passed immediately when re-run alone, confirming a flake rather than a regression. The existing fixed 1.5-second settle delay in the shared `plan()` helper (already documented above) was insufficient under full-suite load, especially shortly after the Oracle container restarts. Added a bounded retry (up to 2 extra attempts, 1.5s apart) around the `service.create(...)` call in `plan()`, scoped to Oracle and error code 1466 only — the retry-on-ORA-01466 Oracle's own docs recommend, not a new production retry mechanism.

After both fixes, three consecutive full runs: **122/122 passed, 0 errors, 11 skipped (optional benchmarks), BUILD SUCCESS** — `mvn -B -ntp test '-Dmigration.oracle.port=1521' '-Dmigration.pg.port=5433' '-DargLine=-Xmx1g'` with `MIGRATION_ORACLE_PASSWORD`/`MIGRATION_PG_USERNAME=postgres`/`MIGRATION_PG_PASSWORD` set via environment (pg-dev's local port happened to be remapped to 5433 to avoid a native PostgreSQL service already on 5432; CI uses the standard 5432).

## Continuous integration (P8)

Two workflows under `.github/workflows/`:

- **`pr-checks.yml`** — runs on every PR and push to `main`. `mvn verify` (H2-backed regression suite), `node --check` on `app.js`, and the three jsdom UI logic scripts. No secrets, no live database, no forked-PR exposure concern.
- **`live-db-integration.yml`** — `workflow_dispatch` (manual) and a daily `schedule` only, deliberately **not** `pull_request`, since GitHub never gives repository secrets to fork-triggered `pull_request` runs anyway, and this job needs real Oracle/PostgreSQL credentials. Spins up `postgres:16-alpine` and `gvenzl/oracle-free:slim-faststart` as GitHub Actions `services:` on `ubuntu-latest`, runs the exact live-DB `mvn test` invocation above, and uploads `target/surefire-reports/**` and `target/benchmarks/**` as a 90-day artifact. An optional `runBenchmarks` dispatch input also runs the opt-in 100K cross-database benchmarks, which already embed fixture/driver/build identity (Java version, OS, database/driver version) in their JSON output — no workflow change needed for that part, it was already in the benchmark code.

**Required repository secrets** (Settings → Secrets and variables → Actions): `LIVE_DB_ORACLE_PASSWORD`, `LIVE_DB_PG_PASSWORD`. If either is missing, a `check-secrets` job logs an explicit `::warning::` and the dependent `live-db-suite` job shows as **skipped** in the Actions UI — not a silent no-op and not a red failure. Neither secret is echoed anywhere in logs or artifacts; they only ever reach the job as JDBC connection properties.

Not yet done: a real run of `live-db-integration.yml` against actual GitHub-hosted runners (only validated locally against Docker Desktop containers so far, by construction — it needs a GitHub repository with those two secrets configured to execute at all).

See [the improvement plan](IMPROVEMENT-PLAN.md) for priorities, evidence limits and release gates.

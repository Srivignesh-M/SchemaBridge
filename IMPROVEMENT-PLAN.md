# SQL Migration improvement plan

Updated 2026-10-07 from the current source, saved test reports and packaging checks. This document tracks delivered foundations and the validation still needed; it does not establish production support for larger migrations.

## Intended outcome

Deliver a reliable Windows desktop application for Oracle/PostgreSQL table and data migration, initially validated with one million rows per table. Keep the system-Java distribution small, preserve SQL conversion/export, and keep AI out of the product. Support database-to-database, database-to-file and file-to-database workflows with explicit compatibility checks.

Prioritize correct, recoverable transfers over headline throughput. Do not promise a transfer duration until the source/target versions, row widths, indexes, hardware and network have been benchmarked.

## Current constraints and evidence

| Area | Current implementation | Effect |
| --- | --- | --- |
| Scale | Desktop and CLI accept validated row, per-table byte and disk-reserve limits; defaults are 2 million rows and 10 GB per table. SQL input remains limited to 10 MB. | Configurable ceilings are not benchmark results or a support claim. |
| Configuration | Shared `MigrationOptions` are accepted by desktop requests and CLI JSON; settings version 1 is explicit and missing versions default to v1. Effective options are included in plan and execution reports. | Future settings migrations need compatibility tests; CLI settings remain in JSON rather than command-line flags. |
| Planning | Database UI analysis can be metadata-only; PostgreSQL/Oracle catalog statistics provide optional approximate source row counts when unfiltered; asynchronous preparation reports progress and supports cancellation. | Estimates may be stale or unavailable and are not exact counts or filtered-row estimates; preparation still extracts a local snapshot before migration. |
| Representation | Database rows use typed staging files and streamed LOB sidecars. SQL-file imports retain a JSON-lines representation. | Staging format and LOB/type support need a published compatibility matrix and broader cross-database validation. |
| Loading | Typed data uses parameterized JDBC batches bounded by rows and bytes; PostgreSQL COPY is optional. Each table remains a transaction unit. | Oracle throughput and long transaction behavior need representative benchmarks. |
| Timeouts | Fetch size, batch size, query timeout and connection read timeout are configurable. | Defaults need tuning from measured source/target workloads. |
| Scheduling | Bounded preparation and migration executors reject excess work. The four-connection budget allows at most three background preparation/migration sessions, preserving one slot for foreground catalogue and analysis requests. | Stress-test actual foreground responsiveness while all background slots are occupied. |
| Recovery | Plans and job journals persist locally; safe failed/cancelled jobs can resume. Uncertain commit outcomes require reconciliation. | Failure-injection coverage and production recovery procedures remain gates for unattended work. |
| Exports | ZIPs stream through short-lived download tickets; the UI exposes a direct save link with its expiry and retry guidance, without buffering the ZIP as a Blob. | WebView save behavior still needs validation on managed Windows machines. |
| Types | LOB sidecars and typed binding exist, with explicit restrictions on unsupported schema features and some values. | Row count alone does not determine whether a table is supported. |
| Distribution | System-Java and bundled-Java packages and Windows smoke scripts exist. | System-Java desktop host launch is blocked in this restricted environment; employee-machine validation remains open. |

## Progress

The first implementation milestone is underway. Shared settings, versioned options, metadata-only analysis with optional catalog-statistics estimates, asynchronous preparation, progress reporting, typed row staging, prepared-statement batching, optional PostgreSQL COPY, streamed downloads and durable job journals are present in the source. This work establishes implementation foundations, not a million-row certification.

The full Maven suite passed: 76 tests, 0 failures and 5 skipped (PostgreSQL integration/benchmark fixtures were not enabled in this run). Tests cover legacy settings defaulting to v1, settings round-trip and unsupported versions, and PostgreSQL metadata-only row estimates after `ANALYZE` (exact extracted rows remain zero). H2 fault-injection tests cover a pre-commit batch failure with safe rollback/resume, a target commit succeeding while its response is lost, a batch response failure followed by rollback failure, disk-reserve exhaustion during LOB staging, abrupt child-process termination after target commit followed by restart recovery, and post-load index recovery after a rolled-back PostgreSQL-mode DDL failure. Uncertain outcomes enter `RECOVERY_REQUIRED` and refuse automatic resume; safely rolled-back post-load failures retain committed rows and resume finalization without reloading. A recovery test verifies effective settings appear in plan/job reports and survive restart. The PostgreSQL fixture reads credentials from environment variables. Both million-row cases passed in dedicated runs after the connection reservation change.

**PostgreSQL benchmark evidence:** PostgreSQL 16.11 on Windows 11 amd64, Java 21.0.9, 8 processors, 1 GiB maximum JVM heap. After the connection reservation change, the narrow prepared-statement fixture completed 1,000,000 rows in 14.09 seconds (3.09 seconds preparation; 10.99 seconds load plus validation), staged 153,670,375 bytes, and sampled 102,594,960 bytes peak heap. The wide PostgreSQL COPY fixture completed 1,000,000 rows in 27.96 seconds (9.12 seconds preparation; 18.78 seconds load plus validation), staged 863,781,486 bytes, and sampled 104,183,392 bytes peak heap. Both passed row-count, aggregate and order-independent SHA-256 validation. Heap sampling is periodic and does not measure process RSS or database memory. These are local PostgreSQL results, not Oracle results or production duration guarantees. JSON reports are written under `target/benchmarks/`.

**Reproducing the PostgreSQL run:** Set `MIGRATION_PG_HOST`, `MIGRATION_PG_DATABASE`, `MIGRATION_PG_USERNAME` and `MIGRATION_PG_PASSWORD` in the test process environment. From the project root, run `mvn -B -ntp test '-Dmigration.pg.port=5432' '-Dmigration.benchmark=true' '-DargLine=-Xmx1g'`. The tests create uniquely named source/target schemas and drop only those schemas afterward; use a disposable local database account with schema creation privileges. Reports are emitted to `target/benchmarks/`.

The Windows system-Java end-to-end check also remains unverified in this environment because desktop host process creation was denied. Keep managed Windows desktop checks as a separate release gate.

| Phase | Delivered | Remaining work |
| --- | --- | --- |
| 1 — Release and benchmark baseline | Windows packaging scripts and launcher checks exist; representative PostgreSQL transfers and narrow/wide 1M PostgreSQL fixtures passed. | Benchmark Oracle directions; add repeatable 100K comparison and disk/RSS/database-load measurements; finish clean/managed Windows checks. |
| 2 — Large migrations | Validated shared options with settings version 1 and legacy defaulting, metadata-only plans with optional PostgreSQL/Oracle statistics-based row estimates, asynchronous preparation/cancellation, row/byte limits, injected disk-reserve exhaustion checks, streamed ZIP downloads and a reserved foreground connection slot exist. Preparation and execution progress now display phase, current table, separate row counters, bytes, elapsed time, throughput and available ETA; row estimates are labelled as estimates and exact counts as extracted rows. ZIP downloads use a visible short-lived save link. Effective options are included in reports. | Stress-test foreground responsiveness with all three background slots occupied; exercise real filesystem exhaustion and timeouts; validate estimates against Oracle and cross-process UI behavior; complete managed-WebView download checks. |
| 3 — Transfer engine | Typed staged database rows and LOB sidecars, prepared-statement batches, optional PostgreSQL COPY, source snapshots and table-atomic commits exist. | Version and document the staging format; measure both loading paths and Oracle; confirm cross-dialect value semantics with real databases; keep heap/RSS and throughput evidence. |
| 4 — Recovery | Local atomic manifests, staged-file hashes, cancellation, committed-row reporting and cautious resume/recovery-required states exist; credentials are excluded from persisted plans. H2 fault injection covers safe pre-commit rollback/resume, lost commit responses, rollback failure and reserve exhaustion during LOB staging. A child JVM now halts after a target data commit and restart correctly preserves uncertain status and blocks replay. PostgreSQL-mode H2 verifies safe post-load index recovery after successful rollback; database-source plans warn that interrupted extraction requires a fresh snapshot. | Next: repeat disconnect/post-load scenarios against actual PostgreSQL and Oracle; document an operator reconciliation procedure. No target-side chunk ledger exists. |
| 5 — Capability expansion | Selected columns, renames, parameterized filters, streamed database LOB staging and typed data fingerprints exist in limited supported paths. | Define cross-database timezone/numeric policies; expand target validation and schema support; upsert/incremental migration and SQL imports beyond 10 MB remain future work. |
| 6 — Organizational rollout | Bundled/system-Java packages, versioned setup scripts and smoke checks exist. | Validate on managed Windows/WebView2, complete signing/distribution review, publish tested compatibility matrix, and pilot with disposable targets. |

Primary source files: `MigrationService.java`, `DatabaseGateway.java`, `MigrationCli.java`, `Model.java`, `static/app.js`, `DesktopLauncher.java` and the Windows launcher/build scripts. Paths are under `src/main` unless noted.

## Phase 1 — Establish a reliable release and benchmark baseline

1. Validate the system-Java desktop on representative employee Windows machines. Test Java absent/old/current, PATH/JAVA_HOME fallback, paths with spaces, WebView2 absent/present, uploads, native Save dialog, repeated launch and shutdown.
2. Preserve backend operation if the display fails: record the failure, stop safely and identify any committed work. Track each owned process explicitly; cleanup must never stop a process by name alone.
3. Version artifacts and display the build version. Retain bundled-Java and system-Java editions with checksums, dependency versions and clear requirements. Have IT review signing and the approved distribution channel; investigate actual Teams block messages rather than assuming their cause.
4. Create repeatable Oracle/PostgreSQL test datasets: 100,000 rows for comparison with today's implementation, then one million narrow rows, one million wide rows, and representative Unicode/numeric/date/NULL data. Include total exports exceeding 100 MB.
5. Measure extraction, staging, load, index creation and validation separately. Record rows/second, bytes/second, peak JVM heap/RSS, WebView memory, disk use, database load, transaction duration and errors. Record hardware and database versions with every result.

**Exit criteria:** normal-desktop launch/upload/download/close checks pass; 100,000-row baseline is repeatable; benchmark reports distinguish success, partial completion and failure. Existing parser and migration regression tests pass. An H2-only result is not an Oracle/PostgreSQL certification.

## Phase 2 — Make large migrations manageable

1. Keep shared transfer options compatible across CLI, desktop and services. Settings version 1, legacy missing-version fallback and effective-value reporting are implemented; add compatibility coverage as the format evolves.
2. Separate metadata analysis from data extraction. Metadata-only plans now expose optional approximate row counts from PostgreSQL/Oracle catalog statistics for unfiltered tables; estimates are not exact, may be stale or unavailable, and do not estimate filtered rows or data size. Exact full counts remain optional. A separate asynchronous preparation job extracts data with progress and cancellation.
3. Replace the service-wide planning lock with bounded job admission and per-job state transitions. Reserve connection slots for metadata/catalogue requests so they remain usable during transfers.
4. Replace fixed per-table limits with explicit configurable quotas and continuous byte accounting. Check the final buffered bytes as well as intermediate writes. Check free disk space before starting and while writing; retain a configurable reserve. Never silently truncate rows.
5. Show phase, table, rows read, rows sent, rows committed, bytes, elapsed time, rate and an estimated completion time when reliable. Keep estimated and exact totals distinct.
6. Stream ZIPs directly to disk through a validated desktop download path or a short-lived job download URL. Keep creation protected by existing request controls; avoid unrestricted file paths and avoid loading an entire ZIP into a JavaScript Blob.

**Exit criteria:** a job can be prepared beyond 100,000 rows and 100 MB with explicit settings; metadata analysis does not extract all rows; UI stays responsive; disk exhaustion, timeout and cancellation have clear outcomes. CLI and desktop apply the same limits. Raising limits alone does not qualify the million-row release.

## Phase 3 — Improve the transfer engine

1. Introduce typed row readers/writers and a versioned, bounded-memory staging format. Persist data and type metadata, rather than making generated INSERT text the internal transfer format. Produce human-readable SQL only when requested for export.
2. Use one parameterized `PreparedStatement` per table shape and standard JDBC batches. Bound batches by both row count and estimated bytes; select defaults by measurements. Preserve decimal precision, nulls, binary values and timestamp semantics.
3. Add a PostgreSQL `COPY FROM STDIN` loading strategy for eligible tables. Use driver streaming APIs and retain prepared-statement fallback. Verify column mapping, quoting, encodings, identities, constraints and rollback; COPY must not change the selected action's meaning.
4. For Oracle, use standard prepared-statement batching first. Evaluate further bulk-loading mechanisms only after measuring this path. Keep insert-only behavior as the default and do not silently ignore duplicates.
5. Keep the initial reliable mode as snapshot extraction to an immutable local artifact followed by loading. Evaluate optional direct streaming later; use bounded queues/backpressure, enforce connection budgets and declare its recovery limitations.
6. Continue deferring new-table indexes/foreign keys until data loading when permitted. Do not automatically drop existing target constraints or indexes. Add limited parallel loading only for independent tables after single-table correctness and throughput are established.

**Exit criteria:** supported million-row fixtures complete in both Oracle-to-PostgreSQL and PostgreSQL-to-Oracle directions; source/target data checks pass; heap remains bounded as row counts grow. Proposed benchmark target: complete narrow/wide non-LOB fixtures with a 1 GiB JVM heap; record total process memory separately. Aim for at least twice the current 100,000-row baseline throughput on the same benchmark system, but report the measured result instead of promising that gain.

## Phase 4 — Durable jobs, cancellation and safe recovery

1. Persist job manifests, selected actions, schema fingerprints, chunk checksums, progress and stage outcomes in a transactional local store. Store credential references; require credentials again after restart rather than persisting plaintext passwords.
2. Model explicit states: PREPARING, READY, LOADING, FINALIZING, VALIDATING, SUCCEEDED, CANCEL_REQUESTED, CANCELLED, FAILED and RECOVERY_REQUIRED. Persist table-creation and identity/index/constraint outcomes separately.
3. Implement cancellation between batches and driver cancellation with a timeout. Report committed rows separately from the uncommitted batch. Do not claim cancellation rolls back earlier commits or Oracle DDL.
4. Offer table-atomic loading first. Introduce chunk commits only with an explicit partial-commit policy, dependency rules and recovery protocol. Batch size and commit interval are separate controls.
5. Resume loading from verified immutable exported chunks. A local checkpoint alone cannot prove whether a target commit succeeded if connectivity is lost during commit. For reliable automatic chunk replay, use an optional target-side migration ledger written in the same transaction as each data chunk, or an explicitly designed staging/idempotency protocol. If neither is permitted, require reconciliation instead of automatically replaying an uncertain chunk.
6. A restarted source query cannot generally recreate a lost snapshot. Only resume extraction with a stable, validated source snapshot/key strategy; otherwise re-extract before target writes. Parallel readers must share a documented consistency model or be restricted to quiescent sources.
7. Resume post-load steps independently after data commits, rechecking schema state and fingerprints. Pin artifacts for active/recoverable jobs and clean them only through a clear retention policy.

**Exit criteria:** injected failures before/during/after commit, disk-full events, network loss and process termination never produce silent duplicate or missing rows. A job either resumes correctly or stops in RECOVERY_REQUIRED with accurate committed/uncertain status. Existing targets are not destructively reset to recover a job.

## Phase 5 — Expand migration capabilities in a controlled order

| Order | Capability | Current status | Remaining completion criteria |
| --- | --- | --- | --- |
| 1 | Column mapping, selected columns and parameterized row filters | Implemented for selected database tables with supported operators and dependency checks. | Broaden type/operator coverage and verify mappings against Oracle and PostgreSQL versions in use. |
| 2 | CLOB/BLOB/large text and binary streaming | Typed database staging streams large values through sidecar files; SQL-literal export remains restricted for large Oracle LOBs. | Cross-database byte/content checks on both engines; document per-type and size limits. |
| 3 | Timezone and numeric policies | Typed binding and fingerprints exist; some timezone/numeric conversions still require explicit review or block conditions. | Define conversion policies and demonstrate round trips for actual Oracle/PostgreSQL versions. |
| 4 | Validation reports | Per-table committed rows and typed fingerprint validation exist. | Validate existing-target DML without relying on whole-table counts; distinguish exact checks from samples and expose useful mismatch details. |
| 5 | Named identities/sequences, ordinary views/indexes and additional constraints | Basic identity maintenance and simple indexes are supported in constrained paths; views and many advanced schema objects are unsupported. | Publish tested support matrix and add only organization-prioritized features with semantic checks. |
| 6 | Explicit upsert and incremental migration | Not implemented; current execution is insert-only. | Require stable keys, conflict/deletion policy, watermarks and reconciliation tests; keep behavior opt-in. |
| 7 | Larger SQL-file imports | File input remains capped at 10 MB and parsed in memory. | Add incremental parsing and bounded disk staging while preserving ordered-script semantics. |

General stored procedures, triggers, packages, arbitrary SQL translation and continuous change-data capture are later projects. Do not represent them as supported by adding a checkbox. AI remains excluded.

## Phase 6 — Organizational rollout

1. Publish supported Java/Windows/WebView2/database versions and the tested size/type matrix.
2. Test a clean machine and an organization-managed machine, including the actual Teams/SharePoint or software-distribution process chosen by IT. Signing and approved distribution are deployment tasks, not ways to bypass security controls.
3. Use saved connection profiles without bundling shared credentials. Make catalogue permissions and secret storage an explicit organizational integration.
4. Add release notes, signed/versioned packages where available, checksum verification, a documented upgrade path and a previous-version fallback. Keep the system-Java package near its current size unless a measured capability justifies growth.
5. Pilot with a small user group and disposable target databases. Broader deployment follows successful real-workload validation and documented recovery procedures.

**Exit criteria:** the complete download/extract/launch/connect/migrate/validate/exit workflow works on managed employee machines, and support can diagnose a failed job without requesting database passwords or exporting row data into logs.

## First implementation milestone

Complete the Oracle benchmark and workstation checks. Shared, versioned transfer options now appear in preflight and execution reports; metadata-only analysis can show approximate catalog-statistics row counts; asynchronous preparation, typed prepared-statement loading, optional PostgreSQL COPY and durable job state are implemented. Safe recovery validation and broader type coverage remain separately reviewable work.

The first million-row claim is limited to database versions, table shapes and data types that pass the tests. It does not imply support for every million-row table. Make durable recovery a gate for unattended large production transfers.

## Inputs needed before finalizing the benchmark and schedule

- Actual Oracle/PostgreSQL versions and required migration directions.
- A representative table definition and approximate row width, including LOBs, identities and foreign keys; synthetic data can be used.
- Employee workstation RAM, expected network conditions and available local disk.
- Whether target staging tables or a migration ledger are allowed.
- Whether the source can be quiesced and whether the destination has concurrent writers.
- Required recovery expectations and transfer-time target.

No production credentials are needed to start implementation. Effort estimates should follow the baseline and these answers; hardware-independent completion-time promises would be misleading.

## Technical references

- PostgreSQL JDBC [CopyManager](https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/copy/CopyManager.html) provides streaming COPY APIs; benchmark it as an optional strategy rather than assuming a fixed speedup.
- Oracle JDBC [Performance Extensions](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/performance-extensions.html) distinguishes prepared-statement batching from generic Statement batching. This supports prioritizing typed prepared statements in the current loader.

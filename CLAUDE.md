# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**SchemaBridge** (Maven artifact `fg-sql-migration`) — a standalone Java 21 / Spring Boot 3.2 app for Oracle ↔ PostgreSQL table/data migration. One module, not part of a larger aggregator. Three ways to run it: a browser UI + REST API, a terminal CLI (`cli` subcommand), and a packaged Windows desktop app (JavaFX-less Swing bootstrap + a C# WebView2 shell in `desktop/`). It is explicitly a **local single-user tool**, not a multi-tenant service — see the `WebBoundary` security notes below.

Full behavioral spec (conversion coverage, type mappings, execution policy, API contract, download format) lives in `README.md` — read it before changing conversion/execution logic, it is long and precise and this file does not repeat it. Companion docs: `CLI-README.md`, `WINDOWS-README.md`, `SYSTEM-JAVA-README.txt`, `STAGING-FORMAT.md`, `RECOVERY-RUNBOOK.md`.

## Commands

Build/test (from repo root — this IS the module root, `pom.xml` is here directly):

```powershell
mvn -B -ntp -f pom.xml verify          # full build + test suite
mvn -f pom.xml spring-boot:run          # run the web app for dev (http://127.0.0.1:8098)
mvn package -DskipTests                 # just the jar
java -jar target\fg-sql-migration-1.0.0-SNAPSHOT.jar --migration.work-dir=.\work
```

Single test class/method (standard Surefire flags):

```powershell
mvn -f pom.xml test -Dtest=SqlParserTest
mvn -f pom.xml test -Dtest=SqlParserTest#someMethodName
```

CLI mode (no HTTP server starts):

```powershell
java -jar target\fg-sql-migration-1.0.0-SNAPSHOT.jar cli init --output migration.json
java -jar target\fg-sql-migration-1.0.0-SNAPSHOT.jar cli plan --config migration.json
java -jar target\fg-sql-migration-1.0.0-SNAPSHOT.jar cli migrate --config migration.json
```

Frontend checks — there is **no Node build step**, `static/` is served as-is, but there are Node-based test scripts under `scripts/`:

```powershell
node --check src\main\resources\static\app.js   # syntax check only

# One-time setup the README does not mention — required before any *-ui.mjs script will run:
npm install jsdom --prefix target\ui-test

# With the app already running on 127.0.0.1:8098:
node scripts\smoke-test.mjs             # HTTP smoke: UI, capabilities, conversion, preview, ZIP
node scripts\smoke-edge-cases.mjs       # the 63-statement Oracle edge-case regression fixture
node scripts\smoke-cli.mjs              # exercises the executable-jar CLI path
node scripts\test-connection-ui.mjs     # jsdom-driven test of app.js connection-form logic
node scripts\test-bulk-ui.mjs           # jsdom-driven test of folder/bulk-upload logic (see bug below — needs MIGRATION_URL)
node scripts\test-catalog-ui.mjs        # jsdom-driven test of LCNC datasource picker logic
```

The `*-ui.mjs` scripts are real frontend tests: they load `index.html` + `app.js` into a `jsdom` window and drive the actual DOM/event logic, not a mock. They are **not wired into any CI workflow** (`.github/workflows/*.yml` only package Windows/browser artifacts) and are easy to forget since the repo ships no `package.json` — `target/ui-test` is a throwaway npm prefix you create yourself.

Optional independent grammar check (pglast against PostgreSQL's real parser):

```powershell
python -m pip install --target target\pg-parser pglast==8.5
python scripts\validate-postgres-output.py
```

## Architecture

**Single flat package** `com.fingress.migration` (~25 classes, no sub-packages). Read it as a pipeline:

```
SqlParser → ScriptConverter (+ SqlExpression) → Compatibility → SqlWriter
                                                        ↓
                                              MigrationService (orchestrator)
                                   ↙              ↓                ↘
                       DatabaseGateway      ConnectionBudget     RowStore / CopyLoader
                      (JDBC, per dialect)  (4-conn ceiling)    (staged artifacts, LOB sidecars)
                                                        ↓
                          MigrationController / DatasourceController (REST)  ← WebBoundary (filter) / ApiErrors (@RestControllerAdvice)
                                                        ↓
                                        MigrationCli (terminal) · DesktopLauncher (Swing+WebView2 shell)
```

- **`Model`** is the single DTO/record hub for everything crossing a boundary: `Dialect` enum, parsed SQL (`Table`/`Column`/`Key`/`Insert`), plan/report views, job views, `ConnectionSpec`. Start here when tracing a field through the system.
- **`SqlParser`/`ScriptConverter`/`SqlWriter`** implement a bounded, tokenized SQL grammar (not a general parser) — see README "Conversion coverage" for exactly what's supported before extending it.
- **`MigrationService`** owns plan/job lifecycle, persists plans and job journals to the configured work dir (survives restart), and runs jobs asynchronously on a bounded two-worker queue. Execution policy is insert-only; Oracle DDL commits implicitly so partial failures can leave created tables behind — this is by design per README, not a bug to "fix" by wrapping in a bigger transaction.
- **`ConnectionBudget`** enforces a process-wide 4-connection ceiling shared across catalogue/source/target JDBC sessions — don't add new DB call sites without going through it.
- **`DatasourceCatalog`/`DatasourceController`** implement the optional LCNC datasource picker, reading `fg_solutions.fg_datasource` from a separately configured Postgres catalogue DB (`MIGRATION_CATALOG_URL`/`catalog-local.properties`, gitignored). Independent of the migration data path.
- **`WebBoundary`** (a `OncePerRequestFilter`) is the entire security model: CSP/no-store headers, rejects non-local `Host` headers, requires `X-Migration-Client: migration-ui` on mutating `/api/*` calls, and blocks cross-origin writes. **See the known bug below before trusting its allow-list.**
- **`MigrationApplication`** branches main() three ways: `--desktop` → `DesktopLauncher`; `cli ...`/`--help` → `MigrationCli`; anything else → normal Spring Boot web app.
- Frontend (`src/main/resources/static/{index.html,app.js,styles.css}`) is vanilla JS/CSS, no framework, no build step, served directly as Spring static resources. `app.js` owns all form state, validation, bulk-folder/file loading (natural sort, BOM stripping, 10 MB combined limit), and the fetch calls to `/api/*`.
- `desktop/FingressDesktop.cs` is a separate C# WebView2 host launched as a subprocess by `DesktopLauncher`, pointed at the embedded server's dynamically chosen port on `127.0.0.1`.

## Revert feature

A job can be reverted after it succeeds: remove the rows it inserted, and drop any table it created (never touches pre-existing tables or unrelated rows; identity/sequence counters are not reset). Two-step confirmation, mirroring the download-ticket pattern: `POST /jobs/{id}/revert-preview` rechecks the target and returns a short-lived (`5 min`) `RevertPreview` token; `POST /jobs/{id}/revert` consumes that token and runs asynchronously, same as `execute`. UI lives in `index.html`'s `#revertSection` (wired in `app.js` via `renderRevert`/`reviewRevert`/`confirmRevert`) — reachable from the Results step after a successful job, or "View report / revert" in saved history.

- **`RevertRows`** (new class) does the actual guarded work: locks tables in stable order, verifies every row to be deleted still matches what was staged (`DataFingerprint`) before touching anything, computes safe child-before-parent deletion order, and — critically — checks for *any* dependency (FK, view) on a table before dropping it, failing closed if it can't prove there are none.
- **`MigrationService.Revert`** (inner class, mirrors `Job`) tracks state (`QUEUED→RUNNING→REVERTED`/`FAILED`/`PARTIAL`/`RECOVERY_REQUIRED`), persists into the same `job.json` journal (new `RevertManifest`), and survives restart the same way jobs do. `Job.targetObjectIds` (via new `DatabaseGateway.objectIdentity`) and `Job.createdFingerprints` are captured **during the original migration**, not computed retroactively — older jobs from before this feature shipped won't have them and revert correctly refuses with "no saved target table identity."
- Row removal commits first, table drops happen after, one at a time, each re-checking dependencies immediately beforehand (never `CASCADE`) — because Oracle DDL commits implicitly and can't be rolled back if something goes wrong mid-sequence.

**Oracle-specific gotcha, confirmed by live testing, not just reading code:** the cross-schema dependency check needs `SELECT` on `SYS.DBA_CONSTRAINTS`, `SYS.DBA_DEPENDENCIES`, `SYS.DBA_SYNONYMS`. Granting those three views to a target schema **requires a true `SYS AS SYSDBA` connection** — an account with just the `DBA` role (e.g. `SYSTEM`) hits `ORA-01031: insufficient privileges` even though it has `GRANT ANY OBJECT PRIVILEGE`. This is an Oracle 23ai hard restriction on SYS-owned dictionary objects, not a configurable legacy toggle (`O7_DICTIONARY_ACCESSIBILITY` doesn't even exist as a parameter in this version anymore). Confirmed empirically: `GRANT SELECT ON SYS.DBA_CONSTRAINTS TO <schema>` as `SYSTEM` fails; the identical grant as `sys ... as sysdba` succeeds. A real Oracle DBA has to run this grant once per target schema, as SYS, before revert can be used there — document this for anyone deploying revert against real Oracle. Without it, revert fails closed with a clear message rather than silently skipping the dependency check (this is correct, tested behavior — see `oracleRevertRequiresCompleteDependencyVisibility` in `OracleTransferTest`).

**Validated 2026-10-08**, continuing work Codex started and ran out of budget on mid-implementation (it was researching this exact Oracle privilege question when it stopped):
- `RevertServiceTest` (H2): 9/9 pass.
- Full regression suite: 101/101 pass, 0 regressions from this feature.
- Live Oracle (local Docker, `gvenzl/oracle-free`): all 4 `OracleTransferTest` revert scenarios confirmed correct — drops a newly-created table with verified LOB data, keeps an existing parent while dropping a new child, blocks on cross-schema cascade/replaced-table, and fails closed without dictionary access (this last one needs *no* grants and passes against a pristine schema; the other three need the SYS grant above).
- Live PostgreSQL (disposable Docker container): both `PostgresTransferTest` revert scenarios pass (drop created table / preserve existing rows; block on dependent view).
- Full browser walkthrough (Playwright) against live Oracle: ran a real migration, reviewed revert (saw the correct "needs DBA_CONSTRAINTS" block before granting privileges, then the full table/row preview after granting), confirmed, and independently verified via `sqlplus` that the table was actually dropped. Zero unexpected console errors.

## Retracted: `localhost` Host-header rejection

An earlier pass of this document claimed every request with a `Host` header other than the literal `127.0.0.1` got a bare container-level `500` (and that `test-bulk-ui.mjs` failed against `localhost` for this reason). That was observed repeatedly in one session: `localhost`/arbitrary Host values returning an empty-body 500 bypassing both `WebBoundary` and `ApiErrors` entirely, while `127.0.0.1` worked.

**On a later, fully clean retest (fresh process, port confirmed free beforehand, exact original repro steps including the original `node fetch` call) it did not reproduce.** `http://localhost:8098` now works correctly on every route, and `WebBoundary` behaves exactly as its source implies: `localhost` is accepted (it's in the allow-list), and a disallowed `Host` like `evil.example.com` correctly gets a `403` with the filter's own headers — not the mystery empty 500 seen before. `WebBoundary.java` was not touched between the two sessions.

No root cause was found for the original observation, and it could not be reproduced on demand, so no code fix was made. If this resurfaces: check for a stale/zombie process already bound to port 8098 before assuming it's `WebBoundary` or Tomcat config — the original symptom (bare 500, no app-level headers, bypassing a filter whose own logic contradicts the observed behavior) is more consistent with something holding the port other than the current build than with a logic bug in checked-in code.

## Fixed bug: `#tablePicker` dialog rendered permanently open

**Fixed 2026-10-08.** `index.html` has `<dialog id="tablePicker">` (the "Choose tables and names" modal), meant to only appear via `.showModal()` when the user clicks "Or choose a folder" / table-selection entry points — `app.js:296` is the only call site. In the live DOM, `tablePicker.open` was correctly `false` on page load. But `styles.css:25` had:

```css
dialog#tablePicker{display:flex;flex-direction:column;width:min(1080px,calc(100vw - 32px));...}
```

— no `[open]` qualifier. Author CSS always wins over the UA stylesheet's `dialog:not([open]){display:none}`, so this rule forced the dialog to render (`getComputedStyle(...).display === "flex"`) regardless of its actual open/closed state. The comment above it ("Keep the table picker controls visible while long lists and mappings scroll inside it") shows the intent was to style the *open* dialog's internal layout — the `[open]` attribute selector was dropped by mistake.

Impact had been confirmed on desktop (1680px) and mobile (390px) viewports, every wizard step: the full "Choose tables and names" panel sat permanently in-page as an inline block (no backdrop, since `showModal()` was never called), adding ~800–1300px of irrelevant scroll on steps that don't use it, and on **Review** rendering as a centered box that visually covered the compatibility report underneath.

**Fix applied:** `dialog#tablePicker[open]{...}` (added the `[open]` qualifier), `src/main/resources/static/styles.css:25`. Re-verified by Playwright: hidden (`display:none`) on fresh load across all four steps and both viewports; opens correctly as a true modal with proper centering and `::backdrop` (which it couldn't show before, since the CSS fight prevented the native dialog top-layer behavior from ever engaging) when triggered from a DB workflow's "Choose tables" button; closes cleanly (`open:false`) via Cancel. Full regression suite re-run clean: 101/101, 0 regressions.

## Validation status (last checked 2026-10-08, Java 21.0.9 / Maven 3.9.12 / Node 24.13.0)

- `mvn verify`: **BUILD SUCCESS** — 101 tests run, 0 failures, 20 skipped (the skipped ones are `OracleTransferTest`/`PostgresTransferTest`/live-DB `RevertServiceTest` cases, which require live disposable Oracle/PostgreSQL instances per README — H2 is a test fixture only, not proof of cross-dialect compatibility).
- `node --check app.js`: clean.
- Live server smoke tests against a running instance (`smoke-test.mjs`, `smoke-edge-cases.mjs`, `smoke-cli.mjs`): all pass.
- `test-connection-ui.mjs`, `test-catalog-ui.mjs`, `test-bulk-ui.mjs` (default `localhost` URL, no override): all pass.
- Real-browser walkthrough (Playwright) of the full golden path (Workflow → Configure → load example → Review → Results → download ZIP) on desktop and mobile viewports: **functionally works end to end, zero console errors**, and (after the fix above) visually correct on every step.

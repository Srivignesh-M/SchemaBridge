# Schema Bridge UI/UX redesign — design spec

Status: approved by user, pending implementation plan.

## 1. Goal and scope

Redesign the entire frontend of SchemaBridge (`src/main/resources/static/{index.html,app.js,styles.css}`) into a dark, developer-tool-grade visual style with a sidebar application shell, while changing **zero backend behavior**. This is a reskin and information-architecture reorganization of an existing single-session migration wizard — not a new product, not a frontend framework migration, not a rewrite of API contracts.

**Explicitly out of scope** (decided during brainstorming, with reasons):
- A persistent "Connections" manager — no backend storage for saved connections exists. Connection entry stays inside Migration → Configure → Source/Target.
- A full "Schema Explorer" tree (Views/Procedures/Functions/Sequences/Indexes) — backend only exposes `schemas()`/`tables()`/`columns()`. Table browsing stays inside the table picker.
- A timestamped, leveled (INFO/WARN/ERROR) log console — doesn't exist server-side. The real per-table progress/status/message feed is styled as "Execution details" in a monospace, terminal-like panel, but never fabricates timestamps or severity levels.
- React/Vue/Svelte, any bundler, any build step — the existing architecture is vanilla JS/CSS with zero build step, served directly as Spring static resources, consumed directly by the desktop WebView2 shell. This redesign preserves that exactly.

## 2. Information architecture

Sidebar navigation, each item backed by real, already-existing data:

| Item | Backed by | Role |
|---|---|---|
| Dashboard | `GET /jobs`, `GET /storage` | Landing page: quick-start flow cards, recent activity table, storage summary. No invented metrics. |
| Migration | `/plans`, `/preparations`, `/plans/{id}/execute`, `/jobs/{id}`, etc. | The real 4-step wizard: Workflow → Configure → Review → Execution Details. |
| History | `GET /jobs` | Promoted from an embedded card to its own page. |
| Storage | `GET /storage` | Promoted from an embedded card to its own page. |
| Settings | existing export/import settings profile | Promoted to its own page; copy is explicit that this is a local JSON file, not a saved-connections manager. |

Within **Migration → Configure** (step 2), sub-tabs: **Source → Target → Objects → Options** — purely a UI grouping of the existing single `/plans` POST request's fields; no new API calls.

## 3. Visual design system

**Colors** (CSS custom properties, dark only — no light theme toggle):
```
--bg-primary:#0a0e14  --bg-secondary:#0f1420  --bg-surface:#141a24  --bg-elevated:#1a212e
--border:#262e3d  --border-strong:#34404f
--text-primary:#e4e8ef  --text-secondary:#8b94a7  --text-tertiary:#5a6378
--accent:#3fb6ff  --accent-muted:#1c3a52
--success:#3ecf8e  --warning:#e8a33d  --error:#f0555a  --info:#3fb6ff
```
- `--text-secondary` is used for all real/essential labels and metadata; `--text-tertiary` is reserved for decorative or disabled content only.
- Status semantics: `SUCCEEDED`/`MATCH`/`Connected` → success; `FAILED`/`MISMATCH`/`RECOVERY_REQUIRED` → **error** (requires attention, per user correction); `RUNNING`/`QUEUED`/`PREPARING` → info; `CANCELLED` → **neutral/warning** (a deliberate action, not a failure), `warnings` → warning.
- Every status indicator pairs color with text or an icon — never color alone.
- Visible keyboard focus rings on all interactive elements; explicit selected/disabled/error input states (border + background + icon, not color alone).

**Typography:** system font stack only, no external font loading —
- UI text: `system-ui, -apple-system, "Segoe UI", sans-serif`
- Monospace (SQL, identifiers, connection values, execution details, technical values): `ui-monospace, Consolas, "SF Mono", monospace`
- Connection labels and form controls stay in the UI font; only raw technical values go monospace.
- Five sizes: 20/16/14/13/12px (page title → section title → body → secondary → metadata).

**Spacing/radius:** 4/8/12/16/24/32px spacing scale; 6px radius for inputs/buttons, 10–12px for cards/dialogs.

Both approved in the visual companion mockups (`dashboard.html`, `migration-configure-v2.html`).

## 4. Technical architecture

**No build step, no new dependencies.** `index.html` loads multiple **classic** (non-module) scripts in dependency order:

```html
<script src="/js/api.js"></script>
<script src="/js/state.js"></script>
<script src="/js/components/status-badge.js"></script>
<script src="/js/components/data-table.js"></script>
<script src="/js/components/progress-panel.js"></script>
<script src="/js/components/toast.js"></script>
<script src="/js/components/confirm-dialog.js"></script>
<script src="/js/connection/connection-form.js"></script>
<script src="/js/connection/datasource-catalog-picker.js"></script>
<script src="/js/migration/flow-picker.js"></script>
<script src="/js/migration/configure-source.js"></script>
<script src="/js/migration/configure-target.js"></script>
<script src="/js/migration/configure-objects.js"></script>
<script src="/js/migration/table-picker.js"></script>
<script src="/js/migration/table-settings-panel.js"></script>
<script src="/js/migration/filter-row.js"></script>
<script src="/js/migration/configure-options.js"></script>
<script src="/js/migration/stepper.js"></script>
<script src="/js/review/comparison-summary.js"></script>
<script src="/js/review/table-report-table.js"></script>
<script src="/js/execution/execution-details.js"></script>
<script src="/js/execution/revert-panel.js"></script>
<script src="/js/dashboard/dashboard-page.js"></script>
<script src="/js/history/history-page.js"></script>
<script src="/js/storage/storage-page.js"></script>
<script src="/js/settings/settings-page.js"></script>
<script src="/js/shell.js"></script>
<script src="/js/main.js" defer></script>
```

**Why classic scripts, not ES modules:** the existing jsdom test harnesses (`scripts/test-*-ui.mjs`) load the app via `window.eval(fileText)` with `runScripts:'outside-only'` — `eval()` only ever executes classic-script syntax; `import`/`export` statements throw a `SyntaxError` there. Splitting into multiple classic files avoids this incompatibility entirely (each file is still plain, eval-safe JS) at the cost of a small, explicit, mechanical update to the 4 test harnesses: read and `eval()` every file in dependency order instead of just `app.js` (see §7).

**Namespace, not globals:** every file attaches to one `window.SchemaBridge = window.SchemaBridge || {}` object (e.g. `SchemaBridge.components.renderStatusBadge(...)`, `SchemaBridge.state.migration`) instead of polluting `window` directly — this is the one structural improvement over today's flat `$()`-and-bare-function-names style, and it's what makes the file split safe (no name collisions across files).

CSS: `styles.css` restructured with a `:root` token block (§3) followed by component-scoped rules; may split into `tokens.css` + `components.css` behind multiple `<link>` tags if that proves cleaner during implementation — zero build-step impact either way.

**No change to:** `MigrationController`, `MigrationService`, `DatabaseGateway`, `Model`, `WebBoundary` (confirmed its CSP `script-src 'self'` already permits same-origin multi-file script loading, no header change needed), any packaging script (`package-browser.ps1`, `package-windows.ps1`, `package-system-java.ps1`), the desktop WebView2 launcher, or either GitHub Actions workflow. Static files are still served exactly as today.

## 5. State management (cross-cutting)

A single module-level `SchemaBridge.state.migration` object holds the *entire* wizard state — not just `plan`/`finalJob`, but every configuration input: dialects, connection fields, selected source tables, per-table settings (column include/rename, filters, row limit, destination table name), selected per-table actions from Review, current step, `maxStep`, `lastRequest`, `requestVersion`. This object is created once and **only** reset by explicit "Start another workflow" — navigating to History/Storage/Settings and back to Migration must restore the exact in-progress state, unchanged.

- **`invalidate()`** (today's "any input change clears the current plan and forces re-analysis" behavior) is preserved exactly and still fires on every relevant input change, independent of page navigation — navigating away and back must never itself invalidate, only real input changes do.
- **Polling**: leaving the Migration page stops the UI's polling timer only — the server-side job/preparation keeps running regardless (it always has; polling never controlled it). Returning to Migration immediately fetches current status once, then resumes the polling timer **only if** the job/preparation is still in an active state (`QUEUED`/`RUNNING`/`PREPARING`); if it already finished while away, render the final state without restarting polling.
- **Execution action visibility**: exactly today's conditional logic — `execute` hidden unless `plan.targetChecked` and not an ordered script; `download`/`prepareData` gated on `plan.prepared`; ordered-script plans stay review/download-only, never executable. No simplification that could surface an invalid action.
- **Opening a History job while Migration has unfinished work**: "unfinished work" = current step > 1 with no `finalJob` yet for the active plan, or input changes since the last successful analysis (`lastRequest` doesn't match current form state). If unfinished work exists, opening a History entry must prompt ("Opening this saved migration will replace your current unsaved configuration. Continue, or keep editing?") before replacing `SchemaBridge.state.migration`; if there's no unfinished work, open directly as today.
- **Error toasts**: lose the 15-second auto-dismiss; gain a manual close button; `role="alert"`, stay until dismissed. Success/info toasts (download link ready, settings imported) still auto-fade after ~6–8s since they aren't failures.
- **Settings page copy**: explicit that export/import is a local JSON file round-trip only — "Nothing is stored on the server. This does not save connections — passwords are never included and must be re-entered after import."

## 6. Preserved functionality checklist

Every item below is existing, real behavior found in the current `app.js`/`index.html` and confirmed must survive unchanged:
- 500-table selection cap (reject 0 or >500 in the table picker).
- LCNC datasource catalog picker (`/datasources`, `/datasources/{id}/select`) alongside manual connection entry.
- Lazy-loaded per-table settings panel (columns fetched via `/columns` only when a table's settings are expanded) — now presented as collapsible `<details>` disclosures for Columns / Filters / Row limit (per user request), each showing a collapsed-state summary (e.g. "3 of 4 included", "3 active", "50,000").
- Up to 20 AND-combined, boolean-aware filters per table; optional positive-integer row limit; destination table/column renaming.
- Compact header stepper nav (Workflow/Configure/Review/Execution Details), including active/complete/disabled states.
- Preparation flow for metadata-only plans (background full extraction, cancel, progress).
- Download-ticket flow (short-lived URL, click-to-save anchor, 2-minute expiry messaging) and desktop WebView2 save behavior — untouched.
- Revert flow: review (target + expiry + per-table drop/remove-rows list) → explicit acknowledgment checkbox → danger-styled confirm, or dismiss.
- RECOVERY_REQUIRED handling, including the "Review and discard evidence" danger-styled delete path for uncertain jobs/reverts.
- Row-budget percentage line, structured column-difference findings with correction text, "DML only for all matches" bulk action, ordered-script statement-level report.
- Storage orphaned-directory warning, reclaimable-bytes preview.

## 7. Rollout plan

1. Restructure `app.js` into the file layout in §4; restructure `styles.css` with the token system in §3; restructure `index.html` into the sidebar shell + page containers.
2. Update the 4 jsdom test harnesses (`test-connection-ui.mjs`, `test-bulk-ui.mjs`, `test-catalog-ui.mjs`, `test-table-settings-ui.mjs`) to read and `eval()` every `js/*.js` file in dependency order instead of just `app.js` — mechanical change, same assertions, same `window.fetch` mocking pattern.
3. Check `scripts/smoke-test.mjs`/`smoke-edge-cases.mjs` for any assertions on specific DOM structure/IDs that move; update only what's needed to keep testing the same real behavior.
4. `mvn -B -ntp -f pom.xml verify` — confirm the build is unaffected (static resources are copied, not compiled).
5. Start the app and manually verify, per the user's explicit list:
   - Desktop downloads (ZIP ticket + WebView2 save) still work.
   - Navigating to History/Storage/Settings while a job is `RUNNING`: UI polling stops (no network calls), the job keeps running server-side, returning to Migration fetches current state immediately and resumes polling only if still active.
   - Opening a History job with an unfinished Migration configuration active triggers the replace-confirmation; opening with no unfinished work does not.
   - Revert review/confirm/cancel, and the RECOVERY_REQUIRED "discard evidence" path.
   - 500-table cap (accept 500, reject 501), lazy-loaded per-table settings, multi-filter add/remove, row-limit validation.
   - Error toasts persist until manually dismissed; success/info toasts still fade.
   - Responsive behavior: sidebar collapses, table picker stacks, forms remain usable at narrow widths.
   - Zero browser console errors across all of the above.
6. Re-run all four updated jsdom scripts and both HTTP smoke scripts; re-run `mvn verify`.

## 8. Risks / open items

- The jsdom-harness update (step 7.2) is small but is new, necessary engineering work this redesign introduces — not just a reskin side effect. Flagging it explicitly rather than discovering it mid-implementation.
- No other backend, packaging, or CI risk identified — this redesign touches exactly three static files (now split into many) and four test scripts.

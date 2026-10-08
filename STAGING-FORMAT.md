# Local staging format

This note describes the staging artifacts written by the current application. They are private working files, not a supported interchange format. The implementation may change them when the plan-manifest version changes.

## Layout and ownership

Each plan is stored in its own UUID-named directory under the configured work directory. `plan.json` records the plan, table definitions, row counts, options, staged-file names and SHA-256 hashes. `job.json` records execution state. Manifests are written through a temporary file and an atomic move. Credentials are not stored in either manifest.

Artifact names are restricted to `data-N.rows`, `data-N.jsonl`, and `lob-<UUID>`. Symbolic links and names outside this allowlist are rejected. Before target writes, the service checks every staged artifact listed in the plan against its saved SHA-256 hash. A missing or changed artifact blocks execution and requires a fresh plan or extraction.

## Database-source rows (`.rows`)

Database snapshots use UTF-8 JSON Lines. Each line is a JSON array of cells, with one cell for each selected column in the order recorded by the table definition in `plan.json`:

```json
[{"type":"BIGINT","value":"42","file":null},{"type":"TEXT","value":"hello","file":null}]
```

Each cell has a type kind, an optional canonical string value, and an optional sidecar filename. SQL null is represented by both `value` and `file` being null. Integer and decimal values use plain decimal strings; dates, local timestamps, and offset timestamps use ISO text; small binary values use Base64. JDBC binding parses these strings back to typed values rather than executing staged SQL.

Large text and binary values use sidecars in the same plan directory. Text sidecars contain UTF-8 text; binary sidecars contain the original bytes. The cell refers to the sidecar by its generated `lob-<UUID>` filename. Sidecars are hashed and checked with the row file before writes.

## SQL-file rows (`.jsonl`)

SQL-file conversion uses a separate representation: each line is a JSON-escaped SQL `INSERT` statement. This preserves the converter's ordered, supported literal values for export. It is not the database snapshot format and is not loaded through the typed row binder.

## Limits

The plan manifest currently has version `1`; it does not place a separate header in each data file. Column order and types come from `plan.json`, while the manifest version governs restoration. Do not edit, rename, transfer between plans, or consume these files with external tools. The hashes detect accidental or unauthorized changes; they do not encrypt staged database content. Apply normal local disk access controls and delete a plan through the application when its staged data is no longer needed.

"""Optional independent PostgreSQL grammar check; never connects to a database."""
import json
import sys
from pathlib import Path

module = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(module / "target" / "pg-parser"))
from pglast import parse_sql

report = json.loads((module / "target" / "edge-case-report.json").read_text(encoding="utf-8"))
checked = 0
for statement in report["statements"]:
    if statement["convertedSql"]:
        try:
            parse_sql(statement["convertedSql"])
        except Exception as error:
            raise RuntimeError(f"Statement {statement['number']}: {error}") from error
        checked += 1
parse_sql(";\n".join(s["convertedSql"] for s in report["statements"] if s["convertedSql"]))
print(f"PostgreSQL grammar accepted {checked} converted source statements, individually and in source order.")
print("This checks syntax, not catalog dependencies, permissions, data constraints or semantic equivalence.")

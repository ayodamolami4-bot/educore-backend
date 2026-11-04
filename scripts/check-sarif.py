"""Summarize actual CodeQL output and reject unresolved high/critical findings."""
import json
import sys
from pathlib import Path

folder = Path(sys.argv[1] if len(sys.argv) > 1 else "codeql-results")
files = list(folder.glob("*.sarif"))
if not files:
    raise SystemExit("CodeQL produced no SARIF files; do not report a completed scan.")
total = 0
high = []
for file in files:
    for run in json.loads(file.read_text())["runs"]:
        rules = {rule["id"]: rule for rule in run["tool"]["driver"].get("rules", [])}
        for result in run.get("results", []):
            total += 1
            rule = rules.get(result.get("ruleId"), {})
            severity = float(rule.get("properties", {}).get("security-severity", "0"))
            if severity >= 7:
                high.append(result.get("ruleId", "unidentified-rule"))
print(f"CodeQL SARIF: {len(files)} file(s), {total} result(s), {len(high)} high/critical security finding(s).")
if high:
    print("Review and fix:", ", ".join(sorted(set(high))))
    raise SystemExit(1)
print("A completed static scan is not an independent access review or production security sign-off.")

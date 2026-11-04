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
        components = [run["tool"]["driver"], *run["tool"].get("extensions", [])]
        rules = {rule["id"]: rule for component in components for rule in component.get("rules", [])}
        for result in run.get("results", []):
            total += 1
            rule = rules.get(result.get("ruleId"))
            if rule is None:
                raise SystemExit("A scan result has no rule metadata; review the SARIF before accepting the gate.")
            severity = float(rule.get("properties", {}).get("security-severity", "0"))
            locations = [item.get("physicalLocation", {}) for item in result.get("locations", [])]
            print("CodeQL finding:", json.dumps({
                "rule": result.get("ruleId"), "severity": severity,
                "locations": [{"path": item.get("artifactLocation", {}).get("uri"), "line": item.get("region", {}).get("startLine")} for item in locations],
                "message": result.get("message", {}).get("text", "")[:600],
            }))
            if severity >= 7:
                high.append(result.get("ruleId", "unidentified-rule"))
print(f"CodeQL SARIF: {len(files)} file(s), {total} result(s), {len(high)} high/critical security finding(s).")
if high:
    print("Review and fix:", ", ".join(sorted(set(high))))
    raise SystemExit(1)
print("A completed static scan is not an independent access review or production security sign-off.")

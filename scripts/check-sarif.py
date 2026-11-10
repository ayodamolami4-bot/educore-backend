"""Report every CodeQL result; reject unresolved high/critical findings."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def reviewed_finding(result, severity, root):
    review_file = root / "docs/codeql-reviewed-findings.json"
    if not review_file.is_file():
        return None
    locations = result.get("locations", [])
    if len(locations) != 1:
        return None
    location = locations[0].get("physicalLocation", {})
    path = location.get("artifactLocation", {}).get("uri")
    line = location.get("region", {}).get("startLine")
    for review in json.loads(review_file.read_text())["findings"]:
        if (review["rule"], review["severity"], review["path"], review["line"]) != (
                result.get("ruleId"), severity, path, line):
            continue
        if not review.get("reason") or not review.get("sourceSha256"):
            raise ValueError("A finding review requires a reason and frozen source fingerprints.")
        for relative, expected in review["sourceSha256"].items():
            source = (root / relative).resolve()
            if not source.is_relative_to(root.resolve()) or not source.is_file():
                raise ValueError("A finding review references unavailable source.")
            if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                raise ValueError("Reviewed source changed; reassess finding " + review["id"] + ": " + relative)
        return review
    return None


def check(folder, root=ROOT):
    files = sorted(folder.glob("*.sarif"))
    if not files:
        raise ValueError("CodeQL produced no SARIF files; do not report a completed scan.")
    total, reviewed, high = 0, [], []
    for file in files:
        for run in json.loads(file.read_text())["runs"]:
            components = [run["tool"]["driver"], *run["tool"].get("extensions", [])]
            rules = {rule["id"]: rule for component in components for rule in component.get("rules", [])}
            for result in run.get("results", []):
                total += 1
                rule = rules.get(result.get("ruleId"))
                if rule is None:
                    raise ValueError("A scan result has no rule metadata; review SARIF before accepting the gate.")
                severity = float(rule.get("properties", {}).get("security-severity", "0"))
                locations = [item.get("physicalLocation", {}) for item in result.get("locations", [])]
                review = reviewed_finding(result, severity, root) if severity >= 7 else None
                print("CodeQL finding:", json.dumps({
                    "rule": result.get("ruleId"), "severity": severity,
                    "locations": [{"path": item.get("artifactLocation", {}).get("uri"),
                                   "line": item.get("region", {}).get("startLine")} for item in locations],
                    "message": result.get("message", {}).get("text", "")[:600],
                    "review": review["id"] if review else None,
                }))
                if severity >= 7:
                    if review:
                        if review["id"] in reviewed:
                            raise ValueError("A finding review matched more than once; reassess its scope.")
                        reviewed.append(review["id"])
                        print("Reviewed diagnostic finding:", review["id"], review["reason"])
                    else:
                        high.append(result.get("ruleId", "unidentified-rule"))
    print(f"CodeQL SARIF: {len(files)} file(s), {total} result(s), "
          f"{len(high) + len(reviewed)} high/critical finding(s), "
          f"{len(reviewed)} reviewed diagnostic exception(s), {len(high)} unresolved.")
    if high:
        print("Review and fix:", ", ".join(sorted(set(high))))
        return 1
    print("Findings remain visible in uploaded SARIF. No alert is dismissed by this gate.")
    print("A completed static scan is not an independent access review or production security sign-off.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(check(Path(sys.argv[1] if len(sys.argv) > 1 else "codeql-results")))
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(str(error))

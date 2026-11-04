"""Fail when a controller route or page connection is missing from the handoff."""
import csv
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]

def check_integration_wiring(scripts, runner):
    """Check this project's explicit scenario-module convention, not runtime coverage."""
    count = 0
    for module in sorted(scripts.glob("*-tests.mjs")):
        entries = re.findall(r"export\s+async\s+function\s+(\w+)\s*\(", module.read_text())
        assert len(entries) == 1, f"{module.name}: expose one async scenario entry point"
        name = entries[0]
        imports = re.findall(
            r"import\s*\{([^}]+)\}\s*from\s*['\"]\./" + re.escape(module.name) + r"['\"]",
            runner,
        )
        assert any(re.search(r"\b" + re.escape(name) + r"\b", item) for item in imports), (
            f"{module.name}: {name} is missing from the integration runner imports"
        )
        assert re.search(r"\bawait\s+" + re.escape(name) + r"\s*\(", runner), (
            f"{module.name}: {name} is imported but not awaited by the integration runner"
        )
        count += 1
    assert count > 0, "No integration scenario modules were discovered"
    return count

canonical = lambda path: re.sub(r"\{[^}]+\}", "{}", path)
source = set()
controllers = list(root.glob("educore-*/src/main/java/**/*Controller.java"))
controllers += list(root.glob("platform/src/main/java/**/*Controller.java"))
for file in controllers:
    text = file.read_text()
    prefix_match = re.search(r'@RequestMapping\("([^"]*)"\)', text[:text.index("public class")])
    prefix = prefix_match.group(1) if prefix_match else ""
    for match in re.finditer(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?", text):
        route = re.search(r'"([^"]*)"', match.group(2) or "")
        path = prefix + (route.group(1) if route else "")
        if path.startswith("/api/v1/"):
            source.add((match.group(1).upper(), canonical(path[7:])))

spec = json.loads((root / "docs/openapi.json").read_text())
catalogue = {(verb.upper(), canonical(path)) for path, methods in spec["paths"].items() for verb in methods}
assert source == catalogue, {"missing": sorted(source - catalogue), "unimplemented": sorted(catalogue - source)}

def refs(value):
    if isinstance(value, dict):
        if "$ref" in value:
            ref = value["$ref"]
            assert ref.startswith("#/"), "Only local schema references are expected"
            target = spec
            for segment in ref[2:].split("/"):
                target = target[segment.replace("~1", "/").replace("~0", "~")]
        for item in value.values():
            refs(item)
    elif isinstance(value, list):
        for item in value:
            refs(item)
refs(spec)
with (root / "docs/ENDPOINT_CATALOGUE.csv").open(newline="") as file:
    rows = list(csv.DictReader(file))
assert {(row["method"], canonical(row["path"])) for row in rows} == source
connections = root / "docs/screen-connections.json"
if connections.exists():
    screens = json.loads(connections.read_text())["screens"]
    assert len(screens) == 94 and len({screen["id"] for screen in screens}) == 94
    for screen in screens:
        for endpoint in screen["endpoints"]:
            assert (endpoint["method"], canonical(endpoint["path"])) in source, (screen["id"], endpoint)
print(f"API catalogue: {len(source)} implemented operations, resolved schemas and valid page connections")
modules = check_integration_wiring(root / "scripts", (root / "scripts/integration.mjs").read_text())
print(f"Integration wiring: {modules} scenario modules imported and awaited; runtime PASS evidence is still required")

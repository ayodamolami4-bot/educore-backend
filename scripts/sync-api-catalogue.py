"""Refresh the request handoff from the project's simple Spring mappings and Java records.

Response maps and conditional business validation stay documented in API.md. This script
does not infer a complete response schema or permissions from Java method names.
"""
import copy
import csv
import io
import json
import re
from datetime import date
from pathlib import Path

root = Path(__file__).resolve().parents[1]
spec_file = root / "docs/openapi.json"
spec = json.loads(spec_file.read_text())
canonical = lambda value: re.sub(r"\{[^}]+\}", "{}", value)

def balanced(text, start, opening="(", closing=")"):
    depth = 0
    quoted = False
    escape = False
    for index in range(start, len(text)):
        char = text[index]
        if quoted:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("Unbalanced Java declaration")

def split_fields(text):
    parts, start, angles, parens = [], 0, 0, 0
    for index, char in enumerate(text):
        angles += (char == "<") - (char == ">")
        parens += (char == "(") - (char == ")")
        if char == "," and angles == 0 and parens == 0:
            parts.append(text[start:index].strip())
            start = index + 1
    if text[start:].strip():
        parts.append(text[start:].strip())
    return parts

records = {}
java_files = sorted(list(root.glob("educore-*/src/main/java/**/*.java")) + list(root.glob("platform/src/main/java/**/*.java")))
for file in java_files:
    source = file.read_text()
    for match in re.finditer(r"\brecord\s+(\w+)\s*\(", source):
        start = match.end() - 1
        records[file.stem + "." + match.group(1)] = split_fields(source[start+1:balanced(source, start)])

schemas = spec["components"]["schemas"]
built = set()
def schema_for(kind, owner):
    kind = re.sub(r"\s+", "", kind)
    short = kind.rsplit(".", 1)[-1]
    primitive = {
        "String": {"type": "string"}, "UUID": {"type": "string", "format": "uuid"},
        "LocalDate": {"type": "string", "format": "date"},
        "Instant": {"type": "string", "format": "date-time"}, "OffsetDateTime": {"type": "string", "format": "date-time"},
        "boolean": {"type": "boolean"}, "Boolean": {"type": "boolean"},
        "int": {"type": "integer", "format": "int32"}, "Integer": {"type": "integer", "format": "int32"},
        "long": {"type": "integer", "format": "int64"}, "Long": {"type": "integer", "format": "int64"},
        "BigDecimal": {"type": "number"}, "double": {"type": "number"},
        "JsonNode": {"type": "object", "additionalProperties": True}, "Object": {},
        "byte[]": {"type": "string", "format": "binary"}
    }
    if short in primitive:
        return {**primitive[short], "x-java-type": kind}
    if re.match(r"(?:List|Set)<", kind):
        return {"type": "array", "items": schema_for(kind[kind.index("<")+1:-1], owner), "x-java-type": kind}
    if kind.startswith("Map<"):
        fields = split_fields(kind[4:-1])
        return {"type": "object", "additionalProperties": schema_for(fields[1], owner), "x-java-type": kind}
    name = kind if kind in records else owner + "." + kind
    if name not in records:
        candidates = [value for value in records if value.endswith("." + kind)]
        if len(candidates) != 1:
            raise ValueError("Unresolved request type " + owner + ": " + kind)
        name = candidates[0]
    key = name.replace(".", "_")
    if key not in built:
        built.add(key)
        props = {}
        schemas[key] = {"type": "object", "x-java-record": name,
                        "description": "Declared fields; conditional and cross-record validation is enforced by the service. See API.md.", "properties": props}
        for field in records[name]:
            field = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", field)
            declared, prop = field.rsplit(None, 1)
            props[prop] = schema_for(declared, name.split(".")[0])
    return {"$ref": "#/components/schemas/" + key, "x-java-type": kind}

old_paths = spec["paths"]
old_keys = {canonical(path): path for path in old_paths}
paths = {}
template = copy.deepcopy(next(iter(next(iter(old_paths.values())).values())))
for file in [value for value in java_files if value.name.endswith("Controller.java")]:
    source = file.read_text()
    match = re.search(r'@RequestMapping\("([^"]*)"\)', source[:source.index("public class")])
    prefix = match.group(1) if match else ""
    mappings = list(re.finditer(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?", source))
    for match in mappings:
        quoted = re.search(r'"([^"]*)"', match.group(2) or "")
        actual = prefix + (quoted.group(1) if quoted else "")
        if not actual.startswith("/api/v1/"):
            continue
        path = old_keys.get(canonical(actual[7:]), actual[7:])
        verb = match.group(1).lower()
        tail = source[match.end():]
        # Drop method annotations such as @ResponseStatus before locating its signature.
        tail = re.sub(r"^\s*@\w+(?:\([^)]*\))?\s*", "", tail)
        method = re.search(r"\b(\w+)\s*\(", tail)
        assert method, file
        start = method.end()-1
        end = balanced(tail, start)
        params = split_fields(tail[start+1:end])
        body_start = tail.index("{", end)
        body_end = balanced(tail, body_start, "{", "}")
        code = tail[body_start:body_end+1]
        existing = old_paths.get(path, {}).get(verb)
        op = copy.deepcopy(existing or template)
        op["tags"] = ["academics" if "educore-academics" in file.parts else "finance" if "educore-finance" in file.parts else "communication" if "educore-communication" in file.parts else "identity" if "educore-identity" in file.parts else "platform"]
        op["summary"] = verb.upper()+" "+path
        op["operationId"] = op.get("operationId") if existing else verb+"_"+re.sub(r"[^a-zA-Z0-9]+", "_", path).strip("_")
        op["x-controller"] = file.stem
        op["x-source"] = str(file.relative_to(root))
        headers = [param for param in (existing or {}).get("parameters", []) if param["in"] == "header"]
        if not existing:
            for name, required in [("Idempotency-Key", "Idempotency-Key" in code or "key(h)" in code), ("If-Match", "If-Match" in code or "version(h)" in code)]:
                if required:
                    headers.append({"name": name, "in": "header", "required": True, "schema": {"type": "string"}, "description": "Use the same command key/body/version for retries."})
        path_names = re.findall(r"\{([^}]+)\}", path)
        parameters = []
        for name in path_names:
            previous = next((p for p in (existing or {}).get("parameters", []) if p["in"]=="path" and p["name"]==name), None)
            parameters.append(previous or {"name": name, "in": "path", "required": True, "schema": {"type": "string", **({} if name in ("domain", "kind", "type") else {"format": "uuid"})}})
        request = None
        for field in params:
            clean = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", field).strip()
            if not clean:
                continue
            kind, name = clean.rsplit(None, 1)
            if "@RequestBody" in field:
                request = schema_for(kind, file.stem)
            if "@RequestParam" in field:
                alias = re.search(r'@RequestParam\(\s*"([^"]*)"', field)
                value = {"name": alias.group(1) if alias else name, "in": "query", "required": "required = false" not in field and "required=false" not in field and "defaultValue" not in field, "schema": schema_for(kind, file.stem)}
                default = re.search(r'defaultValue\s*=\s*"([^"]*)"', field)
                if default:
                    text = default.group(1)
                    value["schema"]["default"] = int(text) if kind in ("int", "long") else text=="true" if kind=="boolean" else text
                parameters.append(value)
        op["parameters"] = parameters + headers
        op.pop("requestBody", None)
        if request:
            op["requestBody"] = {"required": True, "content": {("application/octet-stream" if "APPLICATION_OCTET_STREAM_VALUE" in (match.group(2) or "") else "application/json"): {"schema": request}}}
        paths.setdefault(path, {})[verb] = op

spec["paths"] = paths
today = date.today().isoformat()
spec["info"]["description"] = "Controller-backed request contract, reviewed "+today+". Conditional validation, permissions and response maps are documented in API.md and SCREEN_CONNECTIONS.md."
spec_file.write_text(json.dumps(spec, ensure_ascii=False, indent=2)+"\n")
rows = []
for path, methods in paths.items():
    for method, op in methods.items():
        headers = {v["name"]: v for v in op.get("parameters", []) if v["in"]=="header"}
        content = op.get("requestBody", {}).get("content", {})
        request = next(iter(content.values()), {}).get("schema", {})
        ref = request.get("$ref", "").split("/")[-1]
        rows.append({"method": method.upper(), "path": path, "owner": op["tags"][0], "request_record": schemas.get(ref, {}).get("x-java-record", ""),
                     "idempotency_key": "required" if headers.get("Idempotency-Key", {}).get("required") else "",
                     "if_match": ("required" if headers["If-Match"].get("required") else "conditional") if "If-Match" in headers else "",
                     "query_fields": ", ".join(v["name"] for v in op.get("parameters", []) if v["in"]=="query"), "source": op["x-source"]})
out = io.StringIO(newline="")
writer = csv.DictWriter(out, fieldnames=list(rows[0]), lineterminator="\n")
writer.writeheader(); writer.writerows(rows)
(root/"docs/ENDPOINT_CATALOGUE.csv").write_bytes(out.getvalue().encode())
lines = ["# Implemented endpoints and request fields", "", f"Reviewed {today}. These {len(rows)} public operations exist in the Java controllers. Prefix paths with `/api/v1`. Internal routes are excluded.", "", "Start with [SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) and [API.md](API.md) for permissions, workflows, versions and response adapters. Views are implemented by the frontend team.", "", "Protected requests use `Authorization: Bearer <accessToken>`. Commands retain their key, body and version when retried. `If-Match` uses the returned resource version, for example `\"v3\"`.", "", "| Method | Path | Request record | Idempotency-Key | If-Match | Query fields |", "| --- | --- | --- | --- | --- | --- |"]
for row in rows:
    lines.append("| "+" | ".join([row["method"], "`"+row["path"]+"`", row["request_record"] or "—", row["idempotency_key"] or "—", row["if_match"] or "—", row["query_fields"] or "—"])+" |")
lines += ["", "## Request fields", "", "These are declared property names and Java types. Required/conditional values are validated by the services. Lists are JSON arrays; maps are objects; UUIDs are string IDs; dates use ISO format. Money is integer kobo and scores allow one decimal place. See API.md for business rules.", ""]
for schema in sorted((value for value in schemas.values() if "x-java-record" in value), key=lambda value:value["x-java-record"]):
    lines += ["### "+schema["x-java-record"], "", "| Field | Type |", "| --- | --- |"]
    for name, field in schema.get("properties", {}).items():
        lines.append("| `"+name+"` | `"+field.get("x-java-type", field.get("type", "object"))+"` |")
    lines.append("")
(root/"docs/FRONTEND_ENDPOINTS.md").write_text("\n".join(lines)+"\n")
print(f"Refreshed {len(rows)} public operations and {sum('x-java-record' in value for value in schemas.values())} record schemas")

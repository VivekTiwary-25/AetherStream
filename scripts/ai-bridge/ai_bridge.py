#!/usr/bin/env python3
"""Host-side bridge from Glorious pipelines to a headless Claude Code CLI."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "schemas" / "glorious-pipeline.schema.json"
EXAMPLES_DIR = ROOT / "schemas" / "examples"
SYSTEM_PROMPT = """You generate a Glorious live truck processing pipeline. Return ONE JSON object only: {\"name\": short name, \"nodes\": [...], \"edges\": [...]}. No markdown or prose. Use only these five node types and their params: filter {field,op,value}; pickFields {fields}; windowAverage {field,windowSec,groupBy?}; thresholdAlert {field,op,value,message}; output {name}. Allowed fields: truckId (string), speedKmh (number), heading (number), x (number), y (number), routeId (string), status (DRIVING/UNLOADING/IDLE/STOPPED), partition (number 0-5), eventTime (epoch milliseconds). Numeric fields: speedKmh, heading, x, y, partition, eventTime. Operators: >, >=, <, <=, ==, !=; string comparisons allow == and != only. groupBy: truckId, routeId, status, partition. windowSec is an integer 5-300. Graph must be acyclic, have 1-12 nodes, at least one output, every path ends in output, and every non-first node has exactly one input. The source is the live truck position stream; do not add a source node. Use short names.\n"""
BUSY = threading.Lock()
FIELDS = {"truckId": "string", "routeId": "string", "status": "string", "speedKmh": "number", "heading": "number", "x": "number", "y": "number", "partition": "number", "eventTime": "number"}
NUMERIC = {k for k, v in FIELDS.items() if v == "number"}
OPS = {">", ">=", "<", "<=", "==", "!="}
CLI_TIMEOUT_SECONDS = 60


def prompt_context() -> str:
    schema = SCHEMA_PATH.read_text(encoding="utf-8") if SCHEMA_PATH.is_file() else "Schema file is not present; follow the constraints above."
    examples = []
    for path in sorted(EXAMPLES_DIR.glob("*.json")) if EXAMPLES_DIR.is_dir() else []:
        try:
            item = json.loads(path.read_text(encoding="utf-8"))
            examples.append(json.dumps({"prompt": item["prompt"], "pipeline": item["pipeline"]}, ensure_ascii=False))
        except (OSError, ValueError, KeyError, TypeError):
            continue
    return SYSTEM_PROMPT + "\nSchema:\n" + schema + "\nSaved examples:\n" + "\n".join(examples)


def parse_pipeline(result: str) -> dict:
    text = result.strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.IGNORECASE)
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end < start:
            raise ValueError("Claude returned no JSON object")
        value = json.loads(text[start:end + 1])
    if not isinstance(value, dict) or not isinstance(value.get("pipeline", value), dict):
        raise ValueError("Claude response must contain a pipeline object")
    return value.get("pipeline", value)


def local_validate(pipeline: dict) -> list[str]:
    errors = []
    if not isinstance(pipeline, dict):
        return ["pipeline must be an object"]
    if not isinstance(pipeline.get("name"), str) or not 1 <= len(pipeline["name"]) <= 60:
        errors.append("pipeline name must be a string of 1 to 60 characters")
    nodes = pipeline.get("nodes")
    edges = pipeline.get("edges")
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= 12:
        return ["nodes must contain between 1 and 12 nodes"]
    if not isinstance(edges, list):
        return ["edges must be an array"]
    by_id = {}
    for node in nodes:
        if not isinstance(node, dict) or not isinstance(node.get("id"), str) or not isinstance(node.get("params"), dict):
            errors.append("each node must have a string id, type and params object")
            continue
        if node["id"] in by_id:
            errors.append("node ids must be unique")
        by_id[node["id"]] = node
        typ, p = node.get("type"), node["params"]
        allowed = {"filter": {"field", "op", "value"}, "pickFields": {"fields"}, "windowAverage": {"field", "windowSec", "groupBy"}, "thresholdAlert": {"field", "op", "value", "message"}, "output": {"name"}}
        required = {"filter": {"field", "op", "value"}, "pickFields": {"fields"}, "windowAverage": {"field", "windowSec"}, "thresholdAlert": {"field", "op", "value", "message"}, "output": {"name"}}
        if typ not in required:
            errors.append(f"{node['id']}: unsupported node type")
            continue
        if not required[typ].issubset(p):
            errors.append(f"{node['id']}: missing required params")
        if set(p) - allowed[typ]:
            errors.append(f"{node['id']}: unsupported params")
        if typ in ("filter", "thresholdAlert"):
            field, op = p.get("field"), p.get("op")
            if field not in FIELDS or op not in OPS:
                errors.append(f"{node['id']}: invalid field or operator")
            elif FIELDS[field] == "string":
                if op not in ("==", "!=") or not isinstance(p.get("value"), str):
                    errors.append(f"{node['id']}: string fields require == or != and a string value")
            elif not isinstance(p.get("value"), (int, float)) or isinstance(p.get("value"), bool):
                errors.append(f"{node['id']}: numeric field requires a numeric value")
            if typ == "thresholdAlert" and (not isinstance(p.get("message"), str) or not 1 <= len(p["message"]) <= 120):
                errors.append(f"{node['id']}: message must be a string of 1 to 120 characters")
        elif typ == "pickFields":
            if not isinstance(p.get("fields"), list) or not p["fields"] or any(not isinstance(f, str) or f not in FIELDS for f in p["fields"]) or len(set(p["fields"])) != len(p["fields"]):
                errors.append(f"{node['id']}: fields must be a unique non-empty list of allowed fields")
        elif typ == "windowAverage":
            if p.get("field") not in NUMERIC or not isinstance(p.get("windowSec"), int) or isinstance(p.get("windowSec"), bool) or not 5 <= p["windowSec"] <= 300:
                errors.append(f"{node['id']}: invalid windowAverage field or windowSec")
            if p.get("groupBy") is not None and p["groupBy"] not in {"truckId", "routeId", "status", "partition"}:
                errors.append(f"{node['id']}: invalid groupBy")
        elif typ == "output" and (not isinstance(p.get("name"), str) or not 1 <= len(p["name"]) <= 60):
            errors.append(f"{node['id']}: output name must be a string of 1 to 60 characters")
    if not by_id:
        return errors + ["pipeline has no usable nodes"]
    incoming = {key: 0 for key in by_id}
    outgoing = {key: [] for key in by_id}
    for edge in edges:
        if not isinstance(edge, dict) or edge.get("from") not in by_id or edge.get("to") not in by_id:
            errors.append("edge references an unknown node")
            continue
        outgoing[edge["from"]].append(edge["to"])
        incoming[edge["to"]] += 1
    if any(count > 1 for count in incoming.values()):
        errors.append("each node must have at most one input")
    if not any(node.get("type") == "output" for node in by_id.values()):
        errors.append("pipeline requires an output")
    visiting, visited = set(), set()
    def visit(node_id):
        if node_id in visiting:
            return False
        if node_id in visited:
            return True
        visiting.add(node_id)
        okay = all(visit(child) for child in outgoing[node_id])
        visiting.remove(node_id)
        visited.add(node_id)
        return okay
    if not all(visit(node_id) for node_id in by_id):
        errors.append("graph must be acyclic")
    reachable = set()
    def ends_at_output(node_id, path):
        if node_id in path:
            return False
        if not outgoing[node_id]:
            return by_id[node_id].get("type") == "output"
        return all(ends_at_output(child, path | {node_id}) for child in outgoing[node_id])
    for node_id in by_id:
        if incoming[node_id] == 0 and not ends_at_output(node_id, set()):
            errors.append("every graph path must end in an output")
            break
    return errors


def validate_remote(pipeline: dict) -> tuple[bool, list[str], str]:
    url = os.environ.get("AI_VALIDATE_URL", "http://localhost:8088/api/glorious/pipelines/validate")
    try:
        data = json.dumps(pipeline).encode("utf-8")
        request = Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
        with urlopen(request, timeout=5) as response:
            body = json.loads(response.read().decode("utf-8"))
        errors = body.get("errors", [])
        return bool(body.get("valid")), [e.get("message", str(e)) if isinstance(e, dict) else str(e) for e in errors], "control-api"
    except (OSError, URLError, ValueError, TimeoutError):
        errors = local_validate(pipeline)
        return not errors, errors, "local"


def run_claude(prompt: str) -> dict:
    executable = os.environ.get("AI_CMD", "claude")
    command = ([sys.executable, executable] if executable.lower().endswith(".py") else [executable]) + ["-p", "--model", os.environ.get("AI_MODEL", "claude-haiku-5-5"), "--output-format", "json", "--tools", "", "--strict-mcp-config", "--no-session-persistence", "--disable-slash-commands", "--system-prompt", prompt_context()]
    env = os.environ.copy()
    if os.environ.get("CLAUDE_CONFIG_DIR"):
        env["CLAUDE_CONFIG_DIR"] = os.environ["CLAUDE_CONFIG_DIR"]
    kwargs = {"stdin": subprocess.PIPE, "stdout": subprocess.PIPE, "stderr": subprocess.PIPE, "text": True, "env": env, "shell": False}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    process = subprocess.Popen(command, **kwargs)
    try:
        stdout, stderr = process.communicate(input=prompt, timeout=CLI_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as exc:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        else:
            import signal
            os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise TimeoutError("Claude Code timed out") from exc
    completed = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    if completed.returncode:
        raise RuntimeError(f"Claude Code exited {completed.returncode}: {completed.stderr.strip()[:500]}")
    try:
        envelope = json.loads(completed.stdout)
        result = envelope.get("result", completed.stdout) if isinstance(envelope, dict) else completed.stdout
    except json.JSONDecodeError:
        result = completed.stdout
    return parse_pipeline(result)


def examples():
    result = []
    for path in sorted(EXAMPLES_DIR.glob("*.json")) if EXAMPLES_DIR.is_dir() else []:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data.get("prompt"), str) and isinstance(data.get("pipeline"), dict):
                result.append((data["prompt"], data["pipeline"]))
        except (OSError, ValueError, AttributeError):
            continue
    return result


def fallback(prompt: str) -> dict:
    options = examples()
    if not options:
        raise RuntimeError("No saved pipeline examples are available")
    words = set(re.findall(r"[a-z0-9]+", prompt.lower()))
    return max(options, key=lambda item: len(words & set(re.findall(r"[a-z0-9]+", item[0].lower()))))[1]


def generate(prompt: str) -> dict:
    try:
        for attempt in range(2):
            pipeline = run_claude(prompt)
            valid, errors, validated_by = validate_remote(pipeline)
            if valid:
                return {"pipeline": pipeline, "source": "ai", "model": os.environ.get("AI_MODEL", "claude-haiku-5-5"), "ms": 0, "validatedBy": validated_by}
            if attempt == 0:
                prompt += "\n\nYour previous pipeline was invalid. Fix it. Validator errors:\n" + json.dumps(errors, ensure_ascii=False)
        raise RuntimeError("Claude returned an invalid pipeline after retry: " + json.dumps(errors, ensure_ascii=False))
    except Exception as exc:
        print(f"AI bridge: {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
        return {"pipeline": fallback(prompt), "source": "example", "note": "Showing a saved example: AI was unavailable", "model": os.environ.get("AI_MODEL", "claude-haiku-5-5"), "ms": 0, "validatedBy": "example"}


class Handler(BaseHTTPRequestHandler):
    server_version = "GloriousAIBridge/1.0"

    def log_message(self, format, *args):
        return

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/health":
            return self.respond(404, {"error": "not found"})
        self.respond(200, {"ok": True, "model": os.environ.get("AI_MODEL", "claude-haiku-5-5"), "configDir": bool(os.environ.get("CLAUDE_CONFIG_DIR"))})

    def do_POST(self):
        if self.path != "/generate":
            return self.respond(404, {"error": "not found"})
        started = time.monotonic()
        if not BUSY.acquire(blocking=False):
            print(f"{datetime.now(timezone.utc).isoformat()} ms=0 source=busy valid=false", file=sys.stderr, flush=True)
            return self.respond(429, {"error": "busy"})
        source, valid = "error", False
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 4096:
                self.respond(413, {"error": "request body must be at most 4 KB"})
                return
            try:
                payload = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeDecodeError):
                self.respond(400, {"error": "invalid JSON"})
                return
            prompt = payload.get("prompt") if isinstance(payload, dict) else None
            if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 500:
                self.respond(400, {"error": "prompt must be a non-empty string of at most 500 characters"})
                return
            result = generate(prompt)
            result["ms"] = round((time.monotonic() - started) * 1000)
            source, valid = result["source"], True
            self.respond(200, result)
        finally:
            BUSY.release()
            elapsed = round((time.monotonic() - started) * 1000)
            print(f"{datetime.now(timezone.utc).isoformat()} ms={elapsed} source={source} valid={str(valid).lower()}", file=sys.stderr, flush=True)


def main():
    host = os.environ.get("AI_BIND", "127.0.0.1")  # Docker Desktop forwards host.docker.internal to host localhost; keeps the bridge off the LAN
    port = int(os.environ.get("AI_PORT", "18090"))
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()

import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import ai_bridge


PIPELINE = {
    "name": "Speeding alert",
    "nodes": [
        {"id": "filter", "type": "filter", "params": {"field": "speedKmh", "op": ">", "value": 60}},
        {"id": "alert", "type": "thresholdAlert", "params": {"field": "speedKmh", "op": ">", "value": 60, "message": "Truck over 60 km/h"}},
        {"id": "output", "type": "output", "params": {"name": "Speeding trucks"}},
    ],
    "edges": [{"from": "filter", "to": "alert"}, {"from": "alert", "to": "output"}],
}


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fake = self.root / "fake_cli.py"
        self.calls = self.root / "calls.json"
        self.fake.write_text(
            "import json, os, sys, time\n"
            "time.sleep(float(os.environ.get('FAKE_DELAY', '0')))\n"
            "p = os.environ['FAKE_CALLS']\n"
            "calls = json.load(open(p)) if os.path.exists(p) else []\n"
            "calls.append({'argv': sys.argv[1:], 'stdin': sys.stdin.read()})\n"
            "json.dump(calls, open(p, 'w'))\n"
            "outputs = json.loads(os.environ['FAKE_OUTPUTS'])\n"
            "result = outputs[min(len(calls)-1, len(outputs)-1)]\n"
            "print(json.dumps({'result': result}))\n",
            encoding="utf-8",
        )
        self.env = patch.dict(os.environ, {
            "AI_CMD": str(self.fake),
            "AI_MODEL": "test-model",
            "FAKE_CALLS": str(self.calls),
            "FAKE_OUTPUTS": json.dumps([json.dumps(PIPELINE)]),
        })
        self.env.start()
        self.addCleanup(self.env.stop)

    def set_outputs(self, outputs):
        os.environ["FAKE_OUTPUTS"] = json.dumps([json.dumps(item) if isinstance(item, dict) else item for item in outputs])

    def test_valid_cli_pipeline_reads_prompt_from_stdin_only(self):
        self.set_outputs([PIPELINE])
        with patch.object(ai_bridge, "validate_remote", return_value=(True, [], "control-api")):
            result = ai_bridge.generate("Alert on fast trucks secret phrase")
        self.assertEqual(result["source"], "ai")
        self.assertEqual(result["pipeline"], PIPELINE)
        calls = json.loads(self.calls.read_text(encoding="utf-8"))
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["stdin"], "Alert on fast trucks secret phrase")
        self.assertNotIn("Alert on fast trucks secret phrase", calls[0]["argv"])
        self.assertIn("--no-session-persistence", calls[0]["argv"])


    def test_repairs_fenced_prose_and_numeric_strings_before_validation(self):
        response = (
            "Here is the pipeline:\n```json\n"
            '{"name":"Speeding alert","nodes":['
            '{"id":"filter","type":"filter","params":{"field":"speedKmh","op":">","value":"60"}},'
            '{"id":"alert","type":"thresholdAlert","params":{"field":"speedKmh","op":">","value":"60","message":"Fast"}},'
            '{"id":"output","type":"output","params":{"name":"Alerts"}}],'
            '"edges":[{"from":"filter","to":"alert"},{"from":"alert","to":"output"}]}'
            "\n```\nThis is the result."
        )
        self.set_outputs([response])
        with patch.object(ai_bridge, "validate_remote", return_value=(True, [], "control-api")) as validate:
            result = ai_bridge.generate("Alert on speeding")
        self.assertEqual(result["source"], "ai")
        pipeline = validate.call_args.args[0]
        self.assertEqual(pipeline["nodes"][0]["params"]["value"], 60)
        self.assertIsInstance(pipeline["nodes"][0]["params"]["value"], int)

    def test_repairs_window_string_and_missing_edges(self):
        pipeline = {
            "name": "Average",
            "nodes": [
                {"id": "average", "type": "windowAverage", "params": {"field": "speedKmh", "windowSec": "30"}},
                {"id": "output", "type": "output", "params": {"name": "Average"}},
            ],
        }
        self.set_outputs([pipeline])
        with patch.object(ai_bridge, "validate_remote", return_value=(True, [], "control-api")) as validate:
            result = ai_bridge.generate("Average speed")
        self.assertEqual(result["source"], "ai")
        repaired = validate.call_args.args[0]
        self.assertEqual(repaired["nodes"][0]["params"]["windowSec"], 30)
        self.assertEqual(repaired["edges"], [{"from": "average", "to": "output"}])

    def test_impossible_request_uses_limited_pipeline_name(self):
        pipeline = {**PIPELINE, "name": "Closest supported: no weather data"}
        self.set_outputs([pipeline])
        with patch.object(ai_bridge, "validate_remote", return_value=(True, [], "control-api")):
            result = ai_bridge.generate("Predict tomorrow's weather at each truck location")
        self.assertEqual(result["source"], "ai")
        self.assertIn("weather", result["pipeline"]["name"])
        calls = json.loads(self.calls.read_text(encoding="utf-8"))
        system_prompt = calls[0]["argv"][calls[0]["argv"].index("--system-prompt") + 1]
        self.assertIn("choose the closest supported pipeline", system_prompt)
        self.assertIn("state the limitation briefly in its pipeline name", system_prompt)

    def test_empty_and_oversized_prompts_return_clear_400(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), ai_bridge.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        for prompt, message in (("", "prompt must be a non-empty string"), ("x" * 501, "prompt must be at most 500 characters")):
            request = Request(
                f"http://127.0.0.1:{server.server_port}/generate",
                data=json.dumps({"prompt": prompt}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with self.assertRaises(HTTPError) as caught:
                urlopen(request, timeout=2)
            self.assertEqual(caught.exception.code, 400)
            self.assertEqual(json.loads(caught.exception.read())["error"], message)
    def test_invalid_response_retries_then_returns_saved_example(self):
        self.set_outputs([{"name": "bad", "nodes": [], "edges": []}])
        with patch.object(ai_bridge, "validate_remote", return_value=(False, ["nodes required"], "control-api")):
            result = ai_bridge.generate("Alert any truck faster than 60 km/h")
        self.assertEqual(result["source"], "example")
        self.assertEqual(result["note"], "Showing a saved example: AI was unavailable")
        calls = json.loads(self.calls.read_text(encoding="utf-8"))
        self.assertEqual(len(calls), 2)
        self.assertIn("nodes required", calls[1]["stdin"])

    def test_timeout_returns_saved_example(self):
        os.environ["FAKE_DELAY"] = "2"
        with patch.object(ai_bridge, "CLI_TIMEOUT_SECONDS", 0.1):
            result = ai_bridge.generate("Average speed per truck")
        self.assertEqual(result["source"], "example")
        self.assertEqual(result["note"], "Showing a saved example: AI was unavailable")

    def test_busy_request_returns_429(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), ai_bridge.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.assertTrue(ai_bridge.BUSY.acquire(blocking=False))
        self.addCleanup(ai_bridge.BUSY.release)
        request = Request(f"http://127.0.0.1:{server.server_port}/generate", data=b'{"prompt":"hello"}', headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(HTTPError) as caught:
            urlopen(request, timeout=2)
        self.assertEqual(caught.exception.code, 429)
        self.assertEqual(json.loads(caught.exception.read()), {"error": "busy"})


if __name__ == "__main__":
    unittest.main()

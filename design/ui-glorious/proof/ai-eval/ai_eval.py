import json, statistics, sys, time, urllib.request

URL = "http://127.0.0.1:18093/generate"
PROMPTS = [
    "Alert me when any truck goes over 60 km/h",
    "Average speed per truck over the last 30 seconds",
    "Show only trucks on partition 2 with their speed",
    "which trucks are slow, under 10 km/h?",
    "list stopped trucks",
    "average speed per route every minute",
    "show truck-003 only",
    "warn me if a truck is heading north fast",
    "how many deliveries did each truck make",
    "what is the weather in Mysuru",
    "fuel level alert below 20 percent",
    "show me something interesting",
    "trucks jo 50 se tez chal rahe hai unka alert do",
    "avrage sped of al truks evry 15 sec",
    "For each partition, compute the average speed over 20 second windows and raise an alert when that average goes above 45 km/h, then output it",
]
rows = []
for p in PROMPTS:
    t0 = time.time()
    try:
        req = urllib.request.Request(URL, data=json.dumps({"prompt": p}).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=95) as r:
            d = json.loads(r.read().decode())
        pl = d.get("pipeline") or {}
        types = [n.get("type") for n in pl.get("nodes", [])]
        rows.append({"prompt": p, "source": d.get("source"), "validatedBy": d.get("validatedBy"), "ms": d.get("ms") or int((time.time() - t0) * 1000), "name": pl.get("name"), "types": types})
    except Exception as e:
        rows.append({"prompt": p, "source": "ERROR", "error": str(e)[:120], "ms": int((time.time() - t0) * 1000)})
json.dump(rows, open(sys.argv[1], "w", encoding="utf-8"), indent=1)
ai = [r for r in rows if r.get("source") == "ai"]
for r in rows:
    print("%-5s %5dms %-45s | %s | %s" % (r.get("source"), r["ms"], r["prompt"][:45], (r.get("name") or r.get("error") or "")[:55], ",".join(r.get("types") or [])))
print("SUMMARY: %d/%d source=ai (validated); median %d ms, max %d ms" % (len(ai), len(rows), statistics.median([r["ms"] for r in ai]) if ai else 0, max([r["ms"] for r in ai]) if ai else 0))

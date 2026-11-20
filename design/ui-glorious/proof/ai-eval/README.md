# Ask AI evaluation (06:15, vivek-pc)

Real headless Claude Code through `scripts/ai-bridge/ai_bridge.py` (vachana-claude login, model claude-haiku-5-5), 15 visitor-style prompts, one at a time. Validation used the bridge's local validator (the live API still runs main, without the new validate endpoint).

**Result: 14/15 returned a valid AI-generated pipeline; median 5.8 s, max 9.5 s.**

- Impossible requests were handled honestly: "fuel level alert" -> pipeline named "Fuel alert unsupported: no fuel field"; "deliveries per truck" -> "UNLOADING events per truck (no count)"; "weather in Mysuru" -> the labelled saved-example fallback ("Showing a saved example: AI was unavailable").
- Hindi-English mix ("trucks jo 50 se tez chal rahe hai unka alert do") and a typo-heavy prompt both produced correct pipelines.

Per-prompt results: `results.json`. Re-run: start the bridge on port 18093 with the vachana config, then `python -I ai_eval.py results.json`.

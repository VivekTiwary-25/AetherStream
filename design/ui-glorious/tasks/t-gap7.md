# Gap #7 check: is the Truck Speeds '1-min avg' really a 60-second window? (static, light)

Files: design/ui-glorious/GAP7.md (new; the only file you write). Do NOT read or describe swarm/. This runs on the boss PC: NO npm install, NO builds, NO docker, NO servers, NO tests.

Read the demo pipeline definition the live demo runs (control-api demo pipeline / examples endpoint sources, compiler window config for speedSink, e.g. PipelineController/ExecutionService/demo JSON under control-api/src/main/resources or compiler) and web/src/glorious/analytics/data.ts + AnalyticsPanel.tsx (how the label is derived from windowStart/windowEnd). Answer in GAP7.md (max 25 lines): the real speedSink window length (with file:line), whether the UI label is derived from real windowEnd-windowStart (file:line), and whether 'readings' is the real eventCount. Verdict: label honest yes/no; if no, the smallest fix (do not apply it).

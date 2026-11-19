# T170: expo-proofs.sh - show the examiner the internals live, on demand  [expo safety, technical depth]

Lanes 7/9. When the examiner asks "show me", the presenter runs infra/scripts/expo-proofs.sh <topic> against the running
stack and gets short, readable output (with a one-line explanation per block):
- checkpoints: list the latest checkpoint manifests in MinIO (bucket/prefix from contract 03 / docker-compose.yml; use the mc
  client inside the minio container or the API) and pretty-print one manifest (checkpoint id, generation, per-partition offsets,
  per-worker state objects).
- offsets: Kafka topic partitions with log-end offsets vs the offsets in the last completed checkpoint (kafka-topics /
  kafka-consumer-groups or kafka-get-offsets inside the kafka container).
- fencing: the generation history of the current execution (API or Postgres executions/audit rows) and the most recent
  "stale generation rejected" log lines from coordinator/worker logs (docker compose logs --since 30m | grep).
- recovery: last recovery from the API/audit: worker lost, checkpoint restored, durationMs.
- all: everything above.
Read-only: it must never change state. Add a CI step at the end of the e2e job in .github/workflows/ci-e2e.yml that runs
`expo-proofs.sh all` after the kill-worker step and checks each block printed something. Add a "Show me" section to
docs/expo/CHEATSHEET.md.

Files (edit only these): infra/scripts/expo-proofs.sh (new), .github/workflows/ci-e2e.yml (one appended step),
docs/expo/CHEATSHEET.md (one section).

Acceptance check: ci-e2e green on your branch with all five blocks visible in the log.

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.

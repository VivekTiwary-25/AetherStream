#!/usr/bin/env bash
# Read-only evidence from the running Compose stack; never trigger a checkpoint or chaos.
set -Eeuo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash: keep container paths like /opt/kafka/... unrewritten.

fail() { printf 'PROOF ERROR: %s\n' "$*" >&2; exit 1; }
jq() { command jq --binary "$@"; } # Keep native jq.exe output usable in Git Bash.
block() { printf '\n[%s] %s\n' "$1" "$2"; }
complete() { printf 'PROOF %s OK\n' "$1"; }

TOPIC="${1:-all}"
[[ $# -le 1 ]] || fail 'Usage: bash infra/scripts/expo-proofs.sh [checkpoints|offsets|fencing|recovery|all]'
case "$TOPIC" in checkpoints|offsets|fencing|recovery|all) ;; *) fail "Unknown topic: $TOPIC" ;; esac
for tool in docker curl jq; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
trap 'fail "Command failed at line $LINENO"' ERR
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
API="${API:-http://localhost:18080}"
EXECUTION="$(curl -fsS --connect-timeout 3 --max-time 10 "${API%/}/api/executions/current")"
EXECUTION_ID="$(jq -er '.id | select(type == "string" and length > 0)' <<< "$EXECUTION")" \
  || fail 'No current execution; run the demo first'

# psql variables quote execution IDs as SQL literals; every session rejects writes.
query() {
  docker compose exec -T -e 'PGOPTIONS=-c default_transaction_read_only=on' postgres \
    psql -X -qAt -U aether -d aether -v ON_ERROR_STOP=1 -v execution_id="$EXECUTION_ID"
}

load_checkpoint() {
  CHECKPOINT="$(query <<'SQL'
SELECT json_build_object('checkpointId', c.checkpoint_id, 'generation', c.generation,
    'manifestKey', c.manifest_key, 'sourceOffsets', c.source_offsets, 'topic', e.plan->>'topic')
FROM checkpoints c JOIN executions e ON e.id = c.execution_id
WHERE c.execution_id = :'execution_id' AND c.state = 'COMPLETED'
ORDER BY c.checkpoint_id DESC LIMIT 1;
SQL
)"
  jq -e '.checkpointId >= 0 and (.sourceOffsets | type == "object" and length > 0)
    and (.topic | type == "string" and length > 0)' <<< "$CHECKPOINT" >/dev/null \
    || fail 'No completed checkpoint with source offsets exists for the current execution'
}

checkpoints() {
  local config bucket host prefix listing manifest key
  config="$(docker compose config --format json)"
  bucket="$(jq -er '.services.coordinator.environment.S3_BUCKET' <<< "$config")"
  # Environment-only mc alias: no alias set, bucket creation, or object writes.
  host="$(jq -er '.services.minio.environment |
    "http://\(.MINIO_ROOT_USER | @uri):\(.MINIO_ROOT_PASSWORD | @uri)@127.0.0.1:9000"' <<< "$config")"
  prefix="executions/$EXECUTION_ID/checkpoints/"
  block checkpoints "Latest MinIO manifests in $bucket/$prefix (up to five, newest first)."
  listing="$(docker compose exec -T -e "MC_HOST_proof=$host" minio \
    mc --json ls --recursive "proof/$bucket/$prefix")"
  listing="$(jq -se '
    if any(.[]; .status == "error") then error("MinIO listing failed") else
      [.[] | select(.type == "file" and (.key | endswith("/manifest.json")))] |
      sort_by(.lastModified) | reverse | .[:5] | select(length > 0) |
      map({key, lastModified, size}) end' <<< "$listing")" \
    || fail 'No checkpoint manifests found in MinIO'
  jq . <<< "$listing"
  complete checkpoints

  block manifest 'The latest COMPLETED checkpoint couples next-read offsets with per-worker state object keys.'
  key="$(jq -er '.manifestKey | select(type == "string" and length > 0)' <<< "$CHECKPOINT")" \
    || fail 'Latest completed checkpoint has no manifest (checkpoint 0 may contain only initial offsets)'
  [[ "$key" == "$prefix"*'/manifest.json' ]] || fail 'Manifest key is outside the current execution checkpoint prefix'
  manifest="$(docker compose exec -T -e "MC_HOST_proof=$host" minio mc cat "proof/$bucket/$key")"
  jq -e --arg id "$EXECUTION_ID" --argjson cp "$CHECKPOINT" '
    .executionId == $id and .checkpointId == $cp.checkpointId and .generation == $cp.generation
    and (.partitions | type == "object" and length > 0)
    and all(.partitions | to_entries[];
      .value.nextOffset == $cp.sourceOffsets[.key] and (.value.objectKey | type == "string" and length > 0))
    and (.partitions | keys) == ($cp.sourceOffsets | keys)
  ' <<< "$manifest" >/dev/null || fail 'Manifest does not match the completed checkpoint'
  jq '{executionId, checkpointId, generation, partitions,
    workerStateObjects: ([.partitions[].objectKey] | unique)}' <<< "$manifest"
  complete manifest
}

offsets() {
  local topic ends rows
  topic="$(jq -r '.topic' <<< "$CHECKPOINT")"
  block offsets "Kafka $topic log-end offsets versus checkpoint $(jq -r '.checkpointId' <<< "$CHECKPOINT") next-read offsets; gap is replay distance, not consumer-group lag."
  ends="$(docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server kafka:9092 --topic "$topic" --time -1)"
  rows="$(jq -Rse --arg topic "$topic" --argjson cp "$CHECKPOINT" '
    [split("\n")[] | rtrimstr("\r") | split(":") |
      select(length == 3 and .[0] == $topic) |
      {partition: .[1], logEnd: (.[2] | tonumber)}] | sort_by(.partition | tonumber) |
    if length == 0 or (map(.partition) | sort) != ($cp.sourceOffsets | keys | sort)
    then error("Kafka/checkpoint partition sets differ") else
      map(. + {checkpointNext: $cp.sourceOffsets[.partition]} |
        . + {replayDistance: (.logEnd - .checkpointNext)}) |
      if any(.[]; .replayDistance < 0) then error("Kafka log end precedes checkpoint") else . end
    end' <<< "$ends")" || fail 'Could not compare every Kafka partition with the completed checkpoint'
  printf 'partition\tlogEnd\tcheckpointNext\treplayDistance\n'
  jq -r '.[] | [.partition, .logEnd, .checkpointNext, .replayDistance] | @tsv' <<< "$rows"
  complete offsets
}

fencing() {
  local history logs matches status
  block fencing 'Current execution generation history fences old assignments; recent rejection logs are observations, not injected failures.'
  history="$(query <<'SQL'
SELECT json_build_object('executionId', e.id, 'currentGeneration', e.generation,
    'checkpointGenerations', (SELECT json_agg(g ORDER BY g.generation) FROM
      (SELECT generation, min(checkpoint_id) AS "firstCheckpoint", max(checkpoint_id) AS "lastCheckpoint"
       FROM checkpoints WHERE execution_id = e.id GROUP BY generation) g),
    'auditHistory', COALESCE((SELECT json_agg(a ORDER BY a.id) FROM
      (SELECT id, at, action, detail FROM audit_log
       WHERE detail->>'executionId' = e.id AND action IN
         ('execution.started', 'execution.recovering', 'execution.recovered', 'execution.rebalanced')
       ORDER BY id DESC LIMIT 20) a), '[]'::json))
FROM executions e WHERE e.id = :'execution_id';
SQL
)"
  jq -e '.currentGeneration >= 1 and (.checkpointGenerations | length > 0)' <<< "$history" >/dev/null \
    || fail 'No generation history for the current execution'
  jq . <<< "$history"
  logs="$(docker compose logs --no-color --since 30m coordinator worker-1 worker-2 worker-3)"
  # A hard-killed worker need never attempt a stale write. Absence is not proof of rejection.
  if matches="$(grep -Ei 'stale.*(generation|assign|reject)|generation.*(stale|reject)|fenced|fencing' <<< "$logs")"; then
    printf '%s\n' "$matches" | tail -n 10
  else
    status=$?
    [[ "$status" == 1 ]] || fail 'Could not filter fencing logs'
    printf 'No stale-generation rejection observed in coordinator/worker logs during the last 30m.\n'
  fi
  complete fencing
}

recovery() {
  local evidence
  block recovery 'Latest completed worker-loss recovery in the stack audit, correlated by execution and audit order (may precede the current execution).'
  evidence="$(query <<'SQL'
SELECT json_build_object('executionId', r.detail->>'executionId',
    'isCurrentExecution', r.detail->>'executionId' = :'execution_id',
    'workerLost', d.detail->>'workerId', 'workerLostAt', d.at,
    'checkpointRestored', r.detail->'checkpointId', 'generation', r.detail->'generation',
    'durationMs', COALESCE(r.detail->'durationMs', r.detail->'recoveryDurationMs'), 'recoveredAt', r.at)
FROM audit_log r
JOIN LATERAL (
    SELECT x.id, x.at, x.detail FROM audit_log x
    WHERE x.action = 'execution.recovering' AND x.id < r.id
      AND x.detail->>'executionId' = r.detail->>'executionId'
      AND x.detail->>'generation' = r.detail->>'generation'
    ORDER BY x.id DESC LIMIT 1
) b ON true
JOIN LATERAL (
    SELECT x.at, x.detail FROM audit_log x
    WHERE x.action = 'worker.dead' AND x.id < b.id
      AND x.detail->>'executionId' = r.detail->>'executionId'
      AND NOT EXISTS (SELECT 1 FROM audit_log prior
        WHERE prior.action IN ('execution.recovered', 'execution.rebalanced')
          AND prior.detail->>'executionId' = r.detail->>'executionId'
          AND prior.id > x.id AND prior.id < b.id)
    ORDER BY x.id DESC LIMIT 1
) d ON true
WHERE r.action = 'execution.recovered'
ORDER BY r.id DESC LIMIT 1;
SQL
)"
  jq -e '.workerLost != null and .checkpointRestored != null
    and (.durationMs | type == "number" and . >= 0)' <<< "$evidence" >/dev/null \
    || fail 'No completed worker-loss recovery with checkpoint and duration is recorded; run the kill-worker demo first'
  jq . <<< "$evidence"
  complete recovery
}

case "$TOPIC" in checkpoints|offsets|all) load_checkpoint ;; esac
case "$TOPIC" in
  checkpoints) checkpoints ;;
  offsets) offsets ;;
  fencing) fencing ;;
  recovery) recovery ;;
  all) checkpoints; offsets; fencing; recovery ;;
esac

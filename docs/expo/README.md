# Expo day index

1. **The night before** — [Offline demo prep and image cache](../EXPO_OFFLINE.md); [prepull script](../../infra/scripts/expo-prepull.sh); [freeze CD deployments](../../infra/scripts/demo-freeze.sh).
2. **30 minutes before** — Run [expo-check.sh](../../infra/scripts/expo-check.sh) and [demo-ready.sh](../../infra/scripts/demo-ready.sh); keep the [CHEATSHEET.md](CHEATSHEET.md) open.
3. **The demo** — Follow [DEMO.md](../../DEMO.md), with [speaker notes](../report/speaker-notes.md) and the [presentation outline](../report/presentation-outline.md).
4. **If something breaks** — Use the [runbook](../runbook.md), [expo-reset.sh](../../infra/scripts/expo-reset.sh), or [expo-proofs.sh](../../infra/scripts/expo-proofs.sh); play the [backup recording](backup/demo-backup.webm) from [docs/expo/backup/](backup/README.md) if needed.
5. **Evidence for the examiner** — [Testing catalogue](../testing.md), [CI/CD evidence](../report/ci-cd-evidence.md), [kill-repeat results](kill-repeat.md), [long-run results](long-run.md), [rehearsal 1](rehearsal-1.md), and [R08 risk review](../reviews/R08-expo-risk-review.md).

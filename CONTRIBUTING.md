# Contributing

## Lanes and folder ownership

Each lane owns its folder; keep changes within that boundary and coordinate shared interfaces through the contracts lane.

| Lane | Owned folders |
|---|---|
| Contracts | `common/`, `docs/contracts/` |
| 1 | `engine/` |
| 2 | `compiler/`, `reference/` |
| 3 | `control-api/` |
| 4 | `simulator/` |
| 5 | `web/src/city/` |
| 6 | `web/src/app/` |
| 7 | `infra/` |
| 8 | `tests/` |
| 9 | `docs/`, `.github/` |

## Change process

1. Read every file in `docs/contracts/` before changing code; contracts are the source of truth. Follow them exactly and do not change them as part of an implementation task.
2. Create a branch named `task/<id>` (for example, `task/123-description`).
3. Run the relevant tests locally. Every change must go through CI on its task branch; merge only when CI is green.
4. Link the task, test commands, and CI runs in the pull request.

## Local CI test commands

These commands match `.github/workflows/ci-java.yml`, `ci-web.yml`, `ci-integration.yml`, and `ci-mock.yml`.

Java build and unit tests (`.github/workflows/ci-java.yml`):

```sh
./gradlew build --console=plain
```

Web checks (`.github/workflows/ci-web.yml`; run from `web/`):

```sh
npm ci
npm run lint
set -o pipefail; npm run test -- --coverage | tee "$RUNNER_TEMP/web-coverage-output.txt"
npm run build
npm run check:no-mock
VITE_USE_MOCK=1 npm run build -- --outDir dist-mock
if npm run check:no-mock -- dist-mock; then
  echo "Mock-enabled build unexpectedly passed the production check"
  exit 1
fi
echo "Mock-enabled build correctly rejected"
```

Integration tests (`.github/workflows/ci-integration.yml`):

```sh
./gradlew :tests:integrationTest --console=plain
```

Mock server smoke test (`.github/workflows/ci-mock.yml`):

```sh
cd web/mock && npm install && node smoke.mjs
```

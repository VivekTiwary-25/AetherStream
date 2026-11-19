# utkarsh-pc Git healthcheck (T183)

## Scope and result

- Host: `UTKARSHLOQ4050` (utkarsh-pc).
- Checked on 2026-11-19, from `10:36:10.3814315+05:30` through `10:37:14.9715808+05:30`.
- Checkout: `C:/Users/UTKARSH KESHRI/.swarm/work/t261119-1034-438f`.
- Branch: `task/t261119-1034-438f`; observed HEAD: `a0f905a`.
- Worktree Git directory: `C:/Users/UTKARSH KESHRI/.swarm/repo/.git/worktrees/t261119-1034-438f`.
- Shared repository Git directory: `C:/Users/UTKARSH KESHRI/.swarm/repo/.git`.
- No running `git*` processes or stale lock files were found. No lock files were removed.
- Worktree prune, worktree list, fsck, status, fetch, and dry-run push all exited **0**.
- `fsck` reported 30 dangling commits and one dangling tree, but no corrupt/missing-object errors. These objects were left untouched.
- **Disk-space risk:** C: had only **1.157 GB** free. No disk cleanup was authorized or performed.

## Reported push failure and limits

The task reports that a previous push of `task/t261119-1027-80ba` failed and that the branch did not reach GitHub. The available error fragment is `push failed: remote: Create a pull request for 'task/t261119-1027-80ba' ...`; the actual error is truncated. This report does not reinterpret that failure as success.

The requested dry-run below succeeded from the assigned checkout. It tested the current HEAD against `healthcheck/utkarsh-dry-run`, not the previously failing branch. A dry-run does not create a branch or exercise every real-push server-side hook/policy; the earlier failure's cause remains undetermined. No real push was attempted.

## Exactly what changed

1. Recursively inspected the shared `.git` directory, including worktree administrative directories, for `*.lock` files after `Get-Process -Name "git*"` found no Git processes. Found **0**; removed-file list: **none**.
2. Ran `git worktree prune -v`. It exited 0 with no output; no stale worktree entries were reported removed.
3. Ran `git fetch` exactly once. It updated the remote-tracking refs listed below and may have downloaded objects/updated normal fetch metadata. No branch checkout, credentials, remotes, or Git configuration were changed.
4. Added this findings file and the explicitly requested task-completion report at `swarm/reports/t261119-1034-438f.md`. No application source, contracts, schema, or other repository files were edited. Nothing else under `swarm/` was read or changed.

No destructive repairs, garbage collection, commits, actual pushes, branch switches, or disk cleanup were performed. `SWARM_TASK.md` was already untracked in the recorded status and was left untouched.

## Worktrees

```text
> git worktree prune -v
Exit code: 0

> git worktree list
C:/Users/UTKARSH KESHRI/.swarm/repo                   a0f905a [main]
C:/Users/UTKARSH KESHRI/.swarm/work/t261119-1034-438f a0f905a [task/t261119-1034-438f]
Exit code: 0
```

## Object integrity: full output

```text
> git fsck --no-progress
dangling commit 7e837883e6ac328ba49e108db4262ef495846b06
dangling commit c505a1eea64e8e857620a36d160a5c6709b9e989
dangling commit 4b107cc5228ccda764bf612b6d53ccf552f1a8b3
dangling commit 8b966b7d0b898fde7097644e2b60c81ea9614e56
dangling commit e496e5bec08295eeb0a3a9e5514f60045d83d775
dangling commit 889c3e86fcfd0f5e4ebf4caf2df6c61cc1c9c5f6
dangling commit f31d747a30a2e0454c850b333fb0f40052f7256c
dangling commit 4e1f2cca0b4f129e2f0d2df000d57c9c53b9b5d3
dangling commit 12a9e024b1a005a07d186690efaa2608dd148558
dangling commit aaac0dd681b8624356111fef3bfb9e9a1eedb1e8
dangling commit 46b1bfa399ae7ad672107b48c7bc91e44949755a
dangling tree 8234a422d458d6ae5fdf3990a5d3cf4d180a0a05
dangling commit b53bc4048b553411faa4a8a487913430a0586530
dangling commit dfc5acc342f8ad7b227f986bc384c7fe149aa577
dangling commit f0455b44e54940f193b7184c143407915dde2f1f
dangling commit 83c9645c26691910fd2d00d820512aa64c9c9d74
dangling commit 30cfae568bc39abe57846b2022b3bef37afb0cfd
dangling commit fb505f40ffa0c7d5c2256810e6eac1e6c2f06c2c
dangling commit 8752eebc4c19fd64aa7bcd7bb13111811a5034fb
dangling commit 93d39384e2f5903a1256213d2ef0c808cead87db
dangling commit 52d5ce4bf18c92f3828dba5b15edd7e94547f99d
dangling commit 6ed978c464ca424a272e367c1170f3b570ef2943
dangling commit e95b00d219fc0dc021c5dcf1b65eda568b6eaadf
dangling commit 4569a2e9ec4877b02d757177ce181c221fd7c85c
dangling commit 2e6bfa5a8993c24c7365004c3745a48feee9c5bd
dangling commit f36bb34e260d9741533899af2ffdcf7d044e7fee
dangling commit 6d6f535b54877702472236c304ae19368ed744d7
dangling commit 8defcedc2c4fc667587518acf4081b216b79398d
dangling commit 7375a446da338202a15793a096825f7cc228e828
dangling commit 07fe1c093230262b57fc07018b2f69fa21e462a3
dangling commit 47fe1acd6327165db1e8e23fd5cc6c701efa7e51
Exit code: 0
```

## Disk space: every FileSystem drive

Collected with `Get-PSDrive -PSProvider FileSystem`. GB means decimal gigabytes (1,000,000,000 bytes); values are rounded to three decimals.

| Drive | Root | Free bytes | Free GB | Used GB |
|---|---|---:|---:|---:|
| C | `C:\` | 1,156,984,832 | 1.157 | 195.663 |
| D | `D:\` | 243,753,480,192 | 243.753 | 60.333 |

## Git metadata and size

No token was present in the displayed remote URL. URL credentials/tokens were filtered from diagnostic output before reporting.

```text
> git remote -v
origin  https://github.com/VivekTiwary-25/AetherStream.git (fetch)
origin  https://github.com/VivekTiwary-25/AetherStream.git (push)
Exit code: 0

> git config --get credential.helper
manager
Exit code: 0

> git --version
git version 2.53.0.windows.2
Exit code: 0
```

The checkout's `.git` is a **77-byte pointer file**. The actual shared repository `.git` directory was measured recursively using `Get-ChildItem -Recurse -Force -File | Measure-Object -Property Length -Sum` before fetch:

| Measurement | Value |
|---|---:|
| Files | 6,196 |
| Sum of file lengths, bytes | 166,309,702 |
| MiB (1,048,576 bytes each) | 158.605 |
| Decimal GB | 0.166310 |

This is logical file size, not filesystem allocation size, and includes shared worktree administrative data.

## Status and fetch: full output

Status was collected before adding either report and before fetch.

```text
> git status -sb
## task/t261119-1034-438f...origin/main
?? SWARM_TASK.md
Exit code: 0

> git fetch
From https://github.com/VivekTiwary-25/AetherStream
 + 967e62a...2f0cdd2 hb/vimlesh-pc       -> origin/hb/vimlesh-pc  (forced update)
 + 6468b0c...26997c8 hb/vivek-pc         -> origin/hb/vivek-pc  (forced update)
 * [new branch]      salvage/rehearsal-2 -> origin/salvage/rehearsal-2
 * [new branch]      salvage/t120        -> origin/salvage/t120
 * [new branch]      salvage/t157        -> origin/salvage/t157
Exit code: 0
```

## Dry-run push: full combined stdout/stderr

Run exactly once from the assigned AetherStream checkout, without changing credentials or remotes:

```text
> git push --dry-run origin HEAD:refs/heads/healthcheck/utkarsh-dry-run 2>&1
To https://github.com/VivekTiwary-25/AetherStream.git
 * [new branch]      HEAD -> healthcheck/utkarsh-dry-run
Exit code: 0
```

## Remaining concerns

- C: free space is low; no files outside the authorized report paths were changed to reclaim space.
- The prior real-push failure remains unexplained without its complete error output; today's successful dry-run is not proof that a real push would succeed.
- Dangling objects remain intact; no repair was necessary based on this `fsck` result.

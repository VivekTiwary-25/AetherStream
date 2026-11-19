# T183: Make this PC's git checkout healthy (utkarsh-pc only, git housekeeping)

Scope - ONLY git housekeeping in the AetherStream checkout on this PC (utkarsh-pc):
1. Check that no git process is running (Get-Process git / tasklist). Only if none is running: find stale *.lock files under the
   repository's .git directory (e.g. .git/index.lock, .git/HEAD.lock, .git/refs/**/*.lock, .git/worktrees/*/*.lock) and remove them.
   List each removed file.
2. Run `git worktree prune -v` and `git worktree list`; report the output.
3. Run `git fsck --no-progress` and report any errors (do not run destructive repairs; if objects are corrupt, report them).
4. Report free disk space in GB for every drive (PowerShell: Get-PSDrive -PSProvider FileSystem).
5. Run `git status -sb` and `git fetch` once to confirm it works; report the output.
Do NOT read, explain or change anything in swarm/. Do not touch any other repository or files. Do not change git config.
Write your findings in docs/expo/healthcheck-utkarsh-git.md (new): what was wrong, exactly what you changed, disk space table.

Files (edit only these): docs/expo/healthcheck-utkarsh-git.md (new). (Lock-file removal and prune are local git housekeeping, not repo edits.)

Acceptance check: the report file exists on your branch with the findings and the disk-space table.

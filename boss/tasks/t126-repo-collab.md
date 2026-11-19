# T126: Collaboration hygiene for the CDD syllabus (templates, CODEOWNERS, CONTRIBUTING)  [docs/CI]

Lane 7/9. Add: .github/pull_request_template.md (summary, linked task, tests run, CI links, checklist incl. contracts followed),
.github/ISSUE_TEMPLATE/bug_report.yml and feature_request.yml (GitHub issue forms), .github/CODEOWNERS mapping each lane folder
to the GitHub user VivekTiwary-25 (only that user; no other names or emails), CONTRIBUTING.md (lanes and folder ownership,
contracts-first rule, branch naming task/<id>, every change goes through CI on its branch, merge only when green, how to run
tests locally: copy exact commands from .github/workflows/), and .github/dependabot.yml (weekly, github-actions + gradle + npm
for web/, open-pull-requests-limit 3). Do not change existing workflows.

Files (edit only these): .github/pull_request_template.md, .github/ISSUE_TEMPLATE/**, .github/CODEOWNERS, CONTRIBUTING.md,
.github/dependabot.yml (all new).

Acceptance check: ci-mock green on your branch; YAML files parse (say how you checked).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.

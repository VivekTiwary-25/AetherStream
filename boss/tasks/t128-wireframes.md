# T128: Low-fidelity wireframes + flow diagram (UX syllabus: prototyping)  [docs/ux]

Lane 9. Create hand-made SVG wireframes (grey boxes + labels, no screenshots) in docs/ux/wireframes/: 01-city-dashboard.svg (city
+ bento panels + header + footer), 02-pipeline-editor.svg (palette, canvas, validation list, Run), 03-chaos-and-recovery.svg
(chaos panel, kill confirm, recovering banner, timeline), 04-compare.svg, 05-ops-savepoint-restore.svg, 06-mobile-390.svg. Each
viewBox 1440x900 (mobile 390x844), readable in light and dark GitHub themes (use a white background rect). Add
docs/ux/wireframes/README.md: one paragraph per wireframe (what problem it explores, what changed in the final UI - compare with
docs/ux/screenshots/) and a mermaid user-flow diagram for the presenter's demo path. Base layout on the real components in
web/src/app (cite files).

Files (edit only these): docs/ux/wireframes/** (new).

Acceptance check: ci-mock green on your branch; every SVG is valid XML (say how you checked).

## Rules (apply to every task)
- Read every file in docs/contracts/ first and follow them exactly. Do not edit docs/contracts/ or infra/db/schema.sql (if a contract looks wrong, say so in your report).
- Do not read or change anything in swarm/ (except your own report file if the swarm asks you to write one).
- Only edit the files listed. If you are on vivek-pc: do NOT start Docker containers or kind there (the live demo runs on it). Acceptance runs in GitHub Actions on your task branch.

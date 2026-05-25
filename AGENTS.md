# Agent Map

Project: mc-battlepass

Primary agent: Codex
Enabled agents: Codex

Codex reads this file first. Keep it short. It is the map, not the manual.

## Default Behavior

- Read [docs/index.md](docs/index.md) before large changes.
- Default to caveman style for chat: terse, exact, no filler. Use normal prose only for safety, irreversible actions, or user confusion.
- Stay inside harness engineering: repo-local context, small maps, execution plans, checks, and doc updates.
- Keep feature code decoupled by package/object. Prefer focused files around 100-300 lines; split larger work by feature boundary, as with role perk files.
- For new product context, use `/init-sonata` and update [docs/project-brief.md](docs/project-brief.md).
- For existing project cleanup or migration, use `/retrofit-sonata` before feature work.
- For multi-step work, create or update an execution plan in [docs/exec-plans/active](docs/exec-plans/active).
- Run checks from [docs/quality.md](docs/quality.md) before final handoff.
- If an agent struggles twice on the same class of issue, add a doc, script, test, fixture, or rule.

## Knowledge Map

- [docs/project-brief.md](docs/project-brief.md): product intent and constraints.
- [docs/architecture/index.md](docs/architecture/index.md): structure and boundaries.
- [docs/quality.md](docs/quality.md): validation commands.
- [docs/exec-plans/README.md](docs/exec-plans/README.md): planning workflow.
- [docs/references/harness-engineering.md](docs/references/harness-engineering.md): harness principles.
- [docs/references/caveman.md](docs/references/caveman.md): compression rules.

## Current Project Facts

- Kind: existing project
- Stack: Gradle Kotlin DSL, Kotlin/JVM, Java 21, NeoForge Minecraft mod
- Package manager: Gradle wrapper
- Default caveman mode: full
- Agent targets: Codex
- Main gameplay notification path: reusable snackbar system in `dev.gisketch.chowkingdom.snackbar`; prefer snackbar over chat/actionbar for new player-facing feature events.
- Runtime test instance: `C:\Users\Arnel Glenn Jimenez\AppData\Roaming\gisketch\modsync\data\launchers\prismlauncher-cracked\11.0.2-1\instances\modsync-ckdm-2026\.minecraft`
- Config work targets the Prism instance above, especially `.minecraft\config\gisketchs_chowkingdom_mod`; do not use repo `runs/` as source of truth.

## Work Loop

1. Clarify goal and acceptance criteria.
2. Read only relevant docs.
3. Plan at the smallest useful level.
4. Implement inside documented boundaries.
5. Validate with current checks.
6. Update docs when behavior, decisions, or constraints change.

<!-- sonata:block=integrations:start -->
## Sonata Integrations

- If running in Pi Agent, use project extensions `rtk` and `cymbal` for repo-local context, searches, reads, and shell/output compression.
- Project skills live in `.pi/skills/`; prompt templates live in `.pi/prompts/`.
- Prefer `rtk`/`cymbal` over raw/native tools when they provide the needed context cheaper or clearer.
- Use raw/native tools for exact logs, unsupported cases, or when `rtk`/`cymbal` are unavailable; mention the fallback in handoff.
<!-- sonata:block=integrations:end -->

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, invoke the `skill` tool with `skill: "graphify"` before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- NEVER RUN graphify update . !! it takes a lot of time, just say to the user, he can run graphify update if needed


# Copy-paste phase assignment

Replace the phase and task placeholders before giving this to an agent.

```text
Work on Eroflix Mobile in /home/mitravanu/Projects/eroflix/mobile.
Git root: /home/mitravanu/Projects/eroflix.
Target: Android 17 / API 37 on Google Pixel 6 (oriole), arm64 release APK.

Assignment: Pxx — <phase name>, task IDs <Pxx.01 ...>.
Your ownership scope: <paths / interfaces>.
Integration owner / shared-file coordination: <name or single-agent>.

Read mobile/AGENTS.md, docs/STATUS.md, docs/ROADMAP.md,
docs/ARCHITECTURE.md, docs/ANDROID17_PIXEL6.md, the applicable contracts,
and the assigned docs/phases/Pxx-*.md before coding.
Inspect git status and preserve existing work.

Check predecessor artifacts and their handoffs. If a required input is missing,
state the exact dependency and continue only independent work in your scope.
Implement the assigned deliverables and meaningful acceptance checks.
Use the canonical repository/model contracts; do not fork a second app or invent
another schema/player/download manager behind the shared interfaces.

Run the relevant build/lint/tests and record actual commands/results.
Label device-dependent checks pending when the Pixel 6 is unavailable.
Keep runtime phone functionality independent of the desktop server.
Store live catalog/media/build outputs and raw logs in ignored locations.

Create docs/handoffs/Pxx-<short-name>.md using the handoff template.
Include changed paths, contract revisions, evidence, known limitations and the
next exact task. Update STATUS if you are the integration owner; otherwise
include the proposed status update in your handoff.
```

## First ready assignment

```text
Assignment: P00 — environment and device baseline.
Tasks: P00.01 through P00.05 in docs/phases/P00-environment.md.
Establish a reproducible API 37 toolchain, record the actual Pixel 6 OS when
available, and hand off the resolved configuration to P01.
```

## Integration review prompt

```text
Review the completed Pxx handoff against its phase document, architecture,
contracts, and acceptance matrix. Inspect the actual changes and evidence.
Identify blocking defects, contract drift, untested device claims, and missing
requirements with concrete file/task references. Run only checks needed to
resolve uncertainty. If integration passes, link evidence and mark the phase
VERIFIED; otherwise record the exact remaining task and owner.
```

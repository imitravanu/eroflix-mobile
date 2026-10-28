# Eroflix mobile working instructions

The user's priority is to finish the app efficiently and conserve quota. Read
`docs/STATUS.md` and the relevant phase specification before starting; use the
existing checkout and preserve uncommitted work.

- Implement features and fixes directly. Do not delegate routine work to another
  model or agent. If an independent Astra audit would be useful, ask the user
  first so they can arrange it; do not interrupt work that can proceed meanwhile.
  Avoid repeated handoffs, duplicate reviews, and frequent progress polling.
- Make meaningful progress before reporting. Keep updates short and report a
  completed result, a material problem, or a needed user action. Do not repeatedly
  announce that a build is still running.
- Batch related edits before building. Run the checks needed for the changed
  behavior; run broader release checks at an appropriate milestone, not after
  every small edit. Investigate genuine failures and never claim an unrun test
  passed. Carry deferred acceptance checks in the existing phase report.
- Keep `docs/STATUS.md` as the concise current phase board. Update a phase handoff
  only when it adds useful evidence or an actionable limitation. Preserve past
  reports and user data; do not create duplicate status documents or delete work
  merely to make the project look tidy.
- The Pixel 6 is the target. Keep remote debugging available until all phases are
  complete or the user asks to disable it. Never enable airplane mode or disable
  hotspot, Wi-Fi, or mobile data. Avoid unnecessary phone interactions, protect
  personal and catalog data, and use isolated fixtures for device tests.
- The project is not complete until remaining phases and required acceptance
  work are actually finished. Be candid about incomplete functionality, signing,
  and any tests that could not be performed.

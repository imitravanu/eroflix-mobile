# P04 — Browse, search, filters and details

**Role:** Compose discovery agent
**Prerequisites:** P02 repositories, fixture/full catalog, query semantics
**Owns:** `feature/catalog/`, discovery UI fixtures/tests; shared UI additions
coordinated through the foundation owner
**Requirements:** R04–R07, R25

## Goal

Deliver the complete browse-to-details experience using real catalog queries,
with one-handed controls and correct state restoration on Pixel 6.

## Read first

[Data contract](../contracts/DATA.md), [architecture](../ARCHITECTURE.md),
[validation](../VALIDATION.md), P02 handoff and the original blueprint's screen
sketches. Use measured providers/counts from the active catalog.

## Work packages

### P04.01 — Catalog feed and page state

- Implement paged poster grid, display-density choice, sort controls and
  scroll-to-top/navigation state. Use stable title IDs as Compose keys.
- Render initial loading, append loading/error, empty/search-no-result,
  provisioning failure and cached-poster placeholder states distinctly.
- Size poster requests to display bounds and keep an explicit bounded cache.
- Restore query, selected filters and scroll position after details/player
  navigation and activity recreation; invalidate stale generation cursors.
- Display active catalog count/version with offline availability clearly.

### P04.02 — Search and smart facets

- Debounce search input, cancel superseded work and reject stale query results.
- Implement provider, country, decade, studio and duration/Unknown controls
  using the repository's facet counts, not client-side filtering of loaded pages.
- Use OR-within/AND-across semantics and disjunctive counts from DATA.
- Keep selected zero-count filters removable; clear incompatible provider
  selections with a visible summary and a reset action.
- Duration displays unknown/unavailable when the active generation lacks data;
  the same controls work with known durations in fixture/future generations.
- Test combined filters with hidden titles and multilingual queries.

### P04.03 — Dual drawers and Back behavior

- Providers open from the left; smart filters open from the right. Only one
  drawer is active at a time, with correctly restored focus and scrim behavior.
- Keep top-bar Providers and Filters buttons always available in catalog routes.
- Add an in-app peek/hold handle inside the safe content area for one-handed
  access, with clear visual/haptic feedback. P08 validates/polishes it on Pixel.
- Preserve both system Back edges; do not implement the old upper-60%-edge
  exclusion rectangle or promise to pass a consumed gesture back after 150 ms.
- Back closes the active modal/drawer first; canceled predictive Back must
  restore its state. Keyboard dismissal and drawer gestures cannot race.

### P04.04 — Details and linked entities

- Build details sheet/page with title, poster, provenance, available metadata,
  source list and Favorite/Saved/Hide actions through shared repositories.
- Wire Play and Download action events to the app-level routes/controllers;
  capability-aware behavior arrives through P05/P06 integrations.
- Performer/studio/series chips open real entity results with counts and
  stable IDs. Country/era chips open the corresponding scoped query.
- Use nullable verified series order, then a documented stable fallback;
  do not guess that lexical title order is episode order.
- Handle absent performers/images/durations gracefully and keep context when
  navigating entity → details → back.

### P04.05 — Accessibility and integration

- Test 1.0, 1.3 and 2.0 font scale, portrait/landscape/split-screen widths,
  gesture and three-button navigation, display cutout and IME insets.
- Give poster/action chips TalkBack labels and sensible focus order. Actions
  remain reachable at least at 48 dp targets; icon-only controls have descriptions.
- Verify repository errors/retries and a catalog-generation change during a query.
- Integrate routes/actions through the app owner and document expected P05/P06
  controller contracts without editing those engines.

## Acceptance

- [x] Browse → search/filter → details → entity → Back restores query/scroll.
- [ ] V02–V04 and relevant V27 scenarios pass with repository fixtures.
- [x] Hidden entries and counts update without stale result flashes.
- [ ] Drawers work from visible buttons and in-app handles; system Back/home
  areas remain functional.
- [x] Full-catalog paging keeps bounded memory and meets query targets on Pixel,
  or device-only timing remains explicitly pending in the handoff.
- [x] Play/download navigation events are integrated or assigned to P05/P06
  with exact typed interfaces; no action silently does nothing.

## Evidence and handoff

Create `docs/handoffs/P04-discovery-ui.md` with screens/state scenarios, query
fixtures, accessibility results and the routes for P05/P06. Next: **P06** once
P05 is ready, then **P08** after download integration.

## Implementation checkpoint — 2026-10-28

Implemented;62 JVM cases, five actual Pixel UI tests and font1.3/2.0 repeats pass.
Isolated repository and full-catalog timing/memory checks pass too. Query p95
216.81ms, facets414.20ms, PSS197.52MiB;720 visited/600 retained with visible
identity restored through Back/recreation. Core behavior has evidence; TalkBack,
actual narrow/split widths, both navigation modes/predictive-Back cancellation
and long-hold affordance acceptance remain. Keep phase IMPLEMENTED until those
physical checks are completed. See the [handoff](../handoffs/P04-discovery-ui.md).

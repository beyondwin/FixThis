# FixThis Product Scene Redesign Design

Date: 2026-05-07
Status: approved design, awaiting written spec review

## Purpose

Redesign the FixThis sample app so it looks like a credible Android product
sample rather than a lightly themed Compose validation app. The redesign keeps
the existing five-tab information architecture and PointPatch validation
coverage, but gives each tab a stronger product scene, shared visual language,
and more realistic workflow surface.

This is a sample-app-only visual redesign. It must not change PointPatch
library, CLI, MCP, Gradle plugin, or package namespaces outside the existing
sample app.

## Approved Direction

Use the **Product Scene Redesign** approach.

The app should use the **Calm Product Studio** visual tone:

- light neutral background
- white or very lightly tinted surfaces
- compact, operational layouts
- teal primary accent
- restrained warning, critical, success, and neutral status colors
- dense but readable hierarchy
- real product workflow surfaces instead of isolated test controls

Avoid marketing-site composition, oversized hero blocks, decorative blobs,
one-note palettes, and generic sample-app labels.

## Scope

Redesign all five tabs and the common component system:

1. Home
2. Queue
3. Project
4. Review
5. Diagnostics

The redesign should primarily touch:

- `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisTheme.kt`
- `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`
- `sample/src/main/java/io/beyondwin/fixthis/sample/model/FixThisDemoData.kt`
- `sample/src/main/java/io/beyondwin/fixthis/sample/components/*`
- `sample/src/main/java/io/beyondwin/fixthis/sample/screens/*`
- sample instrumentation tests only if stable text anchors need adjustment

Do not add a ViewModel, backend, repository, database, login flow, network
integration, or external product architecture.

## Product Architecture

FixThis remains a fictional UI feedback product. The sample app should feel
like a compact studio for triaging UI feedback, reviewing affected surfaces,
and preparing agent handoffs.

Each tab should represent a distinct product scene:

- **Home:** project health and priority overview
- **Queue:** triage inbox for captured feedback
- **Project:** selected feedback detail and affected UI preview
- **Review:** fix-request and agent handoff composer
- **Diagnostics:** inspection and semantics health surface

The app shell should make the product identity visible through a compact studio
header, improved bottom navigation, and consistent surface treatment.

## Common Component System

Keep components small and reusable. The redesign should introduce or refine
components only when they remove duplication or make screen intent clearer.

Recommended component patterns:

- **Studio header:** screen title, status/project pill, optional compact action.
- **Metric card:** compact 2-column dashboard cards with value, label, trend,
  and state chip.
- **Feedback card:** stronger hierarchy with severity chip, state chip,
  metadata chips, summary, and clear actions.
- **Status chips:** shared severity/state/project chips using the FixThis color
  system.
- **Preview panels:** reusable surfaces for affected UI previews, chart regions,
  and weak-semantics visual areas.
- **Section headers:** title plus optional action, kept compact and utilitarian.
- **Activity/timeline rows:** shared row treatment for recent activity and
  project history.

Use icon-like controls where Compose Material dependencies already support them
or where a small text fallback remains accessible. Do not add a new icon
dependency solely for this redesign unless the implementation plan determines
it is already available through the project.

## Screen Designs

### Home

Home should become the strongest first impression. It should show FixThis
Studio as a working product surface immediately, not as a basic sample page.

Required content:

- compact product header
- project/status summary
- 2-column metric grid
- priority feedback cards
- recent activity feed that includes an agent handoff event
- section action that points conceptually to Queue

PointPatch coverage to preserve:

- repeated cards with nearby context
- chips/status labels
- action controls inside cards
- long-ish text that wraps cleanly

### Queue

Queue should feel like a triage inbox.

Required content:

- search field
- filter chips
- repeated feedback cards with row-like internal metadata
- severity, screen, assignee, and age context
- assign/save/review actions
- at least one disabled action

PointPatch coverage to preserve:

- `LazyColumn` repeated content
- nested clickable areas
- repeated labels where surrounding context matters
- disabled controls
- icon button semantics with clear content descriptions

### Project

Project should feel like an issue-detail workspace for one selected feedback
item.

Required content:

- selected feedback title and ID
- severity/state metadata
- affected UI preview panel
- source confidence metadata
- owner metadata
- reproduction note
- agent handoff note
- overflow menu
- close confirmation dialog
- timeline/activity history

PointPatch coverage to preserve:

- dialog selection
- dropdown/menu selection
- visual preview area
- long text
- nested layout context

### Review

Review should remain the form/control coverage screen, but it should look like
a real agent handoff composer.

Required content:

- request title field
- target screen field
- masked token field
- severity dropdown
- include screenshot checkbox
- send to agent queue switch
- validation/helper text
- primary `Submit request` action
- secondary clear/draft action

PointPatch coverage to preserve:

- text field semantics
- password/masked editable field
- checkbox
- switch
- dropdown
- validation/supporting text
- stable CTA used by tests

### Diagnostics

Diagnostics should become an inspection surface rather than a loose set of
edge cases.

Required content:

- visual-only Canvas sparkline
- explicitly labeled semantic chart/timeline region
- diagnostic signal rows
- weak-semantics preview block
- long text row that wraps cleanly
- disabled control
- nested clickable/control row

PointPatch coverage to preserve:

- Canvas-only area
- Canvas with explicit semantics
- weak semantic region
- disabled control
- long text and nested click target behavior

## Data Model

Use local immutable demo data. The existing demo model can be expanded if a
screen needs richer content, but all data must remain deterministic and local.

Allowed additions include:

- screen subtitles
- project summary text
- metric emphasis/status metadata
- timeline event type labels
- preview metadata
- queue filter labels
- diagnostic trend labels

Do not introduce async loading, fake network failures, persistence, or mutable
repository state.

## Accessibility And Semantics

The sample intentionally keeps both strong and weak semantics.

Strong semantics should remain on:

- buttons
- text fields
- dropdown/menu items
- checkbox
- switch
- chips where Material provides semantics
- important icon-only actions through content descriptions
- explicitly labeled chart/timeline regions

Weak semantics should remain on:

- at least one Canvas-only chart region
- at least one affected UI preview panel where area selection is useful
- at least one ambiguous/nested row or card target

Weak semantics are intentional validation surfaces, not defects to remove.

## Error Handling And States

The sample has no backend, so error and edge states are represented visually.

Include:

- critical/high severity state
- blocked/disabled state
- review helper text
- waiting diagnostic state
- resolved/success state

Do not add fake asynchronous loading behavior.

## Testing

Minimum verification:

```bash
./gradlew :app:assembleDebug
```

Keep or update sample instrumentation tests around stable anchors such as:

- `FixThis Studio`
- `Queue`
- `Submit request`
- `Semantic signal timeline`

If connected Android testing is available, run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

If no emulator or device is available, report that connected instrumentation
tests were not run.

## Risks

- Adding too much decoration could distract from the sample app's validation
  purpose.
- Richer cards and chips could overflow on narrow screens or large font scales.
- Removing weak semantics would reduce PointPatch validation coverage.
- Changing visible text could break existing instrumentation test anchors.

Mitigation:

- Keep layouts compact and utilitarian.
- Use stable dimensions and wrapping for cards, buttons, and chips.
- Preserve intentional weak-semantics regions.
- Keep stable test anchor strings unless tests are updated intentionally.

## Acceptance Criteria

- The first screen no longer looks like a default Compose validation sample.
- All five tabs feel like parts of the same FixThis product.
- Home, Queue, Project, Review, and Diagnostics each have a distinct product
  scene.
- Existing validation coverage remains present: form controls, dropdown/menu,
  dialog, Canvas, disabled control, repeated cards, long text, and weak
  semantics.
- The app remains deterministic and local.
- No non-sample PointPatch package or product namespace changes are introduced.
- `./gradlew :app:assembleDebug` passes.

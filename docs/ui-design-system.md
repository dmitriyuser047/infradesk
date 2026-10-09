# InfraDesk UI: Obsidian

The application uses an operator-console visual language: green graphite surfaces in dark mode,
soft gray backgrounds and slightly lighter gray surfaces in light mode, restrained orange actions, and thin structural borders.
Both themes use the same restrained rounded geometry: 6px controls, 10px compact panels and 12px cards.
The user's existing theme preference is preserved.

## Shared presentation

- `frontend/src/styles/tokens.css` owns semantic colors, spacing, shape and theme materials.
- `frontend/src/styles/obsidian.css` styles the shared shell, workspace primitives and page families.
- `WorkspaceHeader`, `WorkspaceSection`, `WorkspaceMetrics` and `WorkspaceTabs` remain the shared components.
- Page styles continue to own their layout and responsive behavior; the shared presentation does not change data contracts.
- Sans-serif text carries navigation, controls and descriptions. Supporting labels are at least 12px;
  buttons and normal subtitles are at least 13px. Monospace gives section headings and metric labels
  their console character at readable sizes, and remains for code and terminal content.
- Orange accents identify navigation, selected tabs and decorative icons. Status colors retain their semantic meaning;
  successful observations remain green, warnings amber and failures red.
- Dark background gradients are decorative; light gradients use barely visible neutral gray tints.
  Light navigation and panels are nearly opaque pale gray, with graphite text and distinct semantic status colors.
  Tables and dialogs use sufficiently opaque surfaces for legibility.
- Translucent navigation and dialog backdrops respect `prefers-reduced-transparency`.
- Navigation uses a compact icon rail for application groups. The selected group's pages appear in a
  horizontal menu inside the workspace; there is no sliding secondary sidebar or separate top bar.
  The section title occupies the first row, with page links beneath it and the account menu on the right.
  Selecting a rail group immediately opens its first authorized page. The URL determines the active
  group and page, so navigation labels always agree with the displayed content.
  Narrow screens use a horizontal rail and wrapping authorized page links.
  The current route restores its group on direct navigation and browser Back, preserving workspace scope.
- Organization selection lives on `/organizations`, creation on `/organizations/new`, reached through the rail organization icon.
  Both are standalone screens with no application navigation, account menu or terminal dock.
  The application entry `/` restores a per-account workspace preference from local storage only after
  membership validation; a first visit asks the user to choose, even with one available organization.
  Explicit links retain their destination. Administration and account settings retain authorized rail links
  and an explicit workspace return. Global administration never grants organization access.
- There is no top context breadcrumb or switcher. Project and environment filters live inside working
  pages and use the existing URL scope. Organization entry shows a one-second splash with its name and a preparing-data message;
  within-organization page navigation does not restart it. Reduced motion disables its animation.
- Navigation uses quiet controls with restrained orange selection accents.
  Account controls reveal a surface on hover.
- Account menus use solid matte surfaces, a quiet border and paired theme/language choices.
  They have no reflective gradient or backdrop blur. Reduced transparency uses solid surfaces;
  reduced motion disables transitions.

## Interaction and state

Status indicators retain their labels as well as their colors. Unknown observations remain distinct
from failures. Destructive actions retain their confirmations and permissions. Dialog focus containment,
Escape handling, navigation scope and the persistent terminal workspace are unchanged.

The terminal's explicit color mode remains independent of the application's theme.
Its dark background tokens preserve the existing terminal palette.

## Verification

Verify overview, inventory, connections, incidents, integrations, configuration editors, notifications,
administration, account settings, login, popovers and dialogs in both themes. Check narrow screens,
contained table scrolling, readable status labels and keyboard focus. Run the existing frontend test
suite and the production frontend build. Preview fixtures must be isolated from production APIs and
identified as demonstration data.

# InfraDesk UI: Obsidian

The application uses an operator-console visual language: graphite surfaces in dark mode,
warm neutral surfaces in light mode, restrained orange actions, and thin structural borders.
Both themes use the same geometry. The user's existing theme preference is preserved.

## Shared presentation

- `frontend/src/styles/tokens.css` owns semantic colors, spacing, shape and theme materials.
- `frontend/src/styles/obsidian.css` styles the shared shell, workspace primitives and page families.
- `WorkspaceHeader`, `WorkspaceSection`, `WorkspaceMetrics` and `WorkspaceTabs` remain the shared components.
- Page styles continue to own their layout and responsive behavior; the shared presentation does not change data contracts.
- Sans-serif text carries names and descriptions; monospace is used for controls, metadata, technical values and compact headings.
- Background gradients are decorative. Tables and dialogs use sufficiently opaque surfaces for legibility.
- Translucent navigation and dialog backdrops respect `prefers-reduced-transparency`.

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

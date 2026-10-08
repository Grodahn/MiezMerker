# Field PWA shell (#64)

Consume the existing `App` and `useAppGate`; do not add a second app/router or auth model.
The visual direction remains [approved Round 2](approved-round-2.md).

## Routes and boundaries

| Route | Current content | Follow-up |
| --- | --- | --- |
| `/` | Location-neutral Home with cat illustration, primary Sync CTA and Futterstellen/Katzen links | #66 implemented |
| `/sync` | Existing Collector, including provisioning and outbox | #67 |
| `/feeding-sites` | Explicit transition with link to existing Nodes | #68 |
| `/cats` | Existing usable Cat management, central fallback avatar | #69 |
| `/nodes` | Preserved original Nodes management; Futterstellen nav active | #68 decides migration/redirect |

Links are ordinary same-origin document links. Native browser history, refresh,
deep links and scroll restoration apply; each document rechecks its session.
The bfcache/pagehide protections from #31 remain. `popstate` updates also update
the gate and active section. Unknown paths retain the existing not-found view.
`/admin/**` belongs exclusively to Spring; neither proxy nor SW boundaries change.

Only a verified online session renders the four destinations. Login/loading has
no navigation or account controls. A legitimate bound offline credential can
open **only `/sync`**, with the existing restricted notice and online login retry.
Home is no longer a Collector alias. Installed PWA starts at `/`; a legitimate
offline entry replaces that URL with `/sync` only after the same centralized
gate confirms permission there. It never renders Home or full navigation.
Without a legitimate offline credential, the entry stays at Login.
Backend authorization, organization selection and immediate logout invalidation
remain authoritative and unchanged.

## Shared UI

- `app/src/ui/ApplicationShell.tsx`: branding, existing account disclosure, main,
  skip link and authenticated navigation. Integrate page content inside this shell;
  never add another header or primary navigation in a screen.
- `ui/navigation.tsx`: four semantic destinations and active-section mapping.
- `ui/tokens.css`: color, type, spacing, border, radius, touch and width tokens.
- `ui/primitives.tsx`: Button, Card, labelled Field, ListLink; native attributes
  pass through. Existing views also receive the same native-control CSS.
- `ui/Graphic.tsx`: responsive contain-fit imagery, fallback and AppIcon. Meaningful
  images require an `alt`; `alt=""` hides decorative art. Icons with adjacent text
  are decorative; standalone icons need `label`. AppIcon is a monochrome symbol;
  primary buttons render its artwork white to retain contrast.
- `style.css`: mobile-first page containers, controls, tables and shell. Main
  scrolls independently; header/nav occupy their own rows in `100dvh`, so no
  reserved nav-height guesses or covered content. Safe-area insets and viewport
  keyboard resizing are enabled. Desktop retains the same four links.

Account/organization controls are in the native keyboard-operable disclosure in
the header; active organization stays visible in its summary. Escape closes
the overlay and returns focus to its summary; leaving it with Tab or an outside
pointer action also dismisses it so it cannot cover focused page content. Large tables keep
localized keyboard-accessible scrolling until their screen redesigns.

## Artwork: replace without screen edits

`app/src/assets.ts` is the single typed semantic registry. Artwork lives under
`assets/branding`, `illustrations`, `icons`, `placeholders`. Use semantic entries,
never file paths, Base64, inline logo markup, or invented backend photo fields.
The logo is a compact 256px PNG export (about 55 KiB) of the existing
`graphics/MiezMerker_logo.png` brand artwork; the original is unchanged. Illustrations and avatars
are static vector fallbacks, not photographs of registered animals or sites.

To exchange graphics, either overwrite the asset file (retain its SVG viewBox /
valid image format), or import a new file in **assets.ts** and change its entry:

| Consumer | Central entry to replace |
| --- | --- |
| Header/logo | `assets.brand.logo` |
| Home | `assets.illustrations.home` |
| Collector Sync illustration | `assets.illustrations.sync` |
| Cat table fallback | `assets.placeholders.cat` |
| Transitional FeedingSite | `assets.placeholders.feedingSite` |

Install icons use the shared `assets/pwa.ts` adapter (consumed by the manifest
and registry); overwrite the existing public PNGs or change those entries.

`assets.icons` also covers navigation, action and status symbols; `illustrations`
reserves background/syncSuccess/error/offline and `placeholders` node/bowl.
Images have responsive `object-fit: contain` sizing; replacement aspect ratios do
not require screen edits. Rebuild and deploy after replacement. Vite keeps assets
external (`assetsInlineLimit: 0`); Workbox precaches PNG/SVG/WebP alongside shell
files. No business data or admin pages are cached. Future real image support
must use actual API data and `Graphic` with a semantic fallback.

Replacement tests render the real header, Home, Sync, Cats and transitional
FeedingSite consumers with changed registry entries. Browser tests verify asset
precache/fetch offline, mobile/tablet/desktop layout, keyboard and history.

#65 adds [the shared status inventory](status-state-inventory.md), `ui/Status.tsx`,
`ui/status-messages.ts` and the `assets.status` registry. Use these for loading,
progress, empty, offline, error and success UI. Actions remain native buttons;
callers own state and retries. `collector/CollectorStatus.tsx` keeps local ACK
completion and backend confirmation separate. Other data screens use the same
presentation through `management/common.tsx`.

#48 owns final Login styling; #67–#69 own final
Sync/FeedingSite/Cat content. Use these tokens, primitives and registry there.

Home (`app/src/home/Home.tsx`) uses shared branding from the shell, decorative
cat art, a native `/sync` link labelled “Futterstelle auslesen”, and two secondary
links. It reads no site/cat data and starts no Bluetooth operation. The existing
collector requires its own explicit user action to open the chooser. Home stays
behind the existing online session gate, including on reload and browser history.
Its scoped CSS keeps the primary action visible on small phone viewports; large
text and shorter windows can scroll inside the shell without covering navigation.

# Orknux — Logo Design

Status: design approved as the vector set (`orknux_brand_board.svg` and
siblings), with four animated variants. Not yet applied to any product.

## The name

- **Orknux** — the full product name. Unique, collision-safe.
- **orkx** — the shorthand: short URL, and the name of the CLI / tooling.

Etymology, chosen deliberately:

- *orkhē-* (Greek ὀρχ-, the root under **orchestra / orchestration**) — what the
  server actually does.
- *nux* (Latin, "nut" → *nucleus*) — the kernel, the core.

Together: **the orchestration kernel.**

Spelled with `k`, not `c`. The `c` spelling collides with **Apache ORC**, the
columnar format, in the same data/infra space, and `orcx` was already taken.
`k` is also the faithful transliteration of the Greek root — and the standard
German spelling of *orc*, so it keeps the connotation intact.

## The connotation conclusion

**The fantasy "orc" reading is intentional, and it lands.** This is the load-
bearing conclusion for the visual identity, so it is written down rather than
left to taste.

Two frontier models from different providers were given the word cold, with no
context, and asked what came to mind. Both, independently:

- went to **orc** first — Tolkien, Warhammer — "rugged, aggressive, tribal,"
  "brutish, green, tusked";
- read `-nux` as **Linux / Unix**, an OS, a distro, a package manager
  ("just install it via orknux");
- pronounced it the same way, **ORK-nucks**, with no hesitation;
- found **no existing meaning** for the exact term online.

One also recovered the Latin *nux* = nut (*nux vomica*) unprompted.

What this settles:

1. **The orc connotation does not need help.** It arrives on its own, from the
   sound. The identity must not add to it.
2. **The `-nux` → Unix reading is free positioning.** It makes the product read
   as infrastructure before anyone reads a word of copy. Let it ride; do not
   correct people toward the *nucleus* etymology.
3. **The etymology is discoverable, not a retcon.** Anyone who digs finds the
   nut. That is enough — it never needs to be explained on the homepage.
4. **The predictable failure mode is the cliché.** Both models, unprompted,
   imagined the same visual: *black, toxic green, metal, angular, orc/mechanical
   aesthetic.* That is the gamertag / metal-band / Twitch-overlay reading, and
   both flagged the name as "not corporate-clean" and "masculine."

**Therefore: the name supplies the edge; the identity supplies the
seriousness.** Because everyone already predicts toxic green and angular metal,
restraint is what makes Orknux look like a company. The mark should be
geometric, quiet, and mechanical — never illustrative, never a creature. A
viewer who knows the name should feel the orc in the silhouette; a viewer who
does not should see a piece of hardware.

**No mascot. No face. No fangs drawn as fangs. No toxic green.**

## Mark

Primary: **a hex nut, seen head-on.**

- *nux* is a nut — and a hex nut is also infrastructure hardware, the most
  boring load-bearing object there is. The double reading carries the whole name.
- A hexagon is the most legible geometric silhouette at favicon size, symmetric,
  and survives being stamped in one color.
- The center hole is the kernel — the void everything is bolted around. Either
  leave it empty so it reads as the **O** of Orknux, or place two or three nodes
  and edges in the negative space for the orchestration read.
- **The orc, handled with restraint:** extend the two upper vertices into slight
  points, so the silhouette picks up a tusk-like lift. At 200px it is character;
  at 16px it is just a slightly aggressive hexagon.

Secondary: **orkx** — the **x drawn as two crossed tusks**, curved, tapering,
meeting off-center. It is a tusk, a crossing (orchestration junction), and a
multiplication sign at once. This is the CLI icon and the short-URL favicon:
distinct from the server mark, sharing its geometry.

## Color

Charcoal ground, one accent. Explicitly **not** fantasy green — it dates the
product instantly and walks straight into the cliché above.

- **Iron-moss `#4A6B52`** — primary. The orc nod without the costume.
- **Molten amber `#D08A1A`** — the forge / kernel-heat read; more distinctive in a
  category drowning in blue and purple. Used for the hollow-nut variant.

## Type

Lowercase `orknux` in an angular grotesk. Tight counters keep it technical;
lowercase keeps a server from shouting.

## Constraints any candidate must survive

- Legible at **16px** as a favicon.
- Works in **one color**, stamped, on both light and dark ground.
- Reads in a **terminal** context for `orkx`.

## Sheets

`logo1.png` — first exploration. Established the hex nut, the tusked vertices
and the two-colour split.

`logo2.png` — the system. Adds what was missing and codifies the rest:

- a **variant ladder** — nut-with-nodes (default), hollow nut (stacked / small),
  outline (one-colour). This is the right answer to the 16px problem: the mark
  sheds interior detail as it shrinks instead of trying to survive intact.
- the **crossed-tusk `x`** for orkx, previously undrawn.
- exact palette, clear-space rule, minimum size, and a terminal treatment that
  uses the tagline well ("Orknux orchestration kernel").

## Vector set — the design as agreed

`orknux_brand_board.svg`, `orknux_logo.svg`, `orknux_icon.svg`. **This is the
approved design.** It settles what the sheets left contradictory:

- **The tusks are integrated.** They lift out of the upper vertices with a notch
  between them rather than protruding as separate spikes, so the silhouette stays
  a hexagon. The creature reading survives; the mascot reading does not.
- **The triad is a closed, rotated triangle.** No node sits dead top, so it no
  longer collides with the Android share glyph.
- **The size ladder is explicit**: primary at 24px+, hollow below 24px, outline
  at 16px. The mark sheds interior detail as it shrinks instead of trying to
  survive intact.
- Palette, clear space, terminal and app-icon treatments are all codified.

### Motion

Four animated variants, all built on the same geometry:

| file | motion | use |
| --- | --- | --- |
| `orknux_kernel_pulse_animated.svg` | triad breathes, edges brighten | idle / hero |
| `orknux_loader_centered_graph_slow_rotate.svg` | dash chases the triad perimeter while the whole graph turns once every 10s | pending work |
| `orknux_orchestration_route_animated.svg` | edges draw in sequence, amber ping at the kernel | a run firing |
| `orknux_mechanical_lockup_animated.svg` | mark settles into place, wordmark slides in | intro / splash |

The route animation is the one that earns its keep: it shows what the product
does — nodes resolving in order, then a run firing — rather than decorating.

Adjustments made to the set as delivered:

- **The lockup's mark was rendering in the corner at the wrong size.** `#mark`
  carried both a `transform` attribute and a CSS `transform` animation; the
  animated CSS value replaces the attribute outright, so `translate(70 55)
  scale(1.55)` was discarded for the whole loop. Placement now lives on an outer
  group and only the inner group animates.
- **`prefers-reduced-motion` added** to the pulse, route and lockup — the loader
  already had it. The route needed more than `animation:none`: its resting state
  is invisible, so the guard restores the finished graph rather than blanking it.
- **Per-edge dash lengths in the route.** All three edges shared
  `stroke-dasharray:55` while measuring 31.3, 34.2 and 49.4, so the short ones sat
  idle before drawing. Each now dashes to its own length.
- **Loader seam.** `18 50` did not tile the ~114.87 perimeter; `15 42.43` cycles
  it exactly twice.

The loader was then replaced by a centred, slowly rotating cut. The triad's
centroid is (104.667, 116.667) and the kernel void is at (100, 109), so its
`translate(-4.667, -7.667)` lands the graph exactly on centre, and the farthest
node sits 26.3 units out — 11.9 more at peak pulse — comfortably inside the
47-unit hole, so the rotation never clips. It also correctly keeps the static
`translate` and the animated `rotate` on separate elements, which is the trap
the lockup fell into. The dash-tiling and reduced-motion fixes above were
re-applied to it, having not carried over from the file it replaced.

### Still open

- **The wordmark is not reproducible.** `orknux_logo.svg` sets live `<text>` in
  `"Noto Sans","DejaVu Sans",Arial,sans-serif`. Without Noto Sans installed the
  letterforms silently change. Convert the wordmark to paths, or name and license
  a real face — and note the brief asked for an *angular* grotesk, where Noto and
  Arial are neutral humanist ones, so that choice is currently defaulted rather
  than made.
- **No transparent-ground logo.** `orknux_logo.svg` bakes in an opaque `#0D1518`
  rect, so it shows a dark slab on any other surface.
- **No dark-ink variant.** The board's ONE COLOR panel labels a near-white
  (`#eeeeea`) mark as "light background", where it would be invisible. Both
  one-color variants are light-on-dark. The nut hole is also a hardcoded
  `#0D1518` disc rather than a knockout, which is what prevents a true
  single-color version.
- **`orknux_icon.svg` exports the primary mark only**, at 512px. The hollow and
  outline variants exist inside the board but not as standalone files, so the
  favicon sizes the ladder calls for cannot be produced from what is there.
- **`orkx` has no mark.** The crossed-tusk `x` was in `logo2.png` but did not
  make it into the vector set.

## Open

- Iron-moss is the primary and molten amber the accent — settled by the vector
  set, which uses moss for the mark and reserves amber for the hollow variant and
  the "run fired" ping.
- The **wordmark typeface** is the one design decision still genuinely open, and
  it blocks `orknux-logo.svg` being reproducible. Everything else below is
  applied.

## Production cuts

`docs/brand/` holds the files meant for use, derived from the board:

| file | what it is |
| --- | --- |
| `orknux-mark.svg` | primary, 24px and up, transparent void |
| `orknux-mark-hollow.svg` | small cut, below 24px |
| `orknux-mark-mono.svg` | one colour via `currentColor` — works on either ground |
| `orknux-logo.svg` | horizontal lockup, transparent ground |
| `orkx-mark.svg` | the crossed tusks, drawn as filled tapers so they hold at 16px |

Three things these fix relative to the board: the kernel void is a mask knockout
rather than a disc painted in the background colour, so a mark can sit on any
surface; the one-colour cut inherits `currentColor` instead of being near-white
only, which is what the board's "light background" variant claimed to be and was
not; and `orkx` has a mark at all.

## Applied

**UI** (`orknux-ui`)

- `public/favicon.svg` and the `<link rel="icon">` that was missing entirely.
- The top-bar and sign-in marks, which were both a generic `file-code` icon
  standing in for a logo.
- `components/Loader.tsx` — the rotating-graph loader as a component, adopted at
  all 23 loading sites across 19 pages. The surrounding `<p>` is kept at each
  site so the pages' own padding and borders survive. It carries a `role` of
  status, announces politely rather than interrupting, and stops under
  `prefers-reduced-motion` with the mark left whole.

**Website** (`orknux-website`)

- `static/favicon.svg` only, replacing the violet three-node placeholder. The
  inline wordmark in `templates/fragments/site.html` is still that placeholder —
  another agent owns this repo, so it was left alone.
- Unrelated to the mark, but noted here because it came out of the same test:
  verbal transmission will produce `orcnux` spellings. Worth checking whether
  `orcnux.com` / `.io` are free, as redirects.

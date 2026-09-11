Renders a self-hosted Lucide glyph at one of five sizes, each with its own stroke-width token (dark values are lighter, because a light stroke on a dark ground blooms).

\`\`\`jsx
<Icon name="scan-line" size={24} label="Scan a code" />
<Icon name="cloud-off" size={16} />   {/* decorative — the label is next to it */}
\`\`\`

- `size`: 16 · 20 · 24 · 32 · 40. Never scale with CSS `transform` — the stroke would scale with it.
- Lucide is outline-only. To show *selected*, change colour, add a background, or add a bar — never swap to a filled twin, because there isn't one.
- Colour with `color` (the SVG uses `stroke: currentColor`). Never set `fill`.

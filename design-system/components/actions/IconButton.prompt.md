An icon-only control. `label` is mandatory — it is both the accessible name and the tooltip.

\`\`\`jsx
<IconButton icon="funnel" label="Filter" pressed={filtersOpen} />
<IconButton icon="trash" label="Delete" tone="danger" />
\`\`\`

Selected state = tinted background + 1px accent ring + accent stroke. Never a fill variant.

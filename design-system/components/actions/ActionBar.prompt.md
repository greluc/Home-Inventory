Holds a screen's actions and moves them to the right place per width: bottom-pinned in the thumb zone below 600px, right-aligned inline above it.

\`\`\`jsx
<ActionBar sticky stack>
  <Button variant="ghost" full>Cancel</Button>
  <Button variant="primary" icon="save" full>Save</Button>
</ActionBar>
\`\`\`

The primary is `order: 2` in CSS, so source order stays cancel-then-confirm while the visual order follows the platform.

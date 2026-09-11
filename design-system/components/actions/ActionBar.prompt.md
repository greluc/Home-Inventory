Holds a screen's actions and moves them to the right place per width: bottom-pinned in the thumb zone below 600px, right-aligned inline above it.

\`\`\`jsx
<ActionBar sticky stack>
  <Button variant="ghost" full>Abbrechen</Button>
  <Button variant="primary" icon="save" full>Speichern</Button>
</ActionBar>
\`\`\`

The primary is `order: 2` in CSS, so source order stays cancel-then-confirm while the visual order follows the platform.

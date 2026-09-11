The action control. Exactly one `primary` per context; everything else is `secondary` or `ghost`.

\`\`\`jsx
<Button variant="primary" icon="save">Änderungen speichern</Button>
<Button variant="ghost" icon="undo-2">Verwerfen</Button>
<Button variant="danger" icon="trash">Endgültig löschen</Button>
\`\`\`

- Coarse pointer forces 44px height whatever the density says.
- `busy` keeps the label and spins the leading icon; under `prefers-reduced-motion` the spin is dropped and the button just disables.
- On compact width put it in `<ActionBar sticky>` so it lands in the thumb zone.

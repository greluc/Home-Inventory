Wraps any control with its label, help text, validation message and the three non-value states.

\`\`\`jsx
<Field id="wbw" label="Wiederbeschaffungswert" required error="Betrag muss größer als 0 sein">
  {({ id, describedBy, invalid }) => <MoneyInput id={id} aria-describedby={describedBy} aria-invalid={invalid} />}
</Field>
\`\`\`

- `restricted` is not `disabled`: it means no permission, and it renders a hatch + lock + words.
- Labels wrap with `overflow-wrap: anywhere`; the split layout gives them 11–16rem, sized for `Mindesthaltbarkeitsdatum`.

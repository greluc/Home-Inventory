Wraps any control with its label, help text, validation message and the three non-value states.

\`\`\`jsx
<Field id="rv" label="Replacement value" required error="Amount must be greater than 0">
  {({ id, describedBy, invalid }) => <MoneyInput id={id} aria-describedby={describedBy} aria-invalid={invalid} />}
</Field>
\`\`\`

- `restricted` is not `disabled`: it means no permission, and it renders a hatch + lock + words.
- Labels wrap with `overflow-wrap: anywhere`; the split layout gives them 11–16rem, sized for a label as long as `Mindesthaltbarkeitsdatum` — German compounds are the width case this had to survive.

One conflicting field, three versions, one decision. Phone-first: stacked full-width cards, not a squeezed three-column table.

```jsx
<ConflictField onChoose={choose} field={{
  key:"name", label:"Name",
  base:"Drill", baseSub:"Version 7 · 03.09. 14:02",
  mine:"Cordless drill GSB 18V-55", mineSub:"This device · offline 09.09.",
  theirs:"Drill, blue", theirsSub:"Version 11 · Anna · 10.09.",
  chosen:"mine" }} />
```

One conflicting field, three versions, one decision. Phone-first: stacked full-width cards, not a squeezed three-column table.

```jsx
<ConflictField onChoose={choose} field={{
  key:"name", label:"Bezeichnung",
  base:"Bohrschrauber", baseSub:"Version 7 · 03.09. 14:02",
  mine:"Akku-Bohrschrauber GSB 18V-55", mineSub:"Dieses Gerät · offline 09.09.",
  theirs:"Bohrschrauber blau", theirsSub:"Version 11 · Anna · 10.09.",
  chosen:"mine" }} />
```

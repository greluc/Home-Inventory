The desktop/tablet-landscape view of a thousand-row list. Columns are user-chosen; sort state is an aria-sort plus an arrow, never colour alone.

```jsx
<Table caption="Artikel" selectable selected={sel} sort={{key:"name",dir:"ascending"}}
  columns={[{key:"name",label:"Bezeichnung"},{key:"code",label:"Code",mono:true},{key:"value",label:"Wiederbeschaffungswert",numeric:true}]}
  rows={items} />
```

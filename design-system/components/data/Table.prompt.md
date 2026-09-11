The desktop/tablet-landscape view of a thousand-row list. Columns are user-chosen; sort state is an aria-sort plus an arrow, never colour alone.

```jsx
<Table caption="Items" selectable selected={sel} sort={{key:"name",dir:"ascending"}}
  columns={[{key:"name",label:"Name"},{key:"code",label:"Code",mono:true},{key:"value",label:"Replacement value",numeric:true}]}
  rows={items} />
```

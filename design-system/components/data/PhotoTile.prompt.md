The grid half of the dense list. Square photo, two-line name, path beneath.

```jsx
<PhotoGrid>{items.map(i => <PhotoTile key={i.id} item={i} selectable selected={sel.has(i.id)} />)}</PhotoGrid>
```

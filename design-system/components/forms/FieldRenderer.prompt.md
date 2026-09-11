Turns a runtime type definition into a form. This is the product's unusual surface: nobody designs the item form, because every installation defines its own.

```jsx
<FieldRenderer split schema={[
  { title: "Identifikation", fields: [
    { key:"name", label:"Bezeichnung", type:"text", required:true, value:"Akku-Bohrschrauber GSR 18V-55" },
    { key:"sn",   label:"Seriennummer", type:"text", mono:true, value:"3 601 JJ0 100" },
  ]},
  { title: "Wert", fields: [
    { key:"wbw", label:"Wiederbeschaffungswert", type:"money", value:189.99, currency:"EUR" },
    { key:"mhd", label:"Mindesthaltbarkeitsdatum", type:"date" },
    { key:"key", label:"Lizenzschlüssel", type:"secret", restricted:true },
  ]},
]} />
```

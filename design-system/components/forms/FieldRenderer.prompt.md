Turns a runtime type definition into a form. This is the product's unusual surface: nobody designs the item form, because every installation defines its own.

```jsx
<FieldRenderer split schema={[
  { title: "Identification", fields: [
    { key:"name", label:"Name", type:"text", required:true, value:"Cordless drill GSR 18V-55" },
    { key:"sn",   label:"Serial number", type:"text", mono:true, value:"3 601 JJ0 100" },
  ]},
  { title: "Value", fields: [
    { key:"rv",  label:"Replacement value", type:"money", value:189.99, currency:"EUR" },
    { key:"bbd", label:"Best-before date", type:"date" },
    { key:"key", label:"Licence key", type:"secret", restricted:true },
  ]},
]} />
```

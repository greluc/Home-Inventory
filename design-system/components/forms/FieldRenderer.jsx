import React from "react";
import { Field } from "./Field.jsx";
import { TextInput } from "./TextInput.jsx";
import { MoneyInput } from "./MoneyInput.jsx";
import { QuantityInput } from "./QuantityInput.jsx";
import { DateInput } from "./DateInput.jsx";
import { Select } from "./Select.jsx";
import { ComboBox } from "./ComboBox.jsx";
import { Checkbox } from "./Checkbox.jsx";
import { TagInput } from "./TagInput.jsx";
import { ReferenceInput } from "./ReferenceInput.jsx";
import { SecretInput } from "./SecretInput.jsx";
import { FileInput } from "./FileInput.jsx";
import { FormSection } from "./FormSection.jsx";

/* The renderer for a runtime-defined type. Every branch produces the same
   Field shell, which is why a form nobody designed still looks designed. */
export function renderControl(def, ctx = {}) {
  const p = { id: def.key, disabled: def.disabled, readOnly: ctx.readOnly || def.readOnly, ...(def.props || {}) };
  switch (def.type) {
    case "multiline": return <TextInput type="multiline" rows={def.rows || 3} value={def.value} {...p} />;
    case "integer":
    case "decimal":
    case "url":
    case "email":
    case "text":     return <TextInput type={def.type} value={def.value} mono={def.mono} {...p} />;
    case "money":    return <MoneyInput amount={def.value} currency={def.currency} {...p} />;
    case "quantity": return <QuantityInput value={def.value} unit={def.unit} units={def.units} {...p} />;
    case "boolean":  return <Checkbox label={def.checkboxLabel || def.label} checked={!!def.value} {...p} />;
    case "date":
    case "datetime": return <DateInput type={def.type} value={def.value} {...p} />;
    case "enum":     return (def.options || []).length > 12
                       ? <ComboBox options={def.options} value={def.value} {...p} />
                       : <Select options={def.options} value={def.value} {...p} />;
    case "multi-enum": return <TagInput values={def.value || []} {...p} />;
    case "reference":  return <ReferenceInput kind={def.refKind} label={def.value} path={def.path} {...p} />;
    case "secret":     return <SecretInput value={def.value} revealed={def.revealed} storedLocally={def.storedLocally} {...p} />;
    case "file":       return <FileInput files={def.value || []} {...p} />;
    default:           return <TextInput value={def.value} {...p} />;
  }
}

export function FieldRenderer({ schema = [], split = false, readOnly = false, className = "", ...rest }) {
  const grouped = schema.some((n) => n.fields);
  const body = (def) => {
    if (def.type === "boolean" && !def.restricted) {
      return <div className="hi-field" key={def.key}>{renderControl(def, { readOnly })}</div>;
    }
    return (
      <Field key={def.key} id={def.key} label={def.label} help={def.help} error={def.error}
             required={def.required} optional={def.optional} readOnly={readOnly || def.readOnly}
             restricted={def.restricted}>
        {renderControl(def, { readOnly })}
      </Field>
    );
  };
  const cls = ["hi-form", split ? "hi-form--split" : "", className].filter(Boolean).join(" ");
  if (!grouped) return <div className={cls} {...rest}>{schema.map(body)}</div>;
  return (
    <div className={className} {...rest}>
      {schema.map((g, i) => (
        <FormSection key={g.title} title={g.title} count={g.fields.length} defaultOpen={g.defaultOpen ?? i === 0}>
          <div className={cls} style={{ maxWidth: "none" }}>{g.fields.map(body)}</div>
        </FormSection>
      ))}
    </div>
  );
}

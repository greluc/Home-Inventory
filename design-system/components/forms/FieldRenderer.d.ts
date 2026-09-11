export type FieldType =
  | "text" | "multiline" | "integer" | "decimal" | "money" | "boolean"
  | "date" | "datetime" | "enum" | "multi-enum" | "url" | "email"
  | "quantity" | "reference" | "secret" | "file";

export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  value?: unknown;
  help?: string;
  error?: string;
  required?: boolean;
  optional?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  /** No permission. Renders the hatched lock plate instead of the control. */
  restricted?: boolean;
  options?: ({ value: string; label: string } | string)[];
  unit?: string; units?: string[]; currency?: string;
  refKind?: "location" | "item"; path?: string[];
  revealed?: boolean; storedLocally?: boolean;
  mono?: boolean; rows?: number;
  checkboxLabel?: string;
  props?: Record<string, unknown>;
}

export interface FieldGroup { title: string; fields: FieldDef[]; defaultOpen?: boolean }

/**
 * Renders a form from a type definition the installation wrote at runtime. There is no fixed
 * "item form"; there is this. Ungrouped schemas render as one stack, grouped schemas as
 * collapsible FormSections with the first open.
 */
export interface FieldRendererProps {
  schema: FieldDef[] | FieldGroup[];
  /** Two-column label/control from 840px up. Off below that — always. A 20-field type on a tablet in portrait is single column with grouped sections; in landscape it is split. */
  split?: boolean;
  readOnly?: boolean;
}
export declare function FieldRenderer(props: FieldRendererProps): JSX.Element;
export declare function renderControl(def: FieldDef, ctx?: { readOnly?: boolean }): JSX.Element;

import type { HTMLAttributes, ReactNode } from "react";

/** The shell every field type shares: label, required marker, help, error, and the three
 *  non-value states (read-only, restricted, disabled). Wrapping every control in it is what
 *  makes a form generated from an unknown type definition look designed. */
export interface FieldProps extends Omit<HTMLAttributes<HTMLDivElement>, "children"> {
  /** Used for htmlFor and to derive the aria-describedby ids. */
  id: string;
  label: string;
  help?: string;
  /** Presence switches the field to invalid: red left bar + border + an icon-led message. Never colour alone. */
  error?: string;
  required?: boolean;
  /** Renders a quiet "optional" chip. Use in long forms where most fields are required. */
  optional?: boolean;
  readOnly?: boolean;
  /** "This field exists but is not for you." Renders a hatched, locked plate — different from empty, different from error, and never a masked value. */
  restricted?: boolean;
  /** Either an element or a render prop receiving { id, describedBy, invalid }. */
  children: ReactNode | ((a: { id: string; describedBy?: string; invalid: boolean }) => ReactNode);
  /** The text on the restricted plate. English default; the client translates (REQ-NFR-032). */
  restrictedLabel?: string;
}
export declare function Field(props: FieldProps): JSX.Element;

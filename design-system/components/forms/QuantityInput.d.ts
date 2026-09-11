import type { InputHTMLAttributes } from "react";

/** quantity = value + unit. Identical anatomy to MoneyInput on purpose: two composite numeric types that look the same are two fewer things to learn. */
export interface QuantityInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "value"> {
  value?: number | string;
  unit?: string;
  /** The symbols offered in the attached selector. The defaults are placeholders — the real list
   *  comes from the field definition, which is tenant configuration, not design-system data. */
  units?: string[];
  readOnly?: boolean;
  /** Accessible name of the unit selector. English default; the client passes the translated string (REQ-NFR-032). */
  unitLabel?: string;
}
export declare function QuantityInput(props: QuantityInputProps): JSX.Element;

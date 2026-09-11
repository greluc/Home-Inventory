import type { InputHTMLAttributes } from "react";

/** quantity = value + unit. Identical anatomy to MoneyInput on purpose: two composite numeric types that look the same are two fewer things to learn. */
export interface QuantityInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "value"> {
  value?: number | string;
  unit?: string;
  units?: string[];
  readOnly?: boolean;
}
export declare function QuantityInput(props: QuantityInputProps): JSX.Element;

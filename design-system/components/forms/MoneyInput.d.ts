import type { InputHTMLAttributes } from "react";

/** money = amount + currency, in one well. Formatting is Intl.NumberFormat in the client — the server sends a decimal and a code, never a formatted string. */
export interface MoneyInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "value"> {
  amount?: number | string;
  currency?: string;
  /** Shown in the attached selector. Keep it to the currencies the tenant actually uses. */
  currencies?: string[];
  readOnly?: boolean;
  locale?: string;
}
export declare function MoneyInput(props: MoneyInputProps): JSX.Element;

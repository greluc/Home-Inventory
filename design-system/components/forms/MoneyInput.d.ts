import type { InputHTMLAttributes } from "react";

/** money = amount + currency, in one well. Formatting is Intl.NumberFormat in the client — the server sends a decimal and a code, never a formatted string. */
export interface MoneyInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "value"> {
  amount?: number | string;
  currency?: string;
  /** Shown in the attached selector. Keep it to the currencies the tenant actually uses. */
  currencies?: string[];
  readOnly?: boolean;
  /** BCP 47 tag for `Intl.NumberFormat`. Omit for the browser default — never hard-code one,
   *  and never ship a pre-formatted placeholder: "0,00" and "0.00" are the same value. */
  locale?: string;
  /** Accessible name of the currency selector. English default; the client passes the translated string (REQ-NFR-032). */
  currencyLabel?: string;
}
export declare function MoneyInput(props: MoneyInputProps): JSX.Element;

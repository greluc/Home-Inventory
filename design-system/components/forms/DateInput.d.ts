import type { InputHTMLAttributes } from "react";

/** date and datetime. Uses the native picker deliberately: it is keyboard- and screen-reader-correct on every platform, it follows color-scheme, and it works offline. A custom calendar would be three of those at best. */
export interface DateInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "value"> {
  type?: "date" | "datetime";
  value?: string;
  readOnly?: boolean;
  locale?: string;
}
export declare function DateInput(props: DateInputProps): JSX.Element;

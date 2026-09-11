import type { SelectHTMLAttributes } from "react";

export interface SelectOption { value: string; label: string }

/** enum, when the list is short and closed. Native <select>: it is the only picker that is usable one-handed on every phone OS and needs no JS to be accessible. Above ~12 options use ComboBox. */
export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, "value"> {
  options: (SelectOption | string)[];
  value?: string;
  placeholder?: string;
  readOnly?: boolean;
}
export declare function Select(props: SelectProps): JSX.Element;

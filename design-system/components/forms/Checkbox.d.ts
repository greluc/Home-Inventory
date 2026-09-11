import type { InputHTMLAttributes, ReactNode } from "react";

/** boolean, and the row selector in the dense list. The check is a Lucide glyph inside the box — the state is a shape change, not only a colour change. */
export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type"> {
  label: ReactNode;
  hint?: string;
  /** "some of the rows below are selected" — renders a minus, not a check. */
  indeterminate?: boolean;
}
export declare function Checkbox(props: CheckboxProps): JSX.Element;

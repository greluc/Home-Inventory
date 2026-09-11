import type { InputHTMLAttributes, ReactNode } from "react";

/** One of a small set of mutually exclusive options — and the field-by-field chooser in conflict resolution, where the three options are base, mine and theirs. */
export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type"> {
  label: ReactNode;
  hint?: string;
}
export interface RadioGroupProps { legend: string; children: ReactNode }
export declare function Radio(props: RadioProps): JSX.Element;
export declare function RadioGroup(props: RadioGroupProps): JSX.Element;

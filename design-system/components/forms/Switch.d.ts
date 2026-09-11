import type { InputHTMLAttributes } from "react";

/** A setting that takes effect immediately — never a form value you still have to save. Always paired with a word ("An"/"Aus"), because a switch whose state is carried only by position and colour fails at a glance and fails for colour-blind users. */
export interface SwitchProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type"> {
  label: string;
  onLabel?: string;
  offLabel?: string;
}
export declare function Switch(props: SwitchProps): JSX.Element;

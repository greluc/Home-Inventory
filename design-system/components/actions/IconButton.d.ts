import type { ButtonHTMLAttributes } from "react";

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "title"> {
  icon: string;
  /** Required. Renders as the visually-hidden name AND the tooltip — a coarse pointer never sees a tooltip, so the label is the real affordance. */
  label: string;
  tone?: "default" | "danger";
  /** Gives the button a visible boundary (3.75:1) — use when it sits on a photo or an empty toolbar. */
  outlined?: boolean;
  /** Toggle state. Selected is shown by background + ring + colour, never by a filled icon twin (Lucide has none). */
  pressed?: boolean;
  size?: 16 | 20 | 24;
}
export declare function IconButton(props: IconButtonProps): JSX.Element;

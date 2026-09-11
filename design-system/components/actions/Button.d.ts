import type { ButtonHTMLAttributes, ReactNode } from "react";

/**
 * The one filled action in a context, or one of the quiet alternatives.
 */
export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "size"> {
  /** "primary" is limited to ONE per context. Destructive actions use "danger" and always sit behind a typed confirmation. */
  variant?: "primary" | "secondary" | "ghost" | "danger";
  /** "lg" (48px) is the phone-only size used in the thumb zone and on the scanner. */
  size?: "md" | "lg";
  /** Leading Lucide icon name. */
  icon?: string;
  /** Trailing Lucide icon name — reserve for "opens elsewhere" (external-link, chevron-right). */
  iconEnd?: string;
  /** Swaps the leading icon for a spinner and blocks input. Keep the label; never replace it with "…". */
  busy?: boolean;
  /** Full-bleed. The default on compact width inside an action bar. */
  full?: boolean;
  as?: "button" | "a";
  children: ReactNode;
}
export declare function Button(props: ButtonProps): JSX.Element;

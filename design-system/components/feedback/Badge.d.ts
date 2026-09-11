import type { HTMLAttributes, ReactNode } from "react";
/** A count bubble, or a short uppercase status attached to another element (a row, a tab, a device). For a system state that needs an icon, use StatusChip. */
export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: "neutral" | "success" | "warning" | "danger" | "conflict";
  /** Renders the accent pill instead of the tone badge. Tabular figures; no "99+" — show the real number, this is an inventory tool. */
  count?: number;
  children?: ReactNode;
}
export declare function Badge(props: BadgeProps): JSX.Element;

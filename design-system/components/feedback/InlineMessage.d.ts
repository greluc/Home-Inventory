import type { HTMLAttributes, ReactNode } from "react";
/** A message that belongs to a place on the page and stays there. Use it — not a toast — whenever the user has to act, because a toast that disappears is a message the user missed. */
export interface InlineMessageProps extends HTMLAttributes<HTMLDivElement> {
  tone?: "info" | "success" | "warning" | "danger" | "conflict" | "degraded" | "offline";
  title?: string;
  icon?: string;
  /** Buttons. Keep them ghost or secondary; an inline message never carries the page's primary action. */
  actions?: ReactNode;
  children?: ReactNode;
}
export declare function InlineMessage(props: InlineMessageProps): JSX.Element;

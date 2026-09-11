import type { ReactNode } from "react";
/** Confirmation of something that already happened and needs no decision — "Verschoben nach Regal B",
 *  "12 Artikel synchronisiert". Anything requiring an action is an InlineMessage instead.
 *  Sits above the bottom bar on compact width so it never covers the thumb zone. Max three at once. */
export interface ToastProps {
  tone?: "neutral" | "success" | "danger" | "conflict" | "offline";
  title: string;
  detail?: string;
  /** A single undo-style control. Undo is the only action a toast may carry. */
  action?: ReactNode;
  onDismiss?: () => void;
}
export declare function Toast(props: ToastProps): JSX.Element;
export declare function ToastStack(props: { children: ReactNode }): JSX.Element;

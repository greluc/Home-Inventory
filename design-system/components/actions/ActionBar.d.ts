import type { HTMLAttributes, ReactNode } from "react";

export interface ActionBarProps extends HTMLAttributes<HTMLDivElement> {
  /** Pins to the bottom of the viewport on compact width (inside the thumb zone, safe-area padded) and goes inline from 600px up. */
  sticky?: boolean;
  /** Stacks full-width buttons with the primary on top — the phone form pattern. */
  stack?: boolean;
  children: ReactNode;
}
export declare function ActionBar(props: ActionBarProps): JSX.Element;
export declare function ActionBarSpacer(): JSX.Element;

import type { HTMLAttributes, ReactNode } from "react";
/** A bounded region on a page: one border, 3px radius, no shadow. Elevation in this system is a
 *  surface step, not a lighting scheme — a card does not float. */
export interface CardProps extends HTMLAttributes<HTMLElement> {
  title?: string;
  actions?: ReactNode;
  /** Removes body padding — for a card whose whole content is a table or a list. */
  flush?: boolean;
  children: ReactNode;
}
export declare function Card(props: CardProps): JSX.Element;

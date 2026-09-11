import type { ReactNode } from "react";
/** A side task that keeps its context visible: filters, the location picker, an item's detail beside the
 *  list, bulk-edit. Right-hand panel from 600px; a bottom sheet with a grip below it. Choose Drawer over
 *  Modal whenever the user benefits from still seeing what they were working on. */
export interface DrawerProps {
  title: string;
  onClose?: () => void;
  footer?: ReactNode;
  /** Off for a filter drawer on a wide screen, where the list stays interactive behind it. */
  scrim?: boolean;
  children: ReactNode;
}
export declare function Drawer(props: DrawerProps): JSX.Element;

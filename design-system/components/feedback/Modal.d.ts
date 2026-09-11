import type { ReactNode } from "react";
/** A decision that must be made before anything else can continue: destructive confirmation, the
 *  "confirm again" second-factor prompt, base-URL confirmation before the first label print.
 *  Below 600px it becomes a bottom sheet so the buttons land in the thumb zone.
 *  If the user could reasonably want to keep looking at the page behind it, use a Drawer. */
export interface ModalProps {
  title: string;
  subtitle?: string;
  /** 52rem instead of 34rem — for the conflict resolver and the column picker only. */
  wide?: boolean;
  onClose?: () => void;
  footer?: ReactNode;
  /** Accessible name of the close button. English default; the client passes the translated string (REQ-NFR-032). */
  closeLabel?: string;
  children: ReactNode;
}
export declare function Modal(props: ModalProps): JSX.Element;

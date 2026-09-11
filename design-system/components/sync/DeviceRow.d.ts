/** A registered device with its reconciliation state: last sync, local data volume, unsent
 *  changes. "Unsent" is a count in --state-pending-fg, not a warning colour — having unsent
 *  changes after a day in a cellar is the expected condition, not a fault. */
export interface DeviceModel {
  name: string;
  platform?: "android" | "ios" | "tablet" | "web" | "desktop";
  lastSync?: string;
  size?: string;
  unsent?: number;
  status?: "success" | "offline" | "pending" | "conflict" | "warning";
  current?: boolean;
}
export declare function DeviceRow(props: {
  device: DeviceModel;
  trailing?: React.ReactNode;
  /** User-visible strings. English defaults; the client passes the translated text from its
   *  resource bundle, because no display text is hard-coded in a component (REQ-NFR-032). */
  currentLabel?: string;
  /** The word after the unsent count, e.g. "unsent". */
  unsentLabel?: string;
}): JSX.Element;

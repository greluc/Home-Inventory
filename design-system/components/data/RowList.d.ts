import type { ReactNode } from "react";

export interface RowItem {
  id: string;
  name: string;
  /** The location path, shown small beneath the name. This is the answer to "where is the drill". */
  path?: string;
  photo?: string;
  icon?: string;
  /** Photo captured offline and not uploaded — the thumbnail gets a dashed edge. */
  pendingPhoto?: boolean;
  status?: string;
  statusNode?: ReactNode;
  trailing?: ReactNode;
}

/** The compact-width rendering of the same rows the Table shows. Two lines — name, then path and
 *  status — because a horizontally scrolled table on a phone is a table nobody reads. The whole
 *  row is one 44px target. */
export interface RowListProps {
  items: RowItem[];
  selected?: string[];
  selectable?: boolean;
  onToggle?: (id: string) => void;
  onOpen?: (id: string) => void;
}
export declare function RowList(props: RowListProps): JSX.Element;

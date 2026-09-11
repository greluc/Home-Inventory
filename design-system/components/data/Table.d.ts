import type { ReactNode } from "react";

export interface Column<R = any> {
  key: string;
  label: string;
  /** Right-aligns and switches on tabular figures. */
  numeric?: boolean;
  /** IBM Plex Mono with slashed zero — public codes, serials, UUIDs. */
  mono?: boolean;
  sortable?: boolean;
  width?: string;
  render?: (row: R) => ReactNode;
}

/**
 * The dense list, table view. Sticky head, row height from the density token, hit targets from
 * the pointer token, and a name column that truncates with ellipsis rather than wrapping —
 * "Akku-Schlagbohrschrauber-Set mit Ladegerät" must not make its row two lines tall in a
 * thousand-row list. The full name is in the row's title attribute and in the detail pane.
 * Below 840px do not render this: switch to the two-line RowList instead.
 */
export interface TableProps<R = any> {
  columns: Column<R>[];
  rows: R[];
  sort?: { key: string; dir: "ascending" | "descending" };
  onSort?: (key: string) => void;
  selectable?: boolean;
  selected?: string[];
  onToggle?: (key: string) => void;
  rowKey?: (row: R) => string;
  /** Visually hidden caption. Required for screen readers on a data table. */
  caption?: string;
}
export declare function Table<R = any>(props: TableProps<R>): JSX.Element;

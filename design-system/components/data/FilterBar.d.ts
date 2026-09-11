import type { ReactNode } from "react";
export interface Facet { key: string; label: string; count?: number; icon?: string; active?: boolean }
/** Faceted filters above the list. Active facets carry the accent tint AND an x — the state is
 *  never colour alone. Scrolls horizontally below 600px; wraps above it. Counts are the facet's
 *  result count, so the user can see a dead end before taking it. */
export interface FilterBarProps { facets: Facet[]; onToggle?: (key: string) => void; trailing?: ReactNode }
export declare function FilterBar(props: FilterBarProps): JSX.Element;
export declare function BulkBar(props: {
  count: number;
  children: ReactNode;
  /** The word after the count, e.g. "selected". English default; the client translates (REQ-NFR-032). */
  selectedLabel?: string;
}): JSX.Element;

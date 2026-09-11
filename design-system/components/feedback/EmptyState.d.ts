import type { ReactNode } from "react";
/** Says what is missing and offers the one action that fixes it. No illustration, no mascot —
 *  a Lucide glyph at 32px in --text-disabled. Distinct wording is required for the four cases:
 *  empty (nothing here yet), no results (your filter matched nothing), error (it broke),
 *  restricted (not for you). */
export interface EmptyStateProps {
  icon?: string;
  title: string;
  /** One sentence. Say what to do, not that something is empty. */
  children?: ReactNode;
  action?: ReactNode;
  /** Tighter padding for an empty pane rather than an empty page. */
  inline?: boolean;
}
export declare function EmptyState(props: EmptyStateProps): JSX.Element;

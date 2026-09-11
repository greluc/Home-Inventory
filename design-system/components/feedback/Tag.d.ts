import type { HTMLAttributes, ReactNode } from "react";
/** A label the user assigned. Square, quiet, capped at 14rem and truncated — a tag is a filter handle, not prose. */
export interface TagProps extends HTMLAttributes<HTMLSpanElement> {
  onRemove?: () => void;
  removeLabel?: string;
  children: ReactNode;
}
export declare function Tag(props: TagProps): JSX.Element;

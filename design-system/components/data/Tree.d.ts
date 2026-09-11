import type { ReactNode } from "react";

export interface TreeNode {
  id: string;
  name: string;
  /** Drives the leading glyph: building → room → shelf → box → compartment. Depth is arbitrary; the icon is what tells you what kind of thing this is. */
  kind?: "building" | "room" | "shelf" | "box" | "compartment" | "area";
  count?: number;
  children?: TreeNode[];
  trailing?: ReactNode;
}

/** Arbitrarily nested storage locations. Indent is 16px per level and the twisty is its own
 *  target, separate from the row, so tapping the label navigates and tapping the twisty expands —
 *  the single most common tree mistake on touch. Labels truncate; they never wrap, because a
 *  wrapping tree loses its indent reading. */
export interface TreeProps {
  nodes: TreeNode[];
  openIds?: string[];
  currentId?: string;
  onToggle?: (id: string) => void;
  onSelect?: (id: string) => void;
  label?: string;
}
export declare function Tree(props: TreeProps): JSX.Element;

/** reference — a pointer to another item or to a location. Shows the leaf name on the first line and the breadcrumb path beneath, truncated from the left of the leaf outward, because the last two segments are what identify a shelf. Opens the tree picker as a drawer on compact width and as a modal from medium up. */
export interface ReferenceInputProps {
  kind?: "location" | "item";
  /** Ancestor segments, root first. Rendered as "Cellar › Shelf B › Box 4". */
  path?: string[];
  label?: string;
  placeholder?: string;
  readOnly?: boolean;
  disabled?: boolean;
  onPick?: () => void;
  onClear?: () => void;
  /** Accessible name of the clear button. English default; the client passes the translated string (REQ-NFR-032). */
  clearLabel?: string;
}
export declare function ReferenceInput(props: ReferenceInputProps): JSX.Element;

/** multi-enum. Chips inside one well; each chip has its own 44px remove target on touch. Chips wrap — the well grows downward rather than scrolling sideways, because a horizontally scrolled value list hides values. */
export interface TagInputProps {
  values?: string[];
  placeholder?: string;
  readOnly?: boolean;
  disabled?: boolean;
  onRemove?: (value: string) => void;
  id?: string;
}
export declare function TagInput(props: TagInputProps): JSX.Element;

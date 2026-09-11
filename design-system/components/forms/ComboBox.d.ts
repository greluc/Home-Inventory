export interface ComboOption { value: string; label: string; icon?: string; meta?: string }

/** enum with a long list, and the type-ahead half of reference. Filters as you type and highlights the matched run. Options are 44px tall on a coarse pointer. */
export interface ComboBoxProps {
  options: (ComboOption | string)[];
  value?: string;
  placeholder?: string;
  /** Shown when nothing matches. Distinct from the empty state of the field itself. */
  emptyText?: string;
  onSelect?: (value: string) => void;
  disabled?: boolean;
  id: string;
}
export declare function ComboBox(props: ComboBoxProps): JSX.Element;

export interface AttachedFile {
  name: string;
  size: string;
  kind?: "pdf" | "img";
  /** Captured offline and not uploaded yet. Renders the pending chip — a normal state, never an error. */
  pending?: boolean;
}

/** file — receipts, manuals, warranty documents. The drop zone is also a 44px tap target, because dragging is a desktop-only gesture and must not be the only way in. */
export interface FileInputProps {
  files?: AttachedFile[];
  hint?: string;
  readOnly?: boolean;
  disabled?: boolean;
  onRemove?: (file: AttachedFile) => void;
  /** User-visible strings. English defaults; the client passes the translated text from its
   *  resource bundle, because no display text is hard-coded in a component (REQ-NFR-032). */
  localOnlyLabel?: string;
  emptyLabel?: string;
  dropLabel?: string;
  removeLabel?: (name: string) => string;
}
export declare function FileInput(props: FileInputProps): JSX.Element;

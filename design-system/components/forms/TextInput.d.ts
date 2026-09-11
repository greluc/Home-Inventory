import type { InputHTMLAttributes } from "react";

/** Covers six of the sixteen generated field types: text, multiline, integer, decimal, url, email. */
export interface TextInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "value"> {
  type?: "text" | "multiline" | "integer" | "decimal" | "url" | "email";
  value?: string | number;
  /** Read-only is a different rendering, not a greyed input: no well, a dashed baseline, and an em dash when empty. */
  readOnly?: boolean;
  /** What a read-only empty value shows. Default "—". Never leave a blank row — empty must be legible as empty. */
  emptyText?: string;
  /** Serial numbers, public codes, licence keys: IBM Plex Mono with slashed zero and 0.04em tracking. */
  mono?: boolean;
  rows?: number;
}
export declare function TextInput(props: TextInputProps): JSX.Element;

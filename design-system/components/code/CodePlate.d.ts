/**
 * A machine-readable code with its human-readable caption, on paper-white ground in BOTH themes.
 * This is the system's one inviolable exception and it is deliberate, not an oversight: an
 * inverted QR is not reliably readable by every scanner, and the caption exists because a label
 * must still be useful when the scan fails — you can read and type 7Q2M-4X9K-D2F, you cannot read
 * and type a UUID.
 * The caption is IBM Plex Mono with slashed zero; the public code alphabet is Crockford Base32,
 * which has already removed I, L, O and U.
 */
export interface CodePlateProps {
  /** The public code, hyphenated for reading: 7Q2M-4X9K-D2F. */
  code?: string;
  size?: number;
  /** QR version 1 is 21 modules and is what a 9-character code produces. */
  modules?: number;
  caption?: boolean;
  inline?: boolean;
  symbology?: "QR" | "DataMatrix" | "Code128";
}
export declare function CodePlate(props: CodePlateProps): JSX.Element;

export interface LabelMedia {
  vendor: string; articleNumber: string; width: number; height: number;
  /** false means at least one dimension was derived rather than taken from a data sheet. The preview then demands a calibration sheet before any bulk print — from the fourth column onwards a guessed pitch diverges visibly. */
  verified?: boolean;
}
export interface LabelLine { text: string; size?: number; bold?: boolean; wrap?: boolean }

/**
 * To-scale preview of a printed label. Keeps its paper-white ground in dark mode — it is a
 * preview of something printed, not a UI surface — and says so with the "Druckvorschau" strap
 * above it, so the exception reads as intentional.
 * Draws the quiet zone, because too small a quiet zone is the commonest reason a printed QR will
 * not read, and warns below 0.33 mm module size and on unverified media.
 */
export interface LabelPreviewProps {
  media?: LabelMedia;
  /** Screen magnification. 1 = actual size at 96dpi. */
  scale?: number;
  lines?: LabelLine[];
  code?: string;
  moduleMm?: number;
  showQuiet?: boolean;
}
export declare function LabelPreview(props: LabelPreviewProps): JSX.Element;

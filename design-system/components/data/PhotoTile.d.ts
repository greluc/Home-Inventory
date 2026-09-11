import type { ReactNode } from "react";
/** The photo-grid view of the item list. The tile caps at 220px wide, so a wide screen gets more
 *  tiles rather than bigger ones. Names clamp to two lines — that is enough for
 *  "Cordless impact driver GSB 18V-55 Professional" and stops a long compound from making one tile taller
 *  than the rest of the row. Items with no photo get a Lucide glyph on the placeholder surface,
 *  never a stretched or letterboxed stand-in image. */
export interface PhotoTileProps {
  item: { id: string; name: string; path?: string; photo?: string; icon?: string; badges?: ReactNode };
  selectable?: boolean;
  selected?: boolean;
  onToggle?: (id: string) => void;
  onOpen?: (id: string) => void;
  /** Accessible name of the selection checkbox, built from the item name. English default;
   *  the client passes a translated builder (REQ-NFR-032). */
  selectLabel?: (name: string) => string;
}
export declare function PhotoTile(props: PhotoTileProps): JSX.Element;
export declare function PhotoGrid(props: { children: ReactNode }): JSX.Element;

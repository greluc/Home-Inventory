import type { SVGProps } from "react";

export type IconSize = 12 | 14 | 16 | 20 | 24 | 32 | 40;

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, "children"> {
  /** Lucide icon name, kebab-case, exactly as the file is named in assets/icons (e.g. "scan-line"). */
  name: string;
  /** Rendered box. Picks the matching stroke-width token; do not set stroke-width by hand.
   *  12 and 14 exist only for glyphs set inside 12px text — chips, badges, sort arrows, crumb separators. */
  size?: IconSize;
  /** Accessible name. Omit for decorative icons that sit beside their own label — the icon is then aria-hidden. */
  label?: string;
}

export declare function Icon(props: IconProps): JSX.Element;

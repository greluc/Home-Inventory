/** Loading placeholder shaped like the content that is coming — row-height bars for a list, a
 *  square for a photo. A calm opacity pulse, never a shimmer sweep, and nothing at all under
 *  prefers-reduced-motion. Use only where the layout is known in advance; otherwise use a
 *  determinate Progress. */
export interface SkeletonProps { w?: string | number; h?: string | number; radius?: string }
export declare function Skeleton(props: SkeletonProps): JSX.Element;
export declare function SkeletonRows(props: { rows?: number }): JSX.Element;

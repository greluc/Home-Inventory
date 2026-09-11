/** The location path — Keller › Regal B › Kiste 4 › Fach 2. Locations nest arbitrarily, so the
 *  crumb collapses from the MIDDLE (first › … › last two) rather than truncating the end: the
 *  last two segments are the ones that identify where something actually is. The ellipsis is a
 *  button that reveals the hidden levels, not decoration. */
export interface Crumb { id: string; name: string }
export interface BreadcrumbProps { path: Crumb[]; collapseFrom?: number; onNavigate?: (id: string) => void; label?: string }
export declare function Breadcrumb(props: BreadcrumbProps): JSX.Element;

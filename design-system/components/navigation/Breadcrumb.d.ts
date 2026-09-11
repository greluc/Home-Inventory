/** The location path — Cellar › Shelf B › Box 4 › Compartment 2. Locations nest arbitrarily, so the
 *  crumb collapses from the MIDDLE (first › … › last two) rather than truncating the end: the
 *  last two segments are the ones that identify where something actually is. The ellipsis is a
 *  button that reveals the hidden levels, not decoration. */
export interface Crumb { id: string; name: string }
export interface BreadcrumbProps {
  path: Crumb[];
  collapseFrom?: number;
  onNavigate?: (id: string) => void;
  /** Accessible name of the nav landmark. English default; the client translates (REQ-NFR-032). */
  label?: string;
  /** Accessible name of the ellipsis button that reveals the collapsed levels. */
  expandLabel?: string;
}
export declare function Breadcrumb(props: BreadcrumbProps): JSX.Element;

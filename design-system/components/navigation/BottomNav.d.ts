/** Compact-width primary navigation, inside the thumb zone. Five slots at most; the middle one is
 *  `action: true` and renders the accent scan button, because scanning is what people came to do.
 *  From 840px this is replaced by NavRail on the left — same items, same order, same icons. */
export interface NavItem { key: string; label: string; icon: string; action?: boolean }
export interface BottomNavProps { items: NavItem[]; value?: string; onChange?: (key: string) => void; label?: string }
export declare function BottomNav(props: BottomNavProps): JSX.Element;
export declare function NavRail(props: BottomNavProps): JSX.Element;

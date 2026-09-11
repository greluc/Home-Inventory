/** Switches between views of the same object. Selected state uses three signals at once —
 *  accent colour, semibold weight and a 2px underline — because Lucide has no filled twin to
 *  swap to and colour alone would fail 1.4.1. Scrolls horizontally rather than wrapping. */
export interface TabItem { key: string; label: string; icon?: string; count?: number }
export interface TabsProps { tabs: TabItem[]; value?: string; onChange?: (key: string) => void; label?: string }
export declare function Tabs(props: TabsProps): JSX.Element;

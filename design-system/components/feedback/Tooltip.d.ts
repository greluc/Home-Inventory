import type { ReactNode } from "react";
/** Supplementary detail for a fine pointer only. It is hidden entirely under a coarse pointer
 *  (media hover:none), so it may never be the only place a function or a required explanation
 *  lives — put that in help text or a visible label. */
export interface TooltipProps { content: ReactNode; placement?: "top" | "bottom"; children: ReactNode }
export declare function Tooltip(props: TooltipProps): JSX.Element;

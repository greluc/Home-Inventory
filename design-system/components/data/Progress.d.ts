/** Determinate wherever a number exists — uploads, print jobs, initial seeding, stocktake coverage.
 *  A spinner that could have been a percentage is a lie about how long this will take.
 *  Indeterminate is reserved for a request with no measurable length. */
export interface ProgressProps { value?: number; max?: number; label?: string; detail?: string; indeterminate?: boolean }
export declare function Progress(props: ProgressProps): JSX.Element;

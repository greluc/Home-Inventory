export type StatusKind =
  | "offline" | "pending" | "conflict" | "degraded" | "restricted" | "unassigned"
  | "success" | "warning" | "danger" | "neutral";

/**
 * The first-class states of this product, drawn as one component so they are impossible to
 * invent twice. Every chip is icon + word: colour never carries the meaning on its own, and
 * "offline" is deliberately hueless because it is normal, not an alarm.
 */
export interface StatusChipProps {
  status?: StatusKind;
  /** Overrides the German default label. Keep it short — this sits in a 32px table row. */
  label?: string;
  /** Appended after a middot, tabular. "Konflikt · 3". */
  count?: number;
  size?: 12 | 14 | 16;
}
export declare function StatusChip(props: StatusChipProps): JSX.Element;
export declare const STATUS: Record<StatusKind, { icon: string; label: string }>;

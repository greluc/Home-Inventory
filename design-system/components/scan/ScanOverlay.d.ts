export type ScanResult = "success" | "duplicate" | "unknown" | "unassigned";
export type ScanMode = "LOOKUP" | "ASSIGN" | "MOVE" | "STOCKTAKE" | "CAPTURE";

/**
 * Full-screen camera scanner. Designed for one hand, in the dark, at arm's length:
 * - every interactive control sits in the bottom band — the 44px mode pills and the 48px
 *   toggles occupy the last ~120px, with the result banner above them (read, not tapped).
 *   Nothing at the top of the screen is required to complete a scan;
 * - the framing indicator is four corners, never a box, because a box covers the code;
 * - the four results are distinguished by icon, word, border colour AND border style, so the
 *   phone can be glanced at rather than read;
 * - feedback is tripled — visual banner, tone, haptic pulse — because the user is holding a box;
 * - a duplicate inside the 2s debounce window is its own result, not silence, otherwise the user
 *   scans the same label three times wondering why nothing happened.
 * The overlay is exempt from the light theme: it is a viewfinder, and a white viewfinder in a
 * cellar is a torch pointed at your own face.
 */
export interface ScanOverlayProps {
  mode?: ScanMode;
  onMode?: (mode: ScanMode) => void;
  result?: ScanResult;
  /** The public code, rendered in mono with slashed zero. */
  code?: string;
  /** One short line under the result — the item name, the target location, the reason. */
  detail?: string;
  torch?: boolean;
  onTorch?: () => void;
  /** Continuous mode: code after code without leaving the screen. */
  continuous?: boolean;
  onContinuous?: () => void;
  sound?: boolean;
  haptics?: boolean;
  /** Running count for continuous mode. */
  tally?: number;
  onClose?: () => void;
  /** The mode's confirming action, e.g. "4 Artikel ablegen" in MOVE. */
  primaryAction?: React.ReactNode;
}
export declare function ScanOverlay(props: ScanOverlayProps): JSX.Element;

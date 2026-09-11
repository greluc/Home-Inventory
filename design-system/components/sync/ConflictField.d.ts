export interface ConflictFieldModel {
  key: string;
  label: string;
  /** The common ancestor — the version the device went offline with. Without it only "last writer wins" remains, and with it silent data loss. */
  base?: string;
  /** This device's value. */
  mine?: string;
  /** The server's value. While the conflict is open, this one is the valid one. */
  theirs?: string;
  chosen?: "base" | "mine" | "theirs";
  baseSub?: string; mineSub?: string; theirsSub?: string;
  /** Set on fields the server merged by rule (set union, quantity delta addition, append-only lists) so the user sees WHY they are not being asked. */
  auto?: string;
}

/**
 * One field of a conflict, decided on its own. This is the surface users meet at the worst
 * moment, so the layout is the design problem: three versions side by side is a desktop idea.
 * Below 840px each field is a stack of three full-width option cards — the whole card is the
 * target, not a 20px radio — with a "3 von 7 Feldern entschieden" counter above. From 840px the
 * same three options become a row. Nothing is ever auto-chosen for the user; the discarded
 * version stays retrievable for 90 days.
 */
export interface ConflictFieldProps {
  field: ConflictFieldModel;
  onChoose: (key: string, side: "base" | "mine" | "theirs") => void;
}
export declare function ConflictField(props: ConflictFieldProps): JSX.Element;
export declare function ConflictProgress(props: { resolved?: number; total?: number }): JSX.Element;

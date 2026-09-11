/** secret — a licence key or password field. Hidden by default and revealed only after a second factor.
 *  It NEVER renders a row of dots: the number of dots tells a shoulder-surfer the length of the secret.
 *  The PWA does not store sensitive fields in IndexedDB, so offline this control says so rather than failing. */
export interface SecretInputProps {
  value?: string;
  revealed?: boolean;
  /** false on the web client: IndexedDB is not encrypted, so the value is fetched on demand and the control says "nicht auf diesem Gerät gespeichert". */
  storedLocally?: boolean;
  onReveal?: () => void;
  onHide?: () => void;
  disabled?: boolean;
}
export declare function SecretInput(props: SecretInputProps): JSX.Element;

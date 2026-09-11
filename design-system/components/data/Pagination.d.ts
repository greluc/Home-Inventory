/** Paging for the dense list. Used at desktop widths; on compact width the list loads more on scroll
 *  instead, because a 44px page-number row eats a phone screen. The total is always shown — it is
 *  the number people actually want.
 *
 *  Every user-visible string is a prop with an English default (REQ-NFR-032): the client passes the
 *  translated text from its resource bundle, and `locale` drives the thousands separator through
 *  `Number.prototype.toLocaleString`. Omitting `locale` uses the browser's. */
export interface PaginationProps {
  page?: number;
  pages?: number;
  total?: number;
  onChange?: (page: number) => void;
  /** BCP 47 tag for the total's number formatting. Omit for the browser default. */
  locale?: string;
  navLabel?: string;
  previousLabel?: string;
  nextLabel?: string;
  /** The noun after the total, e.g. "items" / "Artikel". */
  totalLabel?: string;
}
export declare function Pagination(props: PaginationProps): JSX.Element;

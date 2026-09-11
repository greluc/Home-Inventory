/** Paging for the dense list. Used at desktop widths; on compact width the list loads more on scroll
 *  instead, because a 44px page-number row eats a phone screen. The total is always shown — it is
 *  the number people actually want. */
export interface PaginationProps { page?: number; pages?: number; total?: number; onChange?: (page: number) => void }
export declare function Pagination(props: PaginationProps): JSX.Element;

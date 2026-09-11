import type { HTMLAttributes, ReactNode } from "react";

export interface FormSectionProps extends HTMLAttributes<HTMLElement> {
  title: string;
  /** Field count, shown right-aligned. On a 20-field type this is what tells you a collapsed group still has content. */
  count?: number;
  defaultOpen?: boolean;
  /** Sections stay open on compact width for a short type; a 4-group type opens only the first. */
  collapsible?: boolean;
  children: ReactNode;
}
export declare function FormSection(props: FormSectionProps): JSX.Element;

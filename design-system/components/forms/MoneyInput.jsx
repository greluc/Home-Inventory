import React from "react";

export function MoneyInput({ amount, currency = "EUR", currencies = ["EUR", "CHF", "USD", "GBP"], readOnly, disabled, locale, currencyLabel = "Currency", ...rest }) {
  if (readOnly) {
    const n = amount == null || amount === "" ? null : Number(amount);
    return (
      <div className={"hi-readonly " + (n == null ? "hi-readonly--empty" : "")} style={{ fontVariantNumeric: "tabular-nums" }}>
        {n == null ? "—" : new Intl.NumberFormat(locale, { style: "currency", currency }).format(n)}
      </div>
    );
  }
  return (
    <div className="hi-combo" data-disabled={disabled || undefined}>
      <input type="text" inputMode="decimal" className="hi-combo__num" defaultValue={amount} disabled={disabled} {...rest} />
      <span className="hi-combo__unit">
        <select defaultValue={currency} disabled={disabled} aria-label={currencyLabel}>
          {currencies.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
      </span>
    </div>
  );
}

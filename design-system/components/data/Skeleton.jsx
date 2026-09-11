import React from "react";
export function Skeleton({ w = "100%", h = 12, radius, className = "", style, ...rest }) {
  return <span className={"hi-skel " + className} style={{ display: "block", width: w, height: h, borderRadius: radius, ...style }} aria-hidden="true" {...rest} />;
}
export function SkeletonRows({ rows = 6 }) {
  return (
    <div aria-busy="true" aria-live="polite">
      <span className="hi-sr">Wird geladen…</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="hi-row" style={{ height: "var(--row-h)", padding: "0 var(--cell-pad-x)", gap: "var(--space-150)" }}>
          <Skeleton w="calc(var(--row-h) - 8px)" h="calc(var(--row-h) - 8px)" radius="var(--radius-1)" />
          <Skeleton w={`${38 + ((i * 13) % 28)}%`} h={10} />
          <Skeleton w="12%" h={10} style={{ marginLeft: "auto" }} />
        </div>
      ))}
    </div>
  );
}

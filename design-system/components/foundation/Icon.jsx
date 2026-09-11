import React from "react";

/* Lucide SVGs are fetched once from assets/icons and cached. We convert the
   parsed DOM into React elements rather than using dangerouslySetInnerHTML,
   which the production client forbids by lint rule.
   In the shipped app this component reads the same files out of the
   lucide-static sprite; the markup and the stroke tokens are identical. */
const cache = new Map();
const pending = new Map();

function resolveBase() {
  if (typeof document === "undefined") return "assets/icons/";
  const link = Array.from(document.querySelectorAll('link[rel="stylesheet"]'))
    .map((l) => l.getAttribute("href") || "")
    .find((h) => /styles\.css(\?|$)/.test(h));
  if (link) return link.replace(/styles\.css.*$/, "") + "assets/icons/";
  return "assets/icons/";
}

function toReact(node, key) {
  if (node.nodeType !== 1) return null;
  const props = { key };
  for (const a of node.attributes) {
    if (a.name === "stroke-width" || a.name === "stroke" || a.name === "fill") continue;
    const name = a.name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    props[name === "class" ? "className" : name] = a.value;
  }
  const kids = Array.from(node.childNodes).map((n, i) => toReact(n, i)).filter(Boolean);
  return React.createElement(node.tagName, props, kids.length ? kids : undefined);
}

export function Icon({ name, size = 20, label, className = "", ...rest }) {
  const [children, setChildren] = React.useState(() => cache.get(name) || null);

  React.useEffect(() => {
    let alive = true;
    if (cache.has(name)) { setChildren(cache.get(name)); return; }
    const url = resolveBase() + name + ".svg";
    let p = pending.get(url);
    if (!p) {
      p = fetch(url).then((r) => (r.ok ? r.text() : Promise.reject(new Error(url))));
      pending.set(url, p);
    }
    p.then((txt) => {
      const doc = new DOMParser().parseFromString(txt, "image/svg+xml");
      const svg = doc.querySelector("svg");
      const nodes = svg ? Array.from(svg.childNodes).map((n, i) => toReact(n, i)).filter(Boolean) : [];
      cache.set(name, nodes);
      if (alive) setChildren(nodes);
    }).catch(() => { cache.set(name, []); if (alive) setChildren([]); });
    return () => { alive = false; };
  }, [name]);

  const hidden = !label;
  return (
    <svg
      viewBox="0 0 24 24"
      className={`hi-icon hi-icon--${size} ${className}`}
      data-loading={children ? undefined : "true"}
      aria-hidden={hidden ? "true" : undefined}
      role={hidden ? undefined : "img"}
      aria-label={label || undefined}
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

import React from "react";
import { Icon } from "../foundation/Icon.jsx";

const SIDE = {
  base:   { cls: "base",   icon: "history",   label: "Gemeinsamer Stand" },
  mine:   { cls: "mine",   icon: "smartphone",label: "Dieses Gerät" },
  theirs: { cls: "theirs", icon: "server",    label: "Server" },
};

function Option({ side, value, sub, chosen, onChoose, disabled }) {
  const s = SIDE[side];
  return (
    <button type="button" className={`hi-conflict__opt hi-conflict__opt--${s.cls}`}
            aria-pressed={chosen} disabled={disabled} onClick={onChoose}>
      <span className="hi-conflict__optmeta">
        <Icon name={s.icon === "history" ? "undo-2" : s.icon} size={14} />
        <span>{s.label}</span>
        {chosen ? <Icon name="check" size={14} style={{ marginLeft: "auto" }} /> : null}
      </span>
      <span className="hi-conflict__optval">{value == null || value === "" ? "— leer —" : value}</span>
      {sub ? <span className="hi-conflict__optsub">{sub}</span> : null}
    </button>
  );
}

export function ConflictField({ field, onChoose }) {
  const { key, label, base, mine, theirs, chosen, auto, baseSub, mineSub, theirsSub } = field;
  return (
    <section className="hi-conflict__field" data-resolved={chosen ? "true" : undefined}>
      <header className="hi-conflict__label">
        <Icon name={chosen ? "circle-check" : "git-merge"} size={16}
              style={{ color: chosen ? "var(--state-success-fg)" : "var(--state-conflict-fg)" }} />
        <span>{label}</span>
      </header>
      <div className="hi-conflict__options">
        <Option side="base"   value={base}   sub={baseSub}   chosen={chosen === "base"}   onChoose={() => onChoose(key, "base")} />
        <Option side="mine"   value={mine}   sub={mineSub}   chosen={chosen === "mine"}   onChoose={() => onChoose(key, "mine")} />
        <Option side="theirs" value={theirs} sub={theirsSub} chosen={chosen === "theirs"} onChoose={() => onChoose(key, "theirs")} />
      </div>
      {auto ? <p className="hi-conflict__auto"><Icon name="check-check" size={14} />{auto}</p> : null}
    </section>
  );
}

export function ConflictProgress({ resolved = 0, total = 0 }) {
  return (
    <span className="hi-conflict__progress">
      <Icon name="git-merge" size={16} style={{ color: "var(--state-conflict-fg)" }} />
      <span>{resolved} von {total} Feldern entschieden</span>
    </span>
  );
}

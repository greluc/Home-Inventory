const DS = window.HomeInventoryDesignSystem_e14f83;
const { Icon, IconButton, Button, FieldRenderer, ActionBar, Select, Field, InlineMessage, ScanOverlay,
        ConflictField, ConflictProgress, Modal, StatusChip, Card, Tabs, Toast, ToastStack, Drawer, TextInput } = DS;

function FormScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const split = cls === "expanded" || cls === "large";
  const long = state.formType === "tool";
  const schema = long ? window.TYPE_TOOL : window.TYPE_BOOK;
  const withError = React.useMemo(() => {
    if (!state.showErrors) return schema;
    const mark = (fl) => fl.map((x) => x.key === "t_price" || x.key === "b_year"
      ? { ...x, error: "Wert muss größer als 0 sein" } : x);
    return long ? schema.map((g) => ({ ...g, fields: mark(g.fields) })) : mark(schema);
  }, [schema, state.showErrors, long]);
  return (
    <div className="stack">
      <div className="toolbar">
        <Select options={[{ value: "book", label: "Typ: Buch — 6 Felder" }, { value: "tool", label: "Typ: Elektrowerkzeug — 20 Felder, 4 Gruppen" }]}
                value={state.formType} onChange={(e) => set({ formType: e.target.value })} />
        <span style={{ flex: 1 }} />
        <Button variant="ghost" icon="triangle-alert" onClick={() => set({ showErrors: !state.showErrors })}>
          {state.showErrors ? "Fehler ausblenden" : "Validierung zeigen"}
        </Button>
      </div>
      <InlineMessage tone="info" title="Aus einer Typdefinition erzeugt">
        Diese Installation definiert ihre Feldtypen selbst. Es gibt kein festes Artikelformular — nur diesen Renderer.
        {split ? ' Ab 840 px zweispaltig; die Labelspalte ist auf «Mindesthaltbarkeitsdatum» bemessen, nicht auf «Datum».' : ' Unter 840 px immer einspaltig.'}
      </InlineMessage>
      <FieldRenderer split={split} schema={withError} />
      {compact
        ? <ActionBar sticky stack><Button variant="ghost" full>Abbrechen</Button><Button variant="primary" icon="save" full>Speichern</Button></ActionBar>
        : <ActionBar><Button variant="ghost">Abbrechen</Button><Button variant="primary" icon="save">Speichern</Button></ActionBar>}
    </div>
  );
}

const SCAN_CASES = [
  { key: "idle", result: undefined, code: undefined, detail: undefined, label: "Bereit" },
  { key: "success", result: "success", code: "7Q2M-4X9K-D2F", detail: "Akku-Bohrschrauber GSB 18V-55 → Kiste 4", label: "Erfolg" },
  { key: "duplicate", result: "duplicate", code: "7Q2M-4X9K-D2F", detail: "vor 2 Sekunden schon erfasst — übersprungen", label: "Doppelt (entprellt)" },
  { key: "unknown", result: "unknown", code: "4006381333931", detail: "EAN gehört zu keinem Artikel · als neuen Artikel anlegen?", label: "Unbekannt" },
  { key: "unassigned", result: "unassigned", code: "M1R-6HJ-4TN", detail: "Etikett gedruckt am 03.09., noch keinem Artikel zugeordnet", label: "Nicht zugeordnet" },
];

function ScannerScreen({ cls, state, set }) {
  const c = SCAN_CASES.find((x) => x.key === state.scanCase) || SCAN_CASES[0];
  const phone = cls === "compact";
  return (
    <div className="stack">
      <div className="toolbar">
        <span className="kitseg">
          {SCAN_CASES.map((s) => <button key={s.key} type="button" aria-pressed={state.scanCase === s.key} onClick={() => set({ scanCase: s.key })}>{s.label}</button>)}
        </span>
      </div>
      <div className={"scanwrap" + (phone ? " scanwrap--full" : "")}>
        <ScanOverlay mode={state.scanMode} onMode={(m) => set({ scanMode: m })} result={c.result} code={c.code} detail={c.detail}
          torch={state.torch} onTorch={() => set({ torch: !state.torch })} continuous={state.cont}
          onContinuous={() => set({ cont: !state.cont })} tally={state.cont ? 4 : undefined}
          primaryAction={state.scanMode === "MOVE" ? (
            <button className="hi-scan__ctl hi-scan__ctl--wide" style={{ marginLeft: "auto" }}>
              <Icon name="check" size={20} />4 Artikel ablegen
            </button>) : null} />
      </div>
      {phone ? null : <div className="notecols">
        <Card title="Rückmeldung dreifach"><ul className="notes">
          <li><Icon name="circle-check" size={14} /> Visuell: Rahmenecken färben sich, Banner in 16 px fett</li>
          <li><Icon name="volume-2" size={14} /> Hörbar: kurzer Ton je Ergebnis, vier unterscheidbare Töne</li>
          <li><Icon name="vibrate" size={14} /> Haptisch: 1 Puls Erfolg, 2 kurze doppelt, langer Puls Fehler</li>
        </ul></Card>
        <Card title="Warum es so aussieht"><ul className="notes">
          <li><Icon name="crosshair" size={14} /> Vier Ecken statt Kasten — ein Kasten verdeckt den Code</li>
          <li><Icon name="hand" size={14} /> Alles Bedienbare liegt in den unteren 140 px</li>
          <li><Icon name="moon" size={14} /> Der Sucher ignoriert das helle Design — im Keller wäre Weiß eine Blendlampe</li>
        </ul></Card>
      </div>}
    </div>
  );
}

function ConflictScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const decidable = window.CONFLICT.filter((c) => !c.auto);
  const resolved = Object.keys(state.conflict).length;
  return (
    <div className="stack" style={{ maxWidth: compact ? "none" : "72rem" }}>
      <InlineMessage tone="conflict" title="Zwei Geräte haben diesen Artikel offline geändert">
        Bis zur Entscheidung gilt die Server-Version. Nichts geht verloren: die verworfene Fassung bleibt 90 Tage abrufbar.
      </InlineMessage>
      <div className="toolbar">
        <ConflictProgress resolved={resolved} total={decidable.length} />
        <span style={{ flex: 1 }} />
        <Button variant="ghost" icon="undo-2" onClick={() => set({ conflict: {} })}>Zurücksetzen</Button>
      </div>
      <div className="hi-conflict">
        {window.CONFLICT.map((c) => (
          <ConflictField key={c.key} field={{ ...c, chosen: state.conflict[c.key] }}
            onChoose={(k, s) => set({ conflict: { ...state.conflict, [k]: s } })} />
        ))}
      </div>
      {compact
        ? <ActionBar sticky stack>
            <Button variant="ghost" full>Später entscheiden</Button>
            <Button variant="primary" icon="check" full disabled={resolved < decidable.length}>
              {resolved < decidable.length ? `Noch ${decidable.length - resolved} Felder` : "Entscheidung übernehmen"}
            </Button>
          </ActionBar>
        : <ActionBar>
            <Button variant="ghost">Später entscheiden</Button>
            <Button variant="primary" icon="check" disabled={resolved < decidable.length}>Entscheidung übernehmen</Button>
          </ActionBar>}
    </div>
  );
}

Object.assign(window, { FormScreen, ScannerScreen, ConflictScreen, SCAN_CASES });

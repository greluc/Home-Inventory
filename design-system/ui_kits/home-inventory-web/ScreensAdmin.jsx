const DS = window.HomeInventoryDesignSystem_e14f83;
const { Icon, IconButton, Button, Card, Table, RowList, Tabs, Select, Field, TextInput, Checkbox, Switch,
        StatusChip, Badge, Tag, InlineMessage, EmptyState, Modal, Drawer, Progress, LabelPreview, CodePlate,
        DeviceRow, FilterBar, ActionBar, ComboBox, Avatar, Pagination, Tooltip, SecretInput, Breadcrumb } = DS;
const eur = window.eur;

function SearchScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const hits = window.ITEMS.slice(0, 5);
  return (
    <div className="stack">
      <div className="searchbar">
        <Icon name="search" size={18} />
        <input placeholder="Bezeichnung, Code, Seriennummer, Lagerort…" defaultValue="bohr" aria-label="Suche" />
        <IconButton icon="scan-line" label="Code scannen" />
      </div>
      {state.degraded ? <InlineMessage tone="degraded" title="Suche läuft im Notbetrieb">
        Der Volltextindex ist nicht erreichbar. Es wird auf Präfix- und Teilstringsuche der Datenbank zurückgegriffen — Tippfehler und Wortformen werden nicht gefunden.
      </InlineMessage> : null}
      <div className={compact ? "stack" : "split split--facets"}>
        {!compact ? (
          <aside className="pane pane--facets hi-scroll-thin">
            <div className="pane__head"><span className="pane__title">Gespeicherte Suchen</span></div>
            <ul className="savedlist">
              {window.SAVED_SEARCHES.map((s) => (
                <li key={s.name}><button type="button" className="saved"><Icon name={s.icon} size={16} /><span className="hi-truncate">{s.name}</span><span className="saved__n">{s.count}</span></button></li>
              ))}
            </ul>
            <div className="pane__head"><span className="pane__title">Filter</span></div>
            <div className="facetgroup">
              <span className="facetgroup__t">Lagerort</span>
              <Checkbox label="Keller" hint="412" defaultChecked /><Checkbox label="Werkstatt" hint="306" /><Checkbox label="Garage" hint="296" /><Checkbox label="Dachboden" hint="270" />
              <span className="facetgroup__t">Typ</span>
              <Checkbox label="Elektrowerkzeug" hint="96" /><Checkbox label="Verbrauchsmaterial" hint="141" /><Checkbox label="Umzugskarton" hint="64" />
            </div>
          </aside>
        ) : <FilterBar facets={[{ key: "k", label: "Keller", count: 412, active: true }, { key: "t", label: "Elektrowerkzeug", count: 96 }, { key: "s", label: "Gespeichert", count: 4, icon: "bookmark" }]} />}
        <div className="pane pane--main stack">
          <div className="toolbar"><span className="muted">96 Treffer für <strong>bohr</strong> · sortiert nach Relevanz</span><span style={{ flex: 1 }} />
            <Button variant="ghost" icon="save">Suche speichern</Button></div>
          {compact ? <RowList items={hits} onOpen={(id) => set({ nav: "items", detail: id })} />
                   : <Table caption="Suchergebnisse" columns={window.COLUMNS.slice(0, 4)} rows={hits} />}
        </div>
      </div>
    </div>
  );
}

const FIELD_TYPES = ["text","multiline","integer","decimal","money","boolean","date","datetime","enum","multi-enum","url","email","quantity","reference","secret","file"];

function AdminScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const rows = [
    { id: "f1", label: "Bezeichnung", key: "name", type: "text", req: "Pflicht", grp: "Identifikation" },
    { id: "f2", label: "Wiederbeschaffungswert", key: "replacementValue", type: "money", req: "Optional", grp: "Wert und Beschaffung" },
    { id: "f3", label: "Mindesthaltbarkeitsdatum", key: "bestBefore", type: "date", req: "Bedingt", grp: "Garantie und Wartung" },
    { id: "f4", label: "Lizenzschlüssel", key: "licenceKey", type: "secret", req: "Optional", grp: "Zustand und Zugriff" },
    { id: "f5", label: "Etiketten", key: "tags", type: "multi-enum", req: "Optional", grp: "Zustand und Zugriff" },
  ];
  const cols = [
    { key: "label", label: "Feldname" },
    { key: "key", label: "Schlüssel", mono: true, width: "11rem" },
    { key: "type", label: "Typ", width: "9rem", render: (r) => <span className="hi-badge hi-badge--neutral">{r.type}</span> },
    { key: "req", label: "Pflicht", width: "7rem" },
    { key: "grp", label: "Gruppe", width: "14rem" },
  ];
  return (
    <div className="stack">
      <Tabs value="fields" tabs={[{ key: "types", label: "Artikeltypen", count: 9 }, { key: "fields", label: "Felder", count: 20 },
        { key: "loc", label: "Lagerortkategorien", count: 6 }, { key: "roles", label: "Rollen", count: 4 }, { key: "lists", label: "Wertelisten" }]} />
      <InlineMessage tone="warning" title="Typdefinitionen sind online-only">
        Feld- und Typänderungen lassen sich nicht offline bearbeiten: eine offline geänderte Definition wäre ein Schema-Konflikt, und der ist nicht zuverlässig automatisch lösbar.
      </InlineMessage>
      <div className="toolbar">
        <span className="muted">Typ <strong>Elektrowerkzeug</strong> · 20 Felder in 4 Gruppen</span>
        <span style={{ flex: 1 }} />
        <Button variant="secondary" icon="eye">Formular-Vorschau</Button>
        <Button variant="primary" icon="plus">Feld hinzufügen</Button>
      </div>
      {compact ? <RowList items={rows.map((r) => ({ id: r.id, name: r.label, path: r.type + " · " + r.grp }))} />
               : <Table caption="Felddefinitionen" columns={cols} rows={rows} />}
      <Card title="Neues Feld">
        <div className="hi-form hi-form--split" style={{ maxWidth: "none" }}>
          <Field id="nf1" label="Feldname" required><TextInput placeholder="z. B. Wiederbeschaffungswert" /></Field>
          <Field id="nf2" label="Feldtyp" required help="16 Typen; der Renderer zeichnet jeden in allen Zuständen"><Select options={FIELD_TYPES} value="money" /></Field>
          <Field id="nf3" label="Sichtbar wenn" help="Bedingte Sichtbarkeit — leer heißt immer sichtbar"><TextInput mono placeholder="zustand != 'Defekt'" /></Field>
          <Field id="nf4" label="Gruppe"><Select options={["Identifikation","Wert und Beschaffung","Garantie und Wartung","Zustand und Zugriff"]} value="Wert und Beschaffung" /></Field>
        </div>
      </Card>
    </div>
  );
}

function LabelsScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const jobs = [
    { id: "p1", name: "24 Blanko-Codes · Avery 3474", state: "RENDERED", n: "24", when: "vor 2 Min." },
    { id: "p2", name: "Auswahl: Keller › Regal B", state: "PRINTING", n: "88", when: "läuft" },
    { id: "p3", name: "Gespeicherte Suche: Wert über 200 €", state: "FAILED", n: "64", when: "Drucker meldet Papierstau" },
  ];
  const badge = { RENDERED: "neutral", PRINTING: "neutral", COMPLETED: "success", FAILED: "danger" };
  return (
    <div className="stack">
      <Tabs value="tpl" tabs={[{ key: "tpl", label: "Etikettenvorlage" }, { key: "media", label: "Etikettenmaterial", count: 7 }, { key: "jobs", label: "Druckaufträge", count: 3 }]} />
      <div className={compact ? "stack" : "split split--editor"}>
        <div className="pane pane--main stack">
          <Card title="Vorlage">
            <div className="hi-form" style={{ maxWidth: "none" }}>
              <Field id="l1" label="Etikettenmaterial" help="Avery Zweckform 3474 · 70 × 37 mm · 3 × 8, verifiziert">
                <Select options={["Avery Zweckform 3474 — 70 × 37 mm ✓","Avery Zweckform 3667 — 48,5 × 16,9 mm (nicht verifiziert)","Brother DK-11201 — 29 × 90 mm ✓","Dymo 99012 — 36 × 89 mm ✓"]} value="Avery Zweckform 3474 — 70 × 37 mm ✓" />
              </Field>
              <Field id="l2" label="Zeile 1" help="Ausdruckssprache, nicht Turing-vollständig"><TextInput mono value="{{item.name}}" /></Field>
              <Field id="l3" label="Zeile 2"><TextInput mono value="{{location.path}}" /></Field>
              <Field id="l4" label="Symbologie"><Select options={["QR (mitgeliefert)","DataMatrix (Plugin)","Code128 (Plugin)"]} value="QR (mitgeliefert)" /></Field>
              <Field id="l5" label="Startversatz" help="Teilweise benutzte Bögen weiterverwenden"><TextInput type="integer" value={5} /></Field>
            </div>
          </Card>
        </div>
        <aside className="pane pane--preview">
          <LabelPreview scale={compact ? 1.05 : 1.22} code="7Q2-M4X-9KD"
            media={{ vendor: "Avery Zweckform", articleNumber: "3474", width: 70, height: 37, verified: true }}
            lines={[{ text: "Akku-Bohrschrauber GSB 18V-55 Professional", bold: true, size: 3.1, wrap: true }, { text: "Keller › Regal B › Kiste 4", size: 2.5 }]} />
          <p className="muted small">Die Vorschau behält ihren Papiergrund auch im Dunkelmodus. Sie ist ein Bild von etwas Gedrucktem, keine Oberfläche.</p>
        </aside>
      </div>
      <Card title="Druckaufträge" flush>
        <div className="joblist">
          {jobs.map((j) => (
            <div className="job" key={j.id}>
              <Icon name="printer" size={16} />
              <span className="hi-truncate" style={{ flex: 1 }}>{j.name}</span>
              <span className="muted small">{j.n} Etiketten</span>
              <span className={"hi-badge hi-badge--" + badge[j.state]}>{j.state}</span>
              <span className="muted small">{j.when}</span>
              {j.state === "FAILED" ? <Button variant="secondary" icon="refresh-cw">Erneut</Button> : null}
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}

function StocktakeScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const disc = [
    { id: "d1", name: "Kabeltrommel 25 m", code: "M1R-6HJ-4TN", exp: "Regal A", found: "nicht gefunden", kind: "missing" },
    { id: "d2", name: "Werkzeugkoffer 129-teilig", code: "T5N-9WK-3FG", exp: "Werkbank", found: "Kiste 4", kind: "moved" },
    { id: "d3", name: "Unbekanntes Etikett", code: "Q8V-3ZP-5MT", exp: "—", found: "Regal B", kind: "extra" },
  ];
  const kindChip = { missing: <StatusChip status="danger" label="Fehlt" />, moved: <StatusChip status="warning" label="Woanders" />, extra: <StatusChip status="unassigned" label="Unerwartet" /> };
  return (
    <div className="stack">
      <div className="cards3">
        <Card title="Fortschritt"><Progress label="Keller › Regal B" value={73} max={88} detail="73 von 88" /><p className="sub">Inventurlauf gestartet 09:02</p></Card>
        <Card title="Abweichungen"><p className="big" style={{ color: "var(--state-warning-fg)" }}>3</p><p className="sub">1 fehlt · 1 woanders · 1 unerwartet</p></Card>
        <Card title="Modus"><div className="hi-row"><StatusChip status="offline" /><span className="muted small">Lauf wird lokal geführt</span></div><p className="sub">Abgleich beim nächsten Netz</p></Card>
      </div>
      <div className="toolbar">
        <span className="muted">Abweichungsbericht</span><span style={{ flex: 1 }} />
        <Button variant="secondary" icon="download">CSV</Button>
        <Button variant="primary" icon="scan-line">Weiter scannen</Button>
      </div>
      {compact
        ? <RowList items={disc.map((d) => ({ id: d.id, name: d.name, path: `erwartet ${d.exp} · gefunden ${d.found}`, statusNode: kindChip[d.kind], status: d.kind }))} />
        : <Table caption="Abweichungen" columns={[
            { key: "name", label: "Artikel" }, { key: "code", label: "Code", mono: true, width: "9.5rem" },
            { key: "exp", label: "Erwartet", width: "10rem" }, { key: "found", label: "Gefunden", width: "10rem" },
            { key: "kind", label: "Art", width: "9rem", render: (r) => kindChip[r.kind] }]} rows={disc} />}
    </div>
  );
}

function DevicesScreen({ cls, state, set }) {
  return (
    <div className="stack">
      <div className="cards3">
        <Card title="Mandant"><div className="hi-row"><Icon name="building" size={16} /><strong>Haushalt Greiner</strong></div>
          <p className="sub">3 weitere Mandanten verfügbar</p>
          <Button variant="secondary" icon="arrow-left-right">Mandant wechseln</Button></Card>
        <Card title="Abgleich"><p className="big">09:14</p><p className="sub">Letzter vollständiger Abgleich · Cursor 148 277</p></Card>
        <Card title="Speicher"><Progress label="Lokales Budget" value={412} max={2048} detail="412 MB von 2 GB" /><p className="sub">Älteste Vorschaubilder werden zuerst verworfen — nie unhochgeladene Aufnahmen.</p></Card>
      </div>
      <Card title="Geräte" flush actions={<Button variant="ghost" icon="plus">Gerät registrieren</Button>}>
        {window.DEVICES.map((d) => <DeviceRow key={d.name} device={d} trailing={<IconButton icon="ellipsis-vertical" label={"Aktionen für " + d.name} />} />)}
      </Card>
      <InlineMessage tone="info" title="Bis zu 10 Geräte pro Konto">
        Ein Gerät ohne Kontakt seit 180 Tagen wird abgemeldet und sein Cursor freigegeben. Beim Fernlöschen werden unversandte Änderungen — wo möglich — vorher übertragen.
      </InlineMessage>
    </div>
  );
}

function OverviewScreen({ cls, state, set }) {
  const rows = window.ITEMS.filter((i) => i.warranty).map((i) => ({ ...i, warrantyFmt: new Intl.DateTimeFormat("de-DE", { dateStyle: "medium" }).format(new Date(i.warranty)) }));
  return (
    <div className="stack">
      <div className="cards3">
        <Card title="Gesamtwert"><p className="big">128 640,50 €</p><p className="sub">Summe Wiederbeschaffungswert · 3 211 Artikel</p></Card>
        <Card title="Garantie läuft ab"><p className="big" style={{ color: "var(--state-warning-fg)" }}>18</p><p className="sub">in den nächsten 90 Tagen</p></Card>
        <Card title="Ohne Wert erfasst"><p className="big">207</p><p className="sub">Artikel ohne Wiederbeschaffungswert</p></Card>
      </div>
      <Card title="Nächste Fristen" flush>
        <Table caption="Fristen" columns={[
          { key: "name", label: "Artikel" },
          { key: "warrantyFmt", label: "Garantie bis", width: "10rem" },
          { key: "path", label: "Lagerort", width: "14rem" },
          { key: "value", label: "Wiederbeschaffungswert", numeric: true, width: "12rem", render: (r) => eur(r.value) }]} rows={rows} />
      </Card>
    </div>
  );
}

function AuthScreen({ cls, state, set }) {
  return (
    <div className="authwrap">
      <div className="authcard">
        <div className="hi-row" style={{ gap: 10, marginBottom: 18 }}><span className="kitbar__sq" style={{ width: 28, height: 28, fontSize: 13 }}>HI</span><strong style={{ fontSize: 18 }}>Home Inventory</strong></div>
        {state.authStep === "2fa" ? (
          <div className="hi-form" style={{ maxWidth: "none" }}>
            <p className="muted small">Angemeldet als <strong>lukas@lindenstrasse.example</strong></p>
            <Field id="a3" label="Zweiter Faktor" help="6-stelliger Code aus der Authenticator-App"><TextInput mono placeholder="000000" /></Field>
            <Button variant="primary" icon="shield" full onClick={() => set({ authStep: "confirm" })}>Bestätigen</Button>
            <Button variant="ghost" icon="key-round" full>Passkey verwenden</Button>
          </div>
        ) : state.authStep === "confirm" ? (
          <div className="hi-form" style={{ maxWidth: "none" }}>
            <InlineMessage tone="warning" title="Erneut bestätigen">
              Du möchtest einen Lizenzschlüssel anzeigen. Sensible Felder verlangen eine frische Bestätigung, auch wenn die Sitzung noch gültig ist.
            </InlineMessage>
            <Field id="a4" label="Zweiter Faktor"><TextInput mono placeholder="000000" /></Field>
            <div className="hi-row" style={{ gap: 8 }}>
              <Button variant="ghost" full onClick={() => set({ authStep: "login" })}>Abbrechen</Button>
              <Button variant="primary" icon="eye" full>Anzeigen</Button>
            </div>
            <SecretInput />
          </div>
        ) : (
          <div className="hi-form" style={{ maxWidth: "none" }}>
            <Field id="a1" label="E-Mail-Adresse" required><TextInput type="email" placeholder="name@example.org" /></Field>
            <Field id="a2" label="Passwort" required><TextInput type="text" placeholder="••••••••" /></Field>
            <Button variant="primary" icon="log-out" full onClick={() => set({ authStep: "2fa" })}>Anmelden</Button>
            <Button variant="ghost" icon="globe" full>Mit OIDC anmelden</Button>
            <p className="muted small">Diese Instanz läuft selbstgehostet. Es werden keine Daten an Dritte übertragen.</p>
          </div>
        )}
      </div>
    </div>
  );
}

Object.assign(window, { SearchScreen, AdminScreen, LabelsScreen, StocktakeScreen, DevicesScreen, OverviewScreen, AuthScreen });

const DS = window.HomeInventoryDesignSystem_e14f83;
const { Icon, IconButton, Button, Tabs, Table, RowList, PhotoTile, PhotoGrid, Pagination, FilterBar, BulkBar,
        StatusChip, Badge, Tag, EmptyState, SkeletonRows, Card, Drawer, Checkbox, Breadcrumb, ActionBar,
        InlineMessage, Progress, CodePlate, FieldRenderer, Field, TextInput, Select } = DS;

const eur = (n) => new Intl.NumberFormat("de-DE", { style: "currency", currency: "EUR" }).format(n);
const statusNode = (s) => s ? <StatusChip status={s} /> : null;

const COLUMNS = [
  { key: "name", label: "Bezeichnung", render: (r) => (
      <span className="hi-table__name">
        <span className={"hi-thumb " + (r.status === "pending" ? "hi-thumb--pending" : "")}>
          {r.photo ? <span className="ph" /> : <Icon name="package" size={16} />}
        </span>
        <span className="hi-truncate" title={r.name}>{r.name}</span>
        {r.status ? <StatusChip status={r.status} /> : null}
      </span>) },
  { key: "code", label: "Code", mono: true, width: "9.5rem" },
  { key: "path", label: "Lagerort", width: "14rem", render: (r) => <span className="hi-truncate" style={{ display: "block", color: "var(--text-secondary)" }}>{r.path}</span> },
  { key: "type", label: "Typ", width: "11rem" },
  { key: "qty", label: "Menge", numeric: true, width: "6rem" },
  { key: "value", label: "Wiederbeschaffungswert", numeric: true, width: "12rem", render: (r) => r.status === "restricted"
      ? <span className="hi-chip hi-chip--restricted hi-hatch"><Icon name="lock" size={12} />Keine Berechtigung</span>
      : <span>{eur(r.value)}</span> },
];

function ItemsScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const { view, sel, facets, loading, empty } = state;
  const items = empty ? [] : window.ITEMS;
  const toggle = (id) => set({ sel: sel.includes(id) ? sel.filter((x) => x !== id) : [...sel, id] });
  const FACETS = [
    { key: "keller", label: "Keller", count: 412, icon: "map-pin", active: facets.includes("keller") },
    { key: "warr", label: "Garantie läuft ab", count: 18, icon: "calendar-clock", active: facets.includes("warr") },
    { key: "nophoto", label: "Ohne Foto", count: 207, icon: "image", active: facets.includes("nophoto") },
    { key: "tool", label: "Elektrowerkzeug", count: 96, icon: "wrench", active: facets.includes("tool") },
    { key: "conf", label: "Konflikt", count: 2, icon: "git-merge", active: facets.includes("conf") },
  ];
  return (
    <div className="stack">
      <div className="toolbar">
        <Tabs value={view} onChange={(v) => set({ view: v })} tabs={[
          { key: "table", label: "Tabelle", icon: "table" },
          { key: "grid", label: "Raster", icon: "layout-grid" },
        ]} />
        <span style={{ flex: 1 }} />
        {!compact ? <IconButton icon="columns-3" label="Spalten wählen" outlined /> : null}
        <IconButton icon="funnel" label="Filter" outlined onClick={() => set({ filterOpen: true })} />
        {!compact ? <Button variant="primary" icon="plus">Artikel anlegen</Button> : null}
      </div>

      <FilterBar facets={FACETS} onToggle={(k) => set({ facets: facets.includes(k) ? facets.filter((x) => x !== k) : [...facets, k] })} />

      {sel.length > 0 ? (
        <BulkBar count={sel.length}>
          <Button variant="secondary" icon="move">Verschieben</Button>
          <Button variant="secondary" icon="printer">Etiketten drucken</Button>
          <Button variant="secondary" icon="tag">Etikett zuweisen</Button>
          <Button variant="ghost" icon="x" onClick={() => set({ sel: [] })}>Auswahl aufheben</Button>
        </BulkBar>
      ) : null}

      {loading ? <div className="hi-card hi-card--flush"><SkeletonRows rows={compact ? 6 : 9} /></div>
       : empty ? <div className="hi-card"><EmptyState icon="funnel" title="Keine Treffer"
            action={<Button variant="secondary" icon="x" onClick={() => set({ empty: false, facets: [] })}>Filter zurücksetzen</Button>}>
            Vier Filter sind aktiv. Entferne einen davon oder suche im gesamten Bestand.</EmptyState></div>
       : view === "grid" ? (
          <PhotoGrid>{items.map((i) => (
            <PhotoTile key={i.id} item={{ ...i, photo: null, icon: i.photo ? "image" : "package",
              badges: i.status ? <StatusChip status={i.status} /> : null }}
              selectable selected={sel.includes(i.id)} onToggle={toggle} onOpen={() => set({ detail: i.id })} />
          ))}</PhotoGrid>)
       : compact ? (
          <RowList items={items.map((i) => ({ ...i, statusNode: statusNode(i.status), status: i.status,
            trailing: <span style={{ fontVariantNumeric: "tabular-nums", color: "var(--text-muted)", fontSize: "var(--fs-75)" }}>{i.value ? eur(i.value) : ""}</span> }))}
            selected={sel} onOpen={(id) => set({ detail: id })} />)
       : <Table caption="Artikelbestand" selectable selected={sel} onToggle={toggle}
                sort={{ key: "name", dir: "ascending" }} columns={COLUMNS} rows={items} />}

      {!compact && !loading && !empty ? <Pagination page={1} pages={129} total={3211} /> : null}
      {compact ? <ActionBar sticky><Button variant="primary" icon="plus" full>Artikel anlegen</Button></ActionBar> : null}
    </div>
  );
}

function LocationsScreen({ cls, state, set }) {
  const compact = cls === "compact";
  const rows = window.ITEMS.slice(0, 6);
  const contents = (
    <div className="stack">
      <div className="toolbar">
        <Breadcrumb path={[{ id: "h", name: "Haus Lindenstraße" }, { id: "k", name: "Keller" }, { id: "rb", name: "Regal B" }]} />
        <span style={{ flex: 1 }} />
        <IconButton icon="qr-code" label="Etikett dieses Ortes" outlined />
        <IconButton icon="ellipsis-vertical" label="Weitere Aktionen" outlined />
      </div>
      <div className="cards3">
        <Card title="Inhalt"><p className="big">88</p><p className="sub">Artikel direkt und in Unterorten</p></Card>
        <Card title="Wert"><p className="big">4 812,40 €</p><p className="sub">Summe Wiederbeschaffungswert</p></Card>
        <Card title="Zuletzt bewegt"><p className="big">09.09.</p><p className="sub">Akku-Bohrschrauber → Kiste 4</p></Card>
      </div>
      {compact
        ? <RowList items={rows.map((i) => ({ ...i, statusNode: statusNode(i.status) }))} onOpen={(id) => set({ detail: id })} />
        : <Table caption="Inhalt von Regal B" columns={COLUMNS.slice(0, 4)} rows={rows} />}
    </div>
  );
  const tree = <window.TreePane current={state.loc || "rb"} onSelect={(id) => set({ loc: id })} />;
  if (compact || cls === "medium") {
    return (
      <div className="stack">
        <div className="hi-card hi-card--flush treecard hi-scroll-thin">{tree}</div>
        {contents}
      </div>
    );
  }
  return <div className="split split--tree">{tree}<div className="pane pane--main hi-scroll-thin">{contents}</div></div>;
}

function ItemDetailScreen({ cls, state, set }) {
  const item = window.ITEMS.find((i) => i.id === (state.detail || "i1")) || window.ITEMS[0];
  const compact = cls === "compact";
  const head = (
    <div className="stack">
      <div className="detail__top">
        <div className="detail__photo">{item.photo ? <span className="ph ph--lg" /> : <Icon name="package" size={40} />}</div>
        <div className="hi-col" style={{ gap: 6, minWidth: 0, flex: 1 }}>
          <h2 className="detail__name">{item.name}</h2>
          <Breadcrumb path={item.path.split(" › ").map((n, i) => ({ id: String(i), name: n }))} />
          <div className="hi-row" style={{ flexWrap: "wrap", gap: 6 }}>
            <Tag>Werkzeug</Tag><Tag>Akku</Tag><Tag>Ausleihbar</Tag>
            {item.status ? <StatusChip status={item.status} /> : <StatusChip status="success" label="Synchron" />}
          </div>
        </div>
        <CodePlate code={item.code} size={compact ? 80 : 104} />
      </div>
      {item.status === "conflict" ? (
        <InlineMessage tone="conflict" title="Zwei Versionen dieses Artikels"
          actions={<Button variant="secondary" icon="git-merge" onClick={() => set({ nav: "conflict" })}>Feld für Feld entscheiden</Button>}>
          Dieses Gerät und der Server wurden offline unterschiedlich geändert. Bis zur Entscheidung gilt die Server-Version.
        </InlineMessage>) : null}
      {item.status === "pending" ? <InlineMessage tone="offline" title="1 Foto nur auf diesem Gerät">Wird beim nächsten Abgleich hochgeladen.</InlineMessage> : null}
    </div>
  );
  return (
    <div className="stack">
      {head}
      <Tabs value="details" tabs={[{ key: "details", label: "Details" }, { key: "media", label: "Fotos", count: 3 },
        { key: "hist", label: "Verlauf" }, { key: "labels", label: "Etiketten", count: 1 }]} />
      <FieldRenderer split={cls === "expanded" || cls === "large"} readOnly schema={window.TYPE_TOOL} />
      {compact
        ? <ActionBar sticky stack><Button variant="ghost" icon="move" full>Umlagern</Button><Button variant="primary" icon="square-pen" full>Bearbeiten</Button></ActionBar>
        : <ActionBar><Button variant="ghost" icon="trash">Löschen</Button><Button variant="secondary" icon="move">Umlagern</Button><Button variant="primary" icon="square-pen">Bearbeiten</Button></ActionBar>}
    </div>
  );
}

Object.assign(window, { ItemsScreen, LocationsScreen, ItemDetailScreen, COLUMNS, eur });

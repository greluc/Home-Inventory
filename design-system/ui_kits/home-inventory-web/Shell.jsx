const DS = window.HomeInventoryDesignSystem_e14f83;
const { Icon, IconButton, Button, Tabs, Breadcrumb, BottomNav, NavRail, StatusChip, Avatar, Tree } = DS;

const NAV = [
  { key: "locations", label: "Orte", icon: "folder-tree" },
  { key: "items", label: "Artikel", icon: "package" },
  { key: "scan", label: "Scannen", icon: "scan-line", action: true },
  { key: "search", label: "Suche", icon: "search" },
  { key: "admin", label: "Verwaltung", icon: "settings" },
];

const WIDTHS = [
  { key: "phone", label: "Telefon 390", w: 390, h: 780, cls: "compact" },
  { key: "tabletP", label: "Tablet hoch 834", w: 834, h: 900, cls: "medium" },
  { key: "tabletL", label: "Tablet quer 1194", w: 1194, h: 834, cls: "expanded" },
  { key: "desktop", label: "Desktop", w: null, h: 900, cls: "large" },
];

/* The chrome around the prototype — not part of the product. */
function KitBar({ width, setWidth, theme, setTheme, density, setDensity, offline, setOffline, screen, setScreen, screens }) {
  return (
    <div className="kitbar">
      <span className="kitbar__brand"><span className="kitbar__sq">HI</span>Home Inventory — UI-Kit</span>
      <select className="kitsel" value={screen} onChange={(e) => setScreen(e.target.value)} aria-label="Ansicht">
        {screens.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
      </select>
      <span className="kitseg" role="group" aria-label="Gerätebreite">
        {WIDTHS.map((w) => (
          <button key={w.key} type="button" aria-pressed={width.key === w.key} onClick={() => setWidth(w)}>{w.label}</button>
        ))}
      </span>
      <span className="kitbar__sp" />
      <span className="kitseg">
        <button type="button" aria-pressed={density === "comfortable"} onClick={() => setDensity("comfortable")}>Komfortabel</button>
        <button type="button" aria-pressed={density === "compact"} onClick={() => setDensity("compact")}>Kompakt</button>
      </span>
      <span className="kitseg">
        <button type="button" aria-pressed={offline} onClick={() => setOffline(!offline)}>Offline</button>
      </span>
      <span className="kitseg">
        <button type="button" aria-pressed={theme === "dark"} onClick={() => setTheme("dark")}>Dunkel</button>
        <button type="button" aria-pressed={theme === "light"} onClick={() => setTheme("light")}>Hell</button>
      </span>
    </div>
  );
}

/* The product shell: app bar, status strip, navigation per width class. */
function AppShell({ cls, nav, setNav, title, crumbs, actions, offline, conflicts = 2, degraded, children, noPad, theme, setTheme }) {
  const compact = cls === "compact";
  const medium = cls === "medium";
  const rail = cls === "expanded" || cls === "large";
  return (
    <div className="app" data-cls={cls}>
      {rail ? <NavRail items={NAV} value={nav} onChange={setNav} /> : null}
      <div className="app__main">
        <header className="hi-appbar">
          {compact || medium ? <IconButton icon="panel-left" label="Menü" /> : null}
          <div className="hi-col" style={{ minWidth: 0, gap: 0 }}>
            <span className="hi-appbar__title">{title}</span>
            {crumbs && !compact ? <Breadcrumb path={crumbs} /> : null}
          </div>
          <span className="hi-appbar__spacer" />
          {actions}
          <IconButton icon={theme === "dark" ? "sun" : "moon"} label={theme === "dark" ? "Helles Design" : "Dunkles Design"} onClick={() => setTheme(theme === "dark" ? "light" : "dark")} />
          <Avatar name="Lukas Greiner" />
        </header>
        {offline ? (
          <div className="hi-statusstrip hi-statusstrip--offline">
            <Icon name="cloud-off" size={14} />
            <span>Offline — Änderungen werden lokal gespeichert</span>
            <span style={{ marginLeft: "auto", fontVariantNumeric: "tabular-nums" }}>2 nicht gesendet</span>
          </div>
        ) : null}
        {degraded ? (
          <div className="hi-statusstrip hi-statusstrip--degraded">
            <Icon name="gauge" size={14} />
            <span>Suche im Notbetrieb — Ergebnisse sind weniger präzise</span>
          </div>
        ) : null}
        {conflicts > 0 ? (
          <div className="hi-statusstrip hi-statusstrip--conflict">
            <Icon name="git-merge" size={14} />
            <span>{conflicts} Artikel warten auf eine Entscheidung</span>
            <button className="hi-btn hi-btn--ghost" style={{ marginLeft: "auto", height: 24, minHeight: 24, color: "inherit" }} onClick={() => setNav("conflict")}>Lösen</button>
          </div>
        ) : null}
        <main className={"app__body" + (noPad ? " app__body--flush" : "")}>{children}</main>
        {compact || medium ? <BottomNav items={NAV} value={nav} onChange={setNav} /> : null}
      </div>
    </div>
  );
}

/* Master pane used at expanded/large: the location tree beside its contents. */
function TreePane({ current, onSelect }) {
  const [open, setOpen] = React.useState(["h", "k", "rb"]);
  return (
    <aside className="pane pane--tree hi-scroll-thin">
      <div className="pane__head"><span className="pane__title">Lagerorte</span><IconButton icon="plus" label="Lagerort anlegen" /></div>
      <Tree nodes={window.LOCATIONS} openIds={open} currentId={current} onSelect={onSelect}
            onToggle={(id) => setOpen((o) => o.includes(id) ? o.filter((x) => x !== id) : [...o, id])} />
    </aside>
  );
}

Object.assign(window, { KitBar, AppShell, TreePane, NAV, WIDTHS });

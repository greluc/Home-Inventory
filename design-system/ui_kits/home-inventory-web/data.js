/* Mock data for the UI kit. German throughout, and deliberately so: these are the long compounds
   the shipped German UI actually produces, and they are the width fixture that
   `guidelines/type-german.html`, `components/data/Table.d.ts` and REQ-NFR-071 all lean on.
   Translating them would remove exactly what the screens exist to prove.

   This file and `guidelines/type-german.html` are the TWO entries on the closed carve-out list of
   REQ-CON-012 / REQ-CON-014; everything else in the corpus is English. A third German fixture
   needs a row there and a sentence saying why English will not do. */
const LOCATIONS = [
  { id: "h", name: "Haus Lindenstraße", kind: "building", count: 1284, children: [
    { id: "k", name: "Keller", kind: "room", count: 412, children: [
      { id: "ra", name: "Regal A", kind: "shelf", count: 121 },
      { id: "rb", name: "Regal B", kind: "shelf", count: 88, children: [
        { id: "k4", name: "Kiste 4 — Elektrowerkzeug", kind: "box", count: 12 },
        { id: "k5", name: "Kiste 5", kind: "box", count: 31 },
      ]},
      { id: "hr", name: "Heizungsraum", kind: "room", count: 18 },
    ]},
    { id: "w", name: "Werkstatt", kind: "room", count: 306, children: [
      { id: "s1", name: "Schrank 1", kind: "shelf", count: 94 },
      { id: "wb", name: "Werkbank", kind: "shelf", count: 41 },
    ]},
    { id: "d", name: "Dachboden", kind: "room", count: 270 },
    { id: "g", name: "Garage", kind: "room", count: 296 },
  ]},
];

const ITEMS = [
  { id: "i1", name: "Akku-Bohrschrauber GSB 18V-55 Professional", code: "7Q2M-4X9K-D2F", path: "Keller › Regal B › Kiste 4", loc: "Kiste 4", type: "Elektrowerkzeug", value: 189.99, warranty: "2028-04-30", photo: true, status: null, qty: 1 },
  { id: "i2", name: "Schlagbohrmaschinen-Zubehörset mit Transportkoffer", code: "J4T-8PN-2WQ", path: "Werkstatt › Schrank 1", loc: "Schrank 1", type: "Zubehör", value: 64.5, warranty: "2027-01-15", photo: true, status: "conflict", qty: 1 },
  { id: "i3", name: "Aluminium-Stehleiter 8 Stufen", code: "B9C-K3D-7VX", path: "Garage", loc: "Garage", type: "Werkzeug", value: 120.0, warranty: null, photo: false, status: null, qty: 1 },
  { id: "i4", name: "Kabeltrommel 25 m", code: "M1R-6HJ-4TN", path: "Keller › Regal A", loc: "Regal A", type: "Elektrik", value: 39.9, warranty: "2026-11-02", photo: true, status: "pending", qty: 2 },
  { id: "i5", name: "Winterreifen-Satz 205/55 R16", code: "P7X-2QB-8CD", path: "Dachboden", loc: "Dachboden", type: "Fahrzeugteile", value: 480.0, warranty: null, photo: false, status: null, qty: 4 },
  { id: "i6", name: "Werkzeugkoffer 129-teilig", code: "T5N-9WK-3FG", path: "Werkstatt › Werkbank", loc: "Werkbank", type: "Werkzeug", value: 89.0, warranty: "2029-03-01", photo: true, status: null, qty: 1 },
  { id: "i7", name: "Gartenschlauch-Aufroller wandmontiert", code: "V8D-1LM-6QZ", path: "Garage", loc: "Garage", type: "Garten", value: 54.95, warranty: null, photo: false, status: "restricted", qty: 1 },
  { id: "i8", name: "Wandfarbe Reinweiß 10 l", code: "C3K-7TR-2HV", path: "Keller › Regal A", loc: "Regal A", type: "Verbrauchsmaterial", value: 62.0, warranty: null, photo: true, status: null, qty: 3 },
  { id: "i9", name: "Kompressor 50 l 8 bar", code: "X6B-4NF-9JP", path: "Werkstatt", loc: "Werkstatt", type: "Elektrowerkzeug", value: 229.0, warranty: "2027-08-20", photo: true, status: null, qty: 1 },
  { id: "i10", name: "Umzugskarton Bücher — Arbeitszimmer", code: "R2M-5VQ-1KX", path: "Dachboden", loc: "Dachboden", type: "Umzugskarton", value: 0, warranty: null, photo: false, status: "unassigned", qty: 1 },
];

/* Two runtime-defined item types. Nobody designed either form. */
const TYPE_BOOK = [
  { key: "b_title", label: "Titel", type: "text", required: true, value: "Die Architektur nachhaltiger Systeme" },
  { key: "b_author", label: "Verfasser", type: "text", value: "M. Behrens" },
  { key: "b_isbn", label: "ISBN", type: "text", mono: true, value: "978-3-8362-8745-6", help: "Wird als Fremdcode gebunden" },
  { key: "b_year", label: "Erscheinungsjahr", type: "integer", value: 2024 },
  { key: "b_lang", label: "Sprache", type: "enum", options: ["Deutsch", "Englisch", "Französisch"], value: "Deutsch" },
  { key: "b_lent", label: "Verliehen an", type: "reference", refKind: "item", value: "" },
];

const TYPE_TOOL = [
  { title: "Identifikation", fields: [
    { key: "t_name", label: "Bezeichnung", type: "text", required: true, value: "Akku-Bohrschrauber GSB 18V-55 Professional" },
    { key: "t_maker", label: "Hersteller", type: "enum", options: ["Bosch","Makita","Metabo","Festool","DeWalt","Hilti","Einhell","Ryobi","Milwaukee","Würth","Fein","Hikoki","Flex"], value: "Bosch" },
    { key: "t_sn", label: "Seriennummer", type: "text", mono: true, value: "3 601 JJ0 100" },
    { key: "t_cat", label: "Lagerortkategorie", type: "enum", options: ["Keller","Dachboden","Garage","Werkstatt"], value: "Keller" },
    { key: "t_loc", label: "Lagerort", type: "reference", refKind: "location", value: "Kiste 4", path: ["Keller","Regal B"] },
  ]},
  { title: "Wert und Beschaffung", fields: [
    { key: "t_price", label: "Kaufpreis", type: "money", value: 219.0, currency: "EUR" },
    { key: "t_wbw", label: "Wiederbeschaffungswert", type: "money", value: 189.99, currency: "EUR", help: "Brutto, bei Neubeschaffung heute" },
    { key: "t_date", label: "Kaufdatum", type: "date", value: "2024-04-30" },
    { key: "t_vendor", label: "Händler", type: "text", value: "Werkzeughaus Nord" },
    { key: "t_receipt", label: "Beleg", type: "file", value: [{ name: "Rechnung_Bosch_GSB18V.pdf", size: "412 kB", kind: "pdf" }] },
    { key: "t_insured", label: "Versichert", type: "boolean", value: true, checkboxLabel: "In der Hausratversicherung geführt" },
  ]},
  { title: "Garantie und Wartung", fields: [
    { key: "t_warr", label: "Garantie bis", type: "date", value: "2028-04-30" },
    { key: "t_mhd", label: "Mindesthaltbarkeitsdatum", type: "date", value: "", help: "Nur bei Verbrauchsmaterial relevant" },
    { key: "t_serv", label: "Nächste Wartung", type: "datetime", value: "2026-11-02T09:00" },
    { key: "t_int", label: "Wartungsintervall", type: "quantity", value: 12, unit: "Mon", units: ["Tage","Wochen","Mon","Jahre"] },
    { key: "t_note", label: "Wartungsnotiz", type: "multiline", rows: 2, value: "Bohrfutter läuft leicht unrund — bei nächster Wartung prüfen." },
  ]},
  { title: "Zustand und Zugriff", fields: [
    { key: "t_cond", label: "Zustand", type: "enum", options: ["Neu","Gebraucht","Reparaturbedürftig","Defekt"], value: "Gebraucht" },
    { key: "t_qty", label: "Menge", type: "quantity", value: 1, unit: "Stk" },
    { key: "t_tags", label: "Etiketten", type: "multi-enum", value: ["Werkzeug","Akku","Ausleihbar"] },
    { key: "t_url", label: "Produktseite", type: "url", value: "https://intern.example.org/artikel/gsb18v55" },
    { key: "t_key", label: "Lizenzschlüssel", type: "secret", restricted: true },
  ]},
];

const CONFLICT = [
  { key: "c_name", label: "Bezeichnung",
    base: "Bohrschrauber", baseSub: "Version 7 · 03.09. 14:02",
    mine: "Akku-Bohrschrauber GSB 18V-55 Professional", mineSub: "Dieses Gerät · offline 09.09. 16:41",
    theirs: "Bohrschrauber blau", theirsSub: "Version 11 · Anna · 10.09. 08:12" },
  { key: "c_loc", label: "Lagerort",
    base: "Keller › Regal B", baseSub: "Version 7",
    mine: "Werkstatt › Schrank 1", mineSub: "Dieses Gerät · offline",
    theirs: "Keller › Kiste 4", theirsSub: "Version 11 · Anna" },
  { key: "c_val", label: "Wiederbeschaffungswert",
    base: "219,00 €", baseSub: "Version 7",
    mine: "189,99 €", mineSub: "Dieses Gerät · offline",
    theirs: "219,00 €", theirsSub: "unverändert" },
  { key: "c_tags", label: "Etiketten", auto: "Automatisch zusammengeführt (Vereinigung): Werkzeug · Akku · Ausleihbar. Entfernen schlägt Hinzufügen — keine Entscheidung nötig.",
    base: "Werkzeug", mine: "Werkzeug, Akku", theirs: "Werkzeug, Ausleihbar" },
  { key: "c_key", label: "Lizenzschlüssel",
    base: "— verborgen —", mine: "— verborgen —", theirs: "— verborgen —",
    baseSub: "sensible Felder werden nie automatisch zusammengeführt" },
];

const DEVICES = [
  { name: "Pixel 8 — Keller", platform: "android", lastSync: "vor 3 Std.", size: "412 MB", unsent: 2, status: "offline", current: true },
  { name: "iPad — Werkstatt", platform: "tablet", lastSync: "heute 09:14", size: "1,2 GB", status: "success" },
  { name: "Firefox — Büro-PC", platform: "web", lastSync: "gestern 18:40", size: "88 MB", status: "conflict" },
  { name: "iPhone 15 — Anna", platform: "ios", lastSync: "heute 07:55", size: "664 MB", status: "success" },
];

const SAVED_SEARCHES = [
  { name: "Garantie läuft in 90 Tagen ab", count: 18, icon: "calendar-clock" },
  { name: "Ohne Foto im Keller", count: 207, icon: "image" },
  { name: "Wert über 200 €", count: 64, icon: "euro" },
  { name: "Seit 2 Jahren nicht bewegt", count: 431, icon: "clock" },
];

Object.assign(window, { LOCATIONS, ITEMS, TYPE_BOOK, TYPE_TOOL, CONFLICT, DEVICES, SAVED_SEARCHES });

(function () {
  "use strict";
  var root = document.documentElement;

  var theme = "dark";
  try { theme = localStorage.getItem("homeinv.theme") || "dark"; } catch (e) {}

  function paint() {
    if (theme === "light") root.setAttribute("data-theme", "light");
    else root.removeAttribute("data-theme");
    var btn = document.getElementById("theme-toggle");
    if (!btn) return;
    var label = theme === "light" ? "Switch to the dark appearance" : "Switch to the light appearance";
    btn.title = label;
    btn.setAttribute("aria-label", label);
    var sun = btn.querySelector('[data-glyph="sun"]');
    var moon = btn.querySelector('[data-glyph="moon"]');
    if (sun) sun.hidden = theme === "light";
    if (moon) moon.hidden = theme !== "light";
  }
  paint();

  var toggle = document.getElementById("theme-toggle");
  if (toggle) toggle.addEventListener("click", function () {
    theme = theme === "light" ? "dark" : "light";
    try { localStorage.setItem("homeinv.theme", theme); } catch (e) {}
    paint();
  });

  var tablist = document.querySelector("[data-tabs]");
  if (tablist) {
    var tabs = [].slice.call(tablist.querySelectorAll("[role=tab]"));
    var panels = [].slice.call(document.querySelectorAll("[data-panel]"));
    var show = function (key) {
      tabs.forEach(function (t) { t.setAttribute("aria-selected", String(t.getAttribute("data-tab") === key)); });
      panels.forEach(function (p) { p.hidden = p.getAttribute("data-panel") !== key; });
    };
    tabs.forEach(function (t) {
      t.addEventListener("click", function () { show(t.getAttribute("data-tab")); });
    });
    show(tabs[0].getAttribute("data-tab"));
  }

  var resolver = document.querySelector("[data-conflicts]");
  if (resolver) {
    var fields = [].slice.call(resolver.querySelectorAll(".hi-conflict__field"));
    var count = document.getElementById("conflict-count");
    var total = fields.length;
    var tally = function () {
      var done = fields.filter(function (f) { return f.getAttribute("data-resolved") === "true"; }).length;
      if (count) count.textContent = done + " of " + total + " fields decided";
    };
    fields.forEach(function (field) {
      var opts = [].slice.call(field.querySelectorAll(".hi-conflict__opt"));
      opts.forEach(function (opt) {
        opt.addEventListener("click", function () {
          opts.forEach(function (o) {
            var on = o === opt;
            o.setAttribute("aria-pressed", String(on));
            var check = o.querySelector(".conflict-check");
            if (check) check.hidden = !on;
          });
          field.setAttribute("data-resolved", "true");
          tally();
        });
      });
    });
    tally();
  }
})();

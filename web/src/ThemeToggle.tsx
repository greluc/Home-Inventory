/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useState } from "react";

/**
 * Switches between dark and light.
 *
 * Dark is the default everywhere and light is opt-in (ADR-0033, REQ-NFR-040), and
 * the toggle sits in the bar rather than inside a settings page. That placement is
 * part of the decision: overriding a platform preference is only defensible if
 * reversing it takes one obvious click, and light text on a dark ground is
 * genuinely harder to read for people with astigmatism.
 *
 * What is written here is the mirror in `localStorage`, which the inline script in
 * `index.html` reads before the first paint. The authoritative value is the user
 * profile; this write is what stops the next load from flashing the wrong theme
 * while the profile is still in flight.
 */
export function ThemeToggle(): React.JSX.Element {
  const [theme, setTheme] = useState<"dark" | "light">(
    () => (document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark"),
  );

  function switchTo(next: "dark" | "light"): void {
    document.documentElement.setAttribute("data-theme", next);
    try {
      localStorage.setItem("homeinv.theme", next);
    } catch {
      // A private window may refuse storage. The theme still applies for this
      // page; only the memory of it is lost, which is a smaller problem than
      // failing the click.
    }
    setTheme(next);
  }

  return (
    <button
      type="button"
      className="ghost"
      aria-pressed={theme === "light"}
      onClick={() => switchTo(theme === "light" ? "dark" : "light")}
    >
      {theme === "light" ? "Dunkel" : "Hell"}
    </button>
  );
}

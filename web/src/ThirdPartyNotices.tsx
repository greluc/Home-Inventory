/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

import { api, type ThirdPartyNotices as Notices } from "./api";

/**
 * The third-party licence notices of this installation (REQ-CON-013).
 *
 * **Two artifacts, two notices.** The API image and this bundle carry different
 * dependencies, and neither notice covers the other: the server's comes from the
 * endpoint, the client's from a static file in this bundle that `tools/notices.py`
 * generated from what Rollup actually put into it. Showing one and calling it "the
 * notices" would under-attribute whichever half was left out.
 *
 * It renders nothing until it is opened, and fetches nothing until then either —
 * the server's notice is a few hundred kilobytes, and nobody reaching the login
 * page asked for it.
 *
 * A native `<dialog>` rather than a div with `role="dialog"`: the element brings
 * focus containment, Escape and the inert background with it, and a
 * hand-built one gets those wrong (REQ-NFR-038).
 */
export function ThirdPartyNotices({ onClose }: { onClose: () => void }): React.JSX.Element {
  const { t } = useTranslation();
  const frame = useRef<HTMLDialogElement>(null);
  const [server, setServer] = useState<Notices | null>(null);
  const [client, setClient] = useState<Notices | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    frame.current?.showModal();
  }, []);

  useEffect(() => {
    let current = true;
    const carry = (setter: (value: Notices) => void) => (value: Notices) => {
      if (current) {
        setter(value);
      }
    };
    void Promise.all([
      api.notices().then(carry(setServer)),
      // The bundle's own, beside it in the same image. `fetch` and not the
      // typed client: this is not an API call, it is a file this artifact
      // ships, and nginx serves it from the document root.
      fetch("/third-party-notices.json", { credentials: "omit" })
        .then((answer) => (answer.ok ? (answer.json() as Promise<Notices>) : Promise.reject()))
        .then(carry(setClient)),
    ]).catch(() => {
      if (current) {
        setFailed(true);
      }
    });
    return () => {
      current = false;
    };
  }, []);

  return (
    <dialog className="notices" ref={frame} onClose={onClose} aria-label={t("about.notices")}>
      <header>
        <h2>{t("about.notices")}</h2>
        <button type="button" onClick={() => frame.current?.close()}>
          {t("about.noticesClose")}
        </button>
      </header>

      {failed && <p className="error">{t("about.noticesUnavailable")}</p>}

      <Artifact heading={t("about.noticesServer", { count: server?.components.length ?? 0 })} of={server} />
      <Artifact heading={t("about.noticesClient", { count: client?.components.length ?? 0 })} of={client} />
    </dialog>
  );
}

/**
 * One artifact's components and licence texts.
 *
 * Verbatim, in a `<pre>`, and not reflowed: a licence notice is a legal text
 * whose line breaks are part of it, and a renderer that "tidied" one would be
 * changing somebody else's words.
 *
 * @param heading what this artifact is called, with its component count
 * @param of the notice, or null while it is still being fetched
 */
function Artifact({ heading, of }: { heading: string; of: Notices | null }): React.JSX.Element | null {
  const { t } = useTranslation();
  if (of === null) {
    return null;
  }
  return (
    <section>
      <h3>{heading}</h3>
      <ul className="components">
        {of.components.map((component) => (
          <li key={`${component.name}@${component.version}`}>
            <p>
              <strong>{component.name}</strong> {component.version} —{" "}
              <span className="muted">{component.licences.join(", ")}</span>
            </p>
            {component.notices.map((notice) => (
              <pre key={notice.path}>{notice.text}</pre>
            ))}
          </li>
        ))}
      </ul>
      {of.licences.length > 0 && (
        <>
          <h4>{t("about.noticesLicences")}</h4>
          {of.licences.map((licence) => (
            <details key={licence.id}>
              <summary>{licence.id}</summary>
              <pre>{licence.text}</pre>
            </details>
          ))}
        </>
      )}
    </section>
  );
}

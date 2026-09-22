/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import { api, type Version } from "./api";
import { ThirdPartyNotices } from "./ThirdPartyNotices";

/**
 * Which build this instance is running, and where its source is (REQ-CON-009).
 *
 * The AGPL obliges whoever runs an instance to offer its users the source **of that instance**, and
 * a version number alone does not identify one: two builds of `0.1.0` can differ. So the commit is
 * shown beside it, and the link goes to the source the build was made from.
 *
 * It renders nothing at all until the endpoint answers, and nothing ever if it does not. A footer
 * that said "version unavailable" would be an error message about something nobody asked for; the
 * obligation is discharged by the endpoint, and this is where a person sees it.
 *
 * The third-party notices sit here too, because that obligation is the same shape and is owed to
 * the same person (`REQ-CON-013`, [ADR-0034](../../docs/adr/0034-icon-set-and-no-third-party-hosts.md)):
 * a permissive licence asks for its notice in every copy, and this bundle is a copy. A button
 * rather than a link, and nothing fetched until it is pressed — the server's notice is a few
 * hundred kilobytes and nobody arriving at the login page asked for it.
 */
export function AboutFooter(): React.JSX.Element | null {
  const { t } = useTranslation();
  const [build, setBuild] = useState<Version | null>(null);
  const [showing, setShowing] = useState(false);

  useEffect(() => {
    let current = true;
    api
      .version()
      .then((answer) => {
        if (current) {
          setBuild(answer);
        }
        return answer;
      })
      .catch(() => {
        // Deliberately silent. This is the one part of the page nobody came for,
        // and an instance whose version endpoint is unreachable has a problem
        // this footer is not the place to report.
      });
    return () => {
      current = false;
    };
  }, []);

  if (build === null) {
    return null;
  }

  return (
    <footer className="about">
      <span className="muted">
        {t("about.version", { version: build.version, commit: build.commit })}
      </span>
      <span className="muted">{t("about.licence", { licence: build.licence })}</span>
      <a href={build.source} rel="noreferrer noopener" target="_blank">
        {t("about.source")}
      </a>
      <button type="button" className="as-link" onClick={() => setShowing(true)}>
        {t("about.notices")}
      </button>
      {showing && <ThirdPartyNotices onClose={() => setShowing(false)} />}
    </footer>
  );
}

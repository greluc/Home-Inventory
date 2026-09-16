/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import { api, type Version } from "./api";

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
 */
export function AboutFooter(): React.JSX.Element | null {
  const { t } = useTranslation();
  const [build, setBuild] = useState<Version | null>(null);

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
    </footer>
  );
}

import React from "react";
import { Icon } from "../foundation/Icon.jsx";
export function Avatar({ name = "", size = "sm", device, icon }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
  return (
    <span className={"hi-avatar " + (size === "lg" ? "hi-avatar--lg " : "") + (device ? "hi-avatar--device" : "")} title={name} aria-hidden="true">
      {device || icon ? <Icon name={icon || "smartphone"} size={16} /> : initials}
    </span>
  );
}

import React from "react";
import { Icon } from "../foundation/Icon.jsx";
import { Avatar } from "../data/Avatar.jsx";
import { StatusChip } from "../feedback/StatusChip.jsx";

const PLATFORM = { android: "smartphone", ios: "smartphone", tablet: "tablet", web: "monitor", desktop: "laptop" };

export function DeviceRow({ device, trailing }) {
  const { name, platform = "web", lastSync, size, unsent = 0, status, current } = device;
  return (
    <div className="hi-device">
      <Avatar device icon={PLATFORM[platform] || "monitor"} />
      <div className="hi-device__main">
        <span className="hi-row" style={{ gap: "var(--space-100)" }}>
          <span className="hi-truncate" style={{ fontWeight: "var(--fw-medium)" }}>{name}</span>
          {current ? <span className="hi-badge hi-badge--neutral">Dieses Gerät</span> : null}
        </span>
        <span className="hi-device__meta">
          <span><Icon name="refresh-cw" size={12} /> {lastSync}</span>
          {size ? <span>{size}</span> : null}
          {unsent > 0 ? <span style={{ color: "var(--state-pending-fg)" }}>{unsent} nicht gesendet</span> : null}
        </span>
      </div>
      {status ? <StatusChip status={status} /> : null}
      {trailing}
    </div>
  );
}

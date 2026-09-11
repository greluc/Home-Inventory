import React from "react";
import { Icon } from "../foundation/Icon.jsx";

const ICONS = { pdf: "file-text", img: "image", default: "paperclip" };

export function FileInput({
  files = [], hint = "PDF, JPG or PNG · max. 25 MB", readOnly, disabled, onRemove,
  localOnlyLabel = "On this device only", emptyLabel = "No file",
  dropLabel = "Choose a file or drop it here",
  removeLabel = (name) => "Remove " + name,
}) {
  return (
    <div className="hi-file">
      {files.map((file) => (
        <div className="hi-file__item" key={file.name}>
          <Icon name={ICONS[file.kind] || ICONS.default} size={16} />
          <span className="hi-truncate">{file.name}</span>
          {file.pending ? <span className="hi-chip hi-chip--pending"><Icon name="cloud-upload" size={14} />{localOnlyLabel}</span> : null}
          <span className="hi-file__meta">{file.size}</span>
          {!readOnly && !disabled ? (
            <button type="button" className="hi-iconbtn hi-iconbtn--danger" aria-label={removeLabel(file.name)} onClick={() => onRemove && onRemove(file)}>
              <Icon name="x" size={16} />
            </button>
          ) : null}
        </div>
      ))}
      {readOnly && !files.length ? <div className="hi-readonly hi-readonly--empty">{emptyLabel}</div> : null}
      {!readOnly ? (
        <label className="hi-file__drop">
          <Icon name="upload" size={20} />
          <span>{dropLabel}</span>
          <span style={{ color: "var(--text-disabled)" }}>{hint}</span>
          <input type="file" className="hi-sr" disabled={disabled} />
        </label>
      ) : null}
    </div>
  );
}

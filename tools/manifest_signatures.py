#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The detached signatures that travel with the plugin manifests (`REQ-PLG-004`, `ADR-0085`).

Every plugin this project ships carries its manifest at ``plugins/<name>/manifest.yaml``
and, beside it, ``manifest.yaml.sig``: the base64 of an ECDSA-P256 signature over
that file's bytes. ``deploy/generate.py`` copies the signature into the list the
core reads, and the core verifies it at every start-up against the public key the
deployment installed — offline, because ``api`` has no route out (`ADR-0026`).

**The private key never comes near a build.** This script signs only when a human
hands it one, and CI only ever runs ``--check``, which needs the public half.
A signing key that enters a runner is a signing key that has to be assumed leaked,
which is the rule this repository already applies to every other credential.

Usage::

    python tools/manifest_signatures.py --check
    python tools/manifest_signatures.py --sign --key path/to/cosign.key

``--check`` verifies every committed signature and fails when one does not match
its manifest. A manifest with **no** signature is reported and is not a failure:
an unsigned plugin is a state the operator may permit deliberately, and the core
is where that is decided. A signature with no public key to check it against *is*
a failure here, because it means this repository is carrying something nobody can
verify.

Only ``openssl`` is needed, which every runner and every developer machine has.
``cosign`` produces exactly the same bytes — ``cosign sign-blob --key cosign.key
--output-signature plugins/webhook/manifest.yaml.sig plugins/webhook/manifest.yaml``
— because signing a blob with a key is SHA-256 followed by ECDSA either way.
"""

from __future__ import annotations

import argparse
import base64
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent

#: Where the public key this project signs its own manifests with lives.
#:
#: Committed, because it is public and because the check below and every
#: deployment need it. ``deploy/setup.sh`` seeds each plugin's ``cosign-plugin-*``
#: secret from this file.
PUBLIC_KEY = ROOT / "deploy" / "keys" / "home-inv-plugins.pub.pem"


def manifests() -> list[Path]:
    """Every manifest this repository ships.

    Found rather than listed: a sixth plugin should not be able to arrive without
    its signature being checked, and a list here is a list that would be forgotten.

    :return: the manifest paths, sorted so output is stable
    """
    return sorted((ROOT / "plugins").glob("*/manifest.yaml"))


def verify(manifest: Path, signature: Path, public_key: Path) -> tuple[bool, str]:
    """Checks one detached signature against one manifest.

    :param manifest: the document that was signed
    :param signature: the file holding its base64 signature
    :param public_key: the PEM public key to check against
    :return: whether it verified, and what to say about it
    """
    try:
        raw = base64.b64decode("".join(signature.read_text(encoding="utf-8").split()), validate=True)
    except (ValueError, UnicodeDecodeError) as not_base64:
        return False, f"its signature is not base64 ({not_base64})"

    # openssl wants the signature as a file of raw DER, and the committed form is
    # base64 -- which is what cosign prints and what a YAML scalar can hold.
    with tempfile.NamedTemporaryFile(suffix=".der", delete=False) as handle:
        handle.write(raw)
        der = Path(handle.name)
    try:
        done = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(public_key),
             "-signature", str(der), str(manifest)],
            capture_output=True, text=True, check=False,
        )
    finally:
        der.unlink(missing_ok=True)
    if done.returncode == 0:
        return True, "verified"
    return False, (done.stdout + done.stderr).strip() or "openssl refused it"


def sign(manifest: Path, private_key: Path) -> str:
    """Signs one manifest, returning the base64 an operator would commit.

    :param manifest: the document to sign
    :param private_key: the PEM private key
    :return: the base64 signature, on one line
    :raises SystemExit: when openssl refuses
    """
    done = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(private_key), str(manifest)],
        capture_output=True, check=False,
    )
    if done.returncode != 0:
        raise SystemExit(f"openssl could not sign {manifest}: {done.stderr.decode(errors='replace')}")
    return base64.b64encode(done.stdout).decode("ascii")


def check() -> int:
    """Verifies every committed signature.

    :return: the process exit code
    """
    found = manifests()
    if not found:
        print("No plugin manifests are in this repository.")
        return 0

    signed = [m for m in found if (m.parent / "manifest.yaml.sig").is_file()]
    if not signed:
        print(f"None of the {len(found)} plugin manifests carries a signature yet.")
        print("They register as UNSIGNED, which a deployment permits only deliberately")
        print("(HOMEINV_PLUGINS_ALLOW_UNSIGNED). Sign them with:")
        print("  python tools/manifest_signatures.py --sign --key <your cosign key>")
        return 0

    if not PUBLIC_KEY.is_file():
        print(f"::error::{len(signed)} manifest signatures are committed and "
              f"{PUBLIC_KEY.relative_to(ROOT)} is not. Nothing here or in any deployment "
              "can check them.")
        return 1

    failures = 0
    for manifest in found:
        signature = manifest.parent / "manifest.yaml.sig"
        where = manifest.relative_to(ROOT).as_posix()
        if not signature.is_file():
            print(f"  {where} - no signature (registers as unsigned)")
            continue
        ok, said = verify(manifest, signature, PUBLIC_KEY)
        if ok:
            print(f"  {where} - verified")
        else:
            print(f"::error::{where}: its committed signature does not verify: {said}")
            print("        The manifest changed and the signature did not. Re-sign it:")
            print("        python tools/manifest_signatures.py --sign --key <your cosign key>")
            failures += 1
    return 1 if failures else 0


def sign_all(private_key: Path) -> int:
    """Signs every manifest and writes the signatures beside them.

    :param private_key: the PEM private key
    :return: the process exit code
    """
    if not private_key.is_file():
        raise SystemExit(f"{private_key} is not a file.")
    for manifest in manifests():
        signature = manifest.parent / "manifest.yaml.sig"
        signature.write_text(sign(manifest, private_key) + "\n", encoding="utf-8", newline="\n")
        print(f"  {signature.relative_to(ROOT).as_posix()} - written")
    if not PUBLIC_KEY.is_file():
        print()
        print(f"Now put the PUBLIC half at {PUBLIC_KEY.relative_to(ROOT).as_posix()}:")
        print(f"  openssl ec -in {private_key} -pubout -out {PUBLIC_KEY.relative_to(ROOT).as_posix()}")
        print("It is public, it is committed, and every deployment is seeded from it.")
    return 0


def main() -> int:
    """Reads the arguments and does one of the two things.

    :return: the process exit code
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="verify every committed signature (what CI runs)")
    parser.add_argument("--sign", action="store_true",
                        help="sign every manifest; needs --key")
    parser.add_argument("--key", type=Path,
                        help="the PEM private key to sign with. Never a CI secret")
    args = parser.parse_args()

    if args.sign:
        if not args.key:
            raise SystemExit("--sign needs --key, and the key is yours to hold.")
        return sign_all(args.key)
    if args.check:
        return check()
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())

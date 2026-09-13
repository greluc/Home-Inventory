/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The browser half of the two WebAuthn ceremonies (REQ-AUTH-002).
 *
 * The server speaks the specification's own JSON: the options it sends are what
 * `PublicKeyCredentialCreationOptions.parseCreationOptionsFromJSON()` takes, and the response it
 * expects is what `PublicKeyCredential.toJSON()` produces. Where the browser has those methods they
 * are used; where it does not, the four base64url fields are converted here. That fallback is
 * twenty lines and the alternative is a client that fails on a phone one release old.
 *
 * Nothing here decides anything. The challenge comes from the server and goes back to it, and what
 * a passkey is worth is settled there.
 */

/** Whether this browser can do WebAuthn at all. */
export function passkeysAvailable(): boolean {
  return typeof window !== "undefined" && window.PublicKeyCredential !== undefined;
}

/**
 * Creates a passkey for the options the server issued.
 *
 * @param optionsJson the server's options, as JSON
 * @returns the response, as the JSON the server verifies
 */
export async function createPasskey(optionsJson: string): Promise<string> {
  const options = parseCreationOptions(optionsJson);
  const credential = (await navigator.credentials.create({
    publicKey: options,
  })) as PublicKeyCredential | null;
  if (credential === null) {
    throw new Error("The browser produced no credential.");
  }
  return JSON.stringify(responseOf(credential));
}

/**
 * Proves a passkey for the options the server issued.
 *
 * @param optionsJson the server's options, as JSON
 * @returns the assertion, as the JSON the server verifies
 */
export async function provePasskey(optionsJson: string): Promise<string> {
  const options = parseRequestOptions(optionsJson);
  const credential = (await navigator.credentials.get({
    publicKey: options,
  })) as PublicKeyCredential | null;
  if (credential === null) {
    throw new Error("The browser produced no assertion.");
  }
  return JSON.stringify(responseOf(credential));
}

/**
 * The response, as the JSON the server verifies.
 *
 * `toJSON()` produces it where the browser has that method; otherwise the four base64url fields are
 * assembled here. Typed as `unknown` because both halves go straight to `JSON.stringify` and a
 * second model of what the browser already models would be a second thing to keep in step.
 *
 * @param credential what the authenticator produced
 * @returns the response to send
 */
function responseOf(credential: PublicKeyCredential): unknown {
  const withToJson = credential as PublicKeyCredential & { toJSON?: () => unknown };
  if (typeof withToJson.toJSON === "function") {
    return withToJson.toJSON();
  }

  const response = credential.response;
  const fields: Record<string, string> = {
    clientDataJSON: base64url(response.clientDataJSON),
  };
  if ("attestationObject" in response) {
    fields.attestationObject = base64url(
      (response as AuthenticatorAttestationResponse).attestationObject,
    );
  } else {
    const assertion = response as AuthenticatorAssertionResponse;
    fields.authenticatorData = base64url(assertion.authenticatorData);
    fields.signature = base64url(assertion.signature);
    if (assertion.userHandle !== null) {
      fields.userHandle = base64url(assertion.userHandle);
    }
  }
  return {
    id: credential.id,
    rawId: base64url(credential.rawId),
    type: credential.type,
    clientExtensionResults: {},
    response: fields,
  };
}

function parseCreationOptions(optionsJson: string): PublicKeyCredentialCreationOptions {
  const parser = window.PublicKeyCredential as typeof PublicKeyCredential & {
    parseCreationOptionsFromJSON?: (json: unknown) => PublicKeyCredentialCreationOptions;
  };
  const parsed: Record<string, unknown> = JSON.parse(optionsJson) as Record<string, unknown>;
  if (typeof parser.parseCreationOptionsFromJSON === "function") {
    return parser.parseCreationOptionsFromJSON(parsed);
  }
  const user = parsed.user as Record<string, unknown>;
  return {
    ...parsed,
    challenge: decode(parsed.challenge as string),
    user: { ...user, id: decode(user.id as string) },
    excludeCredentials: descriptors(parsed.excludeCredentials),
  } as unknown as PublicKeyCredentialCreationOptions;
}

function parseRequestOptions(optionsJson: string): PublicKeyCredentialRequestOptions {
  const parser = window.PublicKeyCredential as typeof PublicKeyCredential & {
    parseRequestOptionsFromJSON?: (json: unknown) => PublicKeyCredentialRequestOptions;
  };
  const parsed: Record<string, unknown> = JSON.parse(optionsJson) as Record<string, unknown>;
  if (typeof parser.parseRequestOptionsFromJSON === "function") {
    return parser.parseRequestOptionsFromJSON(parsed);
  }
  return {
    ...parsed,
    challenge: decode(parsed.challenge as string),
    allowCredentials: descriptors(parsed.allowCredentials),
  } as unknown as PublicKeyCredentialRequestOptions;
}

function descriptors(value: unknown): PublicKeyCredentialDescriptor[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return (value as Record<string, unknown>[]).map((descriptor) =>
    Object.assign({}, descriptor, { id: decode(descriptor.id as string) }),
  ) as unknown as PublicKeyCredentialDescriptor[];
}

/** base64url to bytes, which is how the specification writes every binary field. */
function decode(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded.padEnd(padded.length + ((4 - (padded.length % 4)) % 4), "="));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

/** Bytes to base64url, unpadded, which is what the server decodes. */
function base64url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

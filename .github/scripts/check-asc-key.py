#!/usr/bin/env python3
"""Asks App Store Connect whether the release's four secrets are usable.

A wrong key, a key of the wrong kind, a mistyped team ID or a missing app
record all end a release with the same unhelpful message from Xcode, after a
ten-minute Mac build. This asks Apple directly, in a few seconds, and says in
plain words which of them it is.

It reads ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 and APPLE_TEAM_ID from the
environment and prints none of them. Problems are printed as GitHub error
notes; the exit status is 1 if any was found.

Only the Python standard library and the openssl command are used.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com/v1"
APP_ID = "com.crdevtool.padelsync"
WATCH_ID = "com.crdevtool.padelsync.watchkitapp"
TITLE = "App Store Connect key"

problems = 0


def problem(text: str) -> None:
    global problems
    problems += 1
    print(f"::error title={TITLE}::{text}")


def warning(text: str) -> None:
    print(f"::warning title={TITLE}::{text}")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def raw_signature(der: bytes) -> bytes:
    """Turns openssl's signature (two numbers wrapped in DER) into the 64 plain bytes a token wants."""
    if der[0] != 0x30:
        raise ValueError("not a DER sequence")
    at = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = b""
    for _ in range(2):
        if der[at] != 0x02:
            raise ValueError("not a DER integer")
        length = der[at + 1]
        number = der[at + 2:at + 2 + length].lstrip(b"\x00")
        out += number.rjust(32, b"\x00")
        at += 2 + length
    return out


def key_file(text: str, folder: str) -> str:
    """Writes the key to a private file, accepting it as text or base64-encoded."""
    if "BEGIN PRIVATE KEY" not in text:
        try:
            text = base64.b64decode(text, validate=False).decode()
        except Exception:
            text = ""
    if "BEGIN PRIVATE KEY" not in text:
        problem("ASC_KEY_P8 is not an App Store Connect key. Paste the whole .p8 file, including its BEGIN and END lines.")
        sys.exit(1)
    path = os.path.join(folder, "AuthKey.p8")
    with open(os.open(path, os.O_WRONLY | os.O_CREAT, 0o600), "w") as out:
        out.write(text.strip() + "\n")
    return path


def token(key_id: str, issuer: str, key_path: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    claims = b64url(json.dumps({"iss": issuer, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}).encode())
    signing_input = f"{header}.{claims}".encode()
    signed = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path],
        input=signing_input, capture_output=True,
    )
    if signed.returncode != 0:
        problem("ASC_KEY_P8 cannot be used to sign. It must be the .p8 file exactly as downloaded, line breaks included.")
        sys.exit(1)
    return f"{header}.{claims}.{b64url(raw_signature(signed.stdout))}"


def get(path: str, query: dict, bearer: str):
    """Returns (HTTP status, decoded body). Network trouble is status 0."""
    url = f"{API}/{path}?{urllib.parse.urlencode(query)}"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {bearer}"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        try:
            body = json.load(error)
        except Exception:
            body = {}
        return error.code, body
    except Exception as error:  # DNS, time-out, TLS
        return 0, {"errors": [{"detail": str(error)}]}


def apple_said(body: dict) -> str:
    errors = body.get("errors") or [{}]
    return errors[0].get("detail") or errors[0].get("title") or "no reason given"


def main() -> int:
    values = {name: (os.environ.get(name) or "") for name in ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8", "APPLE_TEAM_ID")}
    missing = [name for name, value in values.items() if not value.strip()]
    if missing:
        problem("Secrets not configured: " + ", ".join(missing) + ".")
        return 1
    key_id = "".join(values["ASC_KEY_ID"].split())
    issuer = "".join(values["ASC_ISSUER_ID"].split())
    team = "".join(values["APPLE_TEAM_ID"].split())

    # The shapes Apple uses. A value of the wrong shape is usually one pasted
    # into the wrong secret, which Apple would only answer with "not allowed".
    if not (8 <= len(key_id) <= 12) or not key_id.isalnum():
        problem("ASC_KEY_ID does not look like a Key ID. It is the short code of letters and digits in the Key ID column next to the key, not the key's name or its file name.")
    if len(issuer) != 36 or issuer.count("-") != 4:
        problem("ASC_ISSUER_ID does not look like an Issuer ID. It is the long code with four dashes shown above the list of team keys. If the page shows no Issuer ID, the key is an individual key; create a team key.")
    if len(team) != 10 or not team.isalnum():
        problem("APPLE_TEAM_ID does not look like a Team ID. It is the 10 letters and digits under Membership details at developer.apple.com/account.")
    if problems:
        return 1

    with tempfile.TemporaryDirectory() as folder:
        bearer = token(key_id, issuer, key_file(values["ASC_KEY_P8"], folder))

    # 1. Does Apple accept the key at all, and is the app there?
    status, body = get("apps", {"filter[bundleId]": APP_ID, "fields[apps]": "bundleId,name", "limit": 20}, bearer)
    if status == 401:
        problem(
            "Apple does not accept this key (" + apple_said(body) + "). ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_P8 must all "
            "belong to one team key that has not been revoked: the Key ID from the key's row, the Issuer ID from above "
            "the list of team keys, and that key's own .p8 file."
        )
        return 1
    if status == 403:
        problem("Apple accepts the key but does not let it see the apps (" + apple_said(body) + "). Its role must be Admin.")
        return 1
    if status != 200:
        problem(f"App Store Connect could not be asked (status {status}: {apple_said(body)}). Try again in a few minutes.")
        return 1
    print("ok: Apple accepts the key")
    if any(app.get("attributes", {}).get("bundleId") == APP_ID for app in body.get("data", [])):
        print(f"ok: the app {APP_ID} exists in App Store Connect")
    else:
        problem(f"There is no app with the bundle ID {APP_ID} in App Store Connect. Create it under Apps > New App; an upload has nowhere to go without it.")

    # 2. Are both App IDs registered, and under the team named in APPLE_TEAM_ID?
    status, body = get(
        "bundleIds",
        {"filter[identifier]": APP_ID, "fields[bundleIds]": "identifier,seedId,platform", "limit": 50},
        bearer,
    )
    if status == 200:
        registered = {item["attributes"]["identifier"]: item["attributes"].get("seedId", "") for item in body.get("data", [])}
        for wanted in (APP_ID, WATCH_ID):
            if wanted in registered:
                print(f"ok: the App ID {wanted} is registered")
            else:
                problem(f"The App ID {wanted} is not registered under Certificates, Identifiers & Profiles > Identifiers.")
        teams = {seed for seed in registered.values() if seed}
        if teams and team not in teams:
            problem(
                "APPLE_TEAM_ID is not the team these App IDs belong to. Use the Team ID shown under Membership details "
                "at developer.apple.com/account; it is also the App ID Prefix shown on each identifier's page."
            )
        elif teams:
            print("ok: APPLE_TEAM_ID is the team the App IDs belong to")
    else:
        warning(f"The registered App IDs could not be read (status {status}: {apple_said(body)}).")

    # 3. Only an Admin key may list the team's users, which makes this a
    # cheap way to tell whether the key has the role that signing needs.
    status, body = get("users", {"limit": 1}, bearer)
    if status == 403:
        problem(
            "The key's role is not Admin. Signing an App Store build needs an Admin team key; a key's role cannot be "
            "changed, so create a new team key with Admin and replace ASC_KEY_ID and ASC_KEY_P8."
        )
    elif status == 200:
        print("ok: the key has the Admin role")
    else:
        warning(f"The key's role could not be checked (status {status}: {apple_said(body)}).")

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

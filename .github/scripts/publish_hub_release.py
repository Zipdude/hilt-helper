#!/usr/bin/env python3
"""Publish the Hilt Helper build currently distributed by RuneLite's Plugin Hub."""

import argparse
import base64
from dataclasses import dataclass
import hashlib
from itertools import chain
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


REPO = "Zipdude/hilt-helper"
HUB = "runelite/plugin-hub"
PLUGIN = "ironman-hilt-guide"
MARKER = f"plugins/{PLUGIN}"
CDN = "https://repo.runelite.net/plugins"
RAW = "https://raw.githubusercontent.com"
PROPERTIES = "runelite-plugin.properties"
FIRST_AUTOMATED_VERSION = (0, 5, 2)
SHA = re.compile(r"[0-9a-f]{40}")
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
JAR_HASH = r"[A-Za-z0-9_-]{43}"
BUILT_JAR = re.compile(rf"^built jar with hash ({JAR_HASH}) from commit ({SHA.pattern})$", re.M)
MAX_DOWNLOAD = 32 * 1024 * 1024


class Waiting(Exception):
    """The published Hub build is not yet eligible for a GitHub release."""


@dataclass(frozen=True)
class Pin:
    hub_commit: str
    source_commit: str
    client_version: str


def download(url, *, method="GET", data=None, headers=None, missing_ok=False):
    request = Request(url, data=data, headers={"User-Agent": "Hilt-Helper-Hub-Release"}, method=method)
    for key, value in (headers or {}).items():
        request.add_unredirected_header(key, value)
    try:
        with urlopen(request, timeout=20) as response:
            content = response.read(MAX_DOWNLOAD + 1)
    except HTTPError as error:
        if error.code == 404 and missing_ok:
            return None
        raise RuntimeError(f"{method} {url} returned HTTP {error.code}") from error
    if len(content) > MAX_DOWNLOAD:
        raise ValueError(f"Response exceeds the download limit: {url}")
    return content


def github(path, *, method="GET", payload=None, jar=None, missing_ok=False):
    host = "uploads.github.com" if jar is not None else "api.github.com"
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GH_TOKEN")
    if token and path.startswith(f"{REPO}/"):
        headers["Authorization"] = f"Bearer {token}"
    if jar is not None:
        headers["Content-Type"] = "application/java-archive"
        data = jar
    else:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode() if payload is not None else None
    result = download(f"https://{host}/repos/{path}", method=method, data=data,
                      headers=headers, missing_ok=missing_ok)
    return json.loads(result) if result else None


def pages(path, **query):
    page = 1
    while True:
        args = urlencode(dict(query, per_page=100, page=page))
        rows = github(f"{path}?{args}")
        yield from rows
        if len(rows) < 100:
            break
        page += 1


def properties(content):
    entries = (line.split("=", 1) for line in content.splitlines()
               if "=" in line and not line.lstrip().startswith(("#", "!")))
    return {key.strip(): value.strip() for key, value in entries}


def version_tuple(version):
    match = VERSION.fullmatch(version)
    return tuple(map(int, match.groups())) if match else None


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def hub_text(commit, path):
    return download(f"{RAW}/{HUB}/{commit}/{path}").decode().strip()


def read_pin():
    # Read the public ref over Git to avoid the anonymous REST API rate limit.
    hub_ref = "refs/heads/master"
    hub_commit, ref = git("ls-remote", "--exit-code", f"https://github.com/{HUB}.git", hub_ref).split()
    if ref != hub_ref or not SHA.fullmatch(hub_commit):
        raise ValueError("The Plugin Hub branch did not resolve to a full commit SHA")
    marker = properties(hub_text(hub_commit, MARKER))
    commit = marker.get("commit", "")
    if marker.get("repository", "").lower() != f"https://github.com/{REPO.lower()}.git":
        raise ValueError("The Hub descriptor does not point to the Hilt Helper repository")
    if not SHA.fullmatch(commit):
        raise ValueError("The Hub descriptor does not contain a full source commit SHA")
    client = hub_text(hub_commit, "runelite.version")
    if not version_tuple(client):
        raise ValueError(f"Unexpected RuneLite version {client!r}")
    return Pin(hub_commit, commit, client)


def source_version(pin):
    if subprocess.run(["git", "merge-base", "--is-ancestor", pin.source_commit, "HEAD"],
                      capture_output=True).returncode:
        raise Waiting(f"Waiting for approved source {pin.source_commit} on the default branch")
    version = properties(git("show", f"{pin.source_commit}:{PROPERTIES}")).get("version", "")
    parsed = version_tuple(version)
    if parsed is None:
        raise ValueError(f"Set a numeric major.minor.patch version in {PROPERTIES}")
    return version, parsed


def read_manifest(client, kind, public_key, directory):
    signed = download(f"{CDN}/manifest/{client}_{kind}.js", missing_ok=True)
    if signed is None:
        raise Waiting(f"Waiting for RuneLite {client}'s {kind} Plugin Hub manifest")
    # The RuneLite client reads a big-endian length, the RSA signature, then the JSON.
    signature_end = 4 + int.from_bytes(signed[:4], "big")
    if not 4 < signature_end < len(signed):
        raise ValueError("Invalid signed Plugin Hub manifest envelope")
    signature = directory / "signature.bin"
    signature.write_bytes(signed[4:signature_end])
    data = signed[signature_end:]
    result = subprocess.run(
        ["openssl", "dgst", "-sha256", "-verify", str(public_key), "-signature", str(signature)],
        input=data, capture_output=True,
    )
    if result.returncode:
        raise ValueError(f"RuneLite {client}'s {kind} manifest signature is invalid")
    return json.loads(data)


def manifest_entry(manifest, section):
    matches = [row for row in manifest[section] if row["internalName"] == PLUGIN]
    if len(matches) > 1:
        raise ValueError(f"The Hub manifest has duplicate {PLUGIN} entries in {section}")
    return matches[0] if matches else {}


def log_matches(client, commit, jar_hash):
    log = download(f"{CDN}/log/{PLUGIN}/{client}_{commit}.log", missing_ok=True)
    return log is not None and (jar_hash, commit) in BUILT_JAR.findall(log.decode())


def built_from_pin(pin, jar_hash):
    if log_matches(pin.client_version, pin.source_commit, jar_hash):
        return True

    # After a client update the Hub can reuse a JAR from an earlier client build.
    approval_args = urlencode(dict(sha=pin.hub_commit, path=MARKER, per_page=1))
    approval, = github(f"{HUB}/commits?{approval_args}")
    since = approval["commit"]["committer"]["date"]
    versions = {pin.client_version}
    changes = pages(f"{HUB}/commits", sha=pin.hub_commit, path="runelite.version", since=since)
    for update in chain(changes, (approval,)):
        client = hub_text(update["sha"], "runelite.version")
        if client in versions:
            continue
        versions.add(client)
        if version_tuple(client) and log_matches(client, pin.source_commit, jar_hash):
            return True
    return False


def verified_jar(pin, version):
    # Same certificate path used by ExternalPluginClient, at the official client tag.
    cert_url = (f"{RAW}/runelite/runelite/runelite-parent-{pin.client_version}/"
                "runelite-client/src/main/resources/net/runelite/client/externalplugins/externalplugins.crt")
    certificate = download(cert_url, missing_ok=True)
    if certificate is None:
        raise Waiting(f"Waiting for RuneLite {pin.client_version}'s client certificate")
    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        public_key = directory / "runelite.pem"
        key = subprocess.check_output(
            ["openssl", "x509", "-pubkey", "-noout"], input=certificate,
        )
        public_key.write_bytes(key)
        full = read_manifest(pin.client_version, "full", public_key, directory)
        display = manifest_entry(full, "display")
        if display.get("version") != version or display.get("unavailableReason"):
            raise Waiting(f"Waiting for version {version} in the live Plugin Hub catalogue")
        lite = read_manifest(pin.client_version, "lite", public_key, directory)

    listing = manifest_entry(full, "jars")
    if not listing or listing != manifest_entry(lite, "jars"):
        raise Waiting(f"Waiting for version {version} in the live Plugin Hub download manifest")
    jar_hash = listing["jarHash"]
    if not re.fullmatch(JAR_HASH, jar_hash) or not 0 < listing["jarSize"] <= MAX_DOWNLOAD:
        raise ValueError("Unexpected JAR digest or size in the signed manifest")
    if not built_from_pin(pin, jar_hash):
        raise Waiting(f"Waiting for the Hub build log tying the live JAR to {pin.source_commit}")
    jar = download(f"{CDN}/jar/{PLUGIN}_{jar_hash}.jar", missing_ok=True)
    if jar is None:
        raise Waiting(f"Waiting for the Hub JAR for version {version}")
    actual = base64.urlsafe_b64encode(hashlib.sha256(jar).digest()).decode().rstrip("=")
    if len(jar) != listing["jarSize"] or actual != jar_hash:
        raise ValueError("The downloaded JAR does not match the signed Plugin Hub manifest")
    return jar


def release_to_finish(tag, parsed):
    draft = None
    for release in pages(f"{REPO}/releases"):
        existing = release["tag_name"]
        if release["draft"]:
            if existing == tag:
                draft = release
            continue
        number = version_tuple(existing.removeprefix("v"))
        if existing == tag or (number and number >= parsed and not release["prerelease"]):
            raise Waiting(f"GitHub already has release {existing}; no changes needed")
    return draft


def ensure_tag(tag, commit):
    reference = github(f"{REPO}/git/ref/tags/{tag}", missing_ok=True)
    if reference is None:
        github(f"{REPO}/git/refs", method="POST", payload={"ref": f"refs/tags/{tag}", "sha": commit})
        return
    target = reference["object"]
    while target["type"] == "tag":
        target = github(f"{REPO}/git/tags/{target['sha']}")["object"]
    if target["type"] != "commit" or target["sha"] != commit:
        raise ValueError(f"Existing tag {tag} points to a different source commit")


def verify_draft(draft, marker):
    if not draft["draft"] or marker not in (draft["body"] or "").splitlines():
        raise ValueError(f"A release for {draft['tag_name']} already exists outside this workflow")


def upload_jar(draft, name, jar, digest):
    assets = draft["assets"]
    if any(asset["name"] != name for asset in assets):
        raise ValueError("The release draft contains an unexpected asset")
    if assets:
        asset, = assets
        if asset["state"] == "uploaded" and asset["digest"] == digest:
            return
        # Only clear a failed or outdated upload on a workflow-owned draft.
        github(f"{REPO}/releases/assets/{asset['id']}", method="DELETE")
    args = urlencode({"name": name})
    uploaded = github(f"{REPO}/releases/{draft['id']}/assets?{args}", method="POST", jar=jar)
    if uploaded["digest"] != digest or uploaded["state"] != "uploaded":
        raise ValueError("GitHub did not confirm the verified JAR upload")


def publish_release(pin, version, parsed, draft, jar):
    tag = f"v{version}"
    marker = f"<!-- hilt-helper-hub-release {pin.source_commit} -->"
    if draft:
        verify_draft(draft, marker)
    ensure_tag(tag, pin.source_commit)
    if draft is None:
        notes_file = Path(__file__).resolve().parents[1] / "release-notes" / f"{tag}.md"
        notes = notes_file.read_text(encoding="utf-8").strip() + "\n\n" if notes_file.exists() else ""
        source = f"https://github.com/{HUB}/blob/{pin.hub_commit}/{MARKER}"
        body = (f"{notes}The JAR is the build distributed by the RuneLite Plugin Hub. "
                f"[Approved source]({source}).\n\n{marker}")
        draft = github(f"{REPO}/releases", method="POST", payload={
            "tag_name": tag, "target_commitish": pin.source_commit, "name": f"Hilt Helper {version}",
            "body": body, "draft": True, "generate_release_notes": True,
        })
    name = f"hilt-helper-{version}.jar"
    digest = f"sha256:{hashlib.sha256(jar).hexdigest()}"
    upload_jar(draft, name, jar, digest)

    latest_pin = read_pin()
    if (pin.source_commit, pin.client_version) != (latest_pin.source_commit, latest_pin.client_version):
        raise Waiting("The Hub approved source or client changed during upload; will recheck")
    latest_draft = release_to_finish(tag, parsed)
    if latest_draft is None or latest_draft["id"] != draft["id"]:
        raise ValueError(f"The verified release draft for {tag} changed during upload")
    verify_draft(latest_draft, marker)
    assets = [(asset["name"], asset["state"], asset["digest"]) for asset in latest_draft["assets"]]
    if assets != [(name, "uploaded", digest)]:
        raise ValueError("The release draft no longer contains the verified Hub JAR")
    ensure_tag(tag, pin.source_commit)
    release = github(f"{REPO}/releases/{draft['id']}", method="PATCH", payload={
        "draft": False, "make_latest": "true",
    })
    print(f"Published {release['html_url']} with {name} ({digest})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="publish an eligible GitHub release")
    args = parser.parse_args()
    if args.publish and not os.environ.get("GH_TOKEN"):
        parser.error("--publish requires GH_TOKEN")
    try:
        pin = read_pin()
        version, number = source_version(pin)
        if number < FIRST_AUTOMATED_VERSION:
            raise Waiting(f"Hub still pins {version}; automated releases start at 0.5.2")
        draft = release_to_finish(f"v{version}", number)
        jar = verified_jar(pin, version)
        if args.publish:
            publish_release(pin, version, number, draft, jar)
        else:
            print(f"Ready to release {version} from {pin.source_commit}")
    except Waiting as waiting:
        print(waiting)
    except (KeyError, OSError, RuntimeError, TypeError, ValueError, URLError,
            subprocess.CalledProcessError) as error:
        sys.exit(f"Release check failed: {error}")


if __name__ == "__main__":
    main()

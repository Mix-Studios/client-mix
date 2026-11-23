#!/usr/bin/env python3
"""Upload locally built launcher files to a GitHub release.

The CI workflow builds every platform and publishes a release on its own. This
script does the same job from a machine that has the files, for the cases where
a build was made by hand: it stages the files, writes the checksums.txt and
latest.json the site and the launcher's updater read, and then creates or
updates the GitHub release with gh.

    python3 tools/upload_release.py v1.2.0
    python3 tools/upload_release.py v1.2.0 build/libs/MixClient-1.2.0-all.jar build/installer/dist
    python3 tools/upload_release.py v1.2.0-test --prerelease --dry-run

With no paths it picks up build/installer/dist and
build/libs/MixClient-<version>-all.jar. The tag must already exist on the remote
(the release's download URLs point at it); run `git push origin v1.2.0` first.

The metadata rules mirror .github/workflows/release.yml on purpose: the launcher
refuses files without a SHA-256 and the website ranks files by platform, so the
two producers must agree on the schema and the pre-release rule.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_REPO = "Mix-Studios/client-mix"
ROOT = Path(__file__).resolve().parent.parent

# format -> (platform, arch); the arch is refined from the file name.
FORMATS = {
    ".msi": ("windows", "x64"),
    ".dmg": ("macos", None),
    ".deb": ("linux", "x64"),
    ".rpm": ("linux", "x64"),
    ".jar": ("any", "any"),
}

# Written by this script, never uploaded as a build.
METADATA = {"checksums.txt", "latest.json", "notes.md"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_prerelease_tag(tag: str) -> bool:
    lowered = tag.lower()
    named = re.search(
        r"(^|[^a-z])(test|beta|alpha|preview|snapshot|nightly|canary|rc\d*)([^a-z]|$)",
        lowered,
    )
    shaped = re.match(r"^v?\d+(\.\d+)*$", lowered)
    return bool(named) or not shaped


def repo_slug() -> str:
    try:
        url = subprocess.run(
            ["git", "-C", str(ROOT), "remote", "get-url", "origin"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return DEFAULT_REPO

    match = re.search(r"github\.com[:/]+([^/]+/[^/]+?)(?:\.git)?$", url)
    return match.group(1) if match else DEFAULT_REPO


def collect(sources: list[Path]) -> list[Path]:
    """Every supported build file named by the sources, without duplicates."""
    files: list[Path] = []
    names: set[str] = set()

    for source in sources:
        if not source.exists():
            raise SystemExit(f"upload-release: {source} does not exist")

        candidates = sorted(source.iterdir()) if source.is_dir() else [source]
        for candidate in candidates:
            if not candidate.is_file() or candidate.name.startswith("._"):
                continue
            if candidate.name in METADATA:
                continue
            if candidate.suffix.lower() not in FORMATS:
                print(f"  skipping {candidate} (not an msi/dmg/deb/rpm/jar)")
                continue
            if candidate.name in names:
                raise SystemExit(
                    f"upload-release: two files are called {candidate.name}; "
                    "rename one or upload them separately"
                )
            if candidate.suffix.lower() == ".jar" and not candidate.name.endswith("-all.jar"):
                print(f"  warning: {candidate.name} is not the runnable -all jar")
            names.add(candidate.name)
            files.append(candidate)

    for source in sources:
        if source.is_dir() and not any(f.parent == source for f in files):
            raise SystemExit(f"upload-release: {source} has no msi/dmg/deb/rpm/jar files")
    return files


def stage(files: list[Path], staging: Path) -> list[Path]:
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    staged = []
    for file in files:
        target = staging / file.name
        shutil.copy2(file, target)
        staged.append(target)
    return staged


def write_checksums(staging: Path, staged: list[Path]) -> Path:
    lines = [f"{sha256(file)}  {file.name}" for file in staged]
    path = staging / "checksums.txt"
    path.write_text("\n".join(sorted(lines, key=lambda line: line.split("  ", 1)[1])) + "\n",
                    encoding="utf-8")
    return path


def write_latest(staging: Path, staged: list[Path], tag: str, repo: str,
                 prerelease: bool) -> dict:
    version = tag.lstrip("vV")
    files = []
    for file in sorted(staged, key=lambda item: item.name):
        extension = file.suffix.lower()
        if extension not in FORMATS:
            continue
        platform, arch = FORMATS[extension]
        if arch is None:
            if re.search(r"arm64|aarch64", file.name, re.I):
                arch = "arm64"
            elif re.search(r"x64|x86_64|amd64", file.name, re.I):
                arch = "x64"
            else:
                arch = "any"
        files.append({
            "name": file.name,
            "platform": platform,
            "arch": arch,
            "format": extension.lstrip("."),
            "installer": extension != ".jar",
            "size": file.stat().st_size,
            "sha256": sha256(file),
            "url": f"https://github.com/{repo}/releases/download/{tag}/{file.name}",
        })

    latest = {
        "schema": 1,
        "version": tag,
        "version_number": version,
        "name": f"Mix Client {tag}",
        "published_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "prerelease": prerelease,
        "release_url": f"https://github.com/{repo}/releases/tag/{tag}",
        "checksums_url": f"https://github.com/{repo}/releases/download/{tag}/checksums.txt",
        "files": files,
    }
    (staging / "latest.json").write_text(json.dumps(latest, indent=2) + "\n", encoding="utf-8")
    return latest


def write_notes(staging: Path, checksums: Path, repo: str, prerelease: bool) -> Path:
    lines = [
        "Installers for Windows, macOS (Apple silicon and Intel) and Linux (DEB and RPM), "
        "plus the plain runnable jar. Each one bundles its own Java runtime.",
        "",
        "The client mod is inside every download: the launcher installs the matching client "
        "into your mods folder when you press Play, and the launcher updates itself when a "
        "newer version is released.",
        "",
        "The builds are unsigned. macOS blocks the first launch of an unsigned app: "
        "right-click MixClient in Applications and choose Open, or run "
        "`xattr -dr com.apple.quarantine /Applications/MixClient.app`. Windows shows a "
        "SmartScreen prompt: choose More info, then Run anyway.",
        "",
        "**checksums.txt**",
        "",
        "```",
        checksums.read_text(encoding="utf-8").rstrip(),
        "```",
        "",
        "**Machine-readable**",
        "",
        "`latest.json` lists the version and every file with its platform, size and SHA-256, "
        "so the launcher can check for its own update.",
        "",
        "**Verify**",
        "",
        "```sh",
        "sha256sum -c checksums.txt    # macOS: shasum -a 256 -c checksums.txt",
        f"gh attestation verify <file> --repo {repo}",
        "```",
    ]
    if prerelease:
        lines += [
            "",
            "**Test build.** This tag is not a stable version, so it is published as a "
            "pre-release and is not offered as the main download.",
        ]
    path = staging / "notes.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def run(command: list[str], dry_run: bool) -> None:
    print("$ " + " ".join(command))
    if dry_run:
        return
    try:
        subprocess.run(command, check=True)
    except FileNotFoundError:
        raise SystemExit("upload-release: gh is not installed; install it from "
                         "https://cli.github.com and run `gh auth login`")
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"upload-release: gh failed with exit code {error.returncode}")


def upload(tag: str, repo: str, files: list[Path], notes: Path, prerelease: bool,
           dry_run: bool) -> None:
    if shutil.which("gh") is None:
        raise SystemExit("upload-release: gh is not installed; install it from "
                         "https://cli.github.com and run `gh auth login`")

    if dry_run:
        print("(dry run: not asking gh about the release)")
        exists = False
    else:
        exists = subprocess.run(
            ["gh", "release", "view", tag, "--repo", repo],
            capture_output=True, text=True,
        ).returncode == 0

    if exists:
        run(["gh", "release", "upload", tag, *[str(file) for file in files],
             "--repo", repo, "--clobber"], dry_run)
        if prerelease:
            run(["gh", "release", "edit", tag, "--repo", repo, "--prerelease"], dry_run)
        return

    command = ["gh", "release", "create", tag, *[str(file) for file in files],
               "--repo", repo, "--title", f"Mix Client {tag}",
               "--notes-file", str(notes), "--verify-tag"]
    if prerelease:
        command.append("--prerelease")
    run(command, dry_run)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="upload_release.py",
        description="Publish built MixClient files as a GitHub release.",
    )
    parser.add_argument("tag", help="release tag, e.g. v1.2.0 (it must already be pushed)")
    parser.add_argument("paths", nargs="*", type=Path,
                        help="files or directories to upload; defaults to the usual build outputs")
    parser.add_argument("--repo", default=repo_slug(), help=f"GitHub repository (default {DEFAULT_REPO})")
    parser.add_argument("--dir", type=Path,
                        help="staging directory (default build/release/<tag>)")
    parser.add_argument("--notes", type=Path, help="release body to use instead of the generated one")
    parser.add_argument("--prerelease", action="store_true",
                        help="publish as a pre-release even if the tag looks stable")
    parser.add_argument("--dry-run", action="store_true",
                        help="write checksums.txt and latest.json, print the gh commands, upload nothing")
    args = parser.parse_args(argv)

    tag = args.tag.strip()
    if not tag:
        parser.error("a tag is required")

    version = tag.lstrip("vV")
    prerelease = args.prerelease or is_prerelease_tag(tag)

    sources = list(args.paths)
    if not sources:
        installer_dir = ROOT / "build/installer/dist"
        jar = ROOT / f"build/libs/MixClient-{version}-all.jar"
        if installer_dir.is_dir():
            sources.append(installer_dir)
        if jar.is_file():
            sources.append(jar)
        if not sources:
            raise SystemExit(
                "upload-release: nothing to upload; build first "
                f"(expected {installer_dir} or {jar}) or pass files explicitly")

    files = collect(sources)
    if not files:
        raise SystemExit("upload-release: no supported files to upload")

    staging = args.dir or ROOT / "build/release" / tag
    if not staging.is_absolute():
        staging = ROOT / staging
    staged = stage(files, staging)
    checksums = write_checksums(staging, staged)
    latest = write_latest(staging, staged, tag, args.repo, prerelease)
    notes = args.notes if args.notes else write_notes(staging, checksums, args.repo, prerelease)
    if args.notes and not args.notes.is_file():
        raise SystemExit(f"upload-release: {args.notes} does not exist")

    print(f"release {tag} -> {args.repo}"
          + (" (pre-release)" if prerelease else ""))
    for entry in latest["files"]:
        print(f"  {entry['platform']:<7} {entry['arch']:<5} {entry['format']:<3} "
              f"{entry['sha256'][:12]}  {entry['name']}  ({entry['size']} bytes)")
    print(f"  staged in {staging}")

    upload(tag, args.repo,
           staged + [checksums, staging / "latest.json"],
           notes, prerelease, args.dry_run)

    if args.dry_run:
        print("dry run: nothing was uploaded")
    else:
        print(f"published https://github.com/{args.repo}/releases/tag/{tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

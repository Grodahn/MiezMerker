"""Deployment format. Only finished CI outputs are packaged; never builds code."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import zipfile

class ContractError(ValueError):
    """A controlled diagnostic safe to log without request/credential data."""


POLICY = "FIELD_ALPHA_PHONE_CLOCK"
SHA = re.compile(r"[0-9a-f]{40}")
MAX_BYTES = 512 * 1024 * 1024


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def migrations(jar):
    with zipfile.ZipFile(jar) as archive:
        return {n.removeprefix("BOOT-INF/classes/db/migration/"):
                hashlib.sha256(archive.read(n)).hexdigest()
                for n in sorted(archive.namelist())
                if n.startswith("BOOT-INF/classes/db/migration/") and n.endswith((".sql", ".class"))}


def schema_id(items):
    return hashlib.sha256(json.dumps(items, sort_keys=True).encode()).hexdigest()


def package(root, output, sha, run_id, attempt):
    if not SHA.fullmatch(sha):
        raise ContractError("Full commit SHA required")
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(root / "backend/target/backend-0.1.0-SNAPSHOT.jar", output / "backend.jar")
    shutil.copytree(root / "app/dist", output / "public")
    # This directory is separate from dist-signed. Record the compiled variant
    # before either browser fixture is used. Never include environment/config.
    policy = json.loads((output / "public/build.json").read_text())
    if policy != {"sha": sha, "policy": POLICY}:
        raise ContractError("PWA commit/policy does not match this CI run")
    migration_map = migrations(output / "backend.jar")
    if not migration_map or not (output / "public/sw.js").is_file():
        raise ContractError("Missing migrations or production service worker")
    manifest = dict(format=1, sha=sha, run_id=int(run_id), attempt=int(attempt),
                    policy=POLICY, migrations=migration_map,
                    schema=schema_id(migration_map),
                    files={p.relative_to(output).as_posix(): digest(p)
                           for p in sorted(output.rglob("*")) if p.is_file()})
    (output / "manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")


def extract(archive_path, target):
    """No traversal, links, duplicates, bombs, or unbounded expanded archives."""
    with zipfile.ZipFile(archive_path) as archive:
        seen = set()
        total = 0
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            total += entry.file_size
            if (not path.parts or path.is_absolute() or ".." in path.parts
                    or "\\" in entry.filename or ":" in entry.filename
                    or entry.filename in seen or total > MAX_BYTES
                    or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ContractError("Unsafe artifact archive")
            seen.add(entry.filename)
        archive.extractall(target)


def verify(root, sha, run_id, attempt, *, allow_legacy=False):
    manifest = json.loads((root / "manifest.json").read_text())
    for key, value in dict(format=1, sha=sha, run_id=run_id, attempt=attempt, policy=POLICY).items():
        if manifest.get(key) != value:
            raise ContractError("Artifact identity mismatch: " + key)
    # The root manifest is the only excluded file; nested manifests are payload.
    actual = {p.relative_to(root).as_posix(): digest(p)
              for p in root.rglob("*") if p.is_file() and p != root / "manifest.json"}
    if actual != manifest["files"] or not {"backend.jar", "public/index.html", "public/sw.js", "public/build.json"} <= actual.keys():
        raise ContractError("Artifact checksum/completeness mismatch")
    migration_map = migrations(root / "backend.jar")
    # Already admitted rollback targets may predate Java fingerprints. Their
    # complete JAR checksum remains mandatory. Never admit a new such bundle.
    if allow_legacy and not any(n.endswith(".class") for n in manifest["migrations"]):
        migration_map = {n: h for n, h in migration_map.items() if n.endswith(".sql")}
    if not migration_map or migration_map != manifest["migrations"] or schema_id(migration_map) != manifest["schema"]:
        raise ContractError("Migration manifest mismatch")
    if json.loads((root / "public/build.json").read_text()) != {"sha": sha, "policy": POLICY}:
        raise ContractError("PWA identity mismatch")
    return manifest


if __name__ == "__main__":
    package(Path.cwd(), Path(sys.argv[1]), os.environ["GITHUB_SHA"],
            os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"])

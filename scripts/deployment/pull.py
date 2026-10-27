"""Small native pull deployer. Configuration and credentials live outside source.

Run as a dedicated unprivileged user. Its only privileged operations are the
configured application's systemd start/stop, authorized by a narrow polkit rule.
"""
import json
import os
from pathlib import Path
import shutil
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

from artifacts import ContractError, MAX_BYTES, SHA, extract, verify
from github import GATE, GitHub, approval, trusted


def save(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as output:
        json.dump(value, output, sort_keys=True)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class Deployer:
    def __init__(self, config, api):
        self.config, self.api = config, api
        self.root = Path(config["root"])
        self.releases = self.root / "releases"
        self.state_path = self.root / "state.json"
        self.pending = self.root / "transaction.json"
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else {}

    def command(self, action):
        subprocess.run([self.config["systemctl"], "--no-ask-password", action,
                        self.config["service"]], check=True, timeout=180)

    def switch(self, sha):
        link = self.root / "current"
        if sha is None:
            link.unlink(missing_ok=True)
        else:
            if not SHA.fullmatch(sha):
                raise ContractError("Invalid release pointer")
            temporary = self.root / "current.next"
            temporary.unlink(missing_ok=True)
            temporary.symlink_to("releases/" + sha)
            temporary.replace(link)
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    def healthy(self, sha):
        context = ssl.create_default_context(cafile=self.config.get("ca_file"))
        for _ in range(self.config.get("health_attempts", 60)):
            try:
                with urllib.request.urlopen(self.config["health_url"], timeout=3, context=context) as response:
                    up = json.load(response) == {"status": "UP"}
                with urllib.request.urlopen(self.config["receipt_url"], timeout=3, context=context) as response:
                    receipt = json.load(response)
                if up and receipt == {"sha": sha, "policy": "FIELD_ALPHA_PHONE_CLOCK"}:
                    return True
            except (OSError, ValueError):
                pass
            time.sleep(self.config.get("health_delay", 2))
        return False

    def reject(self, sha):
        rejected = self.state.setdefault("rejected", [])
        self.state["rejected"] = (rejected + [sha])[-32:]
        save(self.state_path, self.state)

    def recover(self):
        if not self.pending.exists():
            return
        transaction = json.loads(self.pending.read_text())
        # A durable journal precedes ANY stop/switch/start. On power loss do
        # not infer success from a stale health response. Restore the previous
        # known-good release. All admitted schema changes require compatibility.
        try:
            self.command("stop")
            self.switch(transaction["old"])
            if transaction["old"]:
                self.command("reset-failed")
                self.command("start")
                if not self.healthy(transaction["old"]):
                    raise RuntimeError("Previous application failed recovery; operator required")
        except Exception:
            save(self.root / "halt.json", {"reason": "Recovery failed; operator required"})
            raise
        self.state = transaction["old_state"]
        # A JAR rollback never rolls Flyway back. Conservatively keep every
        # possibly applied migration in the database high-water mark.
        if transaction["old_state"].get("database_schema") != transaction["database_schema"]:
            # Approval covers returning to old, not the even older release
            # recorded in old_state.previous. That earlier compatibility proof
            # predates this possibly applied migration set.
            self.state["rollback_compatible"] = False
        self.state.update(database_schema=transaction["database_schema"],
                          database_migrations=transaction["database_migrations"])
        self.reject(transaction["new"])
        self.pending.unlink()
        self.retain()
        print("Recovered interrupted/failed deployment; candidate quarantined", flush=True)

    def migration_guard(self, manifest):
        old = self.state.get("sha")
        previous = json.loads((self.releases / old / "manifest.json").read_text()) if old else None
        if self.state.get("database_schema"):
            previous = {"schema": self.state["database_schema"],
                        "migrations": self.state["database_migrations"]}
        if previous and previous["schema"] == manifest["schema"]:
            return
        if previous and any(manifest["migrations"].get(n) != h for n, h in previous["migrations"].items()):
            raise ContractError("Applied Flyway migrations changed/removed; refusing update")
        path = Path(self.config["migration_approvals"]) / (manifest["sha"] + ".json")
        if not path.is_file() or path.is_symlink():
            raise ContractError("Schema/initial deployment requires local migration approval and backup")
        info = path.stat()
        if info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022:
            raise ContractError("Migration approval must be root-owned and not writable by others")
        allowed = json.loads(path.read_text())
        if (allowed.get("sha") != manifest["sha"]
                or allowed.get("from_schema") != (previous["schema"] if previous else None)
                or allowed.get("to_schema") != manifest["schema"]
                or allowed.get("rollback_compatible") is not True
                or not 0 <= time.time() - allowed.get("backup_completed_at", 0) <= 86400
                or not time.time() < allowed.get("expires_at", 0)):
            raise ContractError("Migration approval/backup is stale or incompatible")
        # Local gate validates the actual USB identity and last successful backup.
        subprocess.run([self.config["backup_guard"]], check=True, timeout=10)

    def install(self, folder, manifest, run, rollback=False):
        sha = manifest["sha"]
        if rollback:
            if sha != self.state.get("previous") or self.state.get("rollback_compatible") is not True:
                raise ContractError("No recorded compatible previous release")
            database = {"schema": self.state["database_schema"], "migrations": self.state["database_migrations"]}
        else:
            self.migration_guard(manifest)
            database = manifest
        release = self.releases / sha
        # Every downloaded byte was checked before the versioned directory exists.
        # Files are durable before current can ever reference them.
        for path in folder.rglob("*"):
            path.chmod(0o755 if path.is_dir() else 0o644)
            if path.is_file():
                with path.open("rb") as stream:
                    os.fsync(stream.fileno())
        folder.chmod(0o755)
        if folder != release:
            if release.exists():
                raise ContractError("Release directory already exists; operator must inspect")
            folder.replace(release)
        os.sync()
        transaction = {"old": self.state.get("sha"), "new": sha,
                       "old_state": json.loads(json.dumps(self.state)),
                       "database_schema": database["schema"],
                       "database_migrations": database["migrations"]}
        save(self.pending, transaction)
        try:
            self.command("stop")
            self.switch(sha)
            self.command("reset-failed")
            self.command("start")
            if not self.healthy(sha):
                raise RuntimeError("New application failed healthcheck")
        except Exception:
            self.recover()
            raise
        self.state.update(sha=sha, previous=transaction["old"], run_id=run["id"],
                          run_number=run["run_number"], deployed_at=int(time.time()),
                          database_schema=database["schema"], database_migrations=database["migrations"],
                          rollback_compatible=bool(transaction["old"]), held=rollback)
        if rollback:
            self.state["rejected"] = (self.state.get("rejected", []) + [transaction["old"]])[-32:]
        save(self.state_path, self.state)
        self.pending.unlink()
        self.retain()
        print("Deployed " + sha, flush=True)

    def retain(self):
        # Current, previous and the newest extra version; no data/secret paths.
        protected = {self.state.get("sha"), self.state.get("previous")}
        extras = sorted((p for p in self.releases.iterdir() if p.name not in protected
                         and SHA.fullmatch(p.name) and p.is_dir() and not p.is_symlink()),
                        key=lambda p: p.stat().st_mtime, reverse=True)
        for path in extras[1:]:
            shutil.rmtree(path)

    def poll(self):
        if (self.root / "halt.json").exists():
            raise RuntimeError("Deployment halted after recovery failure; operator required")
        self.recover()
        if self.state.get("held"):
            print("Polling held after operator rollback; explicit --resume required")
            return
        runs = self.api.get("/actions/workflows/ci.yml/runs?event=push&branch=main&per_page=20")["workflow_runs"]
        eligible = [r for r in runs if trusted(r, self.api.repository)
                    and r["status"] == "completed" and r["conclusion"] == "success"]
        if not eligible:
            print("No successful main candidate")
            return
        run = max(eligible, key=lambda r: r["run_number"])
        sha = run["head_sha"]
        if not SHA.fullmatch(sha):
            raise ContractError("Invalid main SHA")
        if (sha == self.state.get("sha") or sha in self.state.get("rejected", [])
                or run["run_number"] <= self.state.get("run_number", 0)):
            print("Unchanged/quarantined version; no downloads or restart")
            return
        # Query metadata by exact name. No marker/payload download without a
        # completed successful trusted approval run. Expired artifacts fail closed.
        markers = self.api.get("/actions/artifacts?name=deployment-approved-" + sha + "&per_page=100")["artifacts"]
        marker = None
        for item in markers:
            gate = self.api.get(f'/actions/runs/{item["workflow_run"]["id"]}')
            if (not item["expired"] and gate["path"] == ".github/workflows/" + GATE
                    and gate["event"] == "workflow_run" and gate["head_branch"] == "main"
                    and gate["repository"]["full_name"].lower() == self.api.repository.lower()
                    and gate["head_repository"]["full_name"].lower() == self.api.repository.lower()
                    and gate["status"] == "completed" and gate["conclusion"] == "success"):
                marker = item
                break
        if marker is None:
            print("Candidate awaits full CI deployment approval")
            return
        release = self.releases / sha
        staged = self.root / "staged.json"
        if release.is_dir() and staged.exists():
            cached = json.loads(staged.read_text())
            expected = approval(self.api, sha)
            if cached != expected or expected["run_id"] != run["id"]:
                raise ContractError("Staged candidate CI identity changed; operator required")
            try:
                manifest = verify(release, sha, expected["run_id"], expected["attempt"])
            except (ValueError, zipfile.BadZipFile):
                self.reject(sha)
                raise
            self.install(release, manifest, run)
            staged.unlink()
            return
        # A previous process may have died during download. This root belongs
        # solely to the deploy user and is protected by the operation lock.
        temporary = self.root / "download"
        if temporary.exists():
            shutil.rmtree(temporary)
        temporary.mkdir(mode=0o700)
        try:
            self.api.download(marker, temporary / "approval.zip", 1024 * 1024)
            extract(temporary / "approval.zip", temporary / "approval")
            declared = json.loads((temporary / "approval/approved.json").read_text())
            expected = approval(self.api, sha)
            if declared != expected or expected["run_id"] != run["id"]:
                raise ContractError("Approval no longer matches final CI attempts")
            artifact = self.api.get(f'/actions/artifacts/{expected["artifact_id"]}')
            if artifact["digest"] != expected["digest"]:
                raise ContractError("Candidate changed after approval")
            if shutil.disk_usage(self.root).free < 2 * 1024**3:
                raise ContractError("Less than 2 GiB SD space; refusing download")
            try:
                self.api.download(artifact, temporary / "payload.zip", MAX_BYTES)
                folder = temporary / "payload"
                extract(temporary / "payload.zip", folder)
                manifest = verify(folder, sha, expected["run_id"], expected["attempt"])
            except (ValueError, zipfile.BadZipFile):
                self.reject(sha)
                raise
            if approval(self.api, sha) != declared:
                raise ContractError("CI changed during download")
            save(staged, declared)
            folder.replace(release)
            self.retain()
            self.install(release, manifest, run)
            staged.unlink()
        finally:
            shutil.rmtree(temporary)

    def rollback(self):
        if self.pending.exists() or (self.root / "halt.json").exists():
            raise ContractError("Resolve interrupted/failed recovery before manual rollback")
        sha = self.state.get("previous") or ""
        if not SHA.fullmatch(sha):
            raise ContractError("No previous release")
        folder = self.releases / sha
        identity = json.loads((folder / "manifest.json").read_text())
        manifest = verify(folder, sha, identity["run_id"], identity["attempt"])
        self.install(folder, manifest, {"id": self.state["run_id"], "run_number": self.state["run_number"]}, rollback=True)


def main():
    # POSIX-only locking and filesystem durability are intentional: no build
    # tools are installed or invoked on the application host.
    import fcntl
    config = json.loads(Path(sys.argv[1]).read_text())
    with open(config["operation_lock"], "r+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Backup/deployment already running; skipped")
            return
        mode = sys.argv[2] if len(sys.argv) > 2 else "--poll"
        if mode == "--rollback":
            Deployer(config, None).rollback()
        elif mode == "--resume":
            deployer = Deployer(config, None)
            deployer.state["held"] = False
            save(deployer.state_path, deployer.state)
            print("Polling resumed; quarantined commits remain excluded")
        elif mode == "--poll":
            token = (Path(os.environ["CREDENTIALS_DIRECTORY"]) / "github-token").read_text().strip()
            Deployer(config, GitHub(config["repository"], token)).poll()
        else:
            raise ContractError("Use --poll, --rollback or --resume")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print("Deployment failed: GitHub HTTP", error.code, file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # HTTP/network exception strings may contain signed blob URLs. Only
        # explicitly controlled validation messages are safe to print.
        message = str(error) if isinstance(error, (ContractError, RuntimeError)) else type(error).__name__
        print("Deployment failed:", message, file=sys.stderr)
        sys.exit(1)

"""Deployment trust and failure injection, without Maven/Node/host credentials."""
import copy
import hashlib
import json
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import warnings

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deployment"))
import artifacts
import github
import pull

SHA = "a" * 40
OLD = "b" * 40
REPO = "Grodahn/MiezMerker"


def run(name, number=1, sha=SHA):
    return dict(path=".github/workflows/" + name, id=number, run_number=number,
                run_attempt=1, status="completed", conclusion="success", event="push",
                head_branch="main", head_sha=sha, repository={"full_name": REPO},
                head_repository={"full_name": REPO})


class API:
    repository = REPO

    def __init__(self):
        self.runs = [run(name, i + 1) for i, name in enumerate(sorted(github.REQUIRED))]
        self.foundation = next(r for r in self.runs if r["path"].endswith("/ci.yml"))
        self.artifact = dict(id=50, name=f'deployment-candidate-{SHA}-{self.foundation["id"]}-1',
                             expired=False, digest="sha256:" + "1" * 64, size_in_bytes=100)
        self.jobs = [dict(status="completed", conclusion="success")]
        self.downloads = []
        self.marker = dict(id=60, name="deployment-approved-" + SHA, expired=False,
                           workflow_run={"id": 90})

    def pages(self, path, key):
        if key == "workflow_runs":
            return iter(self.runs)
        if key == "jobs":
            return iter(self.jobs)
        if key == "artifacts":
            return iter([self.artifact])
        raise AssertionError(path)

    def get(self, path):
        if path.startswith("/actions/workflows/ci.yml/runs"):
            return {"workflow_runs": [self.foundation]}
        if path.startswith("/actions/artifacts?name="):
            return {"artifacts": [self.marker]}
        if path == "/actions/runs/90":
            return run(github.GATE) | {"event": "workflow_run"}
        if path == "/actions/artifacts/50":
            return self.artifact
        raise AssertionError(path)

    def download(self, artifact, target, limit):
        self.downloads.append(artifact["id"])
        if artifact["id"] == 60:
            with zipfile.ZipFile(target, "w") as archive:
                archive.writestr("approved.json", json.dumps(github.approval(self, SHA)))
        else:
            target.write_bytes(self.payload)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        jar = self.source / "backend/target/backend-0.1.0-SNAPSHOT.jar"
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("BOOT-INF/classes/db/migration/V1__init.sql", "create table test(id int);")
        public = self.source / "app/dist"
        public.mkdir(parents=True)
        for name in ["index.html", "sw.js"]:
            (public / name).write_text("tested-content")
        (public / "build.json").write_text(json.dumps(dict(sha=SHA, policy=artifacts.POLICY)))
        self.api = API()
        self.package = self.root / "package"
        artifacts.package(self.source, self.package, SHA, self.api.foundation["id"], 1)
        self.manifest = artifacts.verify(self.package, SHA, self.api.foundation["id"], 1)
        self.config = dict(root=str(self.root / "service"), systemctl="systemctl",
                           service="example-test.service", migration_approvals=str(self.root / "approvals"),
                           backup_guard="backup-guard", health_url="http://localhost/health",
                           receipt_url="http://localhost/build.json", health_attempts=1, health_delay=0)
        (Path(self.config["root"]) / "releases").mkdir(parents=True)
        archive_path = self.root / "payload.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            for p in self.package.rglob("*"):
                if p.is_file():
                    archive.write(p, p.relative_to(self.package).as_posix())
        self.api.payload = archive_path.read_bytes()

    def deployer(self):
        return pull.Deployer(self.config, self.api)

    def test_packaged_identity_and_policy(self):
        self.assertEqual(self.manifest["sha"], SHA)
        self.assertEqual(self.manifest["policy"], artifacts.POLICY)
        self.assertEqual(self.manifest["migrations"], artifacts.migrations(self.package / "backend.jar"))
        for key, value in [("sha", OLD), ("policy", "SIGNED_BACKEND_TIME")]:
            with self.subTest(key=key):
                receipt = dict(sha=SHA, policy=artifacts.POLICY) | {key: value}
                (self.source / "app/dist/build.json").write_text(json.dumps(receipt))
                with self.assertRaisesRegex(ValueError, "PWA commit/policy"):
                    artifacts.package(self.source, self.root / key, SHA, 1, 1)

    def test_checksum_missing_extra_and_commit_mismatch(self):
        (self.package / "public/index.html").write_text("tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            artifacts.verify(self.package, SHA, self.api.foundation["id"], 1)
        with self.assertRaisesRegex(ValueError, "identity"):
            artifacts.verify(self.package, OLD, self.api.foundation["id"], 1)
        (self.package / "public/index.html").unlink()
        with self.assertRaisesRegex(ValueError, "checksum"):
            artifacts.verify(self.package, SHA, self.api.foundation["id"], 1)

    def test_zip_traversal_symlink_duplicate_and_bomb(self):
        # On Windows ZipInfo normalizes backslashes while reading, before our
        # extractor sees them. Exercise the raw-separator rejection on POSIX,
        # where the deployer actually runs (including the full GitHub CI suite).
        names = ["../escape", "/escape", "C:/escape"]
        if os.name == "posix":
            names.append("public\\escape")
        for name in names:
            with self.subTest(name=name):
                archive = self.root / "bad.zip"
                with zipfile.ZipFile(archive, "w") as output:
                    info = zipfile.ZipInfo("raw")
                    info.filename = name  # preserve malicious backslashes on Windows
                    output.writestr(info, "data")
                with self.assertRaises(ValueError):
                    artifacts.extract(archive, self.root / "unpack")
        archive = self.root / "bad.zip"
        with zipfile.ZipFile(archive, "w") as output:
            info = zipfile.ZipInfo("public/link")
            info.external_attr = 0o120777 << 16
            output.writestr(info, "/etc/shadow")
        with self.assertRaises(ValueError):
            artifacts.extract(archive, self.root / "unpack")
        with zipfile.ZipFile(archive, "w") as output, warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            output.writestr('same', 'one')
            output.writestr('same', 'two')
        with self.assertRaises(ValueError):
            artifacts.extract(archive, self.root / "unpack")
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr('large', '1234')
        with patch.object(artifacts, 'MAX_BYTES', 3), self.assertRaises(ValueError):
            artifacts.extract(archive, self.root / "unpack")

    def test_download_digest_and_size_are_checked(self):
        api = github.GitHub(REPO, 'not-a-real-secret')
        data = b'archive-data'
        artifact = dict(id=1, size_in_bytes=len(data), expired=False,
                        digest='sha256:' + hashlib.sha256(data).hexdigest())
        with patch.object(api, 'request', return_value=io.BytesIO(data)):
            api.download(artifact, self.root / 'download.zip', 100)
        with patch.object(api, 'request', return_value=io.BytesIO(b'tampered')):
            with self.assertRaisesRegex(ValueError, 'digest mismatch'):
                api.download(artifact, self.root / 'download.zip', 100)
        with self.assertRaisesRegex(ValueError, 'too large'):
            api.download(artifact, self.root / 'download.zip', 1)

    def test_every_gate_and_latest_attempt_must_succeed(self):
        self.assertEqual(github.approval(self.api, SHA)["artifact_id"], 50)
        for status in ["queued", "in_progress", "completed"]:
            self.api.runs[0].update(status=status, conclusion="failure")
            with self.assertRaises(ValueError):
                github.approval(self.api, SHA)
        self.api.runs[0].update(status="completed", conclusion="success")
        self.api.jobs[0]["conclusion"] = "skipped"
        with self.assertRaisesRegex(ValueError, "job"):
            github.approval(self.api, SHA)
        self.api.jobs[0]["conclusion"] = "success"
        latest = copy.deepcopy(self.api.runs[0])
        latest.update(run_attempt=2, conclusion="failure")
        self.api.runs.append(latest)
        with self.assertRaises(ValueError):
            github.approval(self.api, SHA)

    def test_pr_fork_missing_ci_and_expired_candidate_denied(self):
        for key, value in [("event", "pull_request"), ("head_branch", "topic"),
                           ("head_sha", OLD), ("head_repository", {"full_name": "attacker/fork"})]:
            original = self.api.runs[0][key]
            self.api.runs[0][key] = value
            with self.assertRaisesRegex(ValueError, "missing"):
                github.approval(self.api, SHA)
            self.api.runs[0][key] = original
        self.api.artifact["expired"] = True
        with self.assertRaises(ValueError):
            github.approval(self.api, SHA)

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_initial_update_no_change_and_retention(self):
        deployer = self.deployer()
        with patch.object(deployer, "migration_guard"), patch.object(deployer, "command") as command, patch.object(deployer, "healthy", return_value=True):
            deployer.poll()
            self.assertEqual(deployer.state["sha"], SHA)
            self.assertEqual(deployer.root.joinpath("current").resolve(), deployer.releases / SHA)
            downloads = len(self.api.downloads)
            calls = command.call_count
            deployer.poll()
            self.assertEqual(len(self.api.downloads), downloads)
            self.assertEqual(command.call_count, calls)
            # Second version, same schema, followed by a failed third version.
            next_manifest = dict(self.manifest, sha=OLD)
            next_folder = self.root / "second"
            import shutil
            shutil.copytree(self.package, next_folder)
            deployer.install(next_folder, next_manifest, run("ci.yml", 20, OLD))
            self.assertEqual(deployer.state["previous"], SHA)
            deployer.rollback()
            self.assertEqual(deployer.state["sha"], SHA)
            self.assertTrue(deployer.state["held"])
            self.assertIn(OLD, deployer.state["rejected"])
            with patch.object(self.api, "get", side_effect=AssertionError('held poll must stay offline')):
                deployer.poll()

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_failed_health_compatible_rollback_and_quarantine(self):
        deployer = self.deployer()
        deployer.state = dict(sha=OLD, run_number=1)
        (deployer.releases / OLD).mkdir()
        deployer.switch(OLD)
        with patch.object(deployer, "migration_guard"), patch.object(deployer, "command") as command, patch.object(deployer, "healthy", side_effect=[False, True]):
            with self.assertRaisesRegex(RuntimeError, "healthcheck"):
                deployer.install(self.package, self.manifest, self.api.foundation)
            self.assertEqual(deployer.state["sha"], OLD)
            self.assertIn(SHA, deployer.state["rejected"])
            self.assertEqual(deployer.root.joinpath("current").resolve(), deployer.releases / OLD)
            before = command.call_count
            deployer.poll()
            self.assertEqual(command.call_count, before)
            self.assertEqual(self.api.downloads, [])

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_interrupted_transaction_recovers_even_after_state_write(self):
        deployer = self.deployer()
        (deployer.releases / OLD).mkdir()
        (deployer.releases / SHA).mkdir()
        deployer.state = dict(sha=SHA, run_number=9)
        pull.save(deployer.pending, dict(old=OLD, new=SHA, old_state=dict(sha=OLD, run_number=1),
                                        database_schema=self.manifest["schema"], database_migrations=self.manifest["migrations"]))
        deployer.switch(SHA)
        with patch.object(deployer, "command"), patch.object(deployer, "healthy", return_value=True):
            deployer.recover()
        self.assertEqual(deployer.state["sha"], OLD)
        self.assertEqual(deployer.state["run_number"], 1)
        self.assertFalse(deployer.pending.exists())
        self.assertEqual(deployer.state['database_migrations'], self.manifest['migrations'])

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_failed_recovery_halts_further_restarts(self):
        deployer = self.deployer()
        (deployer.releases / OLD).mkdir()
        pull.save(deployer.pending, dict(old=OLD, new=SHA, old_state={},
                                        database_schema=self.manifest['schema'], database_migrations=self.manifest['migrations']))
        with patch.object(deployer, 'command'), patch.object(deployer, 'healthy', return_value=False):
            with self.assertRaisesRegex(RuntimeError, 'failed recovery'):
                deployer.recover()
        with patch.object(deployer, 'command') as command:
            with self.assertRaisesRegex(RuntimeError, 'halted'):
                deployer.poll()
            command.assert_not_called()

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_failed_migration_invalidates_older_rollback_compatibility(self):
        deployer = self.deployer()
        older = 'c' * 40
        old_state = dict(sha=OLD, previous=older, rollback_compatible=True,
                         database_schema='before', database_migrations={})
        deployer.state = dict(old_state)
        (deployer.releases / OLD).mkdir()
        pull.save(deployer.pending, dict(old=OLD, new=SHA, old_state=old_state,
                                        database_schema=self.manifest['schema'], database_migrations=self.manifest['migrations']))
        with patch.object(deployer, 'command'), patch.object(deployer, 'healthy', return_value=True):
            deployer.recover()
        self.assertFalse(deployer.state['rollback_compatible'])
        self.assertEqual(deployer.state['database_schema'], self.manifest['schema'])
        with self.assertRaisesRegex(ValueError, 'No recorded compatible'):
            deployer.install(self.package, dict(self.manifest, sha=older), self.api.foundation, rollback=True)

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_held_poll_still_recovers_an_interrupted_operator_rollback(self):
        deployer = self.deployer()
        old_state = dict(sha=OLD, held=True, database_schema=self.manifest['schema'])
        deployer.state = dict(old_state)
        (deployer.releases / OLD).mkdir()
        pull.save(deployer.pending, dict(old=OLD, new=SHA, old_state=old_state,
                                        database_schema=self.manifest['schema'], database_migrations=self.manifest['migrations']))
        with patch.object(deployer, 'command') as command, patch.object(deployer, 'healthy', return_value=True), \
                patch.object(self.api, 'get', side_effect=AssertionError('held poll must not query GitHub')):
            deployer.poll()
        self.assertGreater(command.call_count, 0)
        self.assertFalse(deployer.pending.exists())
        self.assertTrue(deployer.state['held'])

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_download_interrupt_and_github_outage_preserve_state(self):
        deployer = self.deployer()
        with patch.object(self.api, "download", side_effect=OSError("interrupted")), patch.object(deployer, "command") as command:
            with self.assertRaises(OSError):
                deployer.poll()
            self.assertFalse((deployer.root / "download").exists())
            self.assertFalse((deployer.releases / SHA).exists())
            command.assert_not_called()
        with patch.object(self.api, "get", side_effect=OSError("offline")), patch.object(deployer, "command") as command:
            with self.assertRaises(OSError):
                deployer.poll()
            command.assert_not_called()

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_corrupt_download_is_quarantined(self):
        self.api.payload = b"not a ZIP"
        deployer = self.deployer()
        with patch.object(deployer, "command") as command:
            with self.assertRaises(zipfile.BadZipFile):
                deployer.poll()
            command.assert_not_called()
        self.assertIn(SHA, deployer.state["rejected"])

    @unittest.skipUnless(os.name == "posix", "POSIX host filesystem and systemd deployment")
    def test_migration_block_reuses_verified_staging_without_download(self):
        deployer = self.deployer()
        with patch.object(deployer, "command") as command:
            for _ in range(2):
                with self.assertRaisesRegex(ValueError, "local migration approval"):
                    deployer.poll()
            command.assert_not_called()
        self.assertEqual(self.api.downloads, [60, 50])
        self.assertTrue((deployer.releases / SHA / "manifest.json").is_file())

    def test_schema_changes_require_backup_compatible_root_approval(self):
        deployer = self.deployer()
        with self.assertRaisesRegex(ValueError, "local migration approval"):
            deployer.migration_guard(self.manifest)
        deployer.state["sha"] = OLD
        (deployer.releases / OLD).mkdir()
        (deployer.releases / OLD / "manifest.json").write_text(json.dumps(self.manifest))
        deployer.migration_guard(self.manifest)  # unchanged schema needs no approval
        altered = copy.deepcopy(self.manifest)
        altered["schema"] = "new"
        altered["migrations"]["V1__init.sql"] = "changed"
        with self.assertRaisesRegex(ValueError, "changed/removed"):
            deployer.migration_guard(altered)


if __name__ == "__main__":
    unittest.main()

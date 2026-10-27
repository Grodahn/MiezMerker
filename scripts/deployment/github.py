"""Authenticated GitHub approval checks shared by publisher and pull client."""
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request

from artifacts import ContractError, POLICY, SHA

REQUIRED = {"ci.yml", "esp32.yml", "offline-auth.yml", "local-issuer.yml", "windows-idf-tools.yml"}
OPTIONAL = set()
GATE = "deployment.yml"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class GitHub:
    def __init__(self, repository, token):
        self.repository = repository
        self.token = token
        self.base = "https://api.github.com/repos/" + repository

    def request(self, path):
        request = urllib.request.Request(self.base + path, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "verified-pull-deployment"})
        return urllib.request.build_opener(NoRedirect).open(request, timeout=30)

    def get(self, path):
        with self.request(path) as response:
            return json.load(response)

    def pages(self, path, key):
        page = 1
        while True:
            data = self.get(path + ("&" if "?" in path else "?") + f"per_page=100&page={page}")[key]
            yield from data
            if len(data) < 100:
                break
            page += 1

    def download(self, artifact, target, limit):
        if artifact.get("expired") or artifact["size_in_bytes"] > limit:
            raise ContractError("Artifact expired or too large")
        try:
            response = self.request(f'/actions/artifacts/{artifact["id"]}/zip')
        except urllib.error.HTTPError as error:
            if error.code != 302:
                raise
            location = error.headers["Location"]
            if urllib.parse.urlsplit(location).scheme != "https":
                raise ContractError("Artifact redirect must use TLS")
            # Deliberately create a NEW request: never forward the API token
            # to blob storage, nor log its signed URL.
            response = urllib.request.urlopen(location, timeout=60)
        count = 0
        checksum = hashlib.sha256()
        with response, open(target, "wb") as output:
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > limit:
                    raise ContractError("Artifact exceeds download limit")
                checksum.update(block)
                output.write(block)
        if artifact.get("digest") != "sha256:" + checksum.hexdigest():
            raise ContractError("GitHub artifact digest mismatch")


def trusted(run, repository, sha=None):
    return (run["event"] == "push" and run["head_branch"] == "main"
            and run["repository"]["full_name"].lower() == repository.lower()
            and run["head_repository"]["full_name"].lower() == repository.lower()
            and (sha is None or run["head_sha"] == sha))


def approved_runs(api, sha):
    if not SHA.fullmatch(sha):
        raise ContractError("Invalid commit SHA")
    latest = {}
    for run in api.pages("/actions/runs?event=push&branch=main&head_sha=" + sha, "workflow_runs"):
        name = run["path"].split("/")[-1]
        if name not in REQUIRED | OPTIONAL or not trusted(run, api.repository, sha):
            continue
        if name not in latest or (run["run_number"], run["run_attempt"]) > (latest[name]["run_number"], latest[name]["run_attempt"]):
            latest[name] = run
    if not REQUIRED <= latest.keys():
        raise ContractError("Required CI workflow missing")
    for run in latest.values():
        if run["status"] != "completed" or run["conclusion"] != "success":
            raise ContractError("CI has not fully succeeded")
        jobs = list(api.pages(f'/actions/runs/{run["id"]}/attempts/{run["run_attempt"]}/jobs', "jobs"))
        if not jobs or any(j["status"] != "completed" or j["conclusion"] != "success" for j in jobs):
            raise ContractError("CI job missing, skipped or unsuccessful")
    return latest


def candidate(api, foundation):
    name = f'deployment-candidate-{foundation["head_sha"]}-{foundation["id"]}-{foundation["run_attempt"]}'
    matches = [a for a in api.pages(f'/actions/runs/{foundation["id"]}/artifacts', "artifacts") if a["name"] == name and not a["expired"]]
    if len(matches) != 1 or not matches[0].get("digest", "").startswith("sha256:"):
        raise ContractError("Unique, checksummed candidate missing")
    return matches[0]


def approval(api, sha):
    runs = approved_runs(api, sha)
    foundation = runs["ci.yml"]
    artifact = candidate(api, foundation)
    return dict(format=1, repository=api.repository.lower(), sha=sha, policy=POLICY,
                run_id=foundation["id"], attempt=foundation["run_attempt"],
                artifact_id=artifact["id"], digest=artifact["digest"],
                workflows={name: {"id": r["id"], "attempt": r["run_attempt"]} for name, r in runs.items()})


if __name__ == "__main__":
    api = GitHub(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"])
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    run = event["workflow_run"]
    if not trusted(run, api.repository) or run["conclusion"] != "success":
        sys.exit(0)
    try:
        result = approval(api, run["head_sha"])
    except ValueError as error:
        print("No deployment approval:", error)
        sys.exit(0)
    target = Path("approval")
    target.mkdir()
    (target / "approved.json").write_text(json.dumps(result, sort_keys=True) + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("publish=true\n")

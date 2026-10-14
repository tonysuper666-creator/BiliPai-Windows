"""Verify downloaded own-repository CI evidence before making an unlaunched candidate package."""
from pathlib import Path
import argparse
import base64
import gzip
import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET

REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
MAX_RECEIPT_BYTES = 32 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_json(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate evidence field")
        result[key] = value
    return result


def receipt_from_log(log):
    lines = [re.sub(r"^\d{4}-\d\d-\d\dT\S+Z ", "", line).rstrip() for line in log.splitlines()]
    starts = [i for i, line in enumerate(lines) if line.startswith("BILIPAI_SOURCE_RECEIPT_BEGIN ")]
    ends = [i for i, line in enumerate(lines) if line == "BILIPAI_SOURCE_RECEIPT_END"]
    require(len(starts) == len(ends) == 1 and starts[0] < ends[0], "Missing or ambiguous sealed receipt")
    expected = lines[starts[0]].split(" ", 1)[1]
    require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None, "Invalid receipt digest")
    encoded = "".join(lines[starts[0] + 1:ends[0]])
    require(len(encoded) <= MAX_RECEIPT_BYTES, "Receipt transport exceeds bound")
    compressed = base64.b64decode(encoded, validate=True)
    with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
        raw = stream.read(MAX_RECEIPT_BYTES + 1)
    require(len(raw) <= MAX_RECEIPT_BYTES, "Expanded receipt exceeds bound")
    require(hashlib.sha256(raw).hexdigest() == expected, "Sealed receipt digest mismatch")
    return json.loads(raw, object_pairs_hook=unique_json), expected


def verify(run, jobs, log, plan, source_sha):
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha) is not None, "Exact source SHA is required")
    repository = run.get("repository", {})
    require(repository.get("full_name") == REPOSITORY and repository.get("private") is False,
            "Only the personal public repository is allowed")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "Validation run is not successful")
    require(run.get("head_sha") == source_sha, "Validation run belongs to another source")
    require(run.get("head_branch", "").startswith("desktop/source-validation-"), "Unapproved validation branch")
    require(run.get("path", "").split("@", 1)[0] == ".github/workflows/windows-source-validation.yml",
            "Unapproved qualification workflow")
    selected = [job for job in jobs.get("jobs", []) if job.get("name") == "source-validation"]
    require(len(selected) == 1, "Qualification job is missing or ambiguous")
    job = selected[0]
    require(job.get("run_id") == run["id"] and job.get("head_sha") == source_sha, "Qualification job identity mismatch")
    require(job.get("status") == "completed" and job.get("conclusion") == "success", "Qualification job did not succeed")
    require(job.get("labels") == ["windows-latest"], "Only the standard Windows runner is allowed")
    payload, digest = receipt_from_log(log)
    receipt = payload["receipt"]
    require(receipt.get("passed") is True and receipt.get("exitCode") == 0, "Source checks failed")
    require(receipt.get("sourceSha") == source_sha and receipt.get("baseHead") == plan["baseHead"], "Receipt source identity mismatch")
    require(receipt.get("windowsVersion") == plan["windowsVersion"], "Receipt application version mismatch")
    pins = {row["path"]: row["sha256"] for row in plan["sources"]}
    require(len(pins) == len(plan["sources"]), "Duplicate source pin")
    require(receipt.get("sourceUnchanged") is True and receipt.get("sourceBefore") == pins and receipt.get("sourceAfter") == pins,
            "Source changed during qualification")
    require(receipt.get("expectedTests") == plan["expectedTests"] == sum(plan["classes"].values()), "Test plan cardinality mismatch")
    summaries = receipt["suites"]
    require(len(summaries) == len(plan["classes"]), "Missing suite evidence")
    for suite in summaries:
        require(suite.get("fresh") is True and suite.get("backgroundError") is False, "Stale XML or background failure")
    require({suite["name"] for suite in summaries} == set(plan["classes"]), "Suite identity mismatch")
    actual = {}
    require(set(payload["rawXml"]) == set(plan["classes"]), "Raw XML suite set mismatch")
    for name, xml in payload["rawXml"].items():
        require("<!DOCTYPE" not in xml.upper() and "<!ENTITY" not in xml.upper(), "Unexpected XML declarations")
        suite = ET.fromstring(xml)
        require(suite.tag == "testsuite" and suite.attrib.get("name") == name, "Raw XML suite identity mismatch")
        require(all(int(suite.attrib.get(key, 0)) == 0 for key in ("failures", "errors", "skipped")), "Unsuccessful test XML")
        actual[name] = int(suite.attrib["tests"])
        require(len(suite.findall("testcase")) == actual[name], "Raw XML testcase count mismatch")
    require(actual == plan["classes"], "Executed tests differ from qualification plan")
    return dict(repository=REPOSITORY, sourceSha=source_sha, windowsVersion=plan["windowsVersion"],
                runId=run["id"], jobId=job["id"], actualTests=sum(actual.values()), suites=len(actual),
                sourceQualified=True, sealedReceiptSHA256=digest, packageLaunchVerified=False,
                hardwareVerified=False, releaseQualified=False, desktopReplaced=False)


def main():
    parser = argparse.ArgumentParser()
    for option in ("run", "jobs", "log", "plan", "source-sha", "output"):
        parser.add_argument("--" + option, required=True)
    args = parser.parse_args()
    read_json = lambda path: json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique_json)
    proof = verify(read_json(args.run), read_json(args.jobs), Path(args.log).read_text(encoding="utf-8-sig"),
                   read_json(args.plan), args.source_sha)
    Path(args.output).write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(proof))


if __name__ == "__main__":
    main()

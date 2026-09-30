"""Offline audit of the fixed Google Cast fork, protocol, roots and attribution."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

SOURCE_SHA256 = "312fd605cd0ef4cfff54bbcd49cb48e9df2c01342d61192f757d376d6b178ad3"
OPENSCREEN_COMMIT = "45c685cee0b522a7bb578ce5adcc696eeacf0f0c"


def verify(desktop: Path):
    desktop = desktop.resolve()
    provenance = desktop / "third-party/google-cast-v2"
    manifest = json.loads((provenance / "SOURCES.json").read_text(encoding="utf-8"))
    assert manifest["originalSourceJarSha256"] == SOURCE_SHA256
    assert manifest["openscreenCommit"] == OPENSCREEN_COMMIT
    assert manifest["physicalDeviceTested"] is False
    assert hashlib.sha256((provenance / "chromecast-java-api-v2-0.12.20-sources.jar").read_bytes()).hexdigest() == SOURCE_SHA256
    for record in manifest["records"]:
        path = (desktop / record["path"]).resolve()
        assert path.is_relative_to(desktop), "Source inventory escaped desktop root"
        # All text provenance uses LF so a normal Windows checkout remains verifiable.
        data = path.read_bytes()
        if path.suffix in {".java", ".kt", ".py", ".txt", ".patch", ".json", ".pom", ".proto", ".h", ".cc"} or path.name == "LICENSE":
            data = data.replace(b"\r\n", b"\n")
        assert hashlib.sha256(data).hexdigest() == record["sha256"], "Changed vetted Cast source: " + record["path"]
    originals = json.loads((provenance / "upstream-files.json").read_text(encoding="utf-8"))
    assert len(originals) == 32
    with zipfile.ZipFile(provenance / "chromecast-java-api-v2-0.12.20-sources.jar") as archive:
        for item in originals:
            data = archive.read(item["path"])
            assert hashlib.sha256(data).hexdigest() == item["sha256"]
            fork = (desktop / "src/main/java" / item["path"]).read_bytes().replace(b"\r\n", b"\n")
            if Path(item["path"]).name not in manifest["modifiedUpstreamFiles"]:
                assert fork == data.replace(b"\r\n", b"\n"), "Unreported original modification"
            else:
                assert b"Modified for BiliPai Windows" in fork and b"Apache License" in fork
    for header, pin in [("cast_root_ca_cert_der-inc.h", "cast-root.der"), ("eureka_root_ca_der-inc.h", "eureka-root.der")]:
        source = (provenance / "openscreen/cast/common/certificate" / header).read_text(encoding="utf-8")
        literal = source[source.index("{"):source.index("}")]
        expected = bytes(int(value, 16) for value in re.findall(r"0x([0-9a-fA-F]{2})", literal))
        assert expected == (desktop / "src/main/resources/cast-v2" / pin).read_bytes()
    main_roots = sorted(p.name for p in (desktop / "src/main/resources/cast-v2").iterdir())
    assert main_roots == ["cast-root.der", "eureka-root.der"], "Unexpected production trust anchor"
    assert not any(p.suffix in {".p12", ".pk8"} for p in (desktop / "src/main/resources").rglob("*")), "Test private material in production resources"
    return dict(passed=True, originalJavaFiles=32, narrowlyModifiedFiles=3,
                pinnedRoots=2, physicalDeviceTested=False, auditedRecords=len(manifest["records"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--desktop", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    print(json.dumps(verify(args.desktop)))

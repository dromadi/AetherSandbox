"""Stage only checksum-verified executables and firmware from a pinned repository workflow."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

root = Path(__file__).resolve().parents[2]
lock = json.loads((root / "tools/vm/engine-lock.json").read_text())
for attempt in range(80):
    run = json.loads(subprocess.check_output(["gh", "api", f"repos/{lock['repository']}/actions/runs/{lock['workflowRun']}"], text=True))
    assert run["head_sha"] == lock["workflowHead"], "Engine workflow head changed"
    if run["status"] == "completed":
        break
    print("Waiting for pinned source-built engine", flush=True)
    time.sleep(15)
assert run["head_sha"] == lock["workflowHead"] and run["conclusion"] == "success", "Pinned engine run not successful"
assets = root / "app/build/generated/vmEngineAssets"
for abi in lock["abis"]:
    stage = root / "build/engine-artifacts" / abi
    stage.mkdir(parents=True, exist_ok=True)
    subprocess.run(["gh", "run", "download", str(lock["workflowRun"]), "--repo", lock["repository"],
                    "--name", f"vm-engine-{abi}", "--dir", str(stage)], check=True)
    assert (stage / "source-commit.txt").read_text().strip() == lock["qemuCommit"]
    hashes = {}
    for line in (stage / "sha256.txt").read_text().splitlines():
        digest, name = line.split()
        hashes[Path(name).name] = digest
    dest = root / "app/build/generated/vmEngineJni" / abi
    dest.mkdir(parents=True, exist_ok=True)
    for guest in ("aarch64", "x86_64"):
        name = f"libaether_qemu_{guest}.so"
        source = stage / name
        assert hashlib.sha256(source.read_bytes()).hexdigest() == hashes[name], "Engine checksum mismatch"
        shutil.copyfile(source, dest / name)
    shutil.copytree(stage / "firmware", assets / "vm-firmware", dirs_exist_ok=True)
    (assets / "vm-engine-provenance").mkdir(parents=True, exist_ok=True)
    for name in ("sha256.txt", "alpine-packages.txt", "source-commit.txt", "version-aarch64.txt", "version-x86_64.txt", "COPYING"):
        shutil.copyfile(stage / name, assets / "vm-engine-provenance" / f"{abi}-{name}")

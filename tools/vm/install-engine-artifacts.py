"""Stage only checksum-verified executables and firmware from a pinned repository workflow."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import base64

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
# VNC needs its keyboard map even when only raw framebuffer input is used. Fetch the
# exact text data from the same pinned upstream source, not from the CI host's QEMU.
for name in ("keymaps/en-us", "linuxboot_dma.bin", "linuxboot.bin"):
    metadata = json.loads(subprocess.check_output(["gh", "api", f"repos/qemu/qemu/contents/pc-bios/{name}?ref={lock['qemuCommit']}"], text=True))
    content = base64.b64decode(metadata["content"])
    assert hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest() == metadata["sha"]
    target = assets / "vm-firmware" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)

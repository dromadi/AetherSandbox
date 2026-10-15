"""Real QEMU/TCG ARM64 Linux boot acceptance. Linux host only; not phone evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import subprocess
import time


def command(engine, image, nonce):
    # No shell, disk, monitor, networking or host directory passthrough.
    return [engine, "-machine", "virt-8.2,gic-version=3", "-accel", "tcg,thread=single",
            "-cpu", "cortex-a53", "-smp", "1", "-m", "256M", "-nodefaults",
            "-display", "none", "-serial", "stdio", "-monitor", "none", "-nic", "none",
            "-no-reboot", "-kernel", str(image), "-append",
            f"console=ttyAMA0 rdinit=/init panic=-1 aether_nonce={nonce}"]


def accepted(code, log, nonce):
    return (code == 0 and "Linux version " in log and
            f"AETHER_VM_LINUX_READY nonce={nonce} " in log and
            "guest=aarch64 pid=1" in log and "AETHER_VM_POWEROFF" in log and
            "Kernel panic" not in log and "AETHER_VM_INIT_FAILED" not in log)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--engine", default="qemu-system-aarch64")
    parser.add_argument("--boots", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.boots <= 20:
        parser.error("boots must be between 1 and 20")
    image = args.kernel.resolve(strict=True)
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"schemaVersion": 1, "environment": "linux-host-ci", "hostAbi": platform.machine(),
              "guestAbi": "aarch64", "accelerator": "tcg", "machine": "virt-8.2",
              "kernelSha256": hashlib.sha256(image.read_bytes()).hexdigest(),
              "qemuVersion": subprocess.check_output([args.engine, "--version"], text=True).splitlines()[0],
              "androidGuestBooted": False, "physicalPhoneVerified": False, "boots": []}
    try:
        for iteration in range(args.boots):
            nonce = secrets.token_hex(16)
            started = time.monotonic()
            process = subprocess.Popen(command(args.engine, image, nonce), stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            timed_out = False
            try:
                output, _ = process.communicate(timeout=90)
            except subprocess.TimeoutExpired:
                timed_out = True
                import signal
                os.killpg(process.pid, signal.SIGKILL)
                output, _ = process.communicate()
            log = output.decode("utf-8", errors="replace")
            (args.output / f"boot-{iteration + 1}.log").write_text(log)
            passed = not timed_out and accepted(process.returncode, log, nonce)
            result = {"iteration": iteration + 1, "exitCode": process.returncode,
                      "seconds": round(time.monotonic() - started, 3), "timeout": timed_out, "passed": passed}
            report["boots"].append(result)
            print(json.dumps(result), flush=True)
            if not passed:
                raise SystemExit("Linux boot acceptance failed; inspect boot log")
    finally:
        report["passed"] = len(report["boots"]) == args.boots and all(b["passed"] for b in report["boots"])
        (args.output / "boot-report.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()

"""Auditoría de solo lectura del entorno local para entrenamiento con Ultralytics."""

from __future__ import annotations

import importlib.util
import json
import platform
import shutil
import subprocess
import sys
from importlib import metadata
from typing import Any


PACKAGES = ("torch", "torchvision", "ultralytics", "onnx", "onnxruntime")


def command_output(command: list[str]) -> dict[str, Any]:
    executable = shutil.which(command[0])
    if executable is None:
        return {"available": False, "executable": None, "output": None}

    completed = subprocess.run(
        [executable, *command[1:]],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    output = (completed.stdout or completed.stderr).strip()
    return {
        "available": completed.returncode == 0,
        "executable": executable,
        "returncode": completed.returncode,
        "output": output,
    }


def package_versions() -> dict[str, str | None]:
    versions: dict[str, str | None] = {}
    for package in PACKAGES:
        if importlib.util.find_spec(package) is None:
            versions[package] = None
            continue
        try:
            versions[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            versions[package] = "importable (version unknown)"
    return versions


def torch_status() -> dict[str, Any]:
    if importlib.util.find_spec("torch") is None:
        return {"installed": False}

    import torch

    cuda_available = torch.cuda.is_available()
    return {
        "installed": True,
        "version": torch.__version__,
        "cuda_build": torch.version.cuda,
        "cuda_available": cuda_available,
        "device_count": torch.cuda.device_count(),
        "device_name": torch.cuda.get_device_name(0) if cuda_available else None,
        "compute_capability": (
            torch.cuda.get_device_capability(0) if cuda_available else None
        ),
    }


def main() -> None:
    nvidia = command_output(
        [
            "nvidia-smi",
            "--query-gpu=name,driver_version,memory.total,compute_cap",
            "--format=csv,noheader,nounits",
        ]
    )
    nvcc = command_output(["nvcc", "--version"])

    report = {
        "platform": platform.platform(),
        "python": {
            "version": platform.python_version(),
            "executable": sys.executable,
            "is_64_bit": sys.maxsize > 2**32,
        },
        "packages": package_versions(),
        "nvidia_smi": nvidia,
        "cuda_toolkit_nvcc": nvcc,
        "torch": torch_status(),
    }
    print(json.dumps(report, indent=2, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()

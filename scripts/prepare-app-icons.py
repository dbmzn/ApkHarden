#!/usr/bin/env python3
"""Export committed app icons from the approved PNG on macOS (no pip dependencies)."""

from pathlib import Path
import struct
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "packaging/icons/workkit-master.png"
OUTPUT = ROOT / "packaging/icons"
RUNTIME = ROOT / "src/main/resources/icons/workkit.png"


def resize(source: Path, destination: Path, size: int) -> None:
    subprocess.run(
        ["sips", "-z", str(size), str(size), str(source), "--out", str(destination)],
        check=True, stdout=subprocess.DEVNULL,
    )


def main() -> None:
    RUNTIME.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="workkit-icons-") as directory:
        temporary = Path(directory)
        source = temporary / "normalized.png"
        subprocess.run(["swift", str(ROOT / "scripts/normalize-app-icon.swift"),
                        str(SOURCE), str(source)], check=True)
        resize(source, RUNTIME, 512)
        iconset = temporary / "workkit.iconset"
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            resize(source, iconset / f"icon_{size}x{size}.png", size)
            resize(source, iconset / f"icon_{size}x{size}@2x.png", size * 2)
        subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(OUTPUT / "workkit.icns")],
            check=True,
        )
        # Modern Windows ICO supports PNG frames, preserving full alpha at every size.
        frames = []
        for size in (16, 24, 32, 48, 64, 128, 256):
            frame = temporary / f"windows-{size}.png"
            resize(source, frame, size)
            frames.append((size, frame.read_bytes()))
        offset = 6 + 16 * len(frames)
        entries = []
        for size, data in frames:
            entries.append(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0,
                                       1, 32, len(data), offset))
            offset += len(data)
        (OUTPUT / "workkit.ico").write_bytes(
            struct.pack("<HHH", 0, 1, len(frames)) + b"".join(entries)
            + b"".join(data for _, data in frames)
        )
    print("Updated runtime PNG, macOS ICNS and Windows ICO.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build a GTNH transport patch from the exact DiscordIntegration 3.0.5 release."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


BASELINE_SHA256 = "8127a6a9cb15f6498ea71f794624ff1d1611c5801ed96fe1812d295f63b71464"
CORE_REVISION = "ca09cc313616ee80d71c23286250b25e79b17a8d"
CLASS_DIR = "chikachi/discord/core"
CLIENT = f"{CLASS_DIR}/DiscordClient.class"
TRANSPORT = {
    f"{CLASS_DIR}/{name}.class"
    for name in ("DiscordTransport", "DiscordTransport$1", "DiscordTransport$1$1")
}
HERE = Path(__file__).resolve().parent
CORE = HERE.parent / "core"


def build(baseline: Path, output: Path) -> None:
    if baseline == output or hashlib.sha256(baseline.read_bytes()).hexdigest() != BASELINE_SHA256:
        raise SystemExit("Expected the original CurseForge 3.0.5 JAR and a different output path")

    source = subprocess.check_output(
        ["git", "-C", CORE, "show", f"{CORE_REVISION}:src/main/java/{CLASS_DIR}/DiscordClient.java"],
        text=True,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        work = Path(directory)
        client = work / "DiscordClient.java"
        client.write_text(source.replace("import net.dv8tion.jda.", "import chikachi.discord.repack.net.dv8tion.jda."))
        subprocess.run(["patch", "-s", "-F", "0", client, HERE / "discord-client.patch"], check=True)
        classes = work / "classes"
        subprocess.run(
            ["javac", "--release", "8", "-encoding", "UTF-8", "-cp", baseline, "-d", classes,
             client, HERE / "DiscordTransport.java"],
            check=True,
        )
        changed = {file.relative_to(classes).as_posix(): file.read_bytes() for file in classes.rglob("*.class")}
        if changed.keys() != TRANSPORT | {CLIENT}:
            raise SystemExit(f"Unexpected compiled entries: {sorted(changed)}")

        candidate = work / "candidate.jar"
        with ZipFile(baseline) as original, ZipFile(candidate, "w") as archive:
            names = set(original.namelist())
            if CLIENT not in names or names & TRANSPORT:
                raise SystemExit("Unexpected classes in release JAR")
            for entry in original.infolist():
                archive.writestr(entry, changed[CLIENT] if entry.filename == CLIENT else original.read(entry))
            for name in sorted(TRANSPORT):
                entry = ZipInfo(name, original.getinfo(CLIENT).date_time)
                archive.writestr(entry, changed[name], ZIP_DEFLATED)

        with ZipFile(baseline) as original, ZipFile(candidate) as archive:
            if set(archive.namelist()) != names | TRANSPORT:
                raise SystemExit("Candidate JAR has unexpected entries")
            for name in names - {CLIENT}:
                if original.read(name) != archive.read(name):
                    raise SystemExit(f"Unrelated JAR entry changed: {name}")
        candidate.replace(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.baseline.resolve(), args.output.resolve())

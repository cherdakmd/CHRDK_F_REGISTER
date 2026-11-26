#!/usr/bin/env python3
"""Check the compatible Core/Skills/Fishing candidate JARs; never publish anything."""
from pathlib import Path
import re
import sys
from zipfile import ZipFile

from verify_release import scalar, verify_standalone_jar


JARS = {
    "core": ("chrdk-core", "CHRDK_Core", {
        "ru/cherdakmd/chrdkregister/api/BankCharge.class",
        "ru/cherdakmd/chrdkregister/api/ChargeInventory.class",
        "ru/cherdakmd/chrdkregister/api/FishingSnapshotChargeProvider.class",
        "ru/cherdakmd/chrdkregister/api/IncomeSourceLineageProvider.class",
        "ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.class",
    }),
    "skills": ("chrdk-skills", "CHRDK_Skills", {
        "ru/cherdakmd/chrdkskills/ChrdkSkillsApi.class",
        "ru/cherdakmd/chrdkskills/FishingCatchBonus.class",
    }),
    "fishing": ("chrdk-fishing", "CHRDK_Fishing", {
        "ru/cherdakmd/chrdkfishing/bukkit/FishingPlugin.class",
        "org/sqlite/JDBC.class", "META-INF/services/java.sql.Driver",
    }),
}


def verify_bundle(directory: Path, version: str) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+-rc\.[1-9]\d*", version):
        raise ValueError("only numbered experimental candidates are supported")
    for kind, (archive_name, plugin, required) in JARS.items():
        path = directory / f"{archive_name}-{version}.jar"
        verify_standalone_jar(path, plugin, version)
        with ZipFile(path) as jar:
            entries = jar.namelist()
            if len(entries) != len(set(entries)):
                raise ValueError(f"{kind}: duplicate archive entries")
            missing = required - set(entries)
            if missing:
                raise ValueError(f"{kind}: missing required classes: {sorted(missing)}")
            if kind != "fishing" and any(name.startswith("ru/cherdakmd/chrdkfishing/") for name in entries):
                raise ValueError(f"{kind}: unexpected Fishing classes in production JAR")
            if kind == "fishing":
                if any(name.startswith(("ru/cherdakmd/chrdkregister/", "ru/cherdakmd/chrdkskills/", "org/slf4j/")) for name in entries):
                    raise ValueError("fishing: unexpected shaded Core/Skills/SLF4J classes")
                descriptor = jar.read("plugin.yml").decode("utf-8")
                if scalar(descriptor, "api-version") != "26.3":
                    raise ValueError("fishing: wrong Purpur API version")
                depends = scalar(descriptor, "depend")
                if not depends.startswith("[") or not depends.endswith("]"):
                    raise ValueError("fishing: invalid dependency list")
                names = [part.strip().strip("'\"") for part in depends[1:-1].split(",")]
                if len(names) != 2 or set(names) != {"CHRDK_Core", "CHRDK_Skills"}:
                    raise ValueError("fishing: incorrect Core/Skills dependencies")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_fishing_bundle.py <plugins-directory> <rc-version>")
    verify_bundle(Path(sys.argv[1]), sys.argv[2])
    print(f"Verified compatible candidate JAR contents for {sys.argv[2]}; this is not a stable release gate.")


if __name__ == "__main__":
    main()

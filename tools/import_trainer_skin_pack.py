#!/usr/bin/env python3
"""Import refs/pokemon_trainer_skin_pack into random trainer skin assets."""

from __future__ import annotations

import hashlib
import json
import shutil
import tempfile
from zipfile import ZipFile
from pathlib import Path

import unify_random_trainers as unify


REPO_ROOT = Path(__file__).resolve().parents[1]
REF_ROOT = REPO_ROOT / "refs/pokemon_trainer_skin_pack"
BY_TRAINER = REF_ROOT / "by_trainer"
SOURCES = REF_ROOT / "sources"
REPORT = REF_ROOT / "import_report.json"
MISSING_PATCH_ZIP = REPO_ROOT / "refs/pokemon_trainer_missing_skin_patch.zip"

GENDER_DIR = {"f": "female", "m": "male", "female": "female", "male": "male"}

FEMALE_SOURCE_HINTS = {
    "aroma_lady", "battle_girl", "beauty", "breeder_f", "cowgirl", "cyclist_f",
    "galactic_grunt_f", "hex_maniac_gen_vi", "hex_maniac_xy", "hex_maniac_za",
    "idol", "kimono_girl", "lady_f", "lass_f", "madame_f", "maid_f",
    "parasol_lady_f", "picnicker_f", "poke_kid_f", "pokefan_f", "psychic_f",
    "ranger_f", "reporter_f", "school_kid_f", "skier_f", "swimmer_f",
    "team_magma_f", "team_magma_f2", "tuber_f", "waitress_f", "young_couple_f",
}

MALE_SOURCE_HINTS = {
    "artist_m", "bird_keeper_gs_m", "bird_keeper_m", "black_belt_m",
    "breeder_m", "bug_catcher_m", "cameraman_m", "camper_m", "clown_m",
    "collector_m", "dragon_tamer_m", "fisher_m", "galactic_grunt_m",
    "gentleman_m", "guitarist_m", "hiker_m", "jogger_m", "ninja_boy_m",
    "officer_m", "pi_m", "rancher_m", "ranger_m", "rich_boy_m",
    "roughneck_m", "ruin_maniac_m", "sailor_m", "scientist_m", "skier_m",
    "swimmer_m", "team_aqua_oras", "team_aqua_rs", "team_magma_m",
    "team_magma_m_v2", "team_rocket_grunt", "tuber_m", "veteran_m",
    "waiter_m", "worker_m", "young_couple_m", "youngster_m",
}

SOURCE_EXTRA_TARGETS = {
    "kimono_girl_pokemon_hgss.png": "kimono_girl/female",
    "team_magma_f.png": "team_magma/female",
    "team_magma_f2.png": "team_magma/female",
}

TITLE_ALIASES = {
    "successor": "sucessor",
}


def digest(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def source_stem(path: Path) -> str:
    stem = path.stem
    return stem[3:] if len(stem) > 3 and stem[:2].isdigit() and stem[2] == "_" else stem


def infer_file_gender(path: Path, parent_gender: str) -> tuple[str, str]:
    stem = source_stem(path)
    clean = unify.clean_id(stem)
    if clean in FEMALE_SOURCE_HINTS or clean.endswith(("_f", "_female")):
        return "female", "filename"
    if clean in MALE_SOURCE_HINTS or clean.endswith(("_m", "_male")):
        return "male", "filename"
    return parent_gender, "folder"


def unique_dest(dest: Path, src: Path) -> tuple[Path, bool]:
    if not dest.exists():
        return dest, False
    if digest(dest) == digest(src):
        return dest, True
    suffix = digest(src)[:8]
    candidate = dest.with_name(f"{dest.stem}_{suffix}{dest.suffix}")
    index = 2
    while candidate.exists() and digest(candidate) != digest(src):
        candidate = dest.with_name(f"{dest.stem}_{suffix}_{index}{dest.suffix}")
        index += 1
    return candidate, candidate.exists()


def copy_png(src: Path, target_folder: str, copied_hashes: set[str]) -> tuple[str, bool]:
    dest_dir = unify.DEFAULT_SKINS / target_folder
    dest_dir.mkdir(parents=True, exist_ok=True)
    src_digest = digest(src)
    for existing in sorted(dest_dir.glob("*.png")):
        if digest(existing) == src_digest:
            copied_hashes.add(src_digest)
            return existing.relative_to(unify.DEFAULT_SKINS).as_posix(), True
    dest, already = unique_dest(dest_dir / src.name, src)
    if not already:
        shutil.copy2(src, dest)
    copied_hashes.add(src_digest)
    return dest.relative_to(unify.DEFAULT_SKINS).as_posix(), already


def import_by_trainer_root(root: Path, active: set[str], copied_hashes: set[str], copied: list[dict[str, str | bool]], left: list[dict[str, str]], corrected: list[dict[str, str]], source_prefix: str) -> None:
    for src in sorted(root.rglob("*.png")):
        rel = src.relative_to(root)
        if len(rel.parts) < 3:
            left.append({"source": f"{source_prefix}/{rel.as_posix()}", "reason": "bad by_trainer path"})
            continue
        title = TITLE_ALIASES.get(rel.parts[0], rel.parts[0])
        folder_gender = GENDER_DIR.get(rel.parts[1].lower())
        if folder_gender is None:
            left.append({"source": f"{source_prefix}/{rel.as_posix()}", "reason": "unknown gender folder"})
            continue
        gender, reason = infer_file_gender(src, folder_gender)
        target = f"{title}/{gender}"
        dest, already = copy_png(src, target, copied_hashes)
        record = {
            "source": f"{source_prefix}/{rel.as_posix()}",
            "target": dest,
            "already": already,
            "activeTarget": target in active,
        }
        copied.append(record)
        if reason == "filename" and gender != folder_gender:
            corrected.append({"source": record["source"], "target": dest, "from": folder_gender, "to": gender})


def main() -> int:
    trainers, _ = unify.load_catalog(unify.DEFAULT_CATALOG)
    active = {trainer["skinFolder"] for trainer in trainers}
    copied_hashes: set[str] = set()
    copied: list[dict[str, str | bool]] = []
    left: list[dict[str, str]] = []
    corrected: list[dict[str, str]] = []

    import_by_trainer_root(BY_TRAINER, active, copied_hashes, copied, left, corrected, "by_trainer")

    for src in sorted(SOURCES.rglob("*.png")):
        if digest(src) in copied_hashes:
            continue
        target = SOURCE_EXTRA_TARGETS.get(src.name)
        if target is None:
            left.append({"source": src.relative_to(REF_ROOT).as_posix(), "reason": "raw source not referenced by by_trainer"})
            continue
        if target not in active:
            left.append({"source": src.relative_to(REF_ROOT).as_posix(), "reason": f"no active catalog folder {target}"})
            continue
        dest, already = copy_png(src, target, copied_hashes)
        copied.append({"source": src.relative_to(REF_ROOT).as_posix(), "target": dest, "already": already, "activeTarget": target in active})

    if MISSING_PATCH_ZIP.exists():
        with tempfile.TemporaryDirectory() as tmp:
            tmp_root = Path(tmp)
            with ZipFile(MISSING_PATCH_ZIP) as archive:
                archive.extractall(tmp_root)
            by_trainer = next(tmp_root.glob("*/by_trainer"), None)
            if by_trainer:
                import_by_trainer_root(by_trainer, active, copied_hashes, copied, left, corrected, MISSING_PATCH_ZIP.name)

    missing = []
    for folder in sorted(active):
        folder_path = unify.DEFAULT_SKINS / folder
        if not any(folder_path.glob("*.png")):
            missing.append(folder)

    report = {
        "activeFolders": len(active),
        "copied": len(copied),
        "alreadyPresent": sum(1 for item in copied if item["already"]),
        "inactiveCopied": sum(1 for item in copied if not item.get("activeTarget")),
        "genderCorrected": len(corrected),
        "left": left,
        "missingActiveFolders": missing,
        "copiedFiles": copied,
        "genderCorrections": corrected,
    }
    unify.write_text(REPORT, json.dumps(report, indent=2))
    print(json.dumps({k: report[k] for k in ("activeFolders", "copied", "alreadyPresent", "inactiveCopied", "genderCorrected")}, indent=2))
    print(f"left={len(left)} missingActiveFolders={len(missing)} report={REPORT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

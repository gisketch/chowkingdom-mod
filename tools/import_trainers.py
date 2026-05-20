#!/usr/bin/env python3
"""Import public trainer roster data into CKDM random trainer catalog JSON.

Inputs are local source checkouts. This script does not download ROM data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tomllib
from pathlib import Path


DEFAULT_TARGET = (
    Path.home()
    / "AppData/Roaming/gisketch/modsync/data/launchers/prismlauncher-cracked/11.0.2-1"
    / "instances/modsync-ckdm-2026/.minecraft/config/gisketchs_chowkingdom_mod/random_trainers/catalog"
)

DEFAULT_RCT_SOURCE = (
    Path(os.environ.get("LOCALAPPDATA", ""))
    / "Temp/rct-mod-1.21.1/common/src/main/resources/data/rctmod/trainers"
)


SOURCE_SPECS = {
    "pokered": ("pret_pokered", "Kanto"),
    "pokecrystal": ("pret_pokecrystal", "Johto/Kanto"),
    "pokeemerald": ("pret_pokeemerald", "Hoenn"),
    "pokefirered": ("pret_pokefirered", "Kanto/Sevii"),
    "pokeplatinum": ("pret_pokeplatinum", "Sinnoh"),
    "pokeheartgold": ("pret_pokeheartgold", "Johto/Kanto"),
}

DIR_ALIASES = {
    "rct": "rct",
    "pret_pokered": "red",
    "pret_pokecrystal": "crys",
    "pret_pokeemerald": "emer",
    "pret_pokefirered": "fire",
    "pret_pokeplatinum": "plat",
    "pret_pokeheartgold": "hgss",
}

UNIQUE_TOKENS = {
    "rival",
    "leader",
    "gym_leader",
    "elite_four",
    "champion",
    "professor",
    "prof",
    "tower_tycoon",
    "frontier_brain",
    "commander",
    "boss",
    "admin",
    "executive",
    "red",
    "blue",
    "cynthia",
    "lance",
    "giovanni",
    "steven",
    "wallace",
}

BLOCKED_WILD_TOKENS = {
    "aaron",
    "agatha",
    "bertha",
    "blaine",
    "blue",
    "bruno",
    "buck",
    "bugsy",
    "byron",
    "candice",
    "cheryl",
    "clair",
    "cynthia",
    "crasher_wake",
    "dawn",
    "erika",
    "fantina",
    "flannery",
    "flint",
    "gardenia",
    "giovanni",
    "glacia",
    "grimsley",
    "juan",
    "jupiter",
    "koga",
    "lance",
    "liza",
    "lorelei",
    "lucas",
    "lucian",
    "maylene",
    "maxie",
    "misty",
    "norman",
    "phoebe",
    "pryce",
    "roark",
    "roxanne",
    "sabrina",
    "sidney",
    "steven",
    "tate",
    "thorton",
    "volkner",
    "wallace",
    "whitney",
    "winona",
    "rival",
    "leader",
    "gym_leader",
    "elite_four",
    "champion",
}

MULTI_TRAINER_TOKENS = {
    "cool_couple",
    "crush_kin",
    "double_team",
    "interviewer",
    "interviewers",
    "old_couple",
    "sis_and_bro",
    "sr_and_jr",
    "twins",
    "young_couple",
}

SPECIAL_SPECIES = {
    "NIDORAN_M": "nidoran_male",
    "NIDORAN_F": "nidoran_female",
    "MR_MIME": "mr_mime",
    "MR__MIME": "mr_mime",
    "MIME_JR": "mime_jr",
    "FARFETCH_D": "farfetchd",
    "FARFETCHD": "farfetchd",
    "HO_OH": "ho_oh",
    "PORYGON_Z": "porygon_z",
}

SPECIAL_MOVES = {
    "NO_MOVE": "",
    "MOVE_NONE": "",
    "NONE": "",
    "PSYCHIC_M": "psychic",
    "MOVE_PSYCHIC_M": "psychic",
    "SELF_DESTRUCT": "selfdestruct",
    "MOVE_SELF_DESTRUCT": "selfdestruct",
    "ANCIENT_POWER": "ancientpower",
    "MOVE_ANCIENT_POWER": "ancientpower",
    "DYNAMIC_PUNCH": "dynamicpunch",
    "MOVE_DYNAMIC_PUNCH": "dynamicpunch",
    "FAINT_ATTACK": "feintattack",
    "MOVE_FAINT_ATTACK": "feintattack",
    "SMELLING_SALT": "smellingsalts",
    "MOVE_SMELLING_SALT": "smellingsalts",
}

FEMALE_FALLBACK_NAMES = (
    "Aika",
    "Alina",
    "Aya",
    "Bianca",
    "Bria",
    "Celia",
    "Clara",
    "Dahlia",
    "Elena",
    "Faye",
    "Gina",
    "Hana",
    "Iris",
    "Jade",
    "Kira",
    "Lena",
    "Mina",
    "Nora",
    "Opal",
    "Rina",
    "Sera",
    "Talia",
    "Vera",
    "Yuna",
)

MALE_FALLBACK_NAMES = (
    "Arlo",
    "Basil",
    "Cal",
    "Dante",
    "Eli",
    "Finn",
    "Galen",
    "Hiro",
    "Ivan",
    "Jace",
    "Kai",
    "Leon",
    "Milo",
    "Nico",
    "Orin",
    "Pax",
    "Reid",
    "Silas",
    "Theo",
    "Vance",
    "Wes",
    "Xander",
    "Yuri",
    "Zane",
)

ANY_FALLBACK_NAMES = FEMALE_FALLBACK_NAMES + MALE_FALLBACK_NAMES


def clean_id(value: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9_.:-]+", "_", value.strip().lower())
    return value.strip("_")


def read_text(path: Path) -> str:
    raw = str(path.resolve())
    if os.name == "nt" and not raw.startswith("\\\\?\\"):
        raw = "\\\\?\\" + raw
    with open(raw, encoding="utf-8") as handle:
        return handle.read()


def long_path(path: Path) -> str:
    raw = str(path.resolve())
    if os.name == "nt" and not raw.startswith("\\\\?\\"):
        return "\\\\?\\" + raw
    return raw


def remove_tree(path: Path) -> None:
    if not path.exists():
        return
    for root, dirs, files in os.walk(long_path(path), topdown=False):
        for file_name in files:
            os.remove(os.path.join(root, file_name))
        for dir_name in dirs:
            os.rmdir(os.path.join(root, dir_name))
    os.rmdir(long_path(path))


def title_words(value: str) -> str:
    words = [w for w in re.split(r"[_\s]+", value.strip()) if w]
    aliases = {"pkmn": "Pokemon", "rs": "", "m": "Male", "f": "Female", "t": ""}
    result = []
    for word in words:
        low = word.lower()
        mapped = aliases.get(low)
        if mapped is None:
            mapped = word.capitalize()
        if mapped:
            result.append(mapped)
    return " ".join(result) or "Trainer"


def stable_index(value: str, size: int) -> int:
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()
    return int(digest[:8], 16) % size


def infer_gender(title: str, value: str = "any") -> str:
    clean = clean_id(value)
    if clean in {"male", "female"}:
        return clean
    title_id = clean_id(title)
    female_tokens = ("girl", "lady", "lass", "beauty", "picnicker", "waitress")
    male_tokens = ("boy", "gentleman", "black_belt", "hiker", "sailor", "waiter")
    if title_id.endswith(("_f", "_female")) or any(token in title_id for token in female_tokens):
        return "female"
    if title_id.endswith(("_m", "_male")) or any(token in title_id for token in male_tokens):
        return "male"
    return "any"


def is_unique_title(title: str) -> bool:
    clean = clean_id(title)
    parts = set(clean.split("_"))
    for token in UNIQUE_TOKENS:
        if clean == token or token in parts:
            return True
        if "_" in token and token in clean:
            return True
    return False


def is_blocked_wild_title(title: str) -> bool:
    clean = clean_id(title)
    parts = set(clean.split("_"))
    for token in BLOCKED_WILD_TOKENS:
        if clean == token or token in parts:
            return True
        if any(part == token or re.fullmatch(rf"{re.escape(token)}\d+", part) for part in parts):
            return True
        if "_" in token and token in clean:
            return True
    return False


def is_multi_trainer(title: str, name: str = "") -> bool:
    clean_title = clean_id(title)
    clean_name = clean_id(name)
    if " & " in name or re.search(r"\b(and|&)\b", name, re.IGNORECASE):
        return True
    for token in MULTI_TRAINER_TOKENS:
        if clean_title == token or token in clean_title or clean_name == token or token in clean_name:
            return True
    return False


def tier_for(title: str, min_level: int, max_level: int, value: str = "") -> str:
    clean = clean_id(value)
    if clean in {"low", "mid", "high", "very_high", "unique"}:
        return clean
    if is_unique_title(title):
        return "unique"
    center = (min_level + max_level) // 2
    if center < 20:
        return "low"
    if center < 45:
        return "mid"
    if center < 70:
        return "high"
    return "very_high"


def category_for(title: str) -> str:
    clean = clean_id(title)
    if is_unique_title(title):
        return "unique"
    if any(token in clean for token in ("rocket", "magma", "aqua", "galactic", "plasma", "flare", "skull", "yell", "star", "grunt")):
        return "team"
    if any(token in clean for token in ("frontier", "tower", "factory", "arcade", "castle")):
        return "battle_facility"
    if any(token in clean for token in ("bug", "bird", "fisher", "swimmer", "hiker", "black_belt", "psychic")):
        return "specialist"
    return "route_trainer"


def skin_path(title: str, gender: str) -> str:
    return "/".join(part for part in (clean_id(title), clean_id(gender)) if part)


def infer_title_from_name(name: str) -> str:
    words = [word for word in re.split(r"\s+", name.strip()) if word]
    if len(words) < 2:
        return "RCT Trainer"
    title = " ".join(words[:-1])
    return title if title else "RCT Trainer"


def scale_jitter(key: str, amount: float = 0.02) -> float:
    return (stable_index(key, 9) - 4) * (amount / 4.0)


def body_defaults(title: str, gender: str) -> tuple[float, float, str]:
    title_id = clean_id(title)
    height = 1.0
    weight = 1.0
    if gender == "female":
        height = 0.98
        weight = 0.94
    elif gender == "male":
        height = 1.02
        weight = 1.02

    if any(token in title_id for token in ("youngster", "bug_catcher", "school", "kid", "camper", "tuber")):
        height -= 0.10
        weight -= 0.08
    if any(token in title_id for token in ("lass", "picnicker", "aroma_lady", "twins")):
        height -= 0.04
        weight -= 0.04
    if any(token in title_id for token in ("hiker", "black_belt", "crush", "roughneck", "sailor", "biker", "ranger")):
        height += 0.08
        weight += 0.14
    if any(token in title_id for token in ("ace_trainer", "cooltrainer", "veteran", "dragon_tamer", "magma_admin", "aqua_admin")):
        height += 0.04
        weight += 0.04
    if any(token in title_id for token in ("beauty", "lady", "idol", "dancer", "kimono")):
        height += 0.02
        weight -= 0.02

    height += scale_jitter(f"{title}:height")
    weight += scale_jitter(f"{title}:weight")
    bust_style = "standard" if gender == "female" else ""
    return round(max(0.6, min(1.4, height)), 2), round(max(0.6, min(1.4, weight)), 2), bust_style


def fallback_trainer_name(title: str, key: str, gender: str) -> str:
    if gender == "female":
        pool = FEMALE_FALLBACK_NAMES
    elif gender == "male":
        pool = MALE_FALLBACK_NAMES
    else:
        pool = ANY_FALLBACK_NAMES
    return pool[stable_index(f"{title}:{key}", len(pool))]


def trainer_given_name(title: str, name: str, key: str, gender: str) -> str:
    clean_name = name.strip()
    if not clean_name:
        return fallback_trainer_name(title, key, gender)
    if re.fullmatch(r"\d+", clean_name):
        return fallback_trainer_name(title, key, gender)
    if clean_id(clean_name) == clean_id(title):
        return fallback_trainer_name(title, key, gender)
    return clean_name


def species_slug(raw: str) -> str:
    raw = raw.strip().rstrip(",")
    raw = raw.removeprefix("SPECIES_")
    if raw in SPECIAL_SPECIES:
        return SPECIAL_SPECIES[raw]
    return re.sub(r"_+", "_", raw.lower()).strip("_")


def move_slug(raw: str) -> str:
    raw = raw.strip().rstrip(",")
    if raw in SPECIAL_MOVES:
        return SPECIAL_MOVES[raw]
    raw = raw.removeprefix("MOVE_")
    if raw in SPECIAL_MOVES:
        return SPECIAL_MOVES[raw]
    return raw.lower().replace("_", "")


def clean_message(text: str) -> str:
    text = text.replace("{TRNAME}", "").replace("\\f", " ").replace("\\r", " ")
    text = text.replace("\\n", " ").replace("\n", " ").replace("\r", " ")
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def pokemon(species: str, level: int, moves: list[str] | None = None) -> dict:
    return {
        "species": f"cobblemon:{species_slug(species)}",
        "level": max(1, min(100, int(level))),
        "gender": "GENDERLESS",
        "nature": "",
        "ability": "",
        "moveset": [m for m in (move_slug(x) for x in (moves or [])) if m][:4],
        "heldItem": "",
        "shiny": False,
        "aspects": [],
    }


def definition(
    source: str,
    region: str,
    key: str,
    title: str,
    name: str,
    team: list[dict],
    dialogue: list[str] | None = None,
) -> dict | None:
    if not team:
        return None
    if is_blocked_wild_title(title) or is_multi_trainer(title, name):
        return None
    min_level = min(mon["level"] for mon in team)
    max_level = max(mon["level"] for mon in team)
    gender = infer_gender(title)
    name = trainer_given_name(title, name, key, gender)
    height, weight, bust_style = body_defaults(title, gender)
    tier = tier_for(title, min_level, max_level)
    full_name = " ".join(part for part in [title.strip(), name.strip()] if part).strip()
    if not full_name:
        full_name = title or "Trainer"
    return {
        "id": clean_id(f"{source}_{key}"),
        "name": full_name,
        "title": title.strip() or "Trainer",
        "gender": gender,
        "archetype": clean_id(title or "trainer"),
        "region": region,
        "category": category_for(title),
        "source": source,
        "skinSet": "",
        "skinFolder": skin_path(title, gender),
        "tier": tier,
        "spawnable": not is_unique_title(title),
        "height": height,
        "weight": weight,
        "bustStyle": bust_style,
        "minLevel": min_level,
        "maxLevel": max_level,
        "team": team,
        "dialogue": (dialogue or [f"{full_name} is ready for a battle."])[:12],
    }


def write_defs(target: Path, source: str, defs: list[dict]) -> int:
    legacy = target / f"imported_{source}"
    if legacy.exists():
        remove_tree(legacy)
    out = target / f"imp_{DIR_ALIASES.get(source, source)}"
    if out.exists():
        remove_tree(out)
    out.mkdir(parents=True, exist_ok=True)
    written = 0
    seen: set[str] = set()
    for item in defs:
        if not item or not item.get("team"):
            continue
        item_id = item["id"]
        if item_id in seen:
            digest = hashlib.sha1(json.dumps(item["team"], sort_keys=True).encode()).hexdigest()[:8]
            item_id = f"{item_id}_{digest}"
            item["id"] = item_id
        seen.add(item_id)
        digest = hashlib.sha1(item_id.encode()).hexdigest()[:10]
        file_stem = clean_id(item_id)[:24].strip("_")
        (out / f"{file_stem}_{digest}.json").write_text(json.dumps(item, indent=2), encoding="utf-8")
        written += 1
    return written


def normalize_existing_rct(target: Path) -> int:
    source_dir = target / "imported_rct"
    if not source_dir.exists():
        return 0
    defs = []
    for path in sorted(source_dir.glob("*.toml")):
        data = tomllib.loads(read_text(path))
        team = data.get("team") or []
        if not team:
            continue
        title = str(data.get("title") or "RCT Trainer")
        gender = infer_gender(title, str(data.get("gender") or "any"))
        min_level = int(data.get("minLevel") or min(mon.get("level", 1) for mon in team))
        max_level = int(data.get("maxLevel") or max(mon.get("level", 1) for mon in team))
        height, weight, bust_style = body_defaults(title, gender)
        defs.append(
            {
                "id": clean_id(str(data.get("id") or path.stem)),
                "name": str(data.get("name") or title),
                "title": title,
                "gender": gender,
                "archetype": clean_id(str(data.get("archetype") or title)),
                "region": str(data.get("region") or ""),
                "category": category_for(title),
                "source": str(data.get("source") or "rct_import"),
                "skinSet": clean_id(str(data.get("skinSet") or "")),
                "skinFolder": skin_path(title, gender),
                "tier": tier_for(title, min_level, max_level, str(data.get("tier") or "")),
                "spawnable": bool(data.get("spawnable", True)) and not is_unique_title(title),
                "height": float(data.get("height") or height),
                "weight": float(data.get("weight") or weight),
                "bustStyle": str(data.get("bustStyle") or bust_style) if gender == "female" else "",
                "minLevel": min_level,
                "maxLevel": max_level,
                "team": [
                    {
                        "species": mon.get("species", ""),
                        "level": int(mon.get("level", 1)),
                        "gender": mon.get("gender", "GENDERLESS"),
                        "nature": mon.get("nature", ""),
                        "ability": mon.get("ability", ""),
                        "moveset": mon.get("moveset", []),
                        "heldItem": mon.get("heldItem", ""),
                        "shiny": bool(mon.get("shiny", False)),
                        "aspects": mon.get("aspects", []),
                    }
                    for mon in team
                ],
                "dialogue": data.get("dialogue") or [f"{title} is ready for a battle."],
            }
        )
    count = write_defs(target, "rct", defs)
    remove_tree(source_dir)
    return count


def asm_tokens(line: str) -> list[str]:
    line = line.split(";", 1)[0]
    if "db" not in line:
        return []
    line = line.split("db", 1)[1].strip()
    return [tok.strip().rstrip(",") for tok in line.split(",") if tok.strip()]


def int_token(token: str) -> int | None:
    token = token.strip()
    if token == "$FF":
        return 255
    if token in {"-1", "0"}:
        return int(token)
    try:
        return int(token)
    except ValueError:
        return None


def parse_pokered(root: Path) -> list[dict]:
    path = root / "data/trainers/parties.asm"
    if not path.exists():
        return []
    defs: list[dict] = []
    current = ""
    group_index = 0
    last_comment = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.endswith("Data:") and not line.startswith(("Trainer", ";")):
            current = line[:-5]
            group_index = 0
            last_comment = ""
            continue
        if not current:
            continue
        if line.startswith(";"):
            last_comment = line[1:].strip()
            continue
        tokens = asm_tokens(line)
        if not tokens:
            continue
        team: list[dict] = []
        if tokens[0] == "$FF":
            values = tokens[1:]
            for i in range(0, len(values) - 1, 2):
                level = int_token(values[i])
                spec = values[i + 1]
                if level is None or spec == "0":
                    break
                team.append(pokemon(spec, level))
        else:
            level = int_token(tokens[0])
            if level is None:
                continue
            for spec in tokens[1:]:
                if spec == "0":
                    break
                team.append(pokemon(spec, level))
        if not team:
            continue
        group_index += 1
        title = title_words(current)
        name = "" if current.lower() in {"brock", "misty", "lance"} else f"{group_index:03d}"
        key = f"{clean_id(current)}_{group_index:03d}"
        dialogue = [f"{title} from {last_comment or 'Kanto'} is ready for a battle."]
        item = definition("pret_pokered", "Kanto", key, title, name, team, dialogue)
        if item:
            defs.append(item)
    return defs


def parse_rct(root: Path) -> list[dict]:
    if not root.exists():
        return []
    defs = []
    for path in sorted(root.rglob("*.json")):
        data = json.loads(read_text(path))
        raw_team = data.get("team") or []
        team = []
        for mon in raw_team:
            species = mon.get("species", "")
            if not species:
                continue
            team.append(
                {
                    "species": species if ":" in species else f"cobblemon:{species_slug(species)}",
                    "level": max(1, min(100, int(mon.get("level", 1)))),
                    "gender": mon.get("gender", "GENDERLESS"),
                    "nature": mon.get("nature", ""),
                    "ability": mon.get("ability", ""),
                    "moveset": [move_slug(move) for move in mon.get("moveset", []) if move_slug(move)][:4],
                    "heldItem": mon.get("heldItem", ""),
                    "shiny": bool(mon.get("shiny", False)),
                    "aspects": mon.get("aspects", []),
                }
            )
        if not team:
            continue
        relative = path.relative_to(root)
        name_raw = data.get("name", "")
        if isinstance(name_raw, dict):
            name_raw = name_raw.get("text") or name_raw.get("literal") or name_raw.get("key") or ""
        name = str(name_raw).strip() or "RCT Trainer"
        title = (
            title_words(relative.parent.as_posix().replace("/", "_"))
            if relative.parent.as_posix() != "."
            else infer_title_from_name(name)
        )
        key = clean_id(relative.with_suffix("").as_posix())
        item = definition("rct_import", "", key, title, name.replace(title, "", 1).strip(), team, ["I have trained this team for the road. Challenge me if you are ready."])
        if item:
            item["id"] = clean_id(f"rct_{key}")
            item["name"] = name
            item["source"] = "rct_import"
            defs.append(item)
    return defs


def parse_pokecrystal(root: Path) -> list[dict]:
    path = root / "data/trainers/parties.asm"
    if not path.exists():
        return []
    defs: list[dict] = []
    group = ""
    comment_title = ""
    active: dict | None = None
    active_type = ""
    index = 0
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.endswith("Group:") and not line.startswith(";"):
            group = line[:-6]
            continue
        if line.startswith(";"):
            match = re.search(r";\s*([A-Z0-9_]+)\s+\((\d+)\)", line)
            if match:
                comment_title = title_words(match.group(1))
                index = int(match.group(2))
            continue
        if line.startswith("db -1"):
            if active:
                title = active["title"]
                name = active["name"]
                key = f"{clean_id(group)}_{index:03d}_{clean_id(name)}"
                item = definition("pret_pokecrystal", "Johto/Kanto", key, title, name.title(), active["team"], active["dialogue"])
                if item:
                    defs.append(item)
            active = None
            active_type = ""
            continue
        if 'db "' in line and "TRAINERTYPE_" in line:
            name = line.split('"', 2)[1].replace("@", "").replace("?", "Rival").strip()
            active_type = re.search(r"TRAINERTYPE_[A-Z_]+", line).group(0)
            title = comment_title or title_words(group)
            active = {"title": title, "name": name, "team": [], "dialogue": [f"{title} {name.title()} is ready for a battle."]}
            continue
        if not active:
            continue
        tokens = asm_tokens(line)
        if len(tokens) < 2:
            continue
        level = int_token(tokens[0])
        if level is None:
            continue
        spec = tokens[1]
        moves: list[str] = []
        if "MOVES" in active_type:
            offset = 3 if "ITEM" in active_type else 2
            moves = tokens[offset : offset + 4]
        active["team"].append(pokemon(spec, level, moves))
    return defs


def parse_c_parties(path: Path) -> dict[str, list[dict]]:
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8", errors="ignore")
    parties: dict[str, list[dict]] = {}
    pattern = re.compile(r"static const struct [^{]+ sParty_([A-Za-z0-9_]+)\[\]\s*=\s*\{(.*?)\n\};", re.S)
    for match in pattern.finditer(text):
        name = match.group(1)
        body = match.group(2)
        team = []
        for mon in re.finditer(r"\{(.*?\.species\s*=\s*SPECIES_[A-Z0-9_]+.*?)(?:\n\s*\},|\n\s*\})", body, re.S):
            block = mon.group(1)
            level_match = re.search(r"\.lvl\s*=\s*(\d+)", block)
            species_match = re.search(r"\.species\s*=\s*(SPECIES_[A-Z0-9_]+)", block)
            if not level_match or not species_match:
                continue
            moves_match = re.search(r"\.moves\s*=\s*\{([^}]+)\}", block)
            moves = []
            if moves_match:
                moves = [m.strip() for m in moves_match.group(1).split(",")]
            team.append(pokemon(species_match.group(1), int(level_match.group(1)), moves))
        if team:
            parties[name] = team
    return parties


def parse_gen3(root: Path, source: str, region: str) -> list[dict]:
    parties = parse_c_parties(root / "src/data/trainer_parties.h")
    trainers_path = root / "src/data/trainers.h"
    if not parties or not trainers_path.exists():
        return []
    text = trainers_path.read_text(encoding="utf-8", errors="ignore")
    defs: list[dict] = []
    entry_re = re.compile(r"\[(TRAINER_[A-Z0-9_]+)\]\s*=\s*\{(.*?)\n\s*\},", re.S)
    for match in entry_re.finditer(text):
        trainer_key = match.group(1)
        body = match.group(2)
        party_match = re.search(r"sParty_([A-Za-z0-9_]+)", body)
        if not party_match:
            continue
        party_key = party_match.group(1)
        team = parties.get(party_key, [])
        if not team:
            continue
        class_match = re.search(r"\.trainerClass\s*=\s*(TRAINER_CLASS_[A-Z0-9_]+)", body)
        name_match = re.search(r'\.trainerName\s*=\s*_\("([^"]*)"\)', body)
        title = title_words((class_match.group(1) if class_match else "TRAINER_CLASS_TRAINER").removeprefix("TRAINER_CLASS_"))
        name = (name_match.group(1) if name_match else "").strip()
        if not name:
            stripped = trainer_key.removeprefix("TRAINER_")
            name = title_words(stripped.split("_")[-1] if "_" in stripped else stripped)
        key = clean_id(trainer_key.removeprefix("TRAINER_"))
        item = definition(source, region, key, title, name.title(), team, [f"{title} {name.title()} is ready for a battle."])
        if item:
            defs.append(item)
    return defs


def parse_pokeplatinum(root: Path) -> list[dict]:
    data_dir = root / "res/trainers/data"
    if not data_dir.exists():
        return []
    defs: list[dict] = []
    for path in sorted(data_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        party = data.get("party") or []
        team = [pokemon(mon.get("species", ""), mon.get("level", 1), mon.get("moves") or []) for mon in party if mon.get("species")]
        if not team:
            continue
        title = title_words(str(data.get("class", "TRAINER_CLASS_TRAINER")).removeprefix("TRAINER_CLASS_"))
        name = str(data.get("name", "")).strip().title()
        messages = []
        for msg in data.get("messages") or []:
            if msg.get("type") in {"TRMSG_PRE_BATTLE", "TRMSG_POST_BATTLE"}:
                val = msg.get("en_US", "")
                messages.append(clean_message("".join(val) if isinstance(val, list) else str(val)))
        key = path.stem
        item = definition("pret_pokeplatinum", "Sinnoh", key, title, name, team, [m for m in messages if m])
        if item:
            defs.append(item)
    return defs


def parse_pokeheartgold(root: Path) -> list[dict]:
    path = root / "files/poketool/trainer/trainers.json"
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    defs: list[dict] = []
    for index, trainer in enumerate(data.get("trainers") or []):
        party = trainer.get("party") or []
        team = [pokemon(mon.get("species", ""), mon.get("level", 1), mon.get("moves") or []) for mon in party if mon.get("species")]
        if not team:
            continue
        title = title_words(str(trainer.get("class", "TRAINERCLASS_TRAINER")).removeprefix("TRAINERCLASS_"))
        name = str(trainer.get("name", "")).replace("{TRNAME}", "").strip()
        messages = []
        for msg in trainer.get("messages") or []:
            if msg.get("type") in {"TRMSG_INTRO", "TRMSG_LAST_POKE", "TRMSG_AFTER"}:
                messages.append(clean_message(str(msg.get("message", ""))))
        key_base = f"{index:04d}_{clean_id(title)}_{clean_id(name)}"
        item = definition("pret_pokeheartgold", "Johto/Kanto", key_base, title, name, team, [m for m in messages if m])
        if item:
            defs.append(item)
    return defs


def parse_sources(sources_root: Path) -> dict[str, list[dict]]:
    return {
        "pret_pokered": parse_pokered(sources_root / "pokered"),
        "pret_pokecrystal": parse_pokecrystal(sources_root / "pokecrystal"),
        "pret_pokeemerald": parse_gen3(sources_root / "pokeemerald", "pret_pokeemerald", "Hoenn"),
        "pret_pokefirered": parse_gen3(sources_root / "pokefirered", "pret_pokefirered", "Kanto/Sevii"),
        "pret_pokeplatinum": parse_pokeplatinum(sources_root / "pokeplatinum"),
        "pret_pokeheartgold": parse_pokeheartgold(sources_root / "pokeheartgold"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources-root", type=Path, default=Path(os.environ.get("TEMP", ".")) / "ckdm-trainer-sources")
    parser.add_argument("--rct-source", type=Path, default=DEFAULT_RCT_SOURCE)
    parser.add_argument("--target", type=Path, default=DEFAULT_TARGET)
    parser.add_argument("--skip-unify", action="store_true", help="Write source-bucket files directly to target for debugging.")
    parser.add_argument("--keep-raw", action="store_true", help="Keep the temporary raw import catalog after unifying.")
    args = parser.parse_args()

    work_target = args.target if args.skip_unify else args.target.parent / "catalog_import_raw"
    if not args.skip_unify and work_target.exists():
        remove_tree(work_target)
    work_target.mkdir(parents=True, exist_ok=True)

    parsed = parse_sources(args.sources_root)
    total = 0
    rct_defs = parse_rct(args.rct_source)
    rct_count = write_defs(work_target, "rct", rct_defs) if rct_defs else normalize_existing_rct(work_target)
    total += rct_count
    if rct_count:
        print(f"rct: parsed={len(rct_defs) if rct_defs else rct_count} written={rct_count}")
    for source, defs in parsed.items():
        count = write_defs(work_target, source, defs)
        total += count
        print(f"{source}: parsed={len(defs)} written={count}")
    print(f"total_written={total}")
    if args.skip_unify:
        print(f"target={args.target}")
        return 0

    import unify_random_trainers as unify

    trainers, catalog_stats = unify.load_catalog(work_target)
    backup = unify.rebuild_catalog(args.target, trainers, False)
    skin_stats = unify.unify_skins(unify.DEFAULT_SKINS, False)
    skin_stats.update(unify.scaffold_skin_placeholders(unify.DEFAULT_SKINS, trainers, False))
    unify.write_report(args.target, catalog_stats, skin_stats, backup, False)
    if not args.keep_raw:
        remove_tree(work_target)
    print(f"target={args.target}")
    print(f"unified_written={catalog_stats['written']} duplicate_entries={catalog_stats['duplicate_entries']} skipped={catalog_stats['skipped']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

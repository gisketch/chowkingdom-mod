#!/usr/bin/env python3
"""Unify CKDM random trainer catalog and skin folders.

The importer can pull rosters from many sources, but runtime catalog ownership
should be by trainer identity, not source. This script rewrites the catalog into:

    catalog/<title_id>/<m|f|x>/<title_id>_<m|f|x>_<name_id>.toml

Duplicate identities are merged by canonical title, gender, and given name.
Skin PNGs are moved into:

    assets/.../textures/entity/random_trainers/<title_id>/<male|female|any>/*.png
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tomllib
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = (
    Path.home()
    / "AppData/Roaming/gisketch/modsync/data/launchers/prismlauncher-cracked/11.0.2-1"
    / "instances/modsync-ckdm-2026/.minecraft/config/gisketchs_chowkingdom_mod/random_trainers/catalog"
)
DEFAULT_SKINS = (
    REPO_ROOT
    / "src/main/resources/assets/gisketchs_chowkingdom_mod/textures/entity/random_trainers"
)
SKIN_PLACEHOLDER = ".gitkeep"

FEMALE_NAMES = {
    "aika", "alina", "aya", "bianca", "bria", "celia", "clara", "dahlia",
    "elena", "faye", "gina", "hana", "iris", "jade", "kira", "lena",
    "mina", "nora", "opal", "rina", "sera", "talia", "vera", "yuna",
}
MALE_NAMES = {
    "arlo", "basil", "cal", "dante", "eli", "finn", "galen", "hiro",
    "ivan", "jace", "kai", "leon", "milo", "nico", "orin", "pax",
    "reid", "silas", "theo", "vance", "wes", "xander", "yuri", "zane",
}
FALLBACK_NAMES = sorted(FEMALE_NAMES | MALE_NAMES)

FEMALE_NAME_HINTS = FEMALE_NAMES | {
    "abigail", "agatha", "aika", "alexa", "alexandra", "alexia", "alexis",
    "alice", "alicia", "alina", "alix", "alize", "allison", "alyssa",
    "amanda", "amber", "amira", "anabel", "angelica", "annie", "annika",
    "ariana", "ariel", "ashlee", "athena", "audrey", "austina", "autumn",
    "ava", "barbs", "belinda", "bertha", "beth", "bethany", "beverly",
    "bianca", "brandi", "brenna", "bria", "brianna", "brooke", "cady",
    "candice", "carly", "carolina", "caroline", "catherine", "celia",
    "celina", "cheryl", "clair", "clara", "clare", "clarice", "clarissa",
    "colette", "cristin", "cynthia", "dahlia", "daisy", "danielle", "dawn",
    "diana", "dianne", "dominique", "doris", "edith", "edna", "eleanor",
    "elena", "emilia", "emily", "emma", "erika", "ethel", "faye",
    "felicia", "gabrielle", "gail", "gardenia", "geneva", "gina", "grace",
    "greta", "halle", "hana", "heather", "hope", "iris", "irene",
    "janny", "jasmine", "jazmyn", "jenn", "jennifer", "jessie", "jody",
    "joyce", "julie", "kassandra", "kate", "katelynn", "keira", "kelly",
    "kira", "laura", "lena", "lola", "lois", "maria", "mariah", "marley",
    "mary", "meagan", "megan", "michelle", "mikayla", "mina", "moira",
    "monique", "naomi", "nora", "olivia", "opal", "piper", "rina",
    "reena", "sabrina", "salma", "samantha", "sandra", "savannah", "sera",
    "shannon", "sharon", "shelly", "sydney", "talia", "tessy", "vera",
    "wendy", "whitney", "winona", "yuna",
}

MALE_NAME_HINTS = MALE_NAMES | {
    "aaron", "abe", "abner", "aidan", "aiden", "al", "albert", "alberto",
    "alec", "alex", "allan", "allen", "aloysius", "alvaro", "andre",
    "andres", "andrew", "angelo", "anthony", "anton", "arlo", "armando",
    "arnie", "arnold", "arturo", "ashton", "auron", "austin", "avery",
    "axle", "barney", "barny", "barry", "basil", "beau", "beck", "beckett",
    "ben", "benjamin", "benny", "berke", "bernie", "bert", "bertrand",
    "bill", "billy", "blake", "bob", "bobby", "boris", "brad", "braden",
    "brady", "branden", "brandon", "braxton", "braydon", "brendan",
    "brenden", "brendon", "brent", "bret", "brett", "brian", "bryan",
    "bryant", "bryce", "burt", "cal", "cale", "caleb", "calvin", "camden",
    "cameron", "camron", "carlos", "carter", "cary", "cedric", "chad",
    "charles", "charlie", "chase", "chaz", "chester", "chip", "chow",
    "chris", "clark", "claude", "clayton", "clinton", "clyde", "coby",
    "cody", "colby", "cole", "colin", "colton", "conner", "connor",
    "conor", "conrad", "cooper", "cordell", "corey", "cory", "craig",
    "curtis", "dale", "dallas", "dalton", "dan", "dane", "daniel", "danny",
    "dante", "darian", "darien", "dario", "darius", "darrius", "darryl",
    "dave", "david", "davis", "dawson", "dayton", "dean", "deandre",
    "demetrius", "denis", "derek", "deshawn", "destin", "dick", "diego",
    "dillan", "dillon", "dion", "dirk", "don", "donald", "donny", "doug",
    "douglas", "drake", "drew", "duncan", "dusty", "dwayne", "dylan",
    "easton", "ed", "eddie", "edgar", "edmond", "edward", "edwardo",
    "edwin", "eli", "elijah", "elliot", "ellis", "emanuel", "emilio",
    "eric", "erick", "erik", "ernest", "ernesto", "ernie", "ethan",
    "eugene", "evan", "fabian", "fernando", "finn", "foster", "fredrick",
    "fritz", "gaku", "galen", "garett", "garret", "garrett", "garrison",
    "gary", "george", "gerald", "gilbert", "greg", "gregg", "gregory",
    "hiro", "ivan", "jace", "jack", "jake", "james", "jamie", "jason",
    "jay", "jeremy", "jerry", "jesse", "jim", "jimmy", "joey", "john",
    "johnny", "jonathan", "jose", "josh", "joshua", "juan", "julian",
    "kai", "keith", "ken", "kenneth", "kevin", "kyle", "lance", "larry",
    "leon", "leonel", "leroy", "logan", "louis", "lucas", "marcel",
    "marco", "mark", "matt", "matthew", "michael", "mickey", "mike", "milo",
    "mitchell", "mo", "nico", "nolan", "owen", "parker", "paul", "pax",
    "pete", "quincy", "randall", "reid", "richard", "robert", "rolando",
    "ryan", "samuel", "sergio", "silas", "steve", "theo", "tim", "tom",
    "tony", "vance", "vincent", "vito", "warren", "wes", "william",
    "wilton", "xander", "yuji", "yuri", "zane",
}

FEMALE_TITLE_IDS = {
    "aroma_lady", "battle_girl", "beauty", "channeler", "cowgirl", "crush_girl",
    "idol", "kimono_girl", "lady", "lass", "madame", "maid", "medium",
    "parasol_lady", "picnicker", "schoolgirl", "waitress",
}

MALE_TITLE_IDS = {
    "biker", "bird_keeper", "bird_keeper_gs", "black_belt", "bug_catcher",
    "burglar", "camper", "cue_ball", "cueball", "dragon_tamer", "engineer",
    "firebreather", "fisher", "fisherman", "gambler", "gentleman", "guitarist",
    "hiker", "juggler", "ninja_boy", "pokemaniac", "poke_maniac", "roughneck",
    "ruin_maniac", "sailor", "sage", "scientist", "super_nerd", "supernerd",
    "tamer", "worker", "youngster",
}

ANY_SKIN_GENDER_OVERRIDES = {
    "aqua_admin": "male",
    "arcade_star": "female",
    "arena_tycoon": "female",
    "biker": "male",
    "swimmer": "male",
}

FEMALE_NAME_HINTS.update({
    "darcy", "haley", "hannah", "hillary", "isabel", "isobel", "jacki",
    "janae", "janet", "jill", "karen", "katherine", "kathleen", "kaylee",
    "kinsey", "kinsley", "krystal", "laurel", "leah", "lily", "macey",
    "madeline", "margaret", "margret", "marigold", "marlene", "may",
    "meghan", "mel", "meredith", "mollie", "rachel", "rebekah", "roxanne",
    "ruth", "shelby", "shirley", "sophie", "stacey", "stacy", "sue",
    "tammy", "valerie", "vicky", "vivi", "vivian",
})

MALE_NAME_HINTS.update({
    "aavery", "admin", "atk", "baily", "biker", "blue", "boy", "brunore",
    "burglar", "catcher", "cooprt", "creator", "def", "fan", "gambler",
    "geoffrey", "gian", "gideon", "glenn", "goon", "grant", "gruff", "grunt",
    "hank", "harlan", "harold", "harris", "harry", "harvey", "hector",
    "henry", "hp", "hugh", "hunter", "ian", "irwin", "isaac", "isaiah",
    "ismael", "issac", "jacob", "jamal", "jared", "jaren", "jasper", "jax",
    "jaxon", "jaylen", "jed", "jeff", "jeffrey", "jin", "joe", "joel",
    "johan", "jonah", "jonathon", "joseph", "josue", "jovan", "justin",
    "kahlil", "kaleb", "karl", "kazu", "keegan", "kenny", "kent", "ketchup",
    "kid", "kirby", "kirk", "koji", "kyler", "lancere", "lawrence", "lee",
    "lewis", "li", "liam", "lloyd", "lonnie", "lowell", "luc", "lukas",
    "luke", "lyle", "malik", "maniac", "marc", "marcos", "markey", "marlon",
    "martin", "marvin", "mathis", "merle", "miguel", "mikey", "miller",
    "mitch", "morgan", "murphy", "myles", "nash", "nat", "nate", "neal",
    "ned", "nicholas", "nicolas", "nikolas", "noel", "oliver", "ondrej",
    "osean", "otis", "parkker", "pat", "perry", "peter", "phil", "phillip",
    "ping", "presley", "quentin", "quinn", "ralph", "ramon", "raul", "ray",
    "red", "reed", "reese", "regis", "rex", "rich", "ricky", "riley", "rob",
    "robby", "rod", "rodney", "roger", "roland", "ron", "ronald", "rory",
    "ross", "roy", "ruben", "runan", "russ", "ryuzoji", "sam", "sammy",
    "scientist", "scott", "sebastian", "shane", "shaun", "shawn", "sid",
    "simon", "skyler", "solenk", "spd", "spe", "spencer", "spenser", "stan",
    "stanley", "stanly", "stephen", "sterling", "switzer", "symes", "tanner",
    "teacher", "ted", "teddy", "terrell", "terry", "teru", "tevin", "theron",
    "thomas", "thug", "timmy", "timothy", "toby", "todd", "tommy", "travis",
    "travon", "trevor", "tristan", "troy", "tully", "ty", "tyler", "tylor",
    "tyron", "tyrone", "victor", "virgil", "wade", "walt", "walter", "wayne",
    "willy", "wyatt", "yale", "yasu", "zac", "zach", "zachary", "zackary",
    "zeek", "zeke", "zeph",
})

UNIQUE_TOKENS = {
    "rival", "leader", "gym_leader", "elite_four", "champion", "professor",
    "prof", "tower_tycoon", "frontier_brain", "commander", "boss", "admin",
    "executive", "red", "blue", "cynthia", "lance", "giovanni", "mars",
    "saturn", "steven", "wallace",
}
BLOCKED_WILD_TOKENS = UNIQUE_TOKENS | {
    "aaron", "agatha", "bertha", "blaine", "bruno", "buck", "bugsy",
    "brock", "byron", "candice", "cheryl", "chuck", "clair",
    "castle_valet", "crasher_wake", "dawn", "erika", "factory_head",
    "falkner", "fantina", "flannery", "flint", "gardenia", "glacia", "grimsley", "janine", "jasmine",
    "juan", "jupiter", "karen", "koga", "lt", "lt_surge", "ltsurge",
    "liza", "lorelei", "lucas", "lucian", "maylene", "maxie", "misty",
    "morty", "norman", "oak", "phoebe", "prof_oak", "profoak", "pryce",
    "roark", "roxanne", "sabrina", "sidney", "tate", "thorton", "volkner",
    "whitney", "winona", "arcade_star", "arena_tycoon", "commanders",
    "dome_ace", "hall_matron", "palace_maven", "pike_queen", "player", "protag",
}
MULTI_TRAINER_TOKENS = {
    "cool_couple", "crush_kin", "double_team", "interviewer", "interviewers",
    "old_couple", "sis_and_bro", "sr_and_jr", "twin", "twins", "young_couple",
}

TITLE_ALIASES = {
    "ace_trainer_male": "Ace Trainer",
    "ace_trainer_female": "Ace Trainer",
    "cooltrainer": "Ace Trainer",
    "cooltrainer_m": "Ace Trainer",
    "cooltrainer_f": "Ace Trainer",
    "cooltra": "Ace Trainer",
    "bugcatcher": "Bug Catcher",
    "bugcatc": "Bug Catcher",
    "birdkeeper": "Bird Keeper",
    "birdkee": "Bird Keeper",
    "blackbelt": "Black Belt",
    "blackbe": "Black Belt",
    "firebre": "Firebreather",
    "gentlem": "Gentleman",
    "guitari": "Guitarist",
    "kimonog": "Kimono Girl",
    "picnick": "Picnicker",
    "pokeman": "Pokemaniac",
    "pkmn_maniac": "Pokemaniac",
    "scienti": "Scientist",
    "superne": "Super Nerd",
    "jrtrainer": "Jr Trainer",
    "jrtrainerf": "Jr Trainer",
    "jrtrainerm": "Jr Trainer",
    "swimmerf": "Swimmer",
    "swimmerm": "Swimmer",
    "gruntf": "Grunt",
    "gruntm": "Grunt",
}

SUFFIXABLE_TITLES = {
    "ace_trainer", "swimmer", "cooltrainer", "jrtrainer", "grunt", "rocket",
    "schoolkid", "skier", "ranger", "psychic", "black_belt", "biker",
}

POKEMON_KEYS = (
    "species", "level", "gender", "nature", "ability", "moveset",
    "heldItem", "shiny", "aspects",
)
TRAINER_KEYS = (
    "id", "name", "title", "gender", "archetype", "region", "category",
    "source", "skinSet", "skinFolder", "tier", "spawnable", "height",
    "weight", "bustStyle", "minLevel", "maxLevel", "team", "dialogue",
)


def clean_id(value: Any) -> str:
    text = str(value or "").strip().lower()
    text = re.sub(r"[^a-z0-9_-]+", "_", text)
    return text.strip("_.-")


def long_path(path: Path) -> str:
    raw = str(path.resolve())
    if os.name == "nt" and not raw.startswith("\\\\?\\"):
        return "\\\\?\\" + raw
    return raw


def read_text(path: Path) -> str:
    with open(long_path(path), encoding="utf-8") as handle:
        return handle.read()


def write_text(path: Path, text: str) -> None:
    os.makedirs(long_path(path.parent), exist_ok=True)
    with open(long_path(path), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def assert_inside(path: Path, parent: Path) -> None:
    resolved = path.resolve()
    root = parent.resolve()
    if resolved != root and root not in resolved.parents:
        raise ValueError(f"Refusing path outside allowed root: {resolved} not under {root}")


def safe_rmtree(path: Path, allowed_parent: Path) -> None:
    if not path.exists():
        return
    assert_inside(path, allowed_parent)
    shutil.rmtree(long_path(path))


def strip_long_prefix(value: str) -> str:
    return value.removeprefix("\\\\?\\")


def walk_files(root: Path, suffixes: set[str]) -> list[Path]:
    result: list[Path] = []
    for dirpath, _, filenames in os.walk(long_path(root)):
        for filename in filenames:
            if Path(filename).suffix.lower() in suffixes:
                result.append(Path(strip_long_prefix(os.path.join(dirpath, filename))))
    return sorted(result)


def title_words(value: str) -> str:
    words = [word for word in re.split(r"[_\s]+", value.strip()) if word]
    return " ".join(word[:1].upper() + word[1:].lower() for word in words) or "Trainer"


def strip_title_suffixes(title_id: str) -> tuple[str, str | None]:
    parts = [part for part in title_id.split("_") if part]
    gender: str | None = None
    while parts and parts[-1].isdigit():
        parts.pop()
    if parts and parts[-1] in {"male", "m"}:
        gender = "male"
        parts.pop()
    elif parts and parts[-1] in {"female", "f"}:
        gender = "female"
        parts.pop()
    clean = "_".join(parts)
    for suffix, suffix_gender in (("female", "female"), ("male", "male"), ("f", "female"), ("m", "male")):
        if clean.endswith(suffix):
            base = clean[: -len(suffix)].strip("_")
            if base in SUFFIXABLE_TITLES or base in TITLE_ALIASES or clean in TITLE_ALIASES:
                return base or clean, gender or suffix_gender
    return clean or title_id, gender


def canonical_title_gender(title: str, gender: str = "any") -> tuple[str, str]:
    raw_title_id = clean_id(title)
    raw_gender = clean_id(gender)
    resolved_gender = raw_gender if raw_gender in {"male", "female"} else "any"
    stripped_id, title_gender = strip_title_suffixes(raw_title_id)
    if resolved_gender == "any" and title_gender:
        resolved_gender = title_gender

    alias_key = raw_title_id if raw_title_id in TITLE_ALIASES else stripped_id
    title_text = TITLE_ALIASES.get(alias_key) or title_words(stripped_id)
    title_id = clean_id(title_text)

    if resolved_gender == "any":
        if title_id.endswith("_female"):
            title_text = title_words(title_id.removesuffix("_female"))
            resolved_gender = "female"
        elif title_id.endswith("_male"):
            title_text = title_words(title_id.removesuffix("_male"))
            resolved_gender = "male"

    return title_text.strip() or "Trainer", resolved_gender


def gender_code(gender: str) -> str:
    return {"male": "m", "female": "f"}.get(gender, "x")


def gender_folder(gender: str) -> str:
    return gender if gender in {"male", "female"} else "any"


def infer_gender_from_name(name: str, title: str, gender: str) -> str:
    if gender in {"male", "female"}:
        return gender
    clean_name = clean_id(name)
    clean_title = clean_id(title)
    for marker, resolved in (("male", "male"), ("female", "female")):
        if clean_name.startswith(f"{clean_title}_{marker}_") or clean_name.startswith(f"{marker}_"):
            return resolved
    return gender


def name_tokens(name: str) -> list[str]:
    generic = {"atk", "def", "big", "boy", "girl", "trainer", "rct", "admin", "creator", "fan"}
    tokens = [part for part in clean_id(name).split("_") if part and part not in generic]
    return tokens or [part for part in clean_id(name).split("_") if part]


def infer_gender_from_given(given: str, title: str, gender: str, seed: str) -> str:
    if gender in {"male", "female"}:
        return gender
    tokens = name_tokens(given)
    for token in tokens:
        if token in FEMALE_NAME_HINTS:
            return "female"
        if token in MALE_NAME_HINTS:
            return "male"

    name_id = "_".join(tokens)
    if name_id.endswith(("ella", "elle", "etta", "ette", "ina", "lina", "ana", "anna", "ia", "lyn", "lynn", "beth")):
        return "female"
    if name_id.endswith(("bert", "ford", "fred", "rick", "mond", "nard", "son", "ton", "don", "den", "dan", "man", "us", "o")):
        return "male"

    title_id = clean_id(title)
    if title_id in FEMALE_TITLE_IDS or any(token in title_id for token in ("girl", "lady", "lass", "beauty", "maid", "madame")):
        return "female"
    if title_id in MALE_TITLE_IDS or any(token in title_id for token in ("boy", "maniac", "fisherman", "gentleman", "black_belt", "hiker", "sailor")):
        return "male"

    if name_id.endswith("a") and not name_id.endswith(("ia", "hua")):
        return "female"
    if name_id.endswith(("y", "ie")):
        return "female" if int(hashlib.sha1(seed.encode("utf-8")).hexdigest()[:2], 16) % 3 == 0 else "male"
    return "female" if int(hashlib.sha1(seed.encode("utf-8")).hexdigest()[:8], 16) % 2 else "male"


def is_token_match(clean: str, tokens: set[str]) -> bool:
    parts = set(part for part in clean.split("_") if part)
    for token in tokens:
        if clean == token or token in parts:
            return True
        if any(part in {f"{token}s", f"{token}m", f"{token}f", f"{token}_m", f"{token}_f"} for part in parts):
            return True
        if "_" in token and token in clean:
            return True
        if any(part == token or re.fullmatch(rf"{re.escape(token)}\d+", part) for part in parts):
            return True
    return False


def is_blocked(title: str, name: str) -> bool:
    clean_title = clean_id(title)
    clean_name = clean_id(name)
    if is_token_match(clean_title, BLOCKED_WILD_TOKENS):
        return True
    if clean_name in {"admin", "boss", "commander", "executive"}:
        return True
    if clean_title in {"rct_trainer", "trainer", "player", "protag"} and is_token_match(clean_name, BLOCKED_WILD_TOKENS):
        return True
    if is_token_match(clean_title, MULTI_TRAINER_TOKENS) or is_token_match(clean_name, MULTI_TRAINER_TOKENS):
        return True
    if re.search(r"\b(and|&)\b", name, re.IGNORECASE):
        return True
    return False


def category_for(title: str) -> str:
    clean = clean_id(title)
    if is_token_match(clean, UNIQUE_TOKENS):
        return "unique"
    if any(token in clean for token in ("rocket", "magma", "aqua", "galactic", "plasma", "flare", "skull", "yell", "star", "grunt")):
        return "team"
    if any(token in clean for token in ("frontier", "tower", "factory", "arcade", "castle")):
        return "battle_facility"
    if any(token in clean for token in ("bug", "bird", "fisher", "swimmer", "hiker", "black_belt", "psychic")):
        return "specialist"
    return "route_trainer"


def tier_for(title: str, min_level: int, max_level: int, value: str = "") -> str:
    clean = clean_id(value)
    if clean in {"low", "mid", "high", "very_high", "unique"}:
        return clean
    if is_token_match(clean_id(title), UNIQUE_TOKENS):
        return "unique"
    center = (min_level + max_level) // 2
    if center < 20:
        return "low"
    if center < 45:
        return "mid"
    if center < 70:
        return "high"
    return "very_high"


def stable_name(seed: str, gender: str) -> str:
    pool = sorted(FEMALE_NAMES if gender == "female" else MALE_NAMES if gender == "male" else FALLBACK_NAMES)
    digest = hashlib.sha1(seed.encode("utf-8")).hexdigest()
    return pool[int(digest[:8], 16) % len(pool)].title()


def strip_name_prefix(name: str, title: str, raw_title: str, gender: str) -> str:
    text = " ".join(str(name or "").replace("@", "").split())
    prefixes = {
        title,
        raw_title,
        f"{title} Male",
        f"{title} Female",
        f"{raw_title} Male",
        f"{raw_title} Female",
    }
    changed = True
    while changed:
        changed = False
        for prefix in sorted(prefixes, key=len, reverse=True):
            prefix = " ".join(prefix.split())
            if prefix and text.lower().startswith(prefix.lower()):
                text = text[len(prefix) :].strip(" _-")
                changed = True
        for word in ("male", "female"):
            if text.lower().startswith(word + " "):
                text = text[len(word) :].strip()
                changed = True
    if not text or re.fullmatch(r"\d+", text):
        return ""
    return text


def canonical_given_name(item: dict[str, Any], title: str, raw_title: str, gender: str, path: Path) -> str:
    name = strip_name_prefix(str(item.get("name") or ""), title, raw_title, gender)
    name_id = clean_id(name)
    if not name_id or name_id == clean_id(title):
        return stable_name(f"{path}:{item.get('id', '')}:{title}", gender)
    return title_words(name_id)


def normalize_species(value: Any) -> str:
    text = str(value or "").strip().lower().replace(" ", "_")
    if not text:
        return ""
    return text if ":" in text else f"cobblemon:{text}"


def normalize_moves(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for move in value:
        clean = clean_id(move).replace("_", "")
        if clean and clean not in result:
            result.append(clean)
    return result[:4]


def normalize_team(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    team: list[dict[str, Any]] = []
    for raw in value:
        if not isinstance(raw, dict):
            continue
        species = normalize_species(raw.get("species"))
        if not species:
            continue
        level = int(raw.get("level") or 1)
        mon = {
            "species": species,
            "level": max(1, min(100, level)),
            "gender": str(raw.get("gender") or "GENDERLESS").upper(),
            "nature": str(raw.get("nature") or "").strip(),
            "ability": str(raw.get("ability") or "").strip(),
            "moveset": normalize_moves(raw.get("moveset")),
            "heldItem": str(raw.get("heldItem") or "").strip(),
            "shiny": bool(raw.get("shiny", False)),
            "aspects": [str(v).strip() for v in raw.get("aspects", []) if str(v).strip()] if isinstance(raw.get("aspects", []), list) else [],
        }
        team.append(mon)
    return team


def body_defaults(title: str, gender: str) -> tuple[float, float, str]:
    clean = clean_id(title)
    height = 0.98 if gender == "female" else 1.02 if gender == "male" else 1.0
    weight = 0.94 if gender == "female" else 1.02 if gender == "male" else 1.0
    if any(token in clean for token in ("youngster", "bug_catcher", "school", "kid", "camper", "tuber")):
        height -= 0.10
        weight -= 0.08
    if any(token in clean for token in ("hiker", "black_belt", "sailor", "biker", "ranger")):
        height += 0.08
        weight += 0.14
    if any(token in clean for token in ("ace_trainer", "veteran", "dragon_tamer")):
        height += 0.04
        weight += 0.04
    return round(max(0.6, min(1.4, height)), 2), round(max(0.6, min(1.4, weight)), 2), ("standard" if gender == "female" else "")


def scale_value(value: Any, fallback: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return fallback
    return round(parsed if 0.6 <= parsed <= 1.4 else fallback, 2)


def read_trainer(path: Path) -> dict[str, Any] | None:
    try:
        if path.suffix.lower() == ".json":
            return json.loads(read_text(path))
        if path.suffix.lower() == ".toml":
            return tomllib.loads(read_text(path))
    except Exception as exc:
        print(f"skip unreadable {path}: {exc}")
    return None


def normalize_trainer(path: Path, item: dict[str, Any]) -> tuple[str, dict[str, Any]] | None:
    raw_title = str(item.get("title") or "")
    title, gender = canonical_title_gender(raw_title or str(item.get("archetype") or "Trainer"), str(item.get("gender") or "any"))
    gender = infer_gender_from_name(str(item.get("name") or ""), title, gender)
    given = canonical_given_name(item, title, raw_title, gender, path)
    gender = infer_gender_from_given(given, title, gender, f"{path}:{item.get('id', '')}:{given}")
    display_name = f"{title} {given}".strip()
    if is_blocked(title, display_name) or is_blocked(raw_title, str(item.get("name") or "")):
        return None
    team = normalize_team(item.get("team"))
    if not team:
        return None

    title_id = clean_id(title)
    code = gender_code(gender)
    name_id = clean_id(given) or stable_name(f"{path}:{title}", gender).lower()
    trainer_id = clean_id(f"{title_id}_{code}_{name_id}")
    min_level = min(mon["level"] for mon in team)
    max_level = max(mon["level"] for mon in team)
    height_default, weight_default, bust_default = body_defaults(title, gender)
    dialogue = [str(line).strip() for line in item.get("dialogue", []) if str(line).strip()] if isinstance(item.get("dialogue", []), list) else []
    if not dialogue:
        dialogue = [f"{display_name} is ready for a battle."]

    normalized = {
        "id": trainer_id,
        "name": display_name,
        "title": title,
        "gender": gender,
        "archetype": title_id,
        "region": str(item.get("region") or "").strip(),
        "category": category_for(title),
        "source": str(item.get("source") or "").strip() or path.parent.name,
        "skinSet": "",
        "skinFolder": f"{title_id}/{gender_folder(gender)}",
        "tier": tier_for(title, min_level, max_level, str(item.get("tier") or "")),
        "spawnable": not is_token_match(title_id, UNIQUE_TOKENS),
        "height": scale_value(item.get("height"), height_default),
        "weight": scale_value(item.get("weight"), weight_default),
        "bustStyle": str(item.get("bustStyle") or bust_default).strip() if gender == "female" else "",
        "minLevel": min_level,
        "maxLevel": max_level,
        "team": team,
        "dialogue": dialogue[:12],
        "_identityBase": f"{title_id}:{name_id}",
        "_genderCode": code,
    }
    return trainer_id, normalized


def trainer_score(item: dict[str, Any]) -> tuple[int, int, int, int]:
    move_count = sum(len(mon.get("moveset", [])) for mon in item.get("team", []))
    team_size = len(item.get("team", []))
    source = item.get("source", "")
    source_score = 3 if "pret_" in source else 2 if "rct" in source else 1
    return move_count, team_size, int(item.get("maxLevel", 1)), source_score


def identity_score(item: dict[str, Any]) -> tuple[int, int, int, int]:
    known_gender = 1 if item.get("gender") in {"male", "female"} else 0
    return known_gender, *trainer_score(item)


def merge_trainers(items: list[dict[str, Any]]) -> dict[str, Any]:
    best_team = max(items, key=trainer_score)
    best_identity = max(items, key=identity_score)
    merged = dict(best_team)
    for key in ("id", "name", "title", "gender", "archetype", "category", "skinFolder", "spawnable", "height", "weight", "bustStyle"):
        merged[key] = best_identity.get(key, merged.get(key))
    sources: list[str] = []
    regions: list[str] = []
    dialogue: list[str] = []
    for item in items:
        for source in str(item.get("source") or "").split(";"):
            source = source.strip()
            if source and source not in sources:
                sources.append(source)
        for region in str(item.get("region") or "").split(";"):
            region = region.strip()
            if region and region not in regions:
                regions.append(region)
        for line in item.get("dialogue", []):
            if line and line not in dialogue:
                dialogue.append(line)
    merged["source"] = ";".join(sources)
    merged["region"] = ";".join(regions)
    merged["dialogue"] = dialogue[:12] or merged.get("dialogue", [])
    merged["minLevel"] = min(mon["level"] for mon in merged["team"])
    merged["maxLevel"] = max(mon["level"] for mon in merged["team"])
    merged["tier"] = tier_for(merged["title"], merged["minLevel"], merged["maxLevel"], merged.get("tier", ""))
    return merged


def collapse_any_gender_duplicates(grouped: dict[str, list[dict[str, Any]]]) -> dict[str, list[dict[str, Any]]]:
    by_base: dict[str, list[str]] = defaultdict(list)
    for key, items in grouped.items():
        if not items:
            continue
        by_base[str(items[0].get("_identityBase", key))].append(key)
    for keys in by_base.values():
        known = [key for key in keys if grouped[key][0].get("_genderCode") in {"m", "f"}]
        any_keys = [key for key in keys if grouped[key][0].get("_genderCode") == "x"]
        if len(known) != 1:
            continue
        target = known[0]
        for any_key in any_keys:
            if any_key == target or any_key not in grouped:
                continue
            grouped[target].extend(grouped.pop(any_key))
    return grouped


def toml_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")


def toml_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return f"{value:.3f}".rstrip("0").rstrip(".")
    if isinstance(value, list):
        if not value:
            return "[]"
        if all(not isinstance(v, dict) for v in value):
            return "[" + ", ".join(toml_value(v) for v in value) + "]"
        return "[\n" + ",\n".join("  " + inline_table(v, POKEMON_KEYS) for v in value) + ",\n]"
    return f'"{toml_escape(str(value))}"'


def inline_table(obj: dict[str, Any], keys: tuple[str, ...]) -> str:
    parts = []
    for key in keys:
        if key in obj:
            parts.append(f"{key} = {toml_value(obj[key])}")
    return "{ " + ", ".join(parts) + " }"


def trainer_toml(item: dict[str, Any]) -> str:
    lines = ["# CKDM unified random trainer roster. Managed by tools/unify_random_trainers.py.", ""]
    for key in TRAINER_KEYS:
        if key in item:
            lines.append(f"{key} = {toml_value(item[key])}")
    return "\n".join(lines) + "\n"


def load_catalog(catalog: Path) -> tuple[list[dict[str, Any]], dict[str, int]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    stats = {"read": 0, "skipped": 0}
    for path in walk_files(catalog, {".toml", ".json"}):
        stats["read"] += 1
        item = read_trainer(path)
        normalized = normalize_trainer(path, item) if item else None
        if normalized is None:
            stats["skipped"] += 1
            continue
        key, trainer = normalized
        grouped[key].append(trainer)
    grouped = collapse_any_gender_duplicates(grouped)
    merged = [merge_trainers(items) for _, items in sorted(grouped.items())]
    stats["duplicate_groups"] = sum(1 for items in grouped.values() if len(items) > 1)
    stats["duplicate_entries"] = sum(len(items) - 1 for items in grouped.values() if len(items) > 1)
    stats["written"] = len(merged)
    return merged, stats


def rebuild_catalog(catalog: Path, trainers: list[dict[str, Any]], dry_run: bool) -> Path | None:
    if dry_run:
        return None
    catalog = catalog.resolve()
    parent = catalog.parent.resolve()
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    tmp = parent / f"catalog_unified_tmp_{timestamp}"
    backup = parent / f"catalog_backup_{timestamp}"
    assert_inside(tmp, parent)
    assert_inside(backup, parent)
    for stale_tmp in parent.glob("catalog_unified_tmp_*"):
        safe_rmtree(stale_tmp, parent)
    safe_rmtree(tmp, parent)
    os.makedirs(long_path(tmp), exist_ok=True)
    for item in trainers:
        title_id = clean_id(item["title"])
        code = gender_code(item["gender"])
        out = tmp / title_id / code / f"{item['id']}.toml"
        write_text(out, trainer_toml(item))
    if catalog.exists():
        shutil.move(long_path(catalog), long_path(backup))
    shutil.move(long_path(tmp), long_path(catalog))
    return backup


def canonical_skin_target(relative: Path) -> Path:
    parts = relative.parts
    top = parts[0] if parts else "trainer"
    folder_gender = parts[1] if len(parts) > 2 else "any"
    title, inferred_gender = canonical_title_gender(top, folder_gender)
    title_id = clean_id(title)
    if inferred_gender not in {"male", "female"}:
        inferred_gender = ANY_SKIN_GENDER_OVERRIDES.get(title_id) or ("female" if title_id in FEMALE_TITLE_IDS else "male")
    return Path(title_id) / gender_folder(inferred_gender) / relative.name


def unique_destination(path: Path) -> Path:
    if not path.exists():
        return path
    digest = hashlib.sha1(str(path).encode("utf-8")).hexdigest()[:8]
    candidate = path.with_name(f"{path.stem}_{digest}{path.suffix}")
    index = 2
    while candidate.exists():
        candidate = path.with_name(f"{path.stem}_{digest}_{index}{path.suffix}")
        index += 1
    return candidate


def unify_skins(root: Path, dry_run: bool) -> dict[str, int]:
    stats = {"png": 0, "moved": 0, "duplicates_removed": 0, "removed_empty_dirs": 0}
    if not root.exists():
        return stats
    root = root.resolve()
    pngs = [path for path in root.rglob("*.png") if path.is_file()]
    for src in pngs:
        stats["png"] += 1
        relative = src.relative_to(root)
        wanted = root / canonical_skin_target(relative)
        if src.resolve() == wanted.resolve():
            continue
        dest = unique_destination(wanted)
        stats["moved"] += 1
        if dry_run:
            continue
        os.makedirs(long_path(dest.parent), exist_ok=True)
        shutil.move(long_path(src), long_path(dest))
    duplicate_removals = duplicate_skin_paths(root)
    stats["duplicates_removed"] = len(duplicate_removals)
    if dry_run:
        return stats
    for duplicate in duplicate_removals:
        assert_inside(duplicate, root)
        duplicate.unlink()
    for path in sorted((p for p in root.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
        assert_inside(path, root)
        try:
            path.rmdir()
            stats["removed_empty_dirs"] += 1
        except OSError:
            pass
    return stats


def scaffold_skin_placeholders(root: Path, trainers: list[dict[str, Any]], dry_run: bool) -> dict[str, int]:
    stats = {"folders": 0, "created_dirs": 0, "placeholders_written": 0, "stale_placeholders_removed": 0}
    root = root.resolve()
    folders = sorted({str(item.get("skinFolder") or "").strip("/") for item in trainers if str(item.get("skinFolder") or "").strip("/")})
    desired = set(folders)
    for folder in folders:
        target = root / Path(folder)
        assert_inside(target, root)
        stats["folders"] += 1
        if not target.exists():
            stats["created_dirs"] += 1
            if not dry_run:
                os.makedirs(long_path(target), exist_ok=True)
        pngs = list(target.glob("*.png")) if target.exists() else []
        placeholder = target / SKIN_PLACEHOLDER
        if not pngs and not placeholder.exists():
            stats["placeholders_written"] += 1
            if not dry_run:
                write_text(placeholder, "Put random trainer skin PNG files for this title/gender here.\n")
    for placeholder in sorted(root.rglob(SKIN_PLACEHOLDER), key=lambda p: len(p.parts), reverse=True):
        folder = placeholder.parent
        relative = folder.relative_to(root).as_posix()
        if relative in desired:
            continue
        has_png = any(folder.glob("*.png"))
        has_other = any(path != placeholder for path in folder.iterdir())
        if has_png or has_other:
            continue
        stats["stale_placeholders_removed"] += 1
        if not dry_run:
            placeholder.unlink()
            current = folder
            while current != root:
                try:
                    current.rmdir()
                except OSError:
                    break
                current = current.parent
    return stats


def duplicate_skin_paths(root: Path) -> list[Path]:
    by_digest: dict[str, list[Path]] = defaultdict(list)
    for path in root.rglob("*.png"):
        digest = hashlib.sha1(path.read_bytes()).hexdigest()
        by_digest[digest].append(path)
    removals: list[Path] = []
    for paths in by_digest.values():
        if len(paths) < 2:
            continue
        keep = min(paths, key=skin_keep_score)
        removals.extend(path for path in paths if path != keep)
    return removals


def skin_keep_score(path: Path) -> tuple[int, int, int, str]:
    parts = path.parts
    gender_folder_name = parts[-2] if len(parts) >= 2 else "any"
    hashed_suffix = re.search(r"_[0-9a-f]{8}$", path.stem) is not None
    return (
        1 if gender_folder_name == "any" else 0,
        1 if hashed_suffix else 0,
        len(str(path)),
        str(path),
    )


def write_report(catalog: Path, catalog_stats: dict[str, int], skin_stats: dict[str, int], backup: Path | None, dry_run: bool) -> None:
    report = {
        "dryRun": dry_run,
        "catalog": catalog_stats,
        "skins": skin_stats,
        "backup": str(backup) if backup else "",
    }
    if not dry_run:
        write_text(catalog.parent / "unify_report.json", json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--input-catalog", type=Path, default=None)
    parser.add_argument("--skins-root", type=Path, default=DEFAULT_SKINS)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    trainers, catalog_stats = load_catalog(args.input_catalog or args.catalog)
    backup = rebuild_catalog(args.catalog, trainers, args.dry_run)
    skin_stats = unify_skins(args.skins_root, args.dry_run)
    skin_stats.update(scaffold_skin_placeholders(args.skins_root, trainers, args.dry_run))
    write_report(args.catalog, catalog_stats, skin_stats, backup, args.dry_run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Shared helpers for ScholarChain dataset scripts (Python 3 standard library only).

Owner: dataset-engineer. Used by validate_datasets.py, make_samples.py,
fetch_enrichment.py, dblp_subset_stats.py, estimate_subset_openalex.py and the
unit tests in test_datasets.py.

Identifier rules implemented here are documented in
datasets/schemas/identifiers-and-linkage.md; keep both in sync.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.parse
from typing import Any, Optional

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
DATASETS_DIR = os.path.dirname(SCRIPTS_DIR)
REPO_DIR = os.path.dirname(DATASETS_DIR)
MANIFEST_PATH = os.path.join(DATASETS_DIR, "dataset-manifest.yaml")

# --------------------------------------------------------------------------
# YAML loading (PyYAML if installed, otherwise the Ruby stdlib Psych parser).
# --------------------------------------------------------------------------


def load_yaml(path: str) -> Any:
    """Load a YAML file without adding a third-party dependency.

    Order: PyYAML (safe_load) if importable; otherwise `ruby` with the stdlib
    YAML (Psych) safe_load, converted to JSON. Fails loudly if neither exists.
    """
    try:
        import yaml  # type: ignore

        with open(path, "r", encoding="utf-8") as fh:
            return yaml.safe_load(fh)
    except ImportError:
        pass
    ruby = shutil.which("ruby")
    if ruby is None:
        raise RuntimeError(
            "No YAML parser available: install PyYAML or provide `ruby` (stdlib YAML)."
        )
    prog = (
        'require "yaml"; require "json"; require "date"; '
        "d = YAML.safe_load(File.read(ARGV[0], encoding: 'UTF-8'), "
        "permitted_classes: [Date], aliases: false); "
        "puts JSON.generate(d)"
    )
    out = subprocess.run(
        [ruby, "-e", prog, path], check=True, capture_output=True, text=True
    )
    return json.loads(out.stdout)


def load_manifest(path: str = MANIFEST_PATH) -> Any:
    return load_yaml(path)


# --------------------------------------------------------------------------
# Checksums
# --------------------------------------------------------------------------


def is_gzip(path: str) -> bool:
    """Detect gzip by magic bytes (bounded prefixes are named *.xml.gz.prefix-N)."""
    with open(path, "rb") as fh:
        return fh.read(2) == b"\x1f\x8b"


def file_digest(path: str, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm)
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------
# DOI
# --------------------------------------------------------------------------

# Accepted resolver/URI prefixes, matched case-insensitively.
_DOI_PREFIXES = (
    "https://doi.org/",
    "http://doi.org/",
    "https://dx.doi.org/",
    "http://dx.doi.org/",
    "doi:",
)
DOI_RE = re.compile(r"10\.[0-9]+(\.[0-9]+)*/\S+")  # used with fullmatch


def _ascii_lower(s: str) -> str:
    """Lowercase only U+0041..U+005A (DOI Handbook 2025 equivalence rule)."""
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def normalize_doi(raw: Optional[str]) -> Optional[str]:
    """Return the canonical DOI form or None if `raw` is not a DOI.

    Canonical form: bare DOI name (no resolver prefix), surrounding
    whitespace trimmed, percent-escapes decoded once when the input was a
    resolver URL, ASCII letters A-Z lowercased (DOI names are
    case-insensitive only for Basic Latin letters), must match DOI_RE.
    """
    if raw is None:
        return None
    s = raw.strip()
    low = s.lower()
    was_url = False
    for p in _DOI_PREFIXES:
        if low.startswith(p):
            s = s[len(p):]
            was_url = p != "doi:"
            break
    if was_url:
        s = urllib.parse.unquote(s)
    s = _ascii_lower(s.strip())
    if not DOI_RE.fullmatch(s):
        return None
    return s


def doi_from_dblp_ee(ee: str) -> Optional[str]:
    """DBLP <ee> elements carry a DOI only as a doi.org resolver URL."""
    low = ee.strip().lower()
    if not (low.startswith("https://doi.org/") or low.startswith("http://doi.org/")
            or low.startswith("https://dx.doi.org/") or low.startswith("http://dx.doi.org/")):
        return None
    return normalize_doi(ee)


# --------------------------------------------------------------------------
# ORCID iD
# --------------------------------------------------------------------------

# ASCII digits only ([0-9], never Unicode \d), matching the Scala contract
# (modules/core ExternalIds.scala Orcid.Pattern). Used with fullmatch.
ORCID_RE = re.compile(r"[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]")
_ORCID_PREFIXES = ("https://orcid.org/", "http://orcid.org/")


def orcid_check_digit(base15: str) -> str:
    """ISO 7064 11,2 check digit, as in ORCID-Source OrcidCheckDigitGenerator."""
    total = 0
    for ch in base15:
        if not ("0" <= ch <= "9"):
            raise ValueError("ORCID check digit input must be ASCII digits: %r" % base15)
        total = (total + (ord(ch) - 48)) * 2
    result = (12 - total % 11) % 11
    return "X" if result == 10 else str(result)


def normalize_orcid(raw: Optional[str]) -> Optional[str]:
    """Return the canonical bare ORCID iD (0000-0002-1825-0097) or None.

    Strips an orcid.org URI prefix, uppercases a trailing x, checks the
    16-character hyphenated shape and the ISO 7064 11,2 check digit.
    """
    if raw is None:
        return None
    s = raw.strip()
    low = s.lower()
    for p in _ORCID_PREFIXES:
        if low.startswith(p):
            s = s[len(p):]
            break
    s = s.upper()
    if not ORCID_RE.fullmatch(s):
        return None
    digits = s.replace("-", "")
    if orcid_check_digit(digits[:15]) != digits[15]:
        return None
    return s


# --------------------------------------------------------------------------
# OpenAlex IDs
# --------------------------------------------------------------------------

OPENALEX_ENTITY_PREFIX = {"work": "W", "author": "A", "source": "S", "institution": "I"}
_OPENALEX_URL = "https://openalex.org/"


def normalize_openalex_id(raw: Optional[str], entity: str) -> Optional[str]:
    """Return the canonical OpenAlex key (e.g. W4366851189) or None.

    OpenAlex IDs are case-insensitive; the canonical form is the uppercase
    entity letter followed by digits, without the https://openalex.org/ prefix.
    """
    if raw is None:
        return None
    s = raw.strip()
    if s.lower().startswith(_OPENALEX_URL):
        s = s[len(_OPENALEX_URL):]
    s = s.upper()
    prefix = OPENALEX_ENTITY_PREFIX[entity]
    if not re.fullmatch(r"%s[1-9][0-9]*" % prefix, s):
        return None
    return s


# --------------------------------------------------------------------------
# DBLP keys and names
# --------------------------------------------------------------------------

DBLP_KEY_RE = re.compile(r"[a-z]+(/[A-Za-z0-9_\-.'+]+)+")  # used with fullmatch
DBLP_HOMONYM_RE = re.compile(r"(?P<base>.*\S) (?P<number>[0-9]{4})")  # ASCII digits; fullmatch


def is_dblp_key(s: str) -> bool:
    return bool(DBLP_KEY_RE.fullmatch(s))


def dblp_stream_prefix(key: str) -> str:
    """First two path segments plus slash, e.g. conf/sigir/Sousa0J23 -> conf/sigir/."""
    parts = key.split("/")
    return "/".join(parts[:2]) + "/"


def split_dblp_name(name: str) -> tuple:
    """Split 'Ricardo Campos 0001' -> ('Ricardo Campos', '0001'); no suffix -> (name, None)."""
    m = DBLP_HOMONYM_RE.fullmatch(name)
    if m:
        return m.group("base"), m.group("number")
    return name, None


def canonical_pair(a: str, b: str) -> tuple:
    """Undirected pair canonicalisation used by the size estimate: lexicographic order."""
    return (a, b) if a <= b else (b, a)


# --------------------------------------------------------------------------
# DBLP DTD entity table (for samples carrying an internal DTD subset)
# --------------------------------------------------------------------------

_ENTITY_DECL_RE = re.compile(r'<!ENTITY\s+([A-Za-z][A-Za-z0-9]*)\s+"([^"]*)"\s*>')


def dtd_general_entities(dtd_text: str) -> dict:
    """Return {name: replacement-text} for general (non-parameter) entities."""
    return {m.group(1): m.group(2) for m in _ENTITY_DECL_RE.finditer(dtd_text)}


def http_user_agent() -> str:
    """User-Agent for polite API use. Contact info only from the environment."""
    ua = "scholarchain-datasets/1 (bounded research fetch)"
    mailto = os.environ.get("SCHOLARCHAIN_CONTACT_MAILTO", "").strip()
    if mailto:
        ua += " (mailto:%s)" % mailto
    return ua


def inside_repo(path: str) -> bool:
    """True if `path` (after resolving symlinks) is the repository root or below it."""
    real = os.path.realpath(os.path.abspath(path))
    root = os.path.realpath(REPO_DIR)
    return real == root or real.startswith(root + os.sep)

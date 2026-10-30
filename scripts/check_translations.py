#!/usr/bin/env python3
"""Check Android English/Ukrainian translation parity during CI builds."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ENGLISH = ROOT / "app/src/main/res/values/strings.xml"
UKRAINIAN = ROOT / "app/src/main/res/values-uk/strings.xml"


def string_keys(path: Path) -> set[str]:
    resource = ET.parse(path).getroot()
    keys = [entry.attrib["name"] for entry in resource.findall("string")]
    duplicates = sorted(name for name, count in Counter(keys).items() if count > 1)
    if duplicates:
        raise ValueError(f"{path}: duplicate string resources: {duplicates}")
    return set(keys)


def main() -> int:
    english = string_keys(ENGLISH)
    ukrainian = string_keys(UKRAINIAN)
    missing_uk = sorted(english - ukrainian)
    missing_en = sorted(ukrainian - english)
    if missing_uk or missing_en:
        if missing_uk:
            print(f"Missing Ukrainian translations: {missing_uk}", file=sys.stderr)
        if missing_en:
            print(f"Missing English translations: {missing_en}", file=sys.stderr)
        return 1
    print(f"Translation check passed: {len(english)} strings in both English and Ukrainian")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, ET.ParseError) as error:
        print(f"Invalid Android translations: {error}", file=sys.stderr)
        raise SystemExit(1) from error

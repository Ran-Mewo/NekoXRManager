#!/usr/bin/env python3
"""sigtest.py — validate a NativePatchSet against an ELF .so.

Convention (v1): one JSON file = one NativePatchSet = one ABI.
  app/src/main/assets/arcore/patches/generic_arm64.json   -> lib/arm64-v8a/libarcore_c.so
  app/src/main/assets/arcore/patches/generic_arm32.json   -> lib/armeabi-v7a/libarcore_c.so
The patch set's "so" field is informational (matches the lib path inside the APK);
the caller passes the actual .so on disk.

JSON schema:
    {
      "id": "generic-v1",
      "comment": "...",
      "so":  "lib/arm64-v8a/libarcore_c.so",
      "patches": [
        {
          "symbol":   "Java_...containsMatchingProfile",
          "anchor":   { "kind": "symbol", "name": "Java_..." },     // optional, defaults to {kind:symbol,name:patches.symbol}
          "patterns": [
            { "sig": "FF 43 01 D1 ?? ?? ?? ?? E0 03 1F 2A",
              "replacement": "20 00 80 52 C0 03 5F D6 1F 20 03 D5" }
          ]
        }
      ]
    }

Anchor kinds:
    {"kind":"symbol", "name":"Java_..."}        — restrict scan to the symbol's body
    {"kind":"string", "value":"Unsupported device"} — find the rodata string, then
                                                     restrict scan to functions that
                                                     xref it (uses lief's xref data;
                                                     if unavailable, falls back to
                                                     scanning the entire .text section)
    {"kind":"section","name":".text"}           — scan the whole named section

Exit code:
    0 — every pattern in every patch matched exactly once
    1 — at least one pattern matched 0 times or >1 times
    2 — input/JSON/ELF error

Usage:
    sigtest.py <path/to/lib.so> <path/to/patchset.json>
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

try:
    import lief
except ImportError:
    sys.exit("lief is required. Install with: tools/.venv/bin/pip install lief")

WILDCARD = -1  # sentinel for ?? bytes


@dataclass
class CompiledSig:
    pattern: list[int]   # values in 0..255, or WILDCARD
    raw: str             # original sig text for error messages


def compile_sig(sig: str) -> CompiledSig:
    out: list[int] = []
    tokens = sig.split()
    if not tokens:
        raise ValueError(f"empty sig: {sig!r}")
    for tok in tokens:
        if tok == "??":
            out.append(WILDCARD)
        elif re.fullmatch(r"[0-9A-Fa-f]{2}", tok):
            out.append(int(tok, 16))
        else:
            raise ValueError(f"bad token {tok!r} in sig {sig!r}")
    return CompiledSig(pattern=out, raw=sig)


def scan(haystack: bytes, base: int, csig: CompiledSig) -> list[int]:
    """Return absolute file offsets of every match of csig within haystack[base:base+len(haystack)]."""
    pat = csig.pattern
    plen = len(pat)
    matches: list[int] = []
    end = len(haystack) - plen
    for i in range(end + 1):
        ok = True
        for j, p in enumerate(pat):
            if p != WILDCARD and haystack[i + j] != p:
                ok = False
                break
        if ok:
            matches.append(base + i)
    return matches


def resolve_anchor_range(binary: lief.Binary, raw: bytes, anchor: dict, default_symbol: str) -> tuple[int, int] | None:
    """Return (file_offset, length) of the anchored window, or None if not resolvable."""
    kind = anchor.get("kind", "symbol")
    if kind == "symbol":
        name = anchor.get("name", default_symbol)
        sym = next((s for s in binary.symbols if s.name == name), None) \
            or next((s for s in binary.dynamic_symbols if s.name == name), None)
        if sym is None or sym.size == 0:
            return None
        # symbol.value is a virtual address; convert to file offset.
        try:
            offset = binary.virtual_address_to_offset(sym.value)
        except Exception:
            return None
        return offset, sym.size
    if kind == "section":
        name = anchor["name"]
        sec = binary.get_section(name)
        if sec is None:
            return None
        return sec.file_offset, sec.size
    if kind == "string":
        target = anchor["value"].encode("utf-8") + b"\x00"
        rodata = binary.get_section(".rodata")
        if rodata is None:
            return None
        ro_bytes = bytes(rodata.content)
        idx = ro_bytes.find(target)
        if idx < 0:
            return None
        # We don't have free xref-walking from a pure-lief script; scope to .text instead
        # and rely on the pattern itself being unique enough. Document in §4 that string
        # anchors fall back to .text-wide scan when the toolchain can't walk xrefs.
        text = binary.get_section(".text")
        if text is None:
            return None
        return text.file_offset, text.size
    raise ValueError(f"unknown anchor kind: {kind!r}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("so", type=Path, help="path to libarcore_c.so")
    ap.add_argument("patchset", type=Path, help="path to NativePatchSet JSON")
    ap.add_argument("--quiet", action="store_true", help="suppress per-pattern lines on success")
    args = ap.parse_args()

    if not args.so.is_file():
        print(f"error: {args.so} not found", file=sys.stderr)
        return 2
    if not args.patchset.is_file():
        print(f"error: {args.patchset} not found", file=sys.stderr)
        return 2

    try:
        ps = json.loads(args.patchset.read_text())
    except json.JSONDecodeError as e:
        print(f"error: {args.patchset} JSON: {e}", file=sys.stderr)
        return 2

    binary = lief.parse(str(args.so))
    if binary is None:
        print(f"error: lief could not parse {args.so}", file=sys.stderr)
        return 2

    raw = args.so.read_bytes()
    set_id = ps.get("id", "<no-id>")
    print(f"[{set_id}] {args.so}  ({len(ps.get('patches', []))} patches)")

    fail = 0
    for patch in ps.get("patches", []):
        symbol = patch["symbol"]
        anchor = patch.get("anchor", {"kind": "symbol", "name": symbol})
        rng = resolve_anchor_range(binary, raw, anchor, symbol)
        if rng is None:
            print(f"  ✗ {symbol}: anchor unresolved ({anchor})")
            fail += 1
            continue
        offset, length = rng
        haystack = raw[offset:offset + length]
        for pat in patch["patterns"]:
            try:
                csig = compile_sig(pat["sig"])
            except ValueError as e:
                print(f"  ✗ {symbol}: {e}")
                fail += 1
                continue
            matches = scan(haystack, offset, csig)
            count = len(matches)
            if count == 1:
                if not args.quiet:
                    print(f"  ✓ {symbol}: 1 match @ 0x{matches[0]:08x}  ({pat['sig'][:40]}{'...' if len(pat['sig'])>40 else ''})")
            else:
                fail += 1
                hits = ", ".join(f"0x{m:08x}" for m in matches[:5])
                if count > 5:
                    hits += ", ..."
                print(f"  ✗ {symbol}: {count} matches  [{hits}]  sig={pat['sig'][:60]}")

    if fail == 0:
        print(f"[{set_id}] OK")
        return 0
    print(f"[{set_id}] FAIL: {fail} pattern(s) did not produce exactly 1 match")
    return 1


if __name__ == "__main__":
    sys.exit(main())

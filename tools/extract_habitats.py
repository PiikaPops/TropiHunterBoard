#!/usr/bin/env python3
"""
Extracts the Tropimon server's habitat pools from the client modpack into one file,
`habitats.json`, that the relay (raid_relay.py, GET /habitats) hands to every HunterBoard.

Cobblemon never sends habitat pools to the client, so a client only knows the ones of its
own Cobblemon jar, and Tropimon replaces all of them. The server does not keep its pools
private either: they ship in the modpack, inside the `tropifurnitures` mod, nested in one
of the hash-named jars of the instance's mods folder. That jar is found by what it
contains, never by its name: the launcher renames the jars on every update.

    python3 extract_habitats.py                          # Tropimon's default instance
    python3 extract_habitats.py --mods <instance>/mods   # another instance
    python3 extract_habitats.py -o /path/to/habitats.json

Then copy habitats.json next to raid_relay.py on the server: the relay picks the new file
up by itself, and every HunterBoard downloads it the next time it starts or joins.

File format (version 1):
    {
      "format": 1,
      "version": "<16 hex: SHA-256 of the pools>",   # changes only when the data does
      "generated": "2026-11-22T02:30:00Z",
      "source": {"tropifurnitures": "1.0.0", "cobblemon": "1.8.4+1.21.1"},
      "pools": {
        "berry_patch": {"replace": true, "data": { ...the pool file, unchanged... }},
        ...
      }
    }
`replace` is true when Cobblemon ships a pool of the same name: the server's file then
replaces it whole, and HunterBoard drops Cobblemon's spawns of that habitat first.
Standard library only.
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import sys
import zipfile

HAB = "data/cobblemon/habitat_pools/"


def default_mods():
    appdata = os.environ.get("APPDATA") or os.path.expanduser("~/.config")
    return os.path.join(appdata, ".Tropimon", "profiles", "stable", "instance", "mods")


def mod_info(z):
    try:
        meta = json.loads(z.read("fabric.mod.json"))
        return meta.get("id"), meta.get("version")
    except Exception:
        return None, None


def find_sources(mods):
    """(cobblemon zip, its version), (zip holding the pools, its version)"""
    cobblemon = pools = None
    for name in sorted(os.listdir(mods)):
        if not name.endswith(".jar"):
            continue
        try:
            z = zipfile.ZipFile(os.path.join(mods, name))
        except Exception:
            continue
        mid, ver = mod_info(z)
        if mid == "cobblemon":
            cobblemon = (z, ver)
        for inner in z.namelist():
            if inner.startswith("META-INF/jars/") and inner.endswith(".jar") and "tropifurnitures" in inner.lower():
                nested = zipfile.ZipFile(io.BytesIO(z.read(inner)))
                if any(n.startswith(HAB) for n in nested.namelist()):
                    pools = (nested, mod_info(nested)[1])
    return cobblemon, pools


def read_pools(z):
    out = {}
    for n in z.namelist():
        if n.startswith(HAB) and n.endswith(".json"):
            name = n[len(HAB):].split("/")[-1][:-len(".json")]
            if name in out:
                raise SystemExit(f"two habitat pools are both named {name}")
            out[name] = json.loads(z.read(n).decode("utf-8"))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--mods", default=default_mods(), help="the Tropimon instance's mods folder")
    ap.add_argument("-o", "--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "habitats.json"))
    args = ap.parse_args()

    if not os.path.isdir(args.mods):
        raise SystemExit(f"no mods folder at {args.mods} (use --mods)")
    cobblemon, source = find_sources(args.mods)
    if source is None:
        raise SystemExit("no jar holding Tropimon's habitat pools (tropifurnitures) in " + args.mods)
    pools = read_pools(source[0])
    if not pools:
        raise SystemExit("the tropifurnitures jar has no habitat pool")
    vanilla = set()
    if cobblemon is not None:
        vanilla = {n[len(HAB):].split("/")[-1][:-len(".json")] for n in cobblemon[0].namelist()
                   if n.startswith(HAB) and n.endswith(".json")}

    canonical = json.dumps(pools, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    bundle = {
        "format": 1,
        "version": hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16],
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": {"tropifurnitures": source[1], "cobblemon": cobblemon[1] if cobblemon else None},
        "pools": {name: {"replace": name in vanilla, "data": pools[name]} for name in sorted(pools)},
    }
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(bundle, fh, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, args.out)
    replaced = sum(1 for p in bundle["pools"].values() if p["replace"])
    print(f"{len(pools)} habitat pools ({replaced} replace Cobblemon's, {len(pools) - replaced} new), "
          f"version {bundle['version']} -> {args.out} ({os.path.getsize(args.out) // 1024} KB)")


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    main()

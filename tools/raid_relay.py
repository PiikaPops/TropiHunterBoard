#!/usr/bin/env python3
"""
HunterBoard raid relay — tells every HunterBoard client which Pokémon the raid boss is,
and when the raid really starts.

Tropimon announces a raid without naming its boss. A player standing near the arena sees
it; their mod reports it here, and the mods of everyone else ask here and show it on the
raid card. The start works the same way: the server opens the raid a little more than five
minutes after the announcement, by an amount only the arena's countdown shows, so the first
player in the arena reports how long after the announcement it starts. Nothing but the raid
is exchanged: no player name, no position.

    python3 raid_relay.py            # listens on 127.0.0.1:8787
    PORT=9000 python3 raid_relay.py

Standard library only. Put it behind the reverse proxy that already serves the domain
(it trusts X-Forwarded-For, so do not expose the port directly), e.g. with nginx:

    location / { proxy_pass http://127.0.0.1:8787; proxy_set_header X-Forwarded-For $remote_addr; }

API
    POST /raid   {"slot": 5876543, "v": 2, "species": "gallade", "aspects": ["mega"]}
    POST /raid   {"slot": 5876543, "delay_ms": 318000}
    GET  /raid?slot=5876543  ->  {"species": "gallade", "aspects": ["mega"], "reports": 3,
                                  "delay_ms": 318000}
                                 each part only once someone has reported it; {} before

`delay_ms` is the time from the announcement reaching the reporter to the raid's start, on
the reporter's own clock: a clock that is off cancels out, every client adds the delay to
the moment the announcement reached it. What is answered is the median of the reports.

`slot` is the announcement time in 5-minute steps (epoch seconds / 300, rounded), which
every client computes the same way from the moment the raid notification arrived.

Everything lives in memory and is forgotten after 30 minutes: a restart loses at most the
raid in progress. One address counts once per raid, and what most addresses agree on wins,
so a single joker cannot rename the boss once two honest players have reported it.

`v` is the rule the reporting mod recognised the boss by. Reports without it, or below
MIN_DETECTION, are accepted and ignored: older HunterBoard versions took any wild Pokémon
standing in the arena for the boss, and would keep spreading one that walked by.

    GET  /habitats   ->  habitats.json, the Tropimon server's habitat pools

The relay also hands out the habitat data HunterBoard no longer ships (Cobblemon never sends
habitat pools to the client, and Tropimon replaces them all). The file is made by
extract_habitats.py from the client modpack and sits next to this script (or wherever the
HABITATS environment variable points). Replacing it is enough: the relay notices the new
file on the next request. Each answer carries the data's version as its ETag; a client that
already has it sends If-None-Match and gets an empty 304. Sent gzip-compressed to clients
that accept it (a 140 KB file is about 15 KB that way).
"""
import gzip
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

KEEP_SECONDS = 30 * 60
MAX_BODY = 512
NAME = re.compile(r"^[a-z0-9_\-]{1,40}$")
DELAY_MIN_MS, DELAY_MAX_MS = 4 * 60_000, 7 * 60_000   # five minutes and a bit, never far off
RATE_WINDOW, RATE_MAX = 60, 30          # requests per address per minute
MIN_DETECTION = 2                       # boss reports from older rules are not counted

HABITATS_FILE = os.environ.get("HABITATS") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "habitats.json")

lock = threading.Lock()
habitats = {"mtime": None, "version": None, "body": None, "gzip": None}


def habitat_data():
    """The habitat file as (version, raw, gzipped), re-read when it changes; None without one.
    A broken replacement is ignored: the last good file keeps being served."""
    try:
        mtime = os.stat(HABITATS_FILE).st_mtime
    except OSError:
        return None if habitats["body"] is None else (habitats["version"], habitats["body"], habitats["gzip"])
    if mtime != habitats["mtime"]:
        habitats["mtime"] = mtime
        try:
            with open(HABITATS_FILE, "rb") as fh:
                body = fh.read()
            data = json.loads(body)
            if data.get("format") != 1 or not isinstance(data.get("pools"), dict) or not data["pools"]:
                raise ValueError("not a habitat file")
            habitats.update(version=str(data["version"]), body=body, gzip=gzip.compress(body, 9))
            print(f"habitat data {habitats['version']}: {len(data['pools'])} pools")
        except (OSError, ValueError) as e:
            print(f"habitat file ignored ({e}); still serving {habitats['version']}")
    if habitats["body"] is None:
        return None
    return habitats["version"], habitats["body"], habitats["gzip"]
raids = {}      # slot -> {"at": time, "votes": {address: (species, aspects)}, "delays": {address: ms}}
hits = {}       # address -> [timestamps]


def allowed(addr, now):
    recent = [t for t in hits.get(addr, []) if now - t < RATE_WINDOW]
    recent.append(now)
    hits[addr] = recent
    return len(recent) <= RATE_MAX


def sweep(now):
    for slot in [s for s, r in raids.items() if now - r["at"] > KEEP_SECONDS]:
        del raids[slot]
    for addr in [a for a, ts in hits.items() if not ts or now - ts[-1] > RATE_WINDOW]:
        del hits[addr]


def plausible(slot, now):
    """A raid announced within the last half hour, give or take a clock that is off."""
    return abs(slot * 300 - now) < KEEP_SECONDS + 600


class Handler(BaseHTTPRequestHandler):
    server_version = "HunterBoardRelay/1"

    def address(self):
        return (self.headers.get("X-Forwarded-For") or self.client_address[0]).split(",")[0].strip()

    def reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def habitats(self, now):
        with lock:
            if not allowed(self.address(), now):
                return self.reply(429, {"error": "slow down"})
            data = habitat_data()
        if data is None:
            return self.reply(404, {"error": "no habitat data"})
        version, body, packed = data
        etag = f'"{version}"'
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.end_headers()
            return
        gz = "gzip" in (self.headers.get("Accept-Encoding") or "")
        payload = packed if gz else body
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        if gz:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        url = urlparse(self.path)
        now = time.time()
        if url.path == "/habitats":
            return self.habitats(now)
        if url.path != "/raid":
            return self.reply(404, {"error": "not found"})
        try:
            slot = int(parse_qs(url.query).get("slot", [""])[0])
        except ValueError:
            return self.reply(400, {"error": "slot"})
        with lock:
            if not allowed(self.address(), now):
                return self.reply(429, {"error": "slow down"})
            sweep(now)
            raid = raids.get(slot)
            answer = {}
            if raid and raid["votes"]:
                tally = {}
                for vote in raid["votes"].values():
                    tally[vote] = tally.get(vote, 0) + 1
                (species, aspects), count = max(tally.items(), key=lambda kv: kv[1])
                answer.update(species=species, aspects=list(aspects), reports=count)
            if raid and raid["delays"]:
                delays = sorted(raid["delays"].values())
                answer["delay_ms"] = delays[len(delays) // 2]
        self.reply(200, answer)

    def do_POST(self):
        now = time.time()
        if urlparse(self.path).path != "/raid":
            return self.reply(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BODY:
                raise ValueError
            data = json.loads(self.rfile.read(length))
            slot = int(data["slot"])
            if not plausible(slot, now):
                raise ValueError
            species = aspects = delay = None
            counted = True
            if "species" in data:
                species = str(data["species"]).lower()
                aspects = tuple(sorted(str(a).lower() for a in data.get("aspects", [])))[:8]
                if not NAME.match(species) or not all(NAME.match(a) for a in aspects):
                    raise ValueError
                # well-formed but from an older rule: answered "ok", never counted
                counted = int(data.get("v", 1)) >= MIN_DETECTION
            if "delay_ms" in data:
                delay = int(data["delay_ms"])
                if not DELAY_MIN_MS <= delay <= DELAY_MAX_MS:
                    raise ValueError
            if species is None and delay is None:
                raise ValueError
        except (ValueError, KeyError, TypeError, json.JSONDecodeError):
            return self.reply(400, {"error": "bad report"})
        addr = self.address()
        with lock:
            if not allowed(addr, now):
                return self.reply(429, {"error": "slow down"})
            sweep(now)
            raid = raids.setdefault(slot, {"at": now, "votes": {}, "delays": {}})
            if species is not None and counted:
                raid["votes"][addr] = (species, aspects)
            if delay is not None:
                raid["delays"][addr] = delay
        self.reply(200, {"ok": True})

    def log_message(self, fmt, *args):   # one line per request is plenty for a Pi's SD card
        if os.environ.get("VERBOSE"):
            super().log_message(fmt, *args)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8787"))
    print(f"HunterBoard raid relay on 127.0.0.1:{port}")
    if habitat_data() is None:
        print(f"no habitat data yet: put habitats.json at {HABITATS_FILE}")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()

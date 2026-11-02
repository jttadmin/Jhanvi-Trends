"""Find and prepare free-license product photos for the seeded demo catalog.

Runs in GitHub Actions (see .github/workflows/product-photos.yml), not in the cluster.

  candidates  For every product in queries.json that has no entry in picks.json, search
              Unsplash (free Unsplash License; Unsplash+ / premium photos are skipped) and
              Openverse (CC0 / public domain only), and write numbered contact sheets to
              candidates/ for a human to review.
  finalize    Download every photo in picks.json, crop it to a 900x900 square and write it to
              services/catalog-service/app/photos/<id>.jpg, plus CREDITS.md.

picks.json:  {"1": {"pick": 3 (sheet index) or "u:photo-...", "y": 0.5, "x": 0.5, "zoom": 1.0,
                    "blur": [[x0, y0, x1, y1]]}, ...}
             pick is a candidate key from candidates/candidates.json. x/y (0..1) move the
             square crop's centre; zoom > 1 crops tighter.
"""
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "services" / "catalog-service" / "app" / "photos"
CAND = HERE / "candidates"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36", "Accept": "application/json"}
SIZE = 900


def get(url: str, accept_json: bool = True, tries: int = 2, timeout: int = 20) -> bytes:
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA if accept_json else {"User-Agent": UA["User-Agent"]})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as exc:  # noqa: BLE001
            print(f"  ! {url[:90]} -> {exc}", flush=True)
            time.sleep(1)
    raise RuntimeError(f"failed: {url}")


def unsplash(query: str, n: int) -> list[dict]:
    """Free photos from Unsplash's public search page. Unsplash+ (paid) photos are served from
    plus.unsplash.com/premium_photo-..., so matching only images.unsplash.com/photo-... skips them."""
    url = "https://unsplash.com/s/photos/" + urllib.parse.quote(query.replace(" ", "-"))
    try:
        html = get(url, accept_json=False).decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return []
    out, seen = [], set()
    for pid in re.findall(r"https://images\.unsplash\.com/(photo-\d+-[0-9a-f]+)", html):
        if pid in seen:
            continue
        seen.add(pid)
        base = f"https://images.unsplash.com/{pid}"
        out.append({"key": f"u:{pid}", "thumb": base + "?w=400&fm=jpg&q=70", "raw": base,
                    "page": base, "author": "", "license": "Unsplash License", "alt": ""})
        if len(out) >= n:
            break
    return out


def openverse(query: str, n: int) -> list[dict]:
    url = "https://api.openverse.org/v1/images/?" + urllib.parse.urlencode(
        {"q": query, "license": "cc0,pdm", "page_size": n, "mature": "false"})
    try:
        data = json.loads(get(url, timeout=45))
    except Exception:  # noqa: BLE001
        return []
    return [{
        "key": f"o:{r['id']}", "thumb": r.get("thumbnail") or r["url"], "raw": r["url"],
        "page": r.get("foreign_landing_url") or r["url"], "author": r.get("creator") or "",
        "license": (r.get("license") or "").upper(), "alt": r.get("title") or "",
    } for r in data.get("results", [])[:n]]


def square(img: Image.Image, x: float = 0.5, y: float = 0.5, zoom: float = 1.0, size: int = SIZE) -> Image.Image:
    img = img.convert("RGB")
    w, h = img.size
    side = int(min(w, h) / max(zoom, 1.0))
    cx, cy = int(w * x), int(h * y)
    left = min(max(cx - side // 2, 0), w - side)
    top = min(max(cy - side // 2, 0), h - side)
    return img.crop((left, top, left + side, top + side)).resize((size, size), Image.LANCZOS)


SOURCES = {"unsplash": True, "openverse": True}


def probe() -> None:
    for name, fn in (("unsplash", unsplash), ("openverse", openverse)):
        t = time.time()
        ok = bool(fn("coffee mug", 1))
        SOURCES[name] = ok
        print(f"probe {name}: {'ok' if ok else 'UNAVAILABLE'} in {time.time() - t:.1f}s", flush=True)


def from_seeds(seeds: list[dict]) -> list[dict]:
    """Candidates chosen by hand (seeds.json): free Unsplash photos served from the image CDN."""
    out = []
    for sd in seeds:
        base = f"https://images.unsplash.com/{sd['id']}"
        out.append({"key": f"u:{sd['id']}", "thumb": base + "?w=400&fm=jpg&q=70", "raw": base,
                    "page": base, "author": "", "license": "Unsplash License", "alt": sd.get("alt", "")})
    return out


def hide_logo(img: Image.Image, box: list[int]) -> None:
    """Paint a small logo out with the surface around it (sides and bottom, which the logo sits on),
    add matching grain so the patch keeps the material's texture, then soften the edges."""
    import random
    x0, y0, x1, y1 = box[:4]
    vertical = len(box) > 4 and box[4] == "v"
    ring = [img.getpixel((x, y1 + 2)) for x in range(x0 - 3, x1 + 3)] + \
           [img.getpixel((x, y)) for y in range(y0, y1 + 3) for x in (x0 - 3, x1 + 2)]
    ring.sort(key=sum)
    base = ring[len(ring) // 2]
    lum = [sum(c) / 3 for c in ring[len(ring) // 4: 3 * len(ring) // 4]]
    grain = (sum((v - sum(lum) / len(lum)) ** 2 for v in lum) / len(lum)) ** 0.5
    rnd = random.Random(f"{box}")

    def avg(x: int, ys: range) -> tuple:
        px = [img.getpixel((x, y)) for y in ys]
        return tuple(sum(c[i] for c in px) / len(px) for i in range(3))

    for x in range(x0, x1):
        if vertical:  # follow the surface's shading: blend the rows just above and below, per column
            top, bottom = avg(x, range(y0 - 5, y0 - 2)), avg(x, range(y1 + 2, y1 + 5))
        for y in range(y0, y1):
            if vertical:
                t = (y - y0) / max(1, y1 - y0 - 1)
                c0 = tuple(a + (b - a) * t for a, b in zip(top, bottom))
            else:
                c0 = base
            n = rnd.gauss(0, grain)
            img.putpixel((x, y), tuple(max(0, min(255, int(c + n))) for c in c0))
    pad = (x0 - 4, y0 - 4, x1 + 4, y1 + 4)
    img.paste(img.crop(pad).filter(ImageFilter.GaussianBlur(1.5)), pad[:2])


def candidates() -> None:
    seeds = json.loads((HERE / "seeds.json").read_text()) if (HERE / "seeds.json").exists() else {}
    if not seeds:
        probe()
    queries = json.loads((HERE / "queries.json").read_text())
    picks = json.loads((HERE / "picks.json").read_text()) if (HERE / "picks.json").exists() else {}
    todo = [pid for pid in queries if pid not in picks]
    CAND.mkdir(exist_ok=True)
    for old in CAND.glob("sheet-*.jpg"):
        old.unlink()
    allc: dict[str, list[dict]] = {}
    for pid in todo:
        if pid in seeds:
            allc[pid] = from_seeds(seeds[pid])[:12]
            print(f"product {pid}: {len(allc[pid])} seeded candidates", flush=True)
            continue
        seen, found = set(), []
        for q in queries[pid]:
            for c in (unsplash(q, 6) if SOURCES["unsplash"] else []) + (openverse(q, 2) if SOURCES["openverse"] else []):
                if c["key"] not in seen:
                    seen.add(c["key"])
                    found.append(c)
        allc[pid] = found[:12]
        print(f"product {pid}: {len(allc[pid])} candidates", flush=True)
    # keep earlier candidates so picks made from older contact sheets still resolve
    prev = json.loads((CAND / "candidates.json").read_text()) if (CAND / "candidates.json").exists() else {}
    prev.update(allc)
    (CAND / "candidates.json").write_text(json.dumps(prev, indent=1))

    font = ImageFont.load_default(size=22)
    big = ImageFont.load_default(size=30)
    t, cols = 240, 6
    per_sheet = 3
    for s in range(0, len(todo), per_sheet):
        group = todo[s:s + per_sheet]
        rows_px = sum(50 + ((len(allc[p]) + cols - 1) // cols or 1) * (t + 30) for p in group)
        sheet = Image.new("RGB", (cols * (t + 10) + 10, rows_px + 10), "white")
        d = ImageDraw.Draw(sheet)
        yy = 10
        for pid in group:
            d.text((10, yy), f"Product {pid}  ({', '.join(queries[pid])})", fill="black", font=big)
            yy += 50
            for i, c in enumerate(allc[pid]):
                xx = 10 + (i % cols) * (t + 10)
                y0 = yy + (i // cols) * (t + 30)
                try:
                    im = square(Image.open(io.BytesIO(get(c["thumb"], accept_json=False))), size=t)
                    sheet.paste(im, (xx, y0))
                except Exception:  # noqa: BLE001
                    d.rectangle((xx, y0, xx + t, y0 + t), outline="red")
                d.text((xx, y0 + t + 2), f"#{i} {c['key'][:16]}", fill="#b42318" if c["key"][0] == "o" else "black", font=font)
            yy += ((len(allc[pid]) + cols - 1) // cols or 1) * (t + 30)
        sheet.save(CAND / f"sheet-{s // per_sheet + 1:02d}.jpg", quality=80)


def finalize() -> None:
    picks = json.loads((HERE / "picks.json").read_text())
    known: dict[str, dict] = {}
    cands = json.loads((CAND / "candidates.json").read_text()) if (CAND / "candidates.json").exists() else {}
    for f in [CAND / "candidates.json", HERE / "picked.json"]:
        if f.exists():
            for lst in json.loads(f.read_text()).values():
                for c in (lst if isinstance(lst, list) else [lst]):
                    known[c["key"]] = c
    for pid, p in picks.items():
        if isinstance(p.get("pick"), int):  # index shown on the contact sheet
            p["pick"] = cands[pid][p["pick"]]["key"]
    OUT.mkdir(parents=True, exist_ok=True)
    picked: dict[str, dict] = {}
    for pid, p in sorted(picks.items(), key=lambda kv: int(kv[0])):
        c = known.get(p["pick"])
        if not c:
            sys.exit(f"product {pid}: {p['pick']} is not in candidates.json")
        src = c["raw"] + "?w=1800&fm=jpg&q=85" if c["key"][0] == "u" else c["raw"]
        img = square(Image.open(io.BytesIO(get(src, accept_json=False))), p.get("x", .5), p.get("y", .5), p.get("zoom", 1.0))
        for box in p.get("blur", []):  # hide small third-party logos: [x0, y0, x1, y1(, "v")] on the 900px square
            hide_logo(img, box)
        img.save(OUT / f"{pid}.jpg", quality=82, optimize=True, progressive=True)
        picked[pid] = c
        print(f"product {pid}: {c['key']} -> {(OUT / f'{pid}.jpg').stat().st_size // 1024} KB", flush=True)
    (HERE / "picked.json").write_text(json.dumps(picked, indent=1))
    lines = ["# Product photo credits", "",
             "Demo product photos for the seeded catalog. Photos under the Unsplash License may be used",
             "free of charge, commercially or not, without attribution; credit is given here anyway.",
             "CC0 / PDM photos are public domain. Brands and product names on the site are fictional;",
             "the photos only illustrate the product type.", "",
             "| Product | Photo | Author | License |", "|---|---|---|---|"]
    for pid, c in picked.items():
        lines.append(f"| {pid} | [{c['key'][2:]}]({c['page']}) | {c['author'] or 'Unsplash contributor'} | {c['license']} |")
    (OUT / "CREDITS.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    {"candidates": candidates, "finalize": finalize}[sys.argv[1]]()

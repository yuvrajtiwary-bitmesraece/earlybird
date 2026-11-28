"""Downloads a logo per domain into raw/: Wikidata's logo (as PNG, 1200 px wide) if known, else the site's largest
PNG/JPEG app icon (apple-touch-icon, manifest icons, <link rel=icon sizes>). Prints what it got."""
import json, re, struct, sys, time, urllib.parse, urllib.request
from html.parser import HTMLParser
raw = sys.argv[2]
UA_WM = "EarlyBirdLogoFinder/1.0 (+https://github.com/yuvrajtiwary-bitmesraece/earlybird)"
UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
def fetch(url, ua, limit=6_000_000):
    req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.geturl(), r.headers.get("Content-Type", ""), r.read(limit)
def dims(b):
    if b[:8] == b"\x89PNG\r\n\x1a\n": return "png", *struct.unpack(">II", b[16:24])
    if b[:2] == b"\xff\xd8":
        i = 2
        while i < len(b) - 9:
            if b[i] != 0xFF: i += 1; continue
            m = b[i + 1]
            if m in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack(">HH", b[i + 5:i + 9]); return "jpg", w, h
            i += 2 + struct.unpack(">H", b[i + 2:i + 4])[0]
    return None, 0, 0
class Links(HTMLParser):
    def __init__(s): super().__init__(); s.icons = []; s.manifest = None
    def handle_starttag(s, tag, a):
        a = dict(a)
        if tag != "link" or not a.get("href"): return
        rel = (a.get("rel") or "").lower()
        if "manifest" in rel: s.manifest = a["href"]
        elif "icon" in rel:
            m = re.match(r"(\d+)x", a.get("sizes") or "")
            s.icons.append((int(m.group(1)) if m else (180 if "apple" in rel else 0), a["href"]))
wd = json.load(open(sys.argv[1], encoding="utf-8"))
for dom, hit in wd.items():
    got = None
    try:
        if hit and hit.get("logo"):
            url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + urllib.parse.quote(hit["logo"]) + "?width=1200"
            _, ct, b = fetch(url, UA_WM)
            kind, w, h = dims(b)
            if kind: got = ("wikidata", kind, w, h, b)
            time.sleep(0.5)
    except Exception as e:
        pass
    if not got:
        cands = []
        try:
            base, ct, html = fetch(f"https://{dom}/", UA_WEB, 2_000_000)
            p = Links(); p.feed(html.decode("utf-8", "ignore"))
            cands += [(sz, urllib.parse.urljoin(base, href)) for sz, href in p.icons]
            if p.manifest:
                try:
                    _, _, mj = fetch(urllib.parse.urljoin(base, p.manifest), UA_WEB, 500_000)
                    for ic in json.loads(mj).get("icons", []):
                        m = re.match(r"(\d+)x", ic.get("sizes", ""))
                        cands.append((int(m.group(1)) if m else 0, urllib.parse.urljoin(urllib.parse.urljoin(base, p.manifest), ic["src"])))
                except Exception: pass
        except Exception: base = f"https://{dom}/"
        cands.append((180, urllib.parse.urljoin(base, "/apple-touch-icon.png")))
        best = None
        for sz, u in sorted(cands, key=lambda c: -c[0]):
            if u.lower().split("?")[0].endswith((".svg", ".ico", ".webp")): continue
            try:
                _, ct, b = fetch(u, UA_WEB)
                kind, w, h = dims(b)
                if kind and (best is None or w > best[2]): best = ("site", kind, w, h, b)
                if best and best[2] >= 180: break
            except Exception: continue
        if not best or best[2] < 120:
            try:
                _, ct, b = fetch(f"https://www.google.com/s2/favicons?domain={dom}&sz=256", UA_WEB)
                kind, w, h = dims(b)
                if kind and w >= 120 and (not best or w > best[2]): best = ("google", kind, w, h, b)
            except Exception: pass
        got = best
    if got:
        src, kind, w, h, b = got
        open(f"{raw}/{dom}.{kind}", "wb").write(b)
        print(f"{dom:28} {src:9} {w}x{h}", flush=True)
    else:
        print(f"{dom:28} NONE", flush=True)

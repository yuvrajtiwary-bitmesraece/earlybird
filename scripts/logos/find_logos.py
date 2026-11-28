"""For each seed company, find its Wikidata item whose official website (P856) matches the domain, and its logo (P154)."""
import csv, json, sys, time, urllib.parse, urllib.request
UA = "EarlyBirdLogoFinder/1.0 (+https://github.com/yuvrajtiwary-bitmesraece/earlybird)"
def get(url):
    for i in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            return json.loads(urllib.request.urlopen(req, timeout=30).read())
        except Exception as e:
            time.sleep(2 + i * 3)
    return {}
def host(u):
    h = urllib.parse.urlparse(u if "//" in u else "//" + u).hostname or ""
    return h.lower().removeprefix("www.")
rows = [r for r in csv.reader(l for l in open(sys.argv[1], encoding="utf-8") if not l.startswith("#"))][1:]
out = {}
for src, tok, dom, company in rows:
    names = [company, company.split(" (")[0]]
    if "(" in company: names.append(company.split("(")[1].rstrip(")"))
    found = None
    for name in dict.fromkeys(names):
        s = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(
            {"action": "wbsearchentities", "search": name, "language": "en", "type": "item", "limit": 8, "format": "json"}))
        ids = [x["id"] for x in s.get("search", [])]
        if not ids: continue
        e = get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(
            {"action": "wbgetentities", "ids": "|".join(ids), "props": "claims", "format": "json"}))
        for qid in ids:
            claims = e.get("entities", {}).get(qid, {}).get("claims", {})
            sites = [c["mainsnak"].get("datavalue", {}).get("value", "") for c in claims.get("P856", [])]
            if any(host(x) == dom or host(x).endswith("." + dom) for x in sites):
                logos = [c["mainsnak"].get("datavalue", {}).get("value") for c in claims.get("P154", [])
                         if c.get("rank") != "deprecated"]
                pref = [c["mainsnak"].get("datavalue", {}).get("value") for c in claims.get("P154", []) if c.get("rank") == "preferred"]
                found = {"qid": qid, "logo": (pref or logos or [None])[0]}
                break
        if found: break
        time.sleep(0.3)
    out[dom] = found
    print(f"{dom:28} {(found or {}).get('qid','-'):12} {(found or {}).get('logo') or '-'}", flush=True)
json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), indent=1, ensure_ascii=False)

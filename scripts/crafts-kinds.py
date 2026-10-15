#!/usr/bin/env python3
"""The kinds of doing — the crafts field's vocabulary (docs/AFTER_0_6.md §1). Every human craft, hobby, sport, martial art or trade the
open taxonomies name, reconciled into one list: Wikidata's class trees of hobby, craft and handicraft (CC0; the hobby tree two steps
down, where it is still about doing — below that it leaks into wine designations, race events and cultivars), Wikipedia's outline of
crafts, Wikipedia's list of sports (every sport with its disciplines under it, the families as the headings), its page of shooting
sports (the disciplines a tree walk never reaches: F-Class, PRS, benchrest, silhouette), its lists of martial arts (by country, with the
Japanese and the Chinese lists for depth), the ILO's ISCO-08 group 7 (the craft and related trades, through Wikidata's codes), Japan's
designated traditional crafts (METI's 伝統的工芸品, as the Kōgei association lists them, with category and prefecture), and the Heritage
Crafts Red List of Endangered Crafts (UK) with each craft's risk band. One entry per kind: English name, Japanese name (Wikidata's label
for the Wikipedia article), other names, parents (a discipline under its sport, a sport under its family), the taxonomies it came from
with their identifiers, the designations as properties, whether it is a sport or martial art (doing, not making) and whether it is an
Olympic sport. The list is not written by hand: this script makes it, and the operator cuts it (`keep`, carried over by id on a rebuild).

  python3 scripts/crafts-kinds.py fetch <dir> [what…]   # downloads the sources into <dir>: wikidata, wikipedia, communities, isco, meti,
                                                        # redlist, entities (the Wikipedia titles' Wikidata entities; after wikipedia); all by default
  python3 scripts/crafts-kinds.py build <dir> <out>     # reconciles them into <out> (one JSON object a line)

Standard library only. The User-Agent names researchzosho.org, as every call the library makes does."""
import csv, json, re, sys, os, urllib.request, urllib.parse, html, io, time
from collections import deque

UA = "ResearchZosho (https://researchzosho.org)"
HOBBY, CRAFT, HANDICRAFT, SPORT, MARTIAL = "Q47728", "Q2207288", "Q877729", "Q31629", "Q11417"
# the trees read, with the root's kind and how deep: the hobby tree is clean two steps down and leaks below (gardening → fruticulture →
# two thousand wine designations and cultivars; sports disciplines → events), so a branch whose descendants are mostly noise is dropped whole
TREES = (("hobby", HOBBY, "hobby", 4), ("craft", CRAFT, "craft", 6), ("handicraft", HANDICRAFT, "craft", 6), ("needlework", "Q1817291", "craft", 4), ("metalworking", "Q953045", "craft", 4),
         ("leather", "Q1186844", "craft", 4), ("instruments", "Q3064237", "craft", 4), ("diy", "Q26384", "craft", 3), ("rpg", "Q160738", "hobby", 2),
         )   # the sport tree is not walked: Wikidata models sports unevenly (swimming down to its events, football hardly at all); Wikipedia's list is the source
# Wikipedia's lists: the page, the kind its items are, whether its headings are kinds too (the families of sports) or only groupings (the
# regions of the martial arts lists), the root every top-level item hangs from, the region every item has, and the headings not to read.
WP_LISTS = (("List of sports", "sport", True, "sports", "", {"history", "see also", "references", "external links", "further reading", "notes", "fictional sports"}),
            ("Shooting sports", "sport", True, "shooting-sports", "", {"history", "great britain", "united states", "olympics", "competition disciplines", "esports", "see also", "references", "further reading", "external links"}),
            ("List of martial arts", "martial-art", False, "martial-arts", "", {"see also", "references", "external links"}),
            ("List of Japanese martial arts", "martial-art", False, "martial-arts", "Japan", {"see also", "references", "external links"}),
            ("List of Chinese martial arts", "martial-art", False, "martial-arts", "China", {"see also", "references", "sources", "citations", "external links"}))
# Communities people join — reenactment societies, LARP and battle-game systems, HEMA groups — from Wikipedia's categories (one level of
# subcategories down) and its list of reenactment groups; each root names the kind of doing its members are in. The pages are kept by
# what Wikidata says they are: a person, a film, a book, an event, a business or a museum is not a community.
CATEGORY_ROOTS = (("Historical reenactment groups", "historical-reenactment"), ("Medieval reenactment", "historical-reenactment"), ("Viking reenactment", "historical-reenactment"),
                  ("Live-action role-playing games", "live-action-role-playing-game"), ("Live-action battle gaming", "live-action-role-playing-game"),
                  ("Historical European martial arts revival", "historical-european-martial-arts"), ("Society for Creative Anachronism", "society-for-creative-anachronism"))   # last: its page comes in with "Medieval reenactment"
COMMUNITY_LISTS = (("List of historical reenactment groups", "historical-reenactment"),)
# the kinds of doing the roots hang from, by their Wikipedia page, made when no other source gave them
KIND_PAGES = (("historical-reenactment", "Historical reenactment", "hobby"), ("live-action-role-playing-game", "Live action role-playing game", "hobby"), ("historical-european-martial-arts", "Historical European martial arts", "martial-art"))
# what Wikidata says a category's page is, read 2026-10-13 over the pages the roots gave: a person, a film or an episode, a book or a codex, an
# event or a tournament or a convention, a museum or a village, a business or a restaurant or a software, a party or parlour or tabletop game, a
# supporters' group, a Facebook group, a model UN — none of them a community one joins to do the thing
NOT_A_COMMUNITY = {"Q5", "Q11424", "Q5398426", "Q7725634", "Q571", "Q482994", "Q15275719", "Q1190554", "Q1656682", "Q4830453", "Q35127", "Q7889", "Q131436", "Q13406463", "Q4167836", "Q33506", "Q2668072", "Q11032", "Q41298", "Q1004", "Q3305213", "Q7366",
                   "Q11410", "Q14947863", "Q839864", "Q5166413", "Q85801811", "Q1643932", "Q17143838", "Q625994", "Q11483816", "Q6659501", "Q756102", "Q636819", "Q115154345", "Q532", "Q215380", "Q21685563", "Q213924", "Q116048824", "Q21191270",
                   "Q942297", "Q3918", "Q500834", "Q1788780", "Q2085381", "Q18127", "Q17524420", "Q718", "Q650711", "Q476300", "Q15116915", "Q1368898", "Q7397", "Q18534542", "Q24354", "Q160738", "Q1979154", "Q45045", "Q188443", "Q132241", "Q1070414", "Q570116", "Q27686", "Q17709182"}
# a page in a group category that is itself a kind of doing (a martial art, a sport, a reenactment): a kind, not a community
DOING_CLASSES = {"Q11417": "martial-art", "Q66088122": "martial-art", "Q31629": "sport", "Q7128792": "sport", "Q206989": "sport", "Q657069": "hobby", "Q1940032": "hobby"}
ORGANISATION_CLASSES = {"Q43229", "Q163740", "Q48204", "Q4438121", "Q121461563", "Q79913", "Q17127659", "Q15911314", "Q1530022", "Q176799", "Q153936"}
# THE CUT (the operator, 2026-10-13): four rules set keep to false and say why (`cut`), so a rebuild keeps the cut and a person can see it.
# 1. an event, a TV show, a championship, a video game or a person that a sports list carries as an item — by Wikidata's class, unless Wikidata
#    also calls it a sport or an art (barrel racing, competition climbing and jousting stay)
CUT_CLASSES = {"Q15275719": "a recurring event", "Q11483816": "an annual event", "Q18608583": "a recurring sporting event", "Q1656682": "an event", "Q1190554": "an occurrence", "Q16510064": "a sporting event",
               "Q7889": "a video game", "Q5398426": "a TV series", "Q15416": "a TV programme", "Q1079023": "a championship", "Q5": "a person", "Q15116915": "a show", "Q11424": "a film", "Q7725634": "a literary work", "Q571": "a book", "Q618779": "an award"}
A_DOING = {"Q31629", "Q11417", "Q7128792", "Q206989", "Q1734"}
# 2. a heading of the sports list that is a classification label, not a thing a person might take up (Bicycle, Equines, Aircraft stay: a thing can be a hobby)
GROUPING_LABELS = {"bat-and-ball games", "invasion games", "marker sports", "mixed discipline", "net and wall games", "pilota family", "rodeo-originated", "sports that use a netted racket", "sports that use a non-netted racket, or paddle", "stick and ball"}
# 3. fantasy sports, and the video-game genres under esports (esports itself, speedrunning, video gaming stay)
GENRE_CLASSES = {"Q659563", "Q1610017", "Q270948", "Q4282636", "Q124695018", "Q208189", "Q116680021", "Q578868"}
# 4. a trade that is not a craft to take up: the "not elsewhere classified" groups, supervisors, cleaners
NOT_A_CRAFT = re.compile(r"(?i)supervisor|cleaner|not elsewhere classified")
ISCO_QUERY = ("SELECT ?item ?code ?en ?ja ?desc WHERE { ?item wdt:P952 ?code . FILTER(STRSTARTS(?code, '7')) OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en)='en') } "
              "OPTIONAL { ?item rdfs:label ?ja FILTER(LANG(?ja)='ja') } OPTIONAL { ?item schema:description ?desc FILTER(LANG(?desc)='en') } }")
OLYMPIC, TYPE_OF_SPORT = "Q212434", "Q31629"
SPARQL = "https://query.wikidata.org/sparql"

def get(url, accept="*/*"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
    with urllib.request.urlopen(req, timeout=300) as r: return r.read().decode("utf-8", "replace")

def tree_csv(root):
    q = ("SELECT ?item ?en ?ja ?desc (GROUP_CONCAT(DISTINCT ?parent; separator='|') AS ?parents) WHERE { ?item wdt:P279* wd:%s . "
         "OPTIONAL { ?item wdt:P279 ?parent . } OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en)='en') } OPTIONAL { ?item rdfs:label ?ja FILTER(LANG(?ja)='ja') } "
         "OPTIONAL { ?item schema:description ?desc FILTER(LANG(?desc)='en') } } GROUP BY ?item ?en ?ja ?desc" % root)
    return get(SPARQL + "?" + urllib.parse.urlencode({"query": q}), "text/csv")

def ids_csv(root):
    return get(SPARQL + "?" + urllib.parse.urlencode({"query": "SELECT DISTINCT ?item WHERE { ?item wdt:P279* wd:%s . }" % root}), "text/csv")

def wikitext(title):
    w = json.loads(get("https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main", "titles": title, "format": "json", "formatversion": "2", "redirects": "1"})))
    return w["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]

def wp_file(d, title): return os.path.join(d, "wp-%s.txt" % title.replace(" ", "_"))

LINK = re.compile(r"\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]+))?\]\]")

def clean_heading(h):
    h = re.sub(r"\{\{.*?\}\}|'{2,3}|<[^>]+>", "", h); h = LINK.sub(lambda m: m.group(2) or m.group(1), h)
    return re.sub(r"\s*\(.*?\)", "", h).split(":")[0].strip()

GENERIC_HEADINGS = {"others", "other", "combined", "individual", "team", "skirmish", "mixed or hybrid", "athletic hybrid", "miscellaneous", "alphabetical listing", "traditional", "modern",
                    "different classification", "both physical and mind", "by retribution", "compromise rules", "kindred activities", "miscellaneous sports", "other ball sports",
                    "other goal sports", "other paddling sports", "other racket sports", "non motorized vehicles", "remote control", "subsurface and recreational", "terrestrial vehicles"}

def wikilist(text, skip):
    """The linked bullets of a Wikipedia list: each with its label, the article, the bullet it sits under, the headings above it, the
    region (a `;Country` line) and the other names given beside it ("(IPSC)", the article's own name). Unlinked bullets group, nothing more."""
    items = []; stack = []; sections = {}; region = ""; abbreviations = {}
    for line in text.splitlines():
        m = re.match(r"^(={2,5})\s*(.*?)\s*=+\s*$", line)
        if m:
            level = len(m.group(1)); name = clean_heading(m.group(2))
            for l in [l for l in sections if l >= level]: del sections[l]
            sections[level] = name; stack = []; region = ""
            continue
        m = re.match(r"^;\s*(?:\[\[([^\]|]+)(?:\|[^\]]+)?\]\]|([^\[].*?))\s*$", line)
        if m: region = (m.group(1) or m.group(2) or "").strip(); stack = []; continue
        m = re.match(r"^(\*+)\s*(.*)$", line)
        if not m: continue
        depth = len(m.group(1)); body = m.group(2).strip().lstrip("'\"")
        while stack and stack[-1][0] >= depth: stack.pop()
        lm = LINK.match(body)
        if not lm: continue
        title = lm.group(1).strip(); label = html.unescape((lm.group(2) or title).strip().strip("'"))
        if any(title.lower().startswith(x) for x in ("list of ", "category:", "file:", "wikipedia:")) or not label: continue
        heads = [sections[l] for l in sorted(sections)]
        if any(h.lower() in skip for h in heads): continue
        aliases = set()
        am = re.match(r"\s*\(([A-Za-z][A-Za-z0-9\-\. ]{1,24})\)", body[lm.end():])
        if am: aliases.add(am.group(1).strip())
        for m2 in re.finditer(LINK.pattern + r"\s*\(([A-Z][A-Za-z0-9\-]{1,7})\)", body):   # "the [[Precision Rifle Series]] (PRS)" anywhere in a bullet: the abbreviation names that link
            abbreviations[(m2.group(2) or m2.group(1)).strip()] = m2.group(3); abbreviations[m2.group(1).strip()] = m2.group(3)
        bare = re.sub(r"\s*\(.*?\)\s*$", "", title)
        words = lambda x: {w[:5] for w in re.findall(r"[a-z]{4,}", x.lower())}
        if bare.lower() != label.lower() and (words(bare) <= words(label) or words(label) <= words(bare)): aliases.add(bare)   # the article's own name, when it names the same thing ("Dance" for "Dancing", "Shooting sports" for "Shooting"; not "Fantasy football" for "Association football")
        items.append({"title": title, "label": label, "depth": depth, "parent": stack[-1][1] if stack else None, "heads": [h for h in heads if h.lower() not in GENERIC_HEADINGS], "region": region, "aliases": sorted(aliases)})
        stack.append((depth, label))
    for it in items:
        a = abbreviations.get(it["label"]) or abbreviations.get(it["title"])
        if a and a.lower() != it["label"].lower(): it["aliases"] = sorted(set(it["aliases"]) | {a})
    return items, abbreviations

def entities(titles):
    """Wikidata's entity for each English Wikipedia title: its labels in English and Japanese, its description and its classes, fifty a call."""
    out = {}; titles = sorted(set(t for t in titles if t))
    for i in range(0, len(titles), 50):
        d = json.loads(get("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({"action": "wbgetentities", "sites": "enwiki", "titles": "|".join(titles[i:i + 50]), "props": "labels|descriptions|aliases|claims|sitelinks", "languages": "en|ja", "format": "json"})))
        for q, e in d.get("entities", {}).items():
            if not q.startswith("Q") or "missing" in e: continue
            title = e.get("sitelinks", {}).get("enwiki", {}).get("title")
            if not title: continue
            classes = [c["mainsnak"]["datavalue"]["value"]["id"] for prop in ("P31", "P279") for c in e.get("claims", {}).get(prop, []) if "datavalue" in c["mainsnak"]]
            site = [c["mainsnak"]["datavalue"]["value"] for c in e.get("claims", {}).get("P856", []) if "datavalue" in c["mainsnak"]]
            names = [a["value"] for a in e.get("aliases", {}).get("en", [])]
            out[title] = {"q": q, "en": e.get("labels", {}).get("en", {}).get("value", ""), "ja": e.get("labels", {}).get("ja", {}).get("value", ""), "desc": e.get("descriptions", {}).get("en", {}).get("value", ""), "classes": classes, "aliases": names, "website": site[0] if site else ""}
        time.sleep(0.3)
    return out

def fetch(d, what=("all",)):
    os.makedirs(d, exist_ok=True)
    def want(x): return "all" in what or x in what
    if want("wikidata"):
        for name, root, kind, maxdepth in TREES:
            open(os.path.join(d, "wd-%s.csv" % name), "w").write(tree_csv(root)); print("wikidata", name)
        for name, root in (("sport", SPORT), ("martialart", MARTIAL)):
            open(os.path.join(d, "wd-%s-ids.csv" % name), "w").write(ids_csv(root)); print("wikidata ids", name)
    if want("isco"):
        open(os.path.join(d, "wd-isco7.csv"), "w").write(get(SPARQL + "?" + urllib.parse.urlencode({"query": ISCO_QUERY}), "text/csv")); print("wikidata isco-08 group 7")
    if want("wikipedia"):
        open(wp_file(d, "Outline of crafts"), "w").write(wikitext("Outline of crafts")); print("wikipedia outline of crafts")
        for page, kind, heads_are_kinds, root, region, skip in WP_LISTS:
            open(wp_file(d, page), "w").write(wikitext(page)); print("wikipedia", page); time.sleep(0.5)
    if want("communities"):
        cats = {}
        def follow(root, sub):
            """A subcategory is read when it is about the same thing as its root (a word of four letters shared) and is not a list of events, people or works."""
            words = lambda x: {w for w in re.findall(r"[a-z]{4,}", x.lower()) if w not in ("games", "game", "groups", "group")}
            if re.search(r"(?i)\b(events?|designers?|people|persons|films?|television|video games?|novels?|books?|reality|competitions?|tournaments?|venues?|museums?)\b", sub): return False
            if sub in dict(CATEGORY_ROOTS): return False   # a root of its own (the SCA): its pages hang from it, not from the root above
            return bool(words(root) & words(sub))
        for root, kind in CATEGORY_ROOTS:
            pages = set(); seen = set()
            def members(cat, depth):
                if cat in seen or depth > 1: return
                seen.add(cat)
                d2 = json.loads(get("https://en.wikipedia.org/w/api.php?" + urllib.parse.urlencode({"action": "query", "list": "categorymembers", "cmtitle": "Category:" + cat, "cmlimit": "500", "format": "json", "formatversion": "2"})))
                for m in d2.get("query", {}).get("categorymembers", []):
                    if m["ns"] == 0: pages.add(m["title"])
                    elif m["ns"] == 14 and follow(root, m["title"][len("Category:"):]): members(m["title"][len("Category:"):], depth + 1)
                time.sleep(0.3)
            members(root, 0); cats[root] = sorted(pages); print("wikipedia category", root, len(pages))
        json.dump(cats, open(os.path.join(d, "wp-categories.json"), "w"), ensure_ascii=False)
        for page, kind in COMMUNITY_LISTS: open(wp_file(d, page), "w").write(wikitext(page)); print("wikipedia", page)
    if want("entities"):
        titles = set()
        if os.path.exists(os.path.join(d, "wp-categories.json")):
            for root, pages in json.load(open(os.path.join(d, "wp-categories.json"))).items(): titles.update(pages); titles.add(root)
        for page, kind in COMMUNITY_LISTS:
            if os.path.exists(wp_file(d, page)):
                for it in wikilist(open(wp_file(d, page)).read(), {"see also", "references", "external links"})[0]: titles.add(it["title"])
        for page, kind, heads_are_kinds, root, region, skip in WP_LISTS:
            for it in wikilist(open(wp_file(d, page)).read(), skip)[0]: titles.add(it["title"]); titles.update(it["heads"])
        for link in re.findall(r"^\*+\s*\[\[([^\]|#]+)", open(wp_file(d, "Outline of crafts")).read(), re.M): titles.add(link.strip())
        titles.update(["Sport", "Martial arts", "Shooting sports", "Craft", "Hobby"]); titles.update(t for k, t, kind in KIND_PAGES)
        ent = entities(titles); json.dump(ent, open(os.path.join(d, "wd-enwiki.json"), "w"), ensure_ascii=False); print("wikidata entities for", len(ent), "of", len(titles), "titles")
    if not want("meti"): return
    index = get("https://kougeihin.jp/craft/")
    cats = sorted(set(re.findall(r'href="(https://kougeihin\.jp/craft_industry/[a-z]+/)"', index)))
    meti = {}
    for u in cats:
        s = get(u)
        for block in re.findall(r'<article class="box">(.*?)</article>', s, re.S):
            link = re.search(r'href="https://kougeihin\.jp/craft/([0-9]+)/"', block); name = re.search(r'<h3 class="title">(.*?)</h3>', block, re.S)
            pref = re.findall(r'craft_pref/[a-z]+/">([^<]+)<', block); cat = re.findall(r'craft_industry/([a-z]+)/">([^<]+)<', block)
            if link and name: meti[link.group(1)] = {"id": link.group(1), "url": "https://kougeihin.jp/craft/%s/" % link.group(1), "ja": html.unescape(re.sub(r"<[^>]+>", "", name.group(1))).strip(), "category": cat[0][1] if cat else "", "prefecture": ", ".join(dict.fromkeys(pref))}
    json.dump(list(meti.values()), open(os.path.join(d, "meti.json"), "w"), ensure_ascii=False); print("meti", len(meti))
    s = get("https://heritagecrafts.org.uk/categories-of-risk/")
    red = {}; band = ""
    for m in re.finditer(r'<h2[^>]*>(.*?)</h2>|<a[^>]+href="(?:https://(?:www\.)?heritagecrafts\.org\.uk)?(/craft/([a-z0-9-]+)/)"[^>]*>(.*?)</a>', s, re.S):
        if m.group(2) is None:
            t = re.sub(r"<[^>]+>", "", m.group(1) or "").strip()
            if re.search(r"(?i)^(extinct in the uk|critically endangered|endangered|currently viable crafts)$", t): band = t
            continue
        slug = m.group(3); name = html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", m.group(4) or ""))).strip()
        if not name:
            h = re.search(r"<(?:h3|h4|p)[^>]*>(.*?)</(?:h3|h4|p)>", s[m.end(): m.end() + 400], re.S)
            name = html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", h.group(1)))).strip() if h else slug.replace("-", " ").capitalize()
        if band and slug not in red: red[slug] = {"slug": slug, "name": name, "band": band, "url": "https://heritagecrafts.org.uk/craft/%s/" % slug}
    json.dump(list(red.values()), open(os.path.join(d, "redlist.json"), "w"), ensure_ascii=False); print("red list", len(red))

NOISE = re.compile(r"(?i)\bwines?\b|\bDOCG?\b|designation of origin|cultivar|\braces?\b|\bstakes\b|streamer|championship|tournament|competition\b|\bseason\b|\bedition\b|olympic|grand prix|\bevents?\b|festival|\bclub\b|\bteam\b|person who|people who|wikimedia|website|video game|\bfilm\b|\balbum\b|\bsong\b|species|\bbreed\b|variety of|\bbeer\b|brewery|vineyard|area in|region of|production in|in [A-Z][a-z]+$|movement$|\bgem cut\b|\bdiamond cut\b|\bcut of (a )?gem")   # a cut is a shape, not a doing
# names that are a class, an event, a category or a place, not a kind of doing: "Women Junior", "4 × 100 metre freestyle relay", "men's table
# tennis", "type of association football", "boxing style", "Hiking in South Africa", "cycling by country" — the Wikidata sport tree's leak
NOISE_NAME = re.compile(r"(?i)^(men|women|woman|boys|girls|mixed|junior|senior|elite|masters|youth)('s)?\b|\bunder[- ]\d+|\b(junior|elite)$|\b\d+(\.\d+)? ?(m|km|metres?|meters?|miles?|yards?)\b|\brelay\b|×|^type of |\bstyles?$|\bby (country|region|city|continent)\b|\bspecialties of\b|youtuber|\bclasses? in\b|\bat the\b|\b(naked|nude)\b|^list of |\bevents?\b|\bchampionships?\b|\btournaments?\b|\bseason\b|\bgrand prix\b|\bfestival\b")
NOISE_PLACE = re.compile(r"\bin (the )?[A-ZÀ-Ž][a-zà-ž]")
def noisy_name(en): return bool(NOISE_NAME.search(en) or NOISE_PLACE.search(en))
METI_EN = {"織物": "woven textiles", "陶磁器": "ceramics", "木工品・竹工品": "woodwork and bamboo work", "漆器": "lacquerware", "金工品": "metalwork", "仏壇・仏具": "Buddhist altars and fittings", "染色品": "dyeing", "文具": "stationery making (brushes, ink, inkstones, abacuses)", "人形・こけし": "dolls and kokeshi", "和紙": "washi paper", "石工品": "stonework", "貴石細工": "gemstone work", "その他の工芸品": "other crafts", "その他繊維製品": "other textile goods", "工芸材料・工芸用具": "craft materials and tools"}

def slug(s): return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
def qid(uri): return uri.rsplit("/", 1)[1]

def build(d, out):
    entries = {}   # key -> entry
    def add(key, **kw):
        e = entries.setdefault(key, {"id": key, "en": "", "ja": "", "aliases": set(), "kind": set(), "parents": [], "sources": [], "sport": False, "olympic": False})
        for k, v in kw.items():
            if k in ("kind", "aliases"): e[k].update(v)
            elif k == "parents":   # in the order they are given: the first parent is where the list places it
                for x in v:
                    if x not in e["parents"]: e["parents"].append(x)
            elif k == "parents_first":   # a heading's own chain outranks a mention under another grouping
                for x in reversed(list(v)):
                    if x in e["parents"]: e["parents"].remove(x)
                    e["parents"].insert(0, x)
            elif k == "olympic": e[k] = e[k] or bool(v)
            elif k == "source":
                if not any(x["taxonomy"] == v["taxonomy"] and x["id"] == v["id"] for x in e["sources"]): e["sources"].append(v)
            elif v and not e.get(k): e[k] = v
        return e
    sports = {qid(r["item"]) for r in csv.DictReader(io.StringIO(open(os.path.join(d, "wd-sport-ids.csv")).read()))} | {qid(r["item"]) for r in csv.DictReader(io.StringIO(open(os.path.join(d, "wd-martialart-ids.csv")).read()))}
    byq = {}
    for name, root, kind, maxdepth in TREES:
        rows = {qid(r["item"]): r for r in csv.DictReader(open(os.path.join(d, "wd-%s.csv" % name)))}
        children = {}
        for q, r in rows.items():
            for p in r["parents"].split("|"):
                if p: children.setdefault(qid(p), []).append(q)
        def noisy(q): return q in rows and bool(NOISE.search(rows[q]["desc"] + " " + rows[q]["en"]))
        def below(q, seen):
            for c in children.get(q, []):
                if c not in seen: seen.add(c); below(c, seen)
            return seen
        # a branch whose descendants are mostly noise (fifteen or more, half of them noise) is left out whole
        leak = set()
        for q in rows:
            ds = below(q, set())
            if len(ds) >= 15 and sum(1 for c in ds if noisy(c)) * 2 >= len(ds): leak.add(q)
        depth = {root: 0}; dq = deque([root])
        while dq:
            x = dq.popleft()
            for c in children.get(x, []):
                if c not in depth and c not in leak: depth[c] = depth[x] + 1; dq.append(c)
        for q, dd in depth.items():
            if dd == 0 or dd > maxdepth or q not in rows: continue
            r = rows[q]
            if not r["en"] or noisy(q) or noisy_name(r["en"]): continue
            e = byq.setdefault(q, {"q": q, "en": r["en"], "ja": r["ja"], "desc": r["desc"], "parents": set(), "kind": set()})
            e["kind"].add(kind)
            for p in r["parents"].split("|"):
                if p: e["parents"].add(qid(p))
    for q, e in byq.items():
        key = slug(e["en"]) or q
        add(key, en=e["en"], ja=e["ja"], desc=e["desc"], kind=e["kind"], source={"taxonomy": "wikidata", "id": q, "url": "https://www.wikidata.org/wiki/" + q}, sport=(q in sports))
        entries[key]["sport"] = entries[key]["sport"] or q in sports
        add(key, parents=[slug(byq[p]["en"]) for p in sorted(e["parents"]) if p in byq])
    ent = json.load(open(os.path.join(d, "wd-enwiki.json"))) if os.path.exists(os.path.join(d, "wd-enwiki.json")) else {}
    # Wikipedia's outline of crafts: the bulleted links; matched to an entry by name, else their own
    w = open(os.path.join(d, "wp-Outline_of_crafts.txt")).read()
    for link in re.findall(r"^\*+\s*\[\[([^\]|#]+)", w, re.M):
        name = link.strip(); key = slug(name)
        if not key: continue
        add(key, en=name, kind={"craft"}, source={"taxonomy": "wikipedia", "id": "Outline of crafts", "url": "https://en.wikipedia.org/wiki/" + urllib.parse.quote(name.replace(" ", "_"))})
        if name in ent: add(key, ja=ent[name]["ja"], desc=ent[name]["desc"])
    # Wikipedia's lists of sports and martial arts: a discipline under its sport, a sport under its family (the headings), the martial arts
    # by country; the Japanese name, the description and the Olympic flag from Wikidata's entity for the article
    def wp_source(page, title): return {"taxonomy": "wikipedia", "id": page, "url": "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")), "article": title}
    def other_names(x, en):
        """Wikidata's other English names, the plain ones: "SCA", "soccer", "HEMA"; not a phrase with a comma or a bracket, at most six."""
        out = []
        for a in x.get("aliases", []):
            a = a.strip()
            if 2 <= len(a) <= 40 and not re.search(r"[,()\[\]/;:]", a) and a.lower() != en.lower() and not noisy_name(a) and a not in out: out.append(a)
        return out[:6]
    def enrich(key, title):
        x = ent.get(title)
        if not x: return
        add(key, ja=x["ja"], desc=x["desc"], olympic=OLYMPIC in x["classes"], aliases=set(other_names(x, entries[key]["en"])), website=x.get("website", ""), source={"taxonomy": "wikidata", "id": x["q"], "url": "https://www.wikidata.org/wiki/" + x["q"]})
    for root, en in (("sports", "Sports"), ("martial-arts", "Martial arts"), ("shooting-sports", "Shooting sports")):
        add(root, en=en, kind={"sport" if root != "martial-arts" else "martial-art"}, sport=True); enrich(root, en if en != "Sports" else "Sport")
    add("martial-arts", parents=["sports"]); add("shooting-sports", parents=["sports"])
    abbreviations = {}   # a name -> its abbreviation, from any page ("the [[Precision Rifle Series]] (PRS)" on the shooting page names the sports list's entry)
    for page, kind, heads_are_kinds, root, region, skip in WP_LISTS:
        text = open(wp_file(d, page)).read(); page_src = wp_source(page, page)
        items, abbr = wikilist(text, skip); abbreviations.update(abbr)
        for it in items:
            if noisy_name(it["label"]): continue
            parent = None
            if it["parent"] and slug(it["parent"]) in entries: parent = slug(it["parent"])
            elif heads_are_kinds and it["heads"]:
                prev = root
                for h in it["heads"]:
                    hk = slug(h)
                    if hk == root or not hk: continue
                    add(hk, en=h, kind={kind}, parents_first=[prev], sport=True, source=dict(page_src, id=page + " § " + h)); enrich(hk, h); prev = hk
                parent = prev
            key = slug(it["label"])
            if not key or key == root: continue
            add(key, en=it["label"], kind={kind}, parents=[parent or root], aliases=set(it["aliases"]), region=(region or it["region"]), sport=True, source=wp_source(page, it["title"]))
            entries[key]["sport"] = True; enrich(key, it["title"])
    # the communities: the pages of each category root (and the list of reenactment groups), kept when Wikidata says they are a group,
    # a society or a game system and not a person, a film, an event or a shop; the root's kind of doing is the parent
    cats = json.load(open(os.path.join(d, "wp-categories.json"))) if os.path.exists(os.path.join(d, "wp-categories.json")) else {}
    for key, title, kind in KIND_PAGES:
        if key not in entries: add(key, en=title, kind={kind}, sport=(kind != "hobby"), source=wp_source(title, title)); enrich(key, title)
    def community(title, parent, source):
        x = ent.get(title)
        if not x or set(x["classes"]) & NOT_A_COMMUNITY or title.lower().startswith("list of") or noisy_name(title): return
        key = slug(title)
        if key and key != parent and key in entries and "community" in entries.get(parent, {}).get("kind", set()) and "community" in entries[key]["kind"]:
            add(key, parents_first=[parent]); return   # a kingdom tagged with medieval reenactment too: it hangs from the SCA first
        if not key or key == parent or key in entries: return   # the root's own page, or a kind of doing already in (airsoft, jousting): not a community
        doing = [DOING_CLASSES[c] for c in x["classes"] if c in DOING_CLASSES]
        organisation = bool(set(x["classes"]) & ORGANISATION_CLASSES)
        if (doing or title in dict(CATEGORY_ROOTS)) and not organisation:   # a category's own page (Medieval reenactment) or a page that is an art, a sport or a reenactment: a kind of doing under the root's kind; the SCA's own category is the SCA
            add(key, en=title, kind={doing[0] if doing else "hobby"}, parents=[parent], sport=bool(doing) and doing[0] != "hobby", source=source); enrich(key, title); return
        add(key, en=title, kind={"community"}, parents=[parent], region="", source=source); enrich(key, title)
    for root, kind in CATEGORY_ROOTS:
        parent = kind if kind in entries else "historical-reenactment"
        if root == "Society for Creative Anachronism" and "society-for-creative-anachronism" not in entries: continue
        for title in cats.get(root, []):
            community(title, kind if kind in entries else parent, {"taxonomy": "wikipedia-category", "id": root, "url": "https://en.wikipedia.org/wiki/Category:" + urllib.parse.quote(root.replace(" ", "_"))})
    for page, kind in COMMUNITY_LISTS:
        if not os.path.exists(wp_file(d, page)): continue
        for it in wikilist(open(wp_file(d, page)).read(), {"see also", "references", "external links"})[0]:
            community(it["title"], kind, wp_source(page, it["title"]))
    for name, a in abbreviations.items():
        key = slug(name)
        if key in entries and a.lower() != entries[key]["en"].lower(): entries[key]["aliases"].add(a)
    # the trades: ISCO-08 major group 7 (craft and related trades workers), each occupation an entry, the code its designation
    GENERIC_NOUNS = {"workers", "makers", "operators", "layers", "setters", "placers", "finishers", "installers", "repairers", "fitters", "servicers", "cleaners", "supervisors", "preparers", "handlers", "cutters", "mechanics", "assemblers", "builders", "erectors", "producers", "processors"}
    OCCUPATION = re.compile(r"(?i)(er|or|ist|ian|man|men|smith|wright|ic|cook|mason|jack|wife)s?$")
    def occupations(label):
        """The occupations named in an ISCO group label, one by one and singular: "bakers, pastry-cooks and confectionery makers" -> baker, pastry-cook, confectionery maker."""
        out = []
        for part in re.split(r",|\band\b|\bor\b|/", label.lower()):
            ws = [w for w in part.split() if w not in ("related", "other", "the", "of", "not", "elsewhere", "classified")]
            if not ws or not OCCUPATION.search(ws[-1]): continue
            last = ws[-1]; single = last[:-1] if last.endswith("s") and not last.endswith("ss") else last
            if last in GENERIC_NOUNS:
                if 2 <= len(ws) <= 4: out.append(" ".join(ws[:-1] + [single]))   # "sheet metal workers" -> sheet metal worker
            elif len(single) >= 5: out.append(single)
        return out
    if os.path.exists(os.path.join(d, "wd-isco7.csv")):
        add("craft-and-related-trades", en="Craft and related trades", desc="the skilled trades: ISCO-08 major group 7, craft and related trades workers", kind={"trade"}, source={"taxonomy": "isco-08", "id": "7", "url": "https://www.ilo.org/public/english/bureau/stat/isco/isco08/"})
        for r in csv.DictReader(open(os.path.join(d, "wd-isco7.csv"))):
            if len(r["code"]) != 4 or not r["en"] or noisy_name(r["en"]): continue
            key = slug(r["en"])
            add(key, en=r["en"], ja=r["ja"], desc=r["desc"], kind={"trade"}, parents=["craft-and-related-trades"], aliases=set(occupations(r["en"])), designation="ISCO-08 " + r["code"], source={"taxonomy": "wikidata", "id": qid(r["item"]), "url": r["item"]})
    # Japan's designated crafts: each its own entry, its category the parent
    for m in json.load(open(os.path.join(d, "meti.json"))):
        cat_en = METI_EN.get(m["category"], m["category"]); cat_key = "japanese-" + slug(cat_en)
        add(cat_key, en="Japanese " + cat_en, ja=m["category"], kind={"craft"}, source={"taxonomy": "meti-category", "id": m["category"], "url": "https://kougeihin.jp/craft/"})
        key = "meti-" + m["id"]
        add(key, en="", ja=m["ja"], kind={"craft"}, parents=[cat_key], region=m["prefecture"], designation="伝統的工芸品 (METI)", source={"taxonomy": "meti", "id": m["id"], "url": m["url"]})
    # the Red List: each craft its own entry (matched by name to an existing one), with its band
    for r in json.load(open(os.path.join(d, "redlist.json"))):
        key = slug(r["name"])
        add(key, en=r["name"], kind={"craft"}, risk=r["band"], region="United Kingdom", source={"taxonomy": "heritage-crafts-red-list", "id": r["slug"], "url": r["url"]})
    # the cut: the four rules, then the operator's own hand — a hand cut (keep false, no rule) stays cut; a rule-cut entry the operator set back
    # to keep true by hand (the rule's reason still on it) stays kept
    def subtree(root):
        out = set(); frontier = [root]
        while frontier:
            k = frontier.pop()
            for key, e in entries.items():
                if e["parents"] and e["parents"][0] == k and key not in out: out.add(key); frontier.append(key)
        return out
    fantasy = subtree("fantasy-sports") | {"fantasy-sports"}; under_esports = subtree("esports")
    def rule(key, e):
        x = byq_entity(e)
        classes = set(x["classes"]) if x else set()
        if "community" not in e["kind"] and classes & set(CUT_CLASSES) and not classes & A_DOING: return CUT_CLASSES[next(c for c in classes if c in CUT_CLASSES)] + ", not a kind of doing"
        if e["en"].lower() in GROUPING_LABELS and any(" § " in s.get("id", "") for s in e["sources"]): return "a classification label of the sports list, not a thing a person takes up"
        if key in fantasy: return "fantasy sport: following, not doing"
        if key in under_esports and key != "esports" and (classes & GENRE_CLASSES or not x): return "a video-game genre, not a kind of doing (esports itself stays)"
        if "trade" in e["kind"] and NOT_A_CRAFT.search(e["en"]): return "an ISCO group that is not a craft to take up"
        return ""
    by_q = {x["q"]: x for x in ent.values()}
    def byq_entity(e):
        for s in e["sources"]:
            if s.get("taxonomy") == "wikidata" and s["id"] in by_q: return by_q[s["id"]]
        return None
    kept = {}
    if os.path.exists(out):
        for line in open(out, encoding="utf-8"):
            if line.strip(): j = json.loads(line); kept[j["id"]] = (bool(j.get("keep", True)), j.get("cut", ""))
    n = 0; cut = 0; by_rule = {}
    with open(out, "w", encoding="utf-8") as f:
        for key in sorted(entries):
            e = entries[key]
            if os.environ.get("CRAFTS_DEBUG") and key in os.environ["CRAFTS_DEBUG"].split(","): print("debug", key, "raw parents", e["parents"], "in entries:", [p in entries for p in e["parents"]])
            if not (e["en"] or e["ja"]): continue
            if "martial-art" in e["kind"] and (re.search(r"(?i)[ -]ha$", e["en"]) or ("派" in e["ja"] and e.get("region") == "Japan")): continue   # a Japanese branch (派, -ha) is a lineage of its school, not a kind of doing; in a Chinese name 派 is the school itself
            e["kind"] = sorted(e["kind"]); e["parents"] = [p for p in e["parents"] if p in entries and p != key]
            e["aliases"] = sorted(a for a in e["aliases"] if a.lower() != e["en"].lower())
            if not e.get("website"): e.pop("website", None)
            why = rule(key, e); before = kept.get(key)
            if why:
                e["cut"] = why; e["keep"] = bool(before and before[0] and before[1])   # kept by hand against the rule: the reason stays on it
                by_rule[why.split(",")[0].split(":")[0]] = by_rule.get(why.split(",")[0].split(":")[0], 0) + (0 if e["keep"] else 1)
            else:
                e["keep"] = before[0] if before else True
                if before and not before[0]: e["cut"] = before[1] or "by hand"
            cut += 0 if e["keep"] else 1
            f.write(json.dumps(e, ensure_ascii=False) + "\n"); n += 1
    print("wrote", n, "kinds to", out, "(%d cut: %s)" % (cut, "; ".join("%s %d" % (k, v) for k, v in sorted(by_rule.items(), key=lambda kv: -kv[1]))))

if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "fetch": fetch(sys.argv[2], tuple(sys.argv[3:]) or ("all",))
    elif len(sys.argv) >= 4 and sys.argv[1] == "build": build(sys.argv[2], sys.argv[3])
    else: print(__doc__); sys.exit(2)

package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.researchzosho.tools.Forums;
import org.researchzosho.tools.Fetch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The company registers the library can read by jurisdiction, each answering "employers of this kind in this place" from the register
 * itself, read before any web search for list pages: France's register through the state's open search API (no key), the United
 * Kingdom's through Companies House's own search page (no key) or its API (a free key), Japan's through gBizINFO (a free token). Each
 * read is recorded in {@link Sources} as a register with its yield, like any source. The activity a register files an employer under
 * is chosen by the model among the register's own sections, never invented: the register lists them, the model picks for the occupation.
 * A record gives the employer's name, the town, what it does as the register files it, its size band when the register gives one, and
 * the address of its record. See docs/DESIGN_CAREER.md, "Registers by jurisdiction".
 */
public final class CareerRegisters {
    private CareerRegisters() { }

    static final ObjectMapper M = new ObjectMapper();
    /** Each service's base address; a test points them at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static {
        BASE.put("sirene", "https://recherche-entreprises.api.gouv.fr"); BASE.put("geo", "https://geo.api.gouv.fr");
        BASE.put("companies_house", "https://find-and-update.company-information.service.gov.uk"); BASE.put("companies_house_api", "https://api.company-information.service.gov.uk");
        BASE.put("gbizinfo", "https://info.gbiz.go.jp");
        BASE.put("cms", "https://data.cms.gov"); BASE.put("urban", "https://educationdata.urban.org"); BASE.put("ods", "https://directory.spineservices.nhs.uk"); BASE.put("postcodes", "https://api.postcodes.io");
        BASE.put("cnes", "https://apidadosabertos.saude.gov.br"); BASE.put("brasilapi", "https://brasilapi.com.br"); BASE.put("datagouv", "https://www.data.gouv.fr"); BASE.put("education_fr", "https://data.education.gouv.fr");
        BASE.put("esr_fr", "https://data.enseignementsup-recherche.gouv.fr"); BASE.put("universities", "http://universities.hipolabs.com");
        BASE.put("overpass", "https://overpass-api.de/api/interpreter"); BASE.put("overpass_2", "https://lz4.overpass-api.de/api/interpreter"); BASE.put("overpass_mirror", "https://overpass.kumi.systems/api/interpreter");
        BASE.put("overpass_mirror_2", "https://overpass.private.coffee/api/interpreter"); BASE.put("nominatim", "https://nominatim.openstreetmap.org");
    }
    public static final String COMPANIES_HOUSE_KEY = "RESEARCHZOSHO_COMPANIES_HOUSE_KEY", GBIZINFO_TOKEN = "RESEARCHZOSHO_GBIZINFO_TOKEN";
    static final int PAGE = 25, MOST_CODES = 8;

    /** One employer as a register files it. */
    public record Employer(String name, String place, String what, String size, String url) { }

    /** A register the library can read. */
    public interface Register {
        String name();
        /** The country it covers, two letters as the bench and the records write them (FR, UK, JP). */
        String country();
        /** The address the source record carries. */
        String address();
        /** Whether it can be read on this library: a keyless one always, a keyed one when the key is set. */
        boolean on();
        /** What it says is needed when it is not on: where the free key comes from. */
        String needs();
        /** The employers filed under the sections chosen, in the place, up to max; the work and the kind of employer for the choices a register's route still needs. */
        List<Employer> read(String place, List<String> sections, String occupation, String employerKind, int max) throws IOException;
    }

    /** The sections of economic activity the European registers share (NACE Rev. 2; France's NAF and the UK's SIC 2007 follow it), each with its plain name. */
    static final String[][] SECTIONS = {
            {"A", "agriculture, forestry and fishing"}, {"B", "mining and quarrying"}, {"C", "manufacturing"}, {"D", "electricity, gas and steam supply"}, {"E", "water supply, sewerage and waste"},
            {"F", "construction"}, {"G", "wholesale and retail trade, repair of vehicles"}, {"H", "transport and storage"}, {"I", "accommodation and food service: hotels, restaurants, catering"},
            {"J", "information and communication: software, IT services, telecoms, publishing, media"}, {"K", "finance and insurance"}, {"L", "real estate"},
            {"M", "professional, scientific and technical: law firms, accountants, architects, engineering offices, research, advertising, design"}, {"N", "administrative and support services: staffing agencies, security, cleaning, travel"},
            {"O", "public administration and defence"}, {"P", "education"}, {"Q", "human health and social work: hospitals, clinics, care homes"}, {"R", "arts, entertainment and recreation"},
            {"S", "other services: associations, repair, personal services"}, {"T", "households as employers"}, {"U", "extraterritorial organisations"}};
    /** The divisions (the first two digits of a code) each section spans. */
    static final Map<String, int[]> DIVISIONS = Map.ofEntries(Map.entry("A", new int[]{1, 3}), Map.entry("B", new int[]{5, 9}), Map.entry("C", new int[]{10, 33}), Map.entry("D", new int[]{35, 35}),
            Map.entry("E", new int[]{36, 39}), Map.entry("F", new int[]{41, 43}), Map.entry("G", new int[]{45, 47}), Map.entry("H", new int[]{49, 53}), Map.entry("I", new int[]{55, 56}), Map.entry("J", new int[]{58, 63}),
            Map.entry("K", new int[]{64, 66}), Map.entry("L", new int[]{68, 68}), Map.entry("M", new int[]{69, 75}), Map.entry("N", new int[]{77, 82}), Map.entry("O", new int[]{84, 84}), Map.entry("P", new int[]{85, 85}),
            Map.entry("Q", new int[]{86, 88}), Map.entry("R", new int[]{90, 93}), Map.entry("S", new int[]{94, 96}), Map.entry("T", new int[]{97, 98}), Map.entry("U", new int[]{99, 99}));

    static String sectionName(String letter) { for (String[] s : SECTIONS) if (s[0].equals(letter)) return s[1]; return ""; }

    public static List<Register> registers() { return List.of(new France(), new UnitedKingdom(), new Japan()); }

    static String setting(String key) { String v = Config.get(key); return v == null ? "" : v.strip(); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    /** Whether an address is one of the registers' own, which the registers read and the list reader must not. */
    public static boolean isRegisterAddress(String address) {
        for (Register r : registers()) if (address != null && address.startsWith(r.address())) return true;
        for (Institutions r : institutions()) if (address != null && address.startsWith(r.address())) return true;
        return false;
    }

    // ---- which country, which sections ----

    static final Map<String, String> COUNTRY_WORDS = Map.ofEntries(Map.entry("france", "FR"), Map.entry("french", "FR"), Map.entry("francia", "FR"), Map.entry("frankreich", "FR"), Map.entry("フランス", "FR"),
            Map.entry("united kingdom", "UK"), Map.entry("uk", "UK"), Map.entry("england", "UK"), Map.entry("scotland", "UK"), Map.entry("wales", "UK"), Map.entry("northern ireland", "UK"), Map.entry("britain", "UK"), Map.entry("great britain", "UK"),
            Map.entry("japan", "JP"), Map.entry("日本", "JP"), Map.entry("japon", "JP"),
            Map.entry("united states", "US"), Map.entry("usa", "US"), Map.entry("us", "US"), Map.entry("u.s.", "US"), Map.entry("u.s.a.", "US"), Map.entry("america", "US"), Map.entry("états-unis", "US"), Map.entry("etats-unis", "US"), Map.entry("estados unidos", "US"), Map.entry("アメリカ", "US"),
            Map.entry("brazil", "BR"), Map.entry("brasil", "BR"), Map.entry("brésil", "BR"), Map.entry("ブラジル", "BR"), Map.entry("india", "IN"), Map.entry("inde", "IN"), Map.entry("インド", "IN"), Map.entry("bharat", "IN"),
            Map.entry("germany", "DE"), Map.entry("deutschland", "DE"), Map.entry("allemagne", "DE"), Map.entry("ドイツ", "DE"), Map.entry("canada", "CA"), Map.entry("カナダ", "CA"), Map.entry("ontario", "CA"), Map.entry("québec", "CA"), Map.entry("quebec", "CA"), Map.entry("british columbia", "CA"), Map.entry("alberta", "CA"),
            Map.entry("australia", "AU"), Map.entry("australie", "AU"), Map.entry("オーストラリア", "AU"), Map.entry("spain", "ES"), Map.entry("españa", "ES"), Map.entry("espana", "ES"), Map.entry("espagne", "ES"), Map.entry("スペイン", "ES"));

    /** The country a place is in, by its own words when they name it, else the model's choice among the countries the registers cover; "" when none of them. */
    public static String countryOf(String place) {
        if (place == null || place.isBlank()) return "";
        String low = place.toLowerCase(Locale.ROOT);
        for (String part : low.split("[,;/]")) { String w = part.strip(); if (COUNTRY_WORDS.containsKey(w)) return COUNTRY_WORDS.get(w); }
        for (var e : COUNTRY_WORDS.entrySet()) if (e.getKey().length() > 3 && low.matches(".*\\b" + Pattern.quote(e.getKey()) + "\\b.*")) return e.getValue();
        if (usState(place) != null) return "US";   // "Austin, Texas", "Austin, TX": a state names the country
        if (!brState(place).isEmpty() && place.matches("(?i).*\\b(MG|SP|RJ|RS|PR|SC|BA|PE|CE|GO|DF|ES|PA|AM|MT|MS|PB|RN|AL|PI|SE|RO|TO|AC|AP|RR|MA|minas gerais|são paulo|sao paulo|rio de janeiro|bahia|paraná|parana|pernambuco|ceará|ceara|goiás|goias|santa catarina|rio grande do sul)\\b.*")) return "BR";
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "Which country is the place below in? Answer with a JSON object and nothing else: {\"country\": \"FR\"} for France, {\"country\": \"UK\"} for the United Kingdom, "
                + "{\"country\": \"JP\"} for Japan, {\"country\": \"US\"} for the United States, {\"country\": \"BR\"} for Brazil, {\"country\": \"IN\"} for India, {\"country\": \"DE\"} for Germany, {\"country\": \"CA\"} for Canada, "
                + "{\"country\": \"AU\"} for Australia, {\"country\": \"ES\"} for Spain, or {\"country\": \"other\"} for any other country.\n\n" + Fence.rule("the place") + "\n" + Fence.wrap("PLACE", place.strip()));
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 40); } catch (RuntimeException e) { return ""; }
        String c = CareerInterview.parseObject(said).path("country").asText("").strip().toUpperCase(Locale.ROOT);
        return COUNTRY_NAMES.containsKey(c) ? c : "";
    }

    /** The sections such employers are filed under, up to three, chosen by the model among the register's own; [] when it names none. */
    public static List<String> sectionsFor(String occupation, String employerKind) { return sectionsFor(occupation, employerKind, ""); }

    /** As above, with the field of work the person decided ("developer tools", "paediatric care") when they named one. */
    public static List<String> sectionsFor(String occupation, String employerKind, String domain) {
        StringBuilder list = new StringBuilder();
        for (String[] s : SECTIONS) list.append(s[0]).append(": ").append(s[1]).append('\n');
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "A person is looking for work" + seeking(occupation, employerKind, domain) + ". "
                + "A company register files every employer under one section of economic activity, listed below by letter. Which sections are the employers who hire for that work filed under? "
                + "Choose from the list only, the likeliest first, at most three. Answer with a JSON array of letters and nothing else.\n\n" + list);
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 60); } catch (RuntimeException e) { return List.of(); }
        List<String> out = new ArrayList<>();
        for (JsonNode n : Career.parseArray(said)) { String l = n.asText("").strip().toUpperCase(Locale.ROOT); if (l.length() == 1 && !sectionName(l).isEmpty() && !out.contains(l) && out.size() < 3) out.add(l); }
        return out;
    }

    static String seeking(String occupation, String employerKind, String domain) {
        return (occupation.isEmpty() ? "" : " as " + occupation) + (employerKind.isEmpty() ? "" : ", at " + employerKind) + (domain == null || domain.isEmpty() ? "" : ", in " + domain);
    }

    public static CareerTargets.Found read(LibraryStore store, Career.Seeker s, String place, String occupation, String employerKind, int max, SourceRules rules) throws IOException { return read(store, s, place, occupation, employerKind, "", max, rules); }

    /**
     * The registers of the place's country read, each recorded as a source with its yield; the employers with the register's record in
     * the why, after the register's own name. {@code domain} is the field of work the person decided, "" when they named none.
     */
    public static CareerTargets.Found read(LibraryStore store, Career.Seeker s, String place, String occupation, String employerKind, String domain, int max, SourceRules rules) throws IOException {
        List<Sources.Source> used = new ArrayList<>();
        Map<String, String> employers = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        String country = countryOf(place);
        // the institutions and the places first: a hospital, a school, a restaurant, a law office is on its own register or on the map, not the company register
        List<String> kinds = kindsFor(employerKind, shapeKind(store, s), occupation);
        CareerTargets.Found inst = readInstitutions(store, s, place, country, kinds, occupation, max, rules);
        used.addAll(inst.sources()); employers.putAll(inst.employers()); notes.addAll(inst.notes());
        if (country.isEmpty()) return new CareerTargets.Found(used, employers, notes);   // most of the world has no company register the library can read: the web search runs as before, and says nothing of registers it does not have
        List<String> sections = null;
        for (Register r : registers()) {
            if (!r.country().equals(country)) continue;
            if (!r.on()) { notes.add(r.name() + " is not read: " + r.needs()); continue; }
            if (rules != null && rules.refused(r.address())) { notes.add(r.name() + " is on your refused list, so it is not read"); continue; }
            if (sections == null) sections = sectionsFor(occupation, employerKind, domain);
            if (sections.isEmpty()) { notes.add(r.name() + " was not read: no section of activity fits " + (occupation.isEmpty() ? "the work" : occupation)); continue; }
            List<Employer> got;
            try { got = r.read(place, sections, occupation + (domain.isEmpty() ? "" : " in " + domain), employerKind, Math.max(1, max - employers.size())); }
            catch (IOException | RuntimeException e) { notes.add(r.name() + " did not answer (" + e.getMessage() + ")"); continue; }
            Sources.Source src = Sources.read(store, r.address(), "register", "employers", "career", place, occupation, "the registers the library knows", got.size());
            if (src == null || src.off()) continue;
            used.add(src);
            for (Employer e : got) {
                if (employers.size() >= max) break;
                String detail = (e.place().isEmpty() ? "" : "in " + e.place()) + (e.what().isEmpty() ? "" : (e.place().isEmpty() ? "" : ", ") + e.what()) + (e.size().isEmpty() ? "" : ", " + e.size()) + (e.url().isEmpty() ? "" : " — " + e.url());
                employers.putIfAbsent(e.name(), src.id() + "\t" + r.name() + " (" + src.id() + ")" + (detail.isEmpty() ? "" : ", " + detail));   // the reason on the list starts with the register's name, which a person knows, not its address
            }
            StringBuilder sec = new StringBuilder(); for (String l : sections) sec.append(sec.length() == 0 ? "" : ", ").append(l).append(" (").append(sectionName(l)).append(")");
            notes.add(r.name() + " (" + src.id() + ") read for " + place + " under " + sec + ": " + got.size() + (got.size() == 1 ? " employer" : " employers"));
        }
        return new CareerTargets.Found(used, employers, notes);
    }

    // ---- France: the state's open search over the company register (SIRENE and the RNE) ----

    static final Map<String, String> FR_BANDS = Map.ofEntries(Map.entry("NN", ""), Map.entry("00", "no employee"), Map.entry("01", "1 or 2 people"), Map.entry("02", "3 to 5 people"), Map.entry("03", "6 to 9 people"),
            Map.entry("11", "10 to 19 people"), Map.entry("12", "20 to 49 people"), Map.entry("21", "50 to 99 people"), Map.entry("22", "100 to 199 people"), Map.entry("31", "200 to 249 people"), Map.entry("32", "250 to 499 people"),
            Map.entry("41", "500 to 999 people"), Map.entry("42", "1,000 to 1,999 people"), Map.entry("51", "2,000 to 4,999 people"), Map.entry("52", "5,000 to 9,999 people"), Map.entry("53", "10,000 people or more"));

    static final class France implements Register {
        @Override public String name() { return "the French company register"; }
        @Override public String country() { return "FR"; }
        @Override public String address() { return BASE.get("sirene") + "/search"; }
        @Override public boolean on() { return true; }
        @Override public String needs() { return ""; }
        @Override public List<Employer> read(String place, List<String> sections, String occupation, String employerKind, int max) throws IOException {
            String town = place.split("[,;/]")[0].strip();
            String geo = CareerSources.get(BASE.get("geo") + "/communes?nom=" + enc(town) + "&fields=codeDepartement,population&boost=population&limit=1");
            JsonNode g = geo == null ? M.createArrayNode() : M.readTree(geo);
            String dept = g.isArray() && !g.isEmpty() ? g.get(0).path("codeDepartement").asText("") : "";
            if (dept.isEmpty()) throw new IOException("the register does not know a town called " + town);
            List<Employer> out = new ArrayList<>();
            for (int page = 1; page <= 4 && out.size() < max; page++) {
                String body = CareerSources.get(address() + "?departement=" + enc(dept) + "&section_activite_principale=" + enc(String.join(",", sections)) + "&tranche_effectif_salarie=12,21,22,31,32,41,42,51,52,53&etat_administratif=A&per_page=" + PAGE + "&page=" + page);
                if (body == null) throw new IOException("no answer");
                JsonNode d = M.readTree(body);
                if (d.has("erreur")) throw new IOException(d.path("erreur").asText());
                JsonNode rs = d.path("results");
                for (JsonNode r : rs) {
                    if (out.size() >= max) break;
                    String nm = r.path("nom_complet").asText("").strip();
                    if (nm.isEmpty()) continue;
                    String commune = "";
                    for (JsonNode e : r.path("matching_etablissements")) if (e.path("commune").asText("").startsWith(dept)) { commune = e.path("libelle_commune").asText(""); break; }
                    if (commune.isEmpty()) commune = r.path("siege").path("libelle_commune").asText("");
                    String naf = r.path("activite_principale").asText(""), section = r.path("section_activite_principale").asText("");
                    String what = naf.isEmpty() ? "" : "filed under NAF " + naf + (sectionName(section).isEmpty() ? "" : " (" + sectionName(section) + ")");
                    out.add(new Employer(nm, title(commune), what, FR_BANDS.getOrDefault(r.path("tranche_effectif_salarie").asText(""), ""), "https://annuaire-entreprises.data.gouv.fr/entreprise/" + r.path("siren").asText("")));
                }
                if (page >= d.path("total_pages").asInt(1)) break;
            }
            return out;
        }
    }

    static String title(String upper) {
        if (upper == null || upper.isEmpty()) return "";
        StringBuilder b = new StringBuilder(); boolean start = true;
        for (char c : upper.toLowerCase(Locale.ROOT).toCharArray()) { b.append(start ? Character.toUpperCase(c) : c); start = c == ' ' || c == '-' || c == '\''; }
        return b.toString();
    }

    // ---- the United Kingdom: Companies House, its search page without a key, its API with one ----

    static List<String[]> ukCodes;
    /** The UK SIC 2007 codes with their descriptions, from the library's own copy of Companies House's list. */
    static synchronized List<String[]> ukCodes() {
        if (ukCodes != null) return ukCodes;
        List<String[]> out = new ArrayList<>();
        try (InputStream in = CareerRegisters.class.getResourceAsStream("uk-sic-2007.tsv")) {
            if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) { if (line.startsWith("#")) continue; int t = line.indexOf('\t'); if (t > 0) out.add(new String[]{line.substring(0, t), line.substring(t + 1)}); }
            }
        } catch (IOException ignored) { }
        ukCodes = out;
        return out;
    }

    /** The UK codes of the sections chosen, up to eight, picked by the model among them for the work; the sections' first codes when it names none. */
    static List<String> ukCodesFor(List<String> sections, String occupation, String employerKind) {
        List<String[]> inSections = new ArrayList<>();
        for (String[] c : ukCodes()) {
            int div = Integer.parseInt(c[0].substring(0, 2));
            for (String s : sections) { int[] r = DIVISIONS.get(s); if (r != null && div >= r[0] && div <= r[1]) { inSections.add(c); break; } }
        }
        if (inSections.isEmpty()) return List.of();
        StringBuilder list = new StringBuilder();
        for (String[] c : inSections) list.append(c[0]).append(' ').append(c[1]).append('\n');
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "A person is looking for work" + seeking(occupation, employerKind, "") + ". "
                + "The UK company register files each employer under activity codes; the codes of the sections that fit are listed below. Which codes would the employers who hire for that work be filed under? "
                + "Choose from the list only, the likeliest first, at most " + MOST_CODES + ". Answer with a JSON array of the five-digit codes and nothing else.\n\n" + list);
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 120); } catch (RuntimeException e) { said = ""; }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>(); for (String[] c : inSections) known.add(c[0]);
        for (JsonNode n : Career.parseArray(said)) { String code = n.asText("").strip(); if (known.contains(code) && out.size() < MOST_CODES) out.add(code); }
        return new ArrayList<>(out);
    }

    static final class UnitedKingdom implements Register {
        @Override public String name() { return "Companies House, the UK company register"; }
        @Override public String country() { return "UK"; }
        @Override public String address() { return BASE.get("companies_house") + "/advanced-search/get-results"; }
        @Override public boolean on() { return true; }
        @Override public String needs() { return ""; }
        @Override public List<Employer> read(String place, List<String> sections, String occupation, String employerKind, int max) throws IOException {
            List<String> codes = ukCodesFor(sections, occupation, employerKind);
            if (codes.isEmpty()) throw new IOException("no activity code chosen for the work");
            String town = place.split("[,;/]")[0].strip();
            return setting(COMPANIES_HOUSE_KEY).isEmpty() ? page(town, codes, max) : api(town, codes, max);
        }

        /** The search page, as the public reads it: twenty companies a page, name, type, number, address and codes. */
        List<Employer> page(String town, List<String> codes, int max) throws IOException {
            StringBuilder q = new StringBuilder(address() + "?registeredOfficeAddress=" + enc(town) + "&status=active");
            for (String c : codes) q.append("&sicCodes=").append(c);
            List<Employer> out = new ArrayList<>();
            for (int page = 1; page <= 3 && out.size() < max; page++) {
                String html = CareerSources.get(q + "&page=" + page);
                if (html == null) throw new IOException("no answer");
                if (html.contains("Enter a valid SIC")) throw new IOException("the register refused the activity codes " + codes);
                int before = out.size();
                Matcher row = Pattern.compile("(?s)<tr class=\"govuk-table__row\">(.*?)</tr>").matcher(html);
                while (row.find() && out.size() < max) {
                    String r = row.group(1);
                    Matcher a = Pattern.compile("href=\"?/company/([A-Z0-9]{8})\"?[^>]*>(.*?)<span").matcher(r);
                    if (!a.find()) continue;
                    String number = a.group(1), nm = Forums.unescape(a.group(2).replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").strip();
                    List<String> items = new ArrayList<>();
                    Matcher li = Pattern.compile("(?s)<li>(.*?)</li>").matcher(r);
                    while (li.find()) { String t = li.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip(); if (!t.isEmpty()) items.add(t); }
                    String addr = "", sic = "", type = items.isEmpty() ? "" : items.get(0);
                    for (String it : items) { if (it.startsWith("SIC codes - ")) sic = it.substring("SIC codes - ".length()).strip(); else if (it.contains(",") && !it.contains("Incorporated on")) addr = it; }
                    out.add(new Employer(title(nm), ukTown(addr, town), (sic.isEmpty() ? "" : "filed under SIC " + sic) + (type.isEmpty() ? "" : (sic.isEmpty() ? "" : ", ") + type.toLowerCase(Locale.ROOT)), "", BASE.get("companies_house") + "/company/" + number));
                }
                if (out.size() == before) break;
            }
            return out;
        }

        /** The API, with the person's free key: the same search as JSON, fifty a call. */
        List<Employer> api(String town, List<String> codes, int max) throws IOException {
            String url = BASE.get("companies_house_api") + "/advanced-search/companies?location=" + enc(town) + "&sic_codes=" + enc(String.join(",", codes)) + "&company_status=active&size=" + Math.min(100, Math.max(20, max));
            String body = CareerSources.get(url, Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString((setting(COMPANIES_HOUSE_KEY) + ":").getBytes(StandardCharsets.UTF_8))));
            if (body == null) throw new IOException("no answer from the API");
            JsonNode d = M.readTree(body);
            if (d.has("errors")) throw new IOException(d.path("errors").toString());
            List<Employer> out = new ArrayList<>();
            for (JsonNode it : d.path("items")) {
                if (out.size() >= max) break;
                String nm = it.path("company_name").asText("").strip(); if (nm.isEmpty()) continue;
                List<String> sic = new ArrayList<>(); for (JsonNode c : it.path("sic_codes")) sic.add(c.asText());
                out.add(new Employer(title(nm), title(it.path("registered_office_address").path("locality").asText("")), sic.isEmpty() ? "" : "filed under SIC " + String.join(", ", sic), "", BASE.get("companies_house") + "/company/" + it.path("company_number").asText("")));
            }
            return out;
        }
    }

    static final List<String> UK_COUNTIES = List.of("greater manchester", "merseyside", "west midlands", "west yorkshire", "south yorkshire", "tyne and wear", "greater london", "surrey", "kent", "essex", "sussex", "east sussex", "west sussex",
            "devon", "cornwall", "somerset", "dorset", "norfolk", "suffolk", "cumbria", "durham", "county durham", "northumberland", "cleveland", "avon", "wirral", "isle of wight", "rutland", "london");

    /** The town in a UK registered address: the part before the county and the country ("…, Altrincham, Cheshire, England WA14 4RW" gives Altrincham; "1 Deansgate, Manchester, England M3 1AZ" gives Manchester); the place searched when it is among the parts, or when the address does not say. */
    static String ukTown(String address, String fallback) {
        if (address == null || address.isBlank()) return fallback;
        List<String> parts = new ArrayList<>(); for (String p : address.split(",\\s*")) if (!p.isBlank()) parts.add(p.strip());
        if (parts.size() < 2) return fallback;
        parts.remove(parts.size() - 1);   // the country and the postcode
        for (String p : parts) if (p.equalsIgnoreCase(fallback)) return p;
        String last = parts.get(parts.size() - 1), low = last.toLowerCase(Locale.ROOT);
        boolean county = low.endsWith("shire") || UK_COUNTIES.contains(low);
        if (county && parts.size() >= 2) return parts.get(parts.size() - 2);
        return last;
    }

    // ---- Japan: gBizINFO, the government's company information, with a free token ----

    static final String[][] PREFECTURES = {{"01", "北海道", "hokkaido"}, {"02", "青森", "aomori"}, {"03", "岩手", "iwate"}, {"04", "宮城", "miyagi"}, {"05", "秋田", "akita"}, {"06", "山形", "yamagata"}, {"07", "福島", "fukushima"},
            {"08", "茨城", "ibaraki"}, {"09", "栃木", "tochigi"}, {"10", "群馬", "gunma"}, {"11", "埼玉", "saitama"}, {"12", "千葉", "chiba"}, {"13", "東京", "tokyo"}, {"14", "神奈川", "kanagawa"}, {"15", "新潟", "niigata"},
            {"16", "富山", "toyama"}, {"17", "石川", "ishikawa"}, {"18", "福井", "fukui"}, {"19", "山梨", "yamanashi"}, {"20", "長野", "nagano"}, {"21", "岐阜", "gifu"}, {"22", "静岡", "shizuoka"}, {"23", "愛知", "aichi"},
            {"24", "三重", "mie"}, {"25", "滋賀", "shiga"}, {"26", "京都", "kyoto"}, {"27", "大阪", "osaka"}, {"28", "兵庫", "hyogo"}, {"29", "奈良", "nara"}, {"30", "和歌山", "wakayama"}, {"31", "鳥取", "tottori"},
            {"32", "島根", "shimane"}, {"33", "岡山", "okayama"}, {"34", "広島", "hiroshima"}, {"35", "山口", "yamaguchi"}, {"36", "徳島", "tokushima"}, {"37", "香川", "kagawa"}, {"38", "愛媛", "ehime"}, {"39", "高知", "kochi"},
            {"40", "福岡", "fukuoka"}, {"41", "佐賀", "saga"}, {"42", "長崎", "nagasaki"}, {"43", "熊本", "kumamoto"}, {"44", "大分", "oita"}, {"45", "宮崎", "miyazaki"}, {"46", "鹿児島", "kagoshima"}, {"47", "沖縄", "okinawa"}};
    /** The cities whose prefecture shares their name is the common case; the capitals and big cities that do not are few. */
    static final Map<String, String> JP_CITIES = Map.ofEntries(Map.entry("sapporo", "01"), Map.entry("札幌", "01"), Map.entry("sendai", "04"), Map.entry("仙台", "04"), Map.entry("yokohama", "14"), Map.entry("横浜", "14"), Map.entry("kawasaki", "14"),
            Map.entry("nagoya", "23"), Map.entry("名古屋", "23"), Map.entry("kobe", "28"), Map.entry("神戸", "28"), Map.entry("kitakyushu", "40"), Map.entry("北九州", "40"), Map.entry("naha", "47"), Map.entry("那覇", "47"), Map.entry("tsu", "24"), Map.entry("otsu", "25"),
            Map.entry("matsue", "32"), Map.entry("matsuyama", "38"), Map.entry("takamatsu", "37"), Map.entry("mito", "08"), Map.entry("utsunomiya", "09"), Map.entry("maebashi", "10"), Map.entry("kofu", "19"), Map.entry("morioka", "03"));

    static String prefectureOf(String place) {
        String low = place.toLowerCase(Locale.ROOT);
        for (String[] p : PREFECTURES) if (low.contains(p[1]) || low.matches(".*\\b" + p[2] + "\\b.*")) return p[0];
        for (var e : JP_CITIES.entrySet()) if (low.contains(e.getKey())) return e.getValue();
        return "";
    }

    static final class Japan implements Register {
        @Override public String name() { return "gBizINFO, Japan's company information"; }
        @Override public String country() { return "JP"; }
        @Override public String address() { return BASE.get("gbizinfo") + "/hojin/v1/hojin"; }
        @Override public boolean on() { return !setting(GBIZINFO_TOKEN).isEmpty(); }
        @Override public String needs() { return "a free gBizINFO API token (info.gbiz.go.jp, the API registration page), set as the setting gbizinfo_token"; }
        @Override public List<Employer> read(String place, List<String> sections, String occupation, String employerKind, int max) throws IOException {
            String pref = prefectureOf(place);
            if (pref.isEmpty()) throw new IOException("the place does not name a prefecture or a city the library knows: " + place);
            List<String> words = jpWords(occupation, employerKind, sections);
            String body = CareerSources.get(address() + "?prefecture=" + pref + "&employee_number_from=20&exist_flg=true&limit=1000&page=1", Map.of("X-hojinInfo-api-token", setting(GBIZINFO_TOKEN)));
            if (body == null) throw new IOException("no answer");
            JsonNode d = M.readTree(body);
            if (d.has("message") && !d.has("hojin-infos")) throw new IOException(d.path("message").asText());
            List<Employer> out = new ArrayList<>();
            for (JsonNode h : d.path("hojin-infos")) {
                if (out.size() >= max) break;
                String nm = h.path("name").asText("").strip(), summary = h.path("business_summary").asText("");
                if (nm.isEmpty()) continue;
                boolean fits = words.isEmpty(); for (String w : words) if (summary.contains(w) || nm.contains(w)) { fits = true; break; }
                if (!fits) continue;
                String people = h.path("employee_number").asText("");
                out.add(new Employer(nm, h.path("location").asText("").replaceFirst("^(.{2,3}?[都道府県])", "$1 ").strip(), summary.isEmpty() ? "" : summary.length() > 80 ? summary.substring(0, 80) + "…" : summary, people.isEmpty() || people.equals("0") ? "" : people + " people", "https://info.gbiz.go.jp/hojin/ichiran?hojinBango=" + h.path("corporate_number").asText("")));
            }
            return out;
        }
    }

    /** The words such employers' line of business carries in Japanese, chosen by the model with the sections as its anchor; [] takes every employer of the prefecture. */
    static List<String> jpWords(String occupation, String employerKind, List<String> sections) {
        StringBuilder sec = new StringBuilder(); for (String l : sections) sec.append(sec.length() == 0 ? "" : "; ").append(sectionName(l));
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "A person in Japan is looking for work" + seeking(occupation, employerKind, "") + ". "
                + "Japan's company information describes each company's line of business in a sentence of Japanese (事業内容). Employers who hire for that work are in these lines of business: " + sec + ". "
                + "Give up to six Japanese words such a description would carry (for a nurse: 病院, 医療, 介護), the surest first. Answer with a JSON array of strings and nothing else.");
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 80); } catch (RuntimeException e) { return List.of(); }
        List<String> out = new ArrayList<>();
        for (JsonNode n : Career.parseArray(said)) { String w = n.asText("").strip(); if (!w.isEmpty() && w.length() <= 12 && out.size() < 6) out.add(w); }
        return out;
    }

    // ================================================================ institution registers by employer kind ================================================================
    //
    // A company register lists companies. A hospital, a school, a university, a care home, a clinic, a research institute is on another
    // register, kept by the state for its own purpose and open: the US hospital, nursing-home and home-health lists (CMS), the US school
    // directory (NCES, through the Urban Institute's open API), the universities of the world (the Hipolabs list), England's NHS
    // organisation directory (ODS), Brazil's health establishments (CNES), France's health establishments (FINESS), schools (the
    // annuaire de l'éducation) and higher education and research (the MESR's list). Each is read by the kind of employer the person
    // decided or the shape named, in their place, before the company registers.

    /** The kinds of institution the registers cover, each with the words a decided kind of employer, a shape or an occupation may carry for it, in the languages the library meets. */
    static final String[][] KINDS = {
            {"hospital", "hospital|hospitals|clinic|clinics|medical center|medical centre|health system|healthcare|health care|hôpital|hopital|hôpitaux|clinique|krankenhaus|klinik|病院|クリニック|hospitais|clínica|clinica|ospedale"},
            {"nursing home", "nursing home|nursing homes|care home|care homes|aged care|elder care|elderly|ehpad|maison de retraite|介護|老人ホーム|casa de repouso|skilled nursing|long-term care|residential care|pflegeheim"},
            {"home health", "home health|home care|homecare|domiciliary|soins à domicile|home nursing|訪問看護|atendimento domiciliar|home care agency"},
            {"pharmacy", "pharmacy|pharmacies|chemist|pharmacie|薬局|farmácia|farmacia|apotheke"},
            {"GP practice", "gp practice|gp surgery|general practice|family practice|primary care|cabinet médical|médecin généraliste|診療所|unidade básica|posto de saúde|hausarzt"},
            {"dental practice", "dental|dentist|dentists|cabinet dentaire|歯科|odontolog|zahnarzt"},
            {"school", "school|schools|primary school|secondary school|high school|elementary|école|ecole|écoles|collège|lycée|lycee|schule|gymnasium|学校|小学校|中学校|高校|escola|escolas|colégio|scuola|academy|school district|教育"},
            {"university", "university|universities|faculty|higher education|grande école|université|universidade|universidad|大学|hochschule|universität|polytechnic|institute of technology|community college|college of|academia|academic"},
            {"research institute", "research|laboratory|laboratories|lab|labs|institute|institut|cnrs|inserm|inria|research centre|research center|研究所|研究|instituto de pesquisa|laboratoire|forschung|postdoc|postdoctoral"},
            // the kinds no official register lists anywhere: read from the map (OpenStreetMap), after the official registers
            {"restaurant", "restaurant|restaurants|kitchen|kitchens|café|cafe|cafés|cafes|bar|bars|pub|pubs|bistro|brasserie|diner|eatery|catering|caterer|food service|fast food|canteen|レストラン|飲食|restaurante|ristorante|gastronomie|chef"},
            {"hotel", "hotel|hotels|hospitality|resort|resorts|hostel|guest house|lodging|ホテル|hôtel|hôtels|hotelaria|hostelería"},
            {"law office", "law firm|law firms|law office|legal practice|lawyer|lawyers|attorney|attorneys|solicitor|solicitors|notary|cabinet d'avocats|avocat|avocats|anwalt|kanzlei|escritório de advocacia|advogado|advogados|法律事務所|弁護士|despacho de abogados|abogado"},
            {"accountancy", "accountancy|accounting firm|accounting|accountant|accountants|bookkeeping|tax advisor|expert-comptable|steuerberater|contabilidade|contador|会計事務所|税理士"},
            {"architecture practice", "architecture practice|architecture firm|architect|architects|architecte|architektur|arquitetura|建築設計"},
            {"estate agency", "estate agent|estate agency|real estate agency|realtor|letting agent|agence immobilière|immobilien|imobiliária|不動産"},
            {"insurance office", "insurance agency|insurance broker|insurance office|assurance|versicherung|seguradora|保険代理店"},
            {"bank", "bank branch|bank|banks|credit union|banque|bank filiale|banco|agência bancária|銀行"},
            {"shop", "shop|shops|store|stores|retail|retailer|boutique|supermarket|grocery|magasin|commerce de détail|einzelhandel|laden|loja|lojas|varejo|tienda|小売|店舗|ショップ"},
            {"gym", "gym|gyms|fitness|fitness centre|fitness center|sports centre|sports center|salle de sport|fitnessstudio|academia de ginástica|スポーツジム"},
            {"veterinary practice", "veterinary|vet clinic|vets|vétérinaire|tierarzt|veterinária|動物病院"},
            {"library", "public library|libraries|library|bibliothèque|bibliothek|biblioteca|図書館"},
            {"museum", "museum|museums|gallery|galleries|musée|museu|museo|博物館|美術館"},
            {"theatre", "theatre|theater|cinema|arts centre|arts center|playhouse|théâtre|theater|teatro|劇場"},
            {"public office", "public body|public bodies|public sector|government office|local government|municipality|city hall|town hall|county|civil service|public administration|courthouse|court|mairie|collectivité|fonction publique|prefeitura|órgão público|市役所|区役所|役所|公務員|ayuntamiento"},
            {"childcare", "childcare|day care|daycare|nursery|crèche|creche|kindertagesstätte|保育園|guardería"},
            {"craft workshop", "workshop|craft|crafts|carpentry|joinery|plumbing|electrician|bakery|butcher|atelier|artisan|handwerk|oficina|工房|職人"},
            {"charity", "charity|charities|non-profit|nonprofit|ngo|ngos|association|foundation|voluntary sector|third sector|organisation caritative|gemeinnützig|instituição sem fins|非営利"}};

    /** A register of institutions of some kinds in one country, or in any ("*"). */
    public interface Institutions {
        String name();
        String country();
        String address();
        List<String> kinds();
        /** The institutions of the kinds asked, in the place, up to max; the occupation for the choices a register's route still needs. */
        List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException;
        /** Whether it is read only for the kinds no other register covered in that country: the map, after the official registers. */
        default boolean lastResort() { return false; }
        /** A word on the last read, for the log ("300 of 1,182 named on the map"); "" when there is none. */
        default String note() { return ""; }
    }

    public static List<Institutions> institutions() { return List.of(new UsHealth(), new UsSchools(), new UkHealth(), new BrazilHealth(), new FranceHealth(), new FranceSchools(), new FranceHigher(), new Universities(), new MapPlaces()); }

    /** The kinds of institution the words of a decided kind of employer or of a shape name; [] when they name none. */
    static List<String> kindsIn(String words) {
        List<String> out = new ArrayList<>();
        if (words == null || words.isBlank()) return out;
        String low = words.toLowerCase(Locale.ROOT);
        for (String[] k : KINDS) for (String w : k[1].split("\\|")) if (saysWord(low, w) && !out.contains(k[0])) { out.add(k[0]); break; }
        return out;
    }

    /** Whether the text carries the word as a word: "bar" in "a wine bar", not in "barrister"; a word of a script without spaces (Japanese) anywhere in it. */
    static boolean saysWord(String low, String w) {
        if (w.isEmpty()) return false;
        if (!w.matches(".*[a-z].*")) return low.contains(w);
        int from = 0;
        while (true) {
            int i = low.indexOf(w, from);
            if (i < 0) return false;
            boolean before = i == 0 || !Character.isLetter(low.charAt(i - 1)), after = i + w.length() >= low.length() || !Character.isLetter(low.charAt(i + w.length()));
            if (before && after) return true;
            from = i + 1;
        }
    }

    /**
     * The kinds of institution to read for a person: the words of the kind of employer they decided and the kind the shape named, else
     * the model's choice among the kinds the registers cover for the occupation ([] when such work is at companies or firms, which the
     * company registers hold). The model chooses among the list only.
     */
    public static List<String> kindsFor(String employerKind, String shapeKind, String occupation) {
        List<String> out = new ArrayList<>(kindsIn(employerKind));
        for (String k : kindsIn(shapeKind)) if (!out.contains(k)) out.add(k);
        if (!out.isEmpty() || occupation == null || occupation.isBlank()) return out;
        // a decided kind whose words name no kind here ("a large, established employer") goes to the model with the occupation: it chooses among the list, or none for a company in general
        StringBuilder list = new StringBuilder(); for (String[] k : KINDS) list.append("- ").append(k[0]).append('\n');
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "A person is looking for work as " + occupation + (employerKind == null || employerKind.isBlank() ? "" : ", at " + employerKind) + ". "
                + "The library can read the public registers and the map's places of these kinds. Which of them employ people for that work? Choose from the list only, the likeliest first, at most three; "
                + "answer [] when such work is at companies in general rather than at places of these kinds. Answer with a JSON array of the kinds' names, as written, and nothing else.\n\n" + list);
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 60); } catch (RuntimeException e) { return out; }
        for (JsonNode n : Career.parseArray(said)) { String k = n.asText("").strip().toLowerCase(Locale.ROOT); for (String[] kk : KINDS) if (kk[0].equals(k) && !out.contains(k) && out.size() < 3) out.add(k); }
        return out;
    }

    /** The kind of employer the shape names for the person, "" when there is no shape yet; read, never changed. */
    static String shapeKind(LibraryStore store, Career.Seeker s) {
        try { CareerShape.Shape sh = CareerShape.kept(store, s); return sh == null ? "" : sh.employerKind(); } catch (IOException | RuntimeException e) { return ""; }
    }

    /**
     * The institution registers of the kinds asked read for the place, each recorded as a source with its yield, the institutions with the
     * register's record in the why; one note names the kinds no register covers in that country.
     */
    static CareerTargets.Found readInstitutions(LibraryStore store, Career.Seeker s, String place, String country, List<String> kinds, String occupation, int max, SourceRules rules) {
        List<Sources.Source> used = new ArrayList<>();
        Map<String, String> employers = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (kinds.isEmpty() || place == null || place.isBlank()) return new CareerTargets.Found(used, employers, notes);
        STORE.set(store);
        try {
            Set<String> covered = new LinkedHashSet<>();
            for (Institutions r : institutions()) {
                if (!r.country().equals("*") && !r.country().equals(country)) continue;
                List<String> mine = new ArrayList<>(); for (String k : kinds) if (r.kinds().contains(k) && !(r.lastResort() && covered.contains(k))) mine.add(k);   // the map only for what no official register listed
                if (mine.isEmpty()) continue;
                if (rules != null && rules.refused(r.address())) { notes.add(r.name() + " is on your refused list, so it is not read"); covered.addAll(mine); continue; }
                List<Employer> got;
                try { got = r.read(place, mine, occupation, Math.max(1, max - employers.size())); }
                catch (IOException | RuntimeException e) { notes.add(r.name() + " did not answer (" + e.getMessage() + ")"); continue; }
                covered.addAll(mine);
                Sources.Source src;
                try { src = Sources.read(store, r.address(), "register", "employers", "career", place, occupation, "the registers the library knows", got.size()); }
                catch (IOException e) { notes.add(r.name() + " could not be recorded as a source (" + e.getMessage() + ")"); continue; }
                if (src == null || src.off()) continue;
                used.add(src);
                for (Employer e : got) {
                    if (employers.size() >= max) break;
                    String detail = (e.place().isEmpty() ? "" : "in " + e.place()) + (e.what().isEmpty() ? "" : (e.place().isEmpty() ? "" : ", ") + e.what()) + (e.size().isEmpty() ? "" : ", " + e.size()) + (e.url().isEmpty() ? "" : " — " + e.url());
                    employers.putIfAbsent(e.name(), src.id() + "\t" + r.name() + " (" + src.id() + ")" + (detail.isEmpty() ? "" : ", " + detail));
                }
                notes.add(r.name() + " (" + src.id() + ") read for " + place + ", " + String.join(" and ", mine) + ": " + got.size() + (got.size() == 1 ? " employer" : " employers") + (r.note().isEmpty() ? "" : ", " + r.note()));
            }
            List<String> none = new ArrayList<>(); for (String k : kinds) if (!covered.contains(k)) none.add(k);
            if (!none.isEmpty()) notes.add("no register of " + String.join(" or ", none) + (country.isEmpty() ? " in that country" : " in " + countryName(country)) + " is known to the library, so the web search and the sources you add stand in for it");
        } finally { STORE.remove(); }
        return new CareerTargets.Found(used, employers, notes);
    }

    // ---- the map: OpenStreetMap, through the Overpass API, for the kinds of place no official register lists anywhere ----

    /** The map's tags for each kind the map answers: each entry a filter the query puts on nodes, ways and relations; a kind with two keys lists two. */
    static final Map<String, List<String>> MAP_TAGS = Map.ofEntries(
            Map.entry("restaurant", List.of("[\"amenity\"~\"^(restaurant|cafe|bar|pub|fast_food|food_court|biergarten|ice_cream)$\"]")),
            Map.entry("hotel", List.of("[\"tourism\"~\"^(hotel|hostel|guest_house|motel|resort|apartment|chalet)$\"]")),
            Map.entry("hospital", List.of("[\"amenity\"=\"hospital\"]")),
            Map.entry("GP practice", List.of("[\"amenity\"~\"^(clinic|doctors)$\"]")),
            Map.entry("dental practice", List.of("[\"amenity\"=\"dentist\"]")),
            Map.entry("pharmacy", List.of("[\"amenity\"=\"pharmacy\"]")),
            Map.entry("nursing home", List.of("[\"amenity\"~\"^(nursing_home|social_facility)$\"]")),
            Map.entry("school", List.of("[\"amenity\"~\"^(school|college)$\"]")),
            Map.entry("university", List.of("[\"amenity\"=\"university\"]")),
            Map.entry("research institute", List.of("[\"office\"=\"research\"]")),
            Map.entry("law office", List.of("[\"office\"~\"^(lawyer|notary)$\"]")),
            Map.entry("accountancy", List.of("[\"office\"~\"^(accountant|tax_advisor|tax)$\"]")),
            Map.entry("architecture practice", List.of("[\"office\"=\"architect\"]")),
            Map.entry("estate agency", List.of("[\"office\"=\"estate_agent\"]")),
            Map.entry("insurance office", List.of("[\"office\"=\"insurance\"]")),
            Map.entry("bank", List.of("[\"amenity\"=\"bank\"]")),
            Map.entry("shop", List.of("[\"shop\"]")),
            Map.entry("gym", List.of("[\"leisure\"~\"^(fitness_centre|sports_centre)$\"]")),
            Map.entry("veterinary practice", List.of("[\"amenity\"=\"veterinary\"]")),
            Map.entry("library", List.of("[\"amenity\"=\"library\"]")),
            Map.entry("museum", List.of("[\"tourism\"~\"^(museum|gallery)$\"]")),
            Map.entry("theatre", List.of("[\"amenity\"~\"^(theatre|cinema|arts_centre)$\"]")),
            Map.entry("public office", List.of("[\"office\"=\"government\"]", "[\"amenity\"~\"^(townhall|courthouse|police|fire_station|post_office)$\"]")),
            Map.entry("childcare", List.of("[\"amenity\"~\"^(childcare|kindergarten)$\"]")),
            Map.entry("craft workshop", List.of("[\"craft\"]")),
            Map.entry("charity", List.of("[\"office\"~\"^(ngo|association|charity|foundation)$\"]")),
            Map.entry("estate agent", List.of("[\"office\"=\"estate_agent\"]")));

    /** A map record's place: the town the mappers wrote, else the one asked for. */
    static final int MAP_PAGE = 300;

    /** The call to Overpass: the query as the body, the answer as text; a test puts its own here. */
    static volatile BiFunction<String, String, String> OVERPASS = CareerRegisters::overpassPost;
    /**
     * The call to Nominatim, the map's place search: its usage policy allows a program with a name at one request a second, while its
     * robots file turns crawlers away from the search path, so this goes straight to it at that pace instead of through the page reader's
     * robots rules. A test puts its own here.
     */
    static volatile Function<String, String> NOMINATIM = CareerRegisters::nominatimGet;
    private static long lastNominatim;

    static String nominatimGet(String url) {
        synchronized (MAP_LOCK) {
            long wait = 1100 - (System.currentTimeMillis() - lastNominatim);
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            lastNominatim = System.currentTimeMillis();
        }
        return CareerSources.fetchWith(url, Map.of("Accept-Language", "en"));
    }
    private static final Object MAP_LOCK = new Object();
    private static long lastOverpass;

    /** The program's name as Overpass wants it: a product and its site, not a browser's. */
    static final String OVERPASS_UA = "ResearchZosho/" + (Version.number() == null ? "dev" : Version.number()) + " (+https://researchzosho.org)";
    /** Why the last Overpass call gave nothing, for the log: "HTTP 504 from overpass-api.de", "no answer within 240 s from kumi.systems". */
    static final ThreadLocal<String> OVERPASS_WHY = new ThreadLocal<>();

    static String overpassPost(String url, String query) {
        String host = CareerSources.host(url);
        try {
            // the official Overpass servers answer 406 to a browser-like user agent ("Mozilla/5.0 (compatible; …)", measured 2026-10-31) and 200 to a plain program's name
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(240)).header("User-Agent", OVERPASS_UA).header("Accept", "application/json")
                    .header("Content-Type", "text/plain; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).version(HttpClient.Version.HTTP_1_1).build().send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) { why("HTTP " + resp.statusCode() + " from " + host); return null; }
            return resp.body();
        } catch (HttpTimeoutException e) { why("no answer within 240 s from " + host); return null;
        } catch (Exception e) { why(e.getClass().getSimpleName() + " from " + host); return null; }
    }

    /** One more reason the map gave nothing, kept with the others for the log. */
    static void why(String reason) { String w = OVERPASS_WHY.get(); OVERPASS_WHY.set(w == null || w.isEmpty() ? reason : w + "; " + reason); }

    /**
     * OpenStreetMap's places of a kind in a town, through the Overpass API: the named ones, those with a website first, then those of a
     * brand or an operator, kept with the library for a month. The map is as good as its mappers made it, and it is the one register of
     * restaurants, hotels, law offices, shops and the like that exists for the whole world and asks for no key; the official registers
     * of a country come first where the library has them. Read gently: one query at a time, a second apart, the main server then a mirror.
     */
    static final class MapPlaces implements Institutions {
        private String note = "";
        @Override public String name() { return "OpenStreetMap, the map's places (© OpenStreetMap contributors)"; }
        @Override public String country() { return "*"; }
        @Override public String address() { return BASE.get("overpass"); }
        @Override public List<String> kinds() { return new ArrayList<>(MAP_TAGS.keySet()); }
        @Override public boolean lastResort() { return true; }
        @Override public String note() { return note; }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            note = "";
            String[] area = areaOf(place);   // {the area id or "", the lat, the lon, the name the map gives}
            if (area == null) throw new IOException("the map does not know the place " + place);
            List<Employer> out = new ArrayList<>();
            List<String> totals = new ArrayList<>();
            for (String kind : kinds) {
                if (out.size() >= max) break;
                List<String> filters = MAP_TAGS.get(kind);
                if (filters == null) continue;
                StringBuilder q = new StringBuilder("[out:json][timeout:120];");
                if (!area[0].isEmpty()) q.append("area(").append(area[0]).append(")->.a;(");
                else q.append("(");
                for (String f : filters) q.append("nwr").append(f).append("[\"name\"]").append(area[0].isEmpty() ? "(around:15000," + area[1] + "," + area[2] + ")" : "(area.a)").append(';');
                q.append(");out tags ").append(MAP_PAGE + 1).append(';');
                OVERPASS_WHY.remove();
                String body = cachedQuery("osm-" + CareerSources.id(area[0] + "|" + area[1] + "|" + area[2] + "|" + kind) + ".json", q.toString());
                if (body == null) throw new IOException("the map did not answer for " + kind + " in " + place + (OVERPASS_WHY.get() == null ? "" : ": " + OVERPASS_WHY.get()));
                JsonNode elements = M.readTree(body).path("elements");
                List<JsonNode> named = new ArrayList<>(), rest = new ArrayList<>();
                int seen = 0;
                for (JsonNode e : elements) {
                    JsonNode t = e.path("tags");
                    if (t.path("name").asText("").isBlank()) continue;
                    seen++;
                    (site(t).isEmpty() ? rest : named).add(e);
                }
                rest.sort((a, b) -> Boolean.compare(b.path("tags").has("brand") || b.path("tags").has("operator"), a.path("tags").has("brand") || a.path("tags").has("operator")));
                named.addAll(rest);
                int before = out.size();
                for (JsonNode e : named) {
                    if (out.size() >= max) break;
                    JsonNode t = e.path("tags");
                    String nm = t.path("name:en").asText(t.path("name").asText("")).strip();
                    if (nm.isEmpty() || out.stream().anyMatch(x -> x.name().equalsIgnoreCase(nm))) continue;
                    String town = t.path("addr:city").asText(t.path("addr:town").asText(t.path("addr:suburb").asText(""))).strip();
                    String what = whatOnTheMap(kind, t);
                    String size = t.path("employees").asText("").isBlank() ? "" : t.path("employees").asText().strip() + " employees";
                    String url = site(t).isEmpty() ? "https://www.openstreetmap.org/" + e.path("type").asText("node") + "/" + e.path("id").asLong() : site(t);
                    out.add(new Employer(nm, town.isEmpty() ? area[3] : town, what, size, url));
                }
                totals.add((out.size() - before) + " of " + (seen > MAP_PAGE ? "more than " + MAP_PAGE : String.valueOf(seen)) + " named " + plural(kind) + " on the map");
            }
            note = String.join("; ", totals);
            return out;
        }

        /**
         * The place as the map knows it: the administrative area's id when the name is an area's, else its point, with the name the map
         * gives; kept a month. The place's whole words first ("Belo Horizonte, MG, Minas Gerais"), then the town alone when those give nothing.
         */
        String[] areaOf(String place) throws IOException {
            boolean answered = false;
            for (String q : List.of(place.strip(), town(place))) {
                if (q.isEmpty()) continue;
                String key = "osm-area-" + CareerSources.id(q.toLowerCase(Locale.ROOT)) + ".json";
                String body = cachedPlace(key, BASE.get("nominatim") + "/search?format=jsonv2&limit=5&q=" + enc(q));
                if (body == null) continue;
                answered = true;
                JsonNode first = null;
                for (JsonNode r : M.readTree(body)) {
                    String type = r.path("osm_type").asText(""), cls = r.path("category").asText(r.path("class").asText(""));
                    boolean areaLike = (type.equals("relation") || type.equals("way")) && (r.path("type").asText("").equals("administrative") || cls.equals("boundary") || cls.equals("place"));
                    if (areaLike) {
                        long id = r.path("osm_id").asLong(0) + (type.equals("relation") ? 3600000000L : 2400000000L);
                        return new String[]{String.valueOf(id), r.path("lat").asText(""), r.path("lon").asText(""), shortName(r)};
                    }
                    if (first == null && !r.path("lat").asText("").isEmpty()) first = r;
                }
                if (first != null) return new String[]{"", first.path("lat").asText(""), first.path("lon").asText(""), shortName(first)};
                if (q.equals(town(place))) break;
            }
            if (!answered) throw new IOException("the map's place search did not answer");
            return null;
        }

        /** The place search's answer, kept a month with the library. */
        static String cachedPlace(String name, String url) {
            try {
                LibraryStore st = STORE.get();
                Path dir = (st == null ? Config.home() : st.root().resolve("catalog")).resolve("career-cache"), f = dir.resolve(name);
                Files.createDirectories(dir);
                if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minus(Duration.ofDays(31)))) return Files.readString(f, StandardCharsets.UTF_8);
                String b = NOMINATIM.apply(url);
                if (b != null && b.strip().startsWith("[")) { Files.writeString(f, b, StandardCharsets.UTF_8); return b; }
                return null;
            } catch (IOException e) { return null; }
        }

        static String shortName(JsonNode r) { String n = r.path("name").asText(""); return n.isEmpty() ? r.path("display_name").asText("").split(",")[0].strip() : n; }

        /** A query's answer kept a month: the main server, then the mirror, each tried twice, a second between calls. */
        static String cachedQuery(String name, String query) {
            try {
                LibraryStore st = STORE.get();
                Path dir = (st == null ? Config.home() : st.root().resolve("catalog")).resolve("career-cache"), f = dir.resolve(name);
                Files.createDirectories(dir);
                if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minus(Duration.ofDays(31)))) return Files.readString(f, StandardCharsets.UTF_8);
                String body = null;
                synchronized (MAP_LOCK) {   // the two official instances, then two mirrors, once each: a server at capacity answers 502 or 504 at once, a busy one takes a minute or two; the official pair once more after a pause when every server turned the query away at once
                    List<String> servers = List.of(BASE.get("overpass"), BASE.get("overpass_2"), BASE.get("overpass_mirror"), BASE.get("overpass_mirror_2"), BASE.get("overpass"), BASE.get("overpass_2"));
                    for (int i = 0; i < servers.size() && body == null; i++) {
                        if (i == 4) { String w = OVERPASS_WHY.get(); if (w == null || !w.contains("HTTP 5")) break; try { Thread.sleep(8000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; } }
                        long wait = 1000 - (System.currentTimeMillis() - lastOverpass);
                        if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                        lastOverpass = System.currentTimeMillis();
                        body = OVERPASS.apply(servers.get(i), query);
                        if (body != null && !body.strip().startsWith("{")) { why("not JSON from " + CareerSources.host(servers.get(i))); body = null; }
                    }
                }
                if (body != null) Files.writeString(f, body, StandardCharsets.UTF_8);
                return body;
            } catch (IOException e) { return null; }
        }

        static String site(JsonNode t) {
            String s = t.path("website").asText(t.path("contact:website").asText(t.path("url").asText(""))).strip();
            if (s.isEmpty()) return "";
            return s.matches("(?i)https?://.*") ? s : "https://" + s;
        }

        /** What the record is, in words: "a restaurant, Chinese", "a hotel of the Taj brand", "a law office". */
        static String whatOnTheMap(String kind, JsonNode t) {
            String base = switch (kind) {
                case "restaurant" -> { String a = t.path("amenity").asText(""); yield a.equals("cafe") ? "a café" : a.equals("fast_food") ? "a fast-food place" : a.equals("pub") ? "a pub" : a.equals("bar") ? "a bar" : "a restaurant"; }
                case "hotel" -> { String a = t.path("tourism").asText(""); yield a.equals("hostel") ? "a hostel" : a.equals("guest_house") ? "a guest house" : a.equals("resort") ? "a resort" : "a hotel"; }
                case "GP practice" -> "a clinic"; case "law office" -> t.path("office").asText("").equals("notary") ? "a notary's office" : "a law office";
                case "accountancy" -> "an accountancy"; case "architecture practice" -> "an architects' practice"; case "estate agency" -> "an estate agency"; case "insurance office" -> "an insurance office";
                case "shop" -> "a shop" + (t.path("shop").asText("").isEmpty() ? "" : " (" + t.path("shop").asText().replace('_', ' ') + ")");
                case "craft workshop" -> "a workshop" + (t.path("craft").asText("").isEmpty() ? "" : " (" + t.path("craft").asText().replace('_', ' ') + ")");
                case "public office" -> t.path("amenity").asText("").isEmpty() ? "a government office" : "a " + t.path("amenity").asText().replace('_', ' ');
                case "charity" -> "a " + t.path("office").asText("charity").replace("ngo", "non-governmental organisation");
                case "theatre" -> "a " + t.path("amenity").asText("theatre").replace('_', ' ');
                default -> (kind.matches("^[aeiou].*") ? "an " : "a ") + kind;
            };
            String cuisine = t.path("cuisine").asText("").replace('_', ' ').replace(';', ',');
            String brand = t.path("brand").asText(t.path("operator").asText("")).strip();
            return base + (cuisine.isEmpty() ? "" : ", " + cuisine) + (brand.isEmpty() ? "" : ", of " + brand);
        }

        static String plural(String kind) {
            return switch (kind) { case "restaurant" -> "restaurants, cafés and bars"; case "hotel" -> "hotels and hostels"; case "law office" -> "law offices"; case "shop" -> "shops"; case "gym" -> "gyms";
                case "charity" -> "charities"; case "library" -> "libraries"; case "pharmacy" -> "pharmacies"; case "accountancy" -> "accountancies"; case "estate agency" -> "estate agencies"; case "theatre" -> "theatres and cinemas";
                default -> kind + "s"; };
        }
    }

    /** The library whose cache a register's big file goes to, set while a read runs. */
    static final ThreadLocal<LibraryStore> STORE = new ThreadLocal<>();

    /** A register's big file, kept with the library for {@code days}: a state's schools, a state's municipalities, France's health establishments. */
    static String cached(String name, String url, int days) {
        try {
            LibraryStore st = STORE.get();
            Path dir = (st == null ? Config.home() : st.root().resolve("catalog")).resolve("career-cache"), f = dir.resolve(name);
            Files.createDirectories(dir);
            if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minus(Duration.ofDays(days)))) return Files.readString(f, StandardCharsets.UTF_8);
            String b = CareerSources.get(url);
            if (b != null) Files.writeString(f, b, StandardCharsets.UTF_8);
            return b != null ? b : (Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null);
        } catch (IOException e) { return null; }
    }

    /** Letters only, accents off, upper case: how a register's file writes a town, so "Bourg-en-Bresse" meets "BOURG EN BRESSE". */
    static String plainUpper(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
    }

    static String town(String place) { return place == null ? "" : place.split("[,;/]")[0].strip(); }

    // ---- the countries: names for the registers and for the sentences ----

    static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(Map.entry("FR", "France"), Map.entry("UK", "the United Kingdom"), Map.entry("JP", "Japan"), Map.entry("US", "the United States"), Map.entry("BR", "Brazil"),
            Map.entry("IN", "India"), Map.entry("DE", "Germany"), Map.entry("CA", "Canada"), Map.entry("AU", "Australia"), Map.entry("ES", "Spain"));
    /** The country as the universities list writes it. */
    static final Map<String, String> COUNTRY_UNI = Map.ofEntries(Map.entry("FR", "France"), Map.entry("UK", "United Kingdom"), Map.entry("JP", "Japan"), Map.entry("US", "United States"), Map.entry("BR", "Brazil"),
            Map.entry("IN", "India"), Map.entry("DE", "Germany"), Map.entry("CA", "Canada"), Map.entry("AU", "Australia"), Map.entry("ES", "Spain"));
    static String countryName(String code) { return COUNTRY_NAMES.getOrDefault(code, code); }

    /** The US states: name, abbreviation, the census FIPS code (the school directory is per state). */
    static final String[][] US_STATES = {{"alabama", "AL", "1"}, {"alaska", "AK", "2"}, {"arizona", "AZ", "4"}, {"arkansas", "AR", "5"}, {"california", "CA", "6"}, {"colorado", "CO", "8"}, {"connecticut", "CT", "9"},
            {"delaware", "DE", "10"}, {"district of columbia", "DC", "11"}, {"florida", "FL", "12"}, {"georgia", "GA", "13"}, {"hawaii", "HI", "15"}, {"idaho", "ID", "16"}, {"illinois", "IL", "17"}, {"indiana", "IN", "18"},
            {"iowa", "IA", "19"}, {"kansas", "KS", "20"}, {"kentucky", "KY", "21"}, {"louisiana", "LA", "22"}, {"maine", "ME", "23"}, {"maryland", "MD", "24"}, {"massachusetts", "MA", "25"}, {"michigan", "MI", "26"},
            {"minnesota", "MN", "27"}, {"mississippi", "MS", "28"}, {"missouri", "MO", "29"}, {"montana", "MT", "30"}, {"nebraska", "NE", "31"}, {"nevada", "NV", "32"}, {"new hampshire", "NH", "33"}, {"new jersey", "NJ", "34"},
            {"new mexico", "NM", "35"}, {"new york", "NY", "36"}, {"north carolina", "NC", "37"}, {"north dakota", "ND", "38"}, {"ohio", "OH", "39"}, {"oklahoma", "OK", "40"}, {"oregon", "OR", "41"}, {"pennsylvania", "PA", "42"},
            {"rhode island", "RI", "44"}, {"south carolina", "SC", "45"}, {"south dakota", "SD", "46"}, {"tennessee", "TN", "47"}, {"texas", "TX", "48"}, {"utah", "UT", "49"}, {"vermont", "VT", "50"}, {"virginia", "VA", "51"},
            {"washington", "WA", "53"}, {"west virginia", "WV", "54"}, {"wisconsin", "WI", "55"}, {"wyoming", "WY", "56"}};
    /** The state a US place names, as [name, abbreviation, fips]; null when none: "Austin, Texas" and "Austin, TX" both give Texas. */
    static String[] usState(String place) {
        String low = place == null ? "" : place.toLowerCase(Locale.ROOT);
        String[] parts = low.split("[,;/]");
        for (String[] st : US_STATES) {
            for (String p : parts) { String w = p.strip(); if (w.equals(st[0]) || w.equalsIgnoreCase(st[1])) return st; }
            if (low.matches(".*\\b" + Pattern.quote(st[0]) + "\\b.*")) return st;
        }
        return null;
    }

    /** The Brazilian states: name, abbreviation. */
    static final String[][] BR_STATES = {{"acre", "AC"}, {"alagoas", "AL"}, {"amapá", "AP"}, {"amapa", "AP"}, {"amazonas", "AM"}, {"bahia", "BA"}, {"ceará", "CE"}, {"ceara", "CE"}, {"distrito federal", "DF"}, {"espírito santo", "ES"},
            {"espirito santo", "ES"}, {"goiás", "GO"}, {"goias", "GO"}, {"maranhão", "MA"}, {"maranhao", "MA"}, {"mato grosso", "MT"}, {"mato grosso do sul", "MS"}, {"minas gerais", "MG"}, {"pará", "PA"}, {"paraíba", "PB"}, {"paraiba", "PB"},
            {"paraná", "PR"}, {"parana", "PR"}, {"pernambuco", "PE"}, {"piauí", "PI"}, {"piaui", "PI"}, {"rio de janeiro", "RJ"}, {"rio grande do norte", "RN"}, {"rio grande do sul", "RS"}, {"rondônia", "RO"}, {"rondonia", "RO"},
            {"roraima", "RR"}, {"santa catarina", "SC"}, {"são paulo", "SP"}, {"sao paulo", "SP"}, {"sergipe", "SE"}, {"tocantins", "TO"}};
    static String brState(String place) {
        String low = place == null ? "" : place.toLowerCase(Locale.ROOT);
        String[] parts = low.split("[,;/]");
        for (String[] st : BR_STATES) {
            for (String p : parts) { String w = p.strip(); if (w.equals(st[0]) || w.equalsIgnoreCase(st[1])) return st[1]; }
            if (low.matches(".*\\b" + Pattern.quote(st[0]) + "\\b.*")) return st[1];
        }
        return "";
    }

    // ---- the United States: hospitals, nursing homes and home health agencies, from the Medicare lists (CMS), and the public schools (NCES) ----

    static final class UsHealth implements Institutions {
        @Override public String name() { return "the US hospital and care lists (Medicare)"; }
        @Override public String country() { return "US"; }
        @Override public String address() { return BASE.get("cms") + "/provider-data/api/1/datastore/query"; }
        @Override public List<String> kinds() { return List.of("hospital", "nursing home", "home health"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place); String[] st = usState(place);
            if (town.isEmpty()) throw new IOException("a town is needed");
            List<Employer> out = new ArrayList<>();
            for (String kind : kinds) {
                if (out.size() >= max) break;
                String ds = switch (kind) { case "hospital" -> "xubh-q36u"; case "nursing home" -> "4pq5-n9py"; default -> "6jpm-sxkc"; };
                String url = address() + "/" + ds + "/0?limit=" + Math.min(100, max) + "&conditions%5B0%5D%5Bproperty%5D=citytown&conditions%5B0%5D%5Bvalue%5D=" + enc(town.toUpperCase(Locale.ROOT)) + "&conditions%5B0%5D%5Boperator%5D=%3D"
                        + (st == null ? "" : "&conditions%5B1%5D%5Bproperty%5D=state&conditions%5B1%5D%5Bvalue%5D=" + st[1] + "&conditions%5B1%5D%5Boperator%5D=%3D");
                String body = CareerSources.get(url);
                if (body == null) throw new IOException("no answer for the " + kind + " list");
                JsonNode d = M.readTree(body);
                if (d.has("message") && !d.has("results")) throw new IOException(d.path("message").asText());
                for (JsonNode r : d.path("results")) {
                    if (out.size() >= max) break;
                    String nm = titleEn(r.path("facility_name").asText(r.path("provider_name").asText("")).replaceAll("\\s+", " ").strip());
                    if (nm.isEmpty()) continue;
                    String city = title(r.path("citytown").asText("")) + (r.path("state").asText("").isEmpty() ? "" : ", " + r.path("state").asText());
                    String what, size = "", id;
                    switch (kind) {
                        case "hospital" -> { what = r.path("hospital_type").asText("").toLowerCase(Locale.ROOT) + own(r.path("hospital_ownership").asText("")) + ("Yes".equalsIgnoreCase(r.path("emergency_services").asText("")) ? ", with an emergency department" : ""); id = "hospital/" + r.path("facility_id").asText(""); }
                        case "nursing home" -> { what = "a nursing home" + own(r.path("ownership_type").asText("")); String beds = r.path("number_of_certified_beds").asText(""); size = beds.isEmpty() ? "" : beds + " certified beds"; id = "nursing-home/" + r.path("cms_certification_number_ccn").asText(""); }
                        default -> { what = "a home health agency" + own(r.path("type_of_ownership").asText("")); id = "home-health/" + r.path("cms_certification_number_ccn").asText(""); }
                    }
                    out.add(new Employer(nm, city, what, size, id.endsWith("/") ? "" : "https://www.medicare.gov/care-compare/details/" + id));
                }
            }
            return out;
        }
        static String own(String o) {
            if (o == null || o.isBlank()) return "";
            String low = o.strip().toLowerCase(Locale.ROOT).replace("voluntary non-profit - ", "non-profit, ").replace("non profit - ", "non-profit, ").replace("for profit - ", "for profit, ").replace("government - ", "run by the ").replace("proprietary", "for profit");
            return ", " + low;
        }
    }

    static String lower(String s) { return s == null ? "" : s.strip().isEmpty() ? "" : Character.toLowerCase(s.strip().charAt(0)) + s.strip().substring(1); }
    /** Title case for an English name: no capital after a possessive apostrophe ("St David's Medical Center"). */
    static String titleEn(String s) { return title(s).replaceAll("'S\\b", "'s"); }

    static final class UsSchools implements Institutions {
        @Override public String name() { return "the US public school directory (NCES)"; }
        @Override public String country() { return "US"; }
        @Override public String address() { return BASE.get("urban") + "/api/v1/schools/ccd/directory"; }
        @Override public List<String> kinds() { return List.of("school"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place); String[] st = usState(place);
            if (st == null) throw new IOException("the school directory is read by state, and the place names none: say the state with the town");
            int year = LocalDate.now().getYear() - 2;   // the directory runs two school years behind
            String body = cached("us-schools-" + st[1] + "-" + year + ".json", address() + "/" + year + "/?fips=" + st[2], 30);
            if (body == null) throw new IOException("no answer for " + st[0]);
            JsonNode d = M.readTree(body);
            String want = town.toUpperCase(Locale.ROOT);
            List<Employer> out = new ArrayList<>();
            for (JsonNode r : d.path("results")) {
                if (out.size() >= max) break;
                if (!want.equals(r.path("city_location").asText("").toUpperCase(Locale.ROOT).strip())) continue;
                if (r.path("school_status").asInt(1) != 1 && r.path("school_status").asInt(1) != 3) continue;   // open, or reopened
                String nm = titleEn(r.path("school_name").asText("")); if (nm.isEmpty()) continue;
                String level = switch (r.path("school_level").asInt(0)) { case 1 -> "a primary school"; case 2 -> "a middle school"; case 3 -> "a high school"; default -> "a school"; };
                String type = switch (r.path("school_type").asInt(1)) { case 2 -> ", special education"; case 3 -> ", career and technical"; case 4 -> ", alternative"; default -> ""; };
                String lea = titleEn(r.path("lea_name").asText(""));
                int enrolled = r.path("enrollment").asInt(0);
                out.add(new Employer(nm, title(r.path("city_location").asText("")) + ", " + st[1], level + type + (r.path("charter").asInt(0) == 1 ? ", a charter school" : "") + (lea.isEmpty() ? "" : ", of " + lea), enrolled > 0 ? enrolled + " pupils" : "",
                        "https://nces.ed.gov/ccd/schoolsearch/school_detail.asp?ID=" + r.path("ncessch").asText("")));
            }
            return out;
        }
    }

    // ---- England: the NHS organisation directory (ODS), by the postcode area of the town ----

    static final Map<String, String> UK_ROLES = Map.ofEntries(Map.entry("hospital", "RO197,RO172"), Map.entry("GP practice", "RO177"), Map.entry("pharmacy", "RO182"), Map.entry("dental practice", "RO110"));

    static final class UkHealth implements Institutions {
        @Override public String name() { return "the NHS organisation directory (ODS)"; }
        @Override public String country() { return "UK"; }
        @Override public String address() { return BASE.get("ods") + "/ORD/2-0-0/organisations"; }
        @Override public List<String> kinds() { return List.of("hospital", "GP practice", "pharmacy", "dental practice"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place);
            String pl = CareerSources.get(BASE.get("postcodes") + "/places?q=" + enc(town) + "&limit=3");
            JsonNode p = pl == null ? M.createObjectNode() : M.readTree(pl);
            String outcode = "";
            for (JsonNode r : p.path("result")) if (r.path("name_1").asText("").equalsIgnoreCase(town)) { outcode = r.path("outcode").asText(""); break; }
            if (outcode.isEmpty() && p.path("result").size() > 0) outcode = p.path("result").get(0).path("outcode").asText("");
            if (outcode.isEmpty()) throw new IOException("the postcode area of " + town + " is not known");
            String area = outcode.replaceAll("[^A-Z].*$", "");
            List<String> roles = new ArrayList<>(); for (String k : kinds) if (UK_ROLES.containsKey(k)) roles.add(UK_ROLES.get(k));
            if (roles.isEmpty()) return List.of();
            Map<String, Employer> out = new LinkedHashMap<>();
            for (int digit = 1; digit <= 9 && out.size() < max; digit++) {
                String body = CareerSources.get(address() + "?PostCode=" + area + digit + "&Roles=" + String.join(",", roles) + "&Status=Active&Limit=100", Map.of("Accept", "application/json"));
                if (body == null) continue;
                JsonNode d = M.readTree(body);
                for (JsonNode o : d.path("Organisations")) {
                    if (out.size() >= max) break;
                    String nm = titleEn(o.path("Name").asText("")); if (nm.isEmpty() || out.containsKey(nm)) continue;
                    out.put(nm, new Employer(nm, o.path("PostCode").asText(""), o.path("PrimaryRoleDescription").asText("").toLowerCase(Locale.ROOT).replace("nhs", "NHS").replace("gp ", "GP "), "", o.path("OrgLink").asText("")));
                }
            }
            return new ArrayList<>(out.values());
        }
    }

    // ---- Brazil: the health establishments (CNES), by municipality and type ----

    static final Map<String, String> BR_TYPES = Map.ofEntries(Map.entry("hospital", "5,7,20,21,62"), Map.entry("GP practice", "1,2,4,36"), Map.entry("home health", "77"), Map.entry("pharmacy", "43"), Map.entry("research institute", "80,67"));
    static final Map<String, String> BR_TYPE_NAMES = Map.ofEntries(Map.entry("5", "a general hospital"), Map.entry("7", "a specialised hospital"), Map.entry("20", "a general emergency unit"), Map.entry("21", "a specialised emergency unit"), Map.entry("62", "a day hospital"),
            Map.entry("1", "a health post"), Map.entry("2", "a basic health unit"), Map.entry("4", "a polyclinic"), Map.entry("36", "a clinic or specialist centre"), Map.entry("77", "a home care service"), Map.entry("43", "a pharmacy"), Map.entry("80", "a public health laboratory"), Map.entry("67", "a central public health laboratory"));

    static final class BrazilHealth implements Institutions {
        @Override public String name() { return "Brazil's register of health establishments (CNES)"; }
        @Override public String country() { return "BR"; }
        @Override public String address() { return BASE.get("cnes") + "/cnes/estabelecimentos"; }
        @Override public List<String> kinds() { return List.of("hospital", "GP practice", "home health", "pharmacy", "research institute"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place), uf = brState(place);
            if (uf.isEmpty()) throw new IOException("the register is read by municipality, and the place names no state: say the state with the town (Belo Horizonte, MG)");
            String list = cached("br-municipios-" + uf + ".json", BASE.get("brasilapi") + "/api/ibge/municipios/v1/" + uf + "?providers=dados-abertos-br,gov,wikipedia", 30);
            if (list == null) throw new IOException("the list of municipalities of " + uf + " did not come");
            String code = "", want = plainUpper(town);
            for (JsonNode m : M.readTree(list)) if (plainUpper(m.path("nome").asText("")).equals(want)) { code = m.path("codigo_ibge").asText(""); break; }
            if (code.length() < 6) throw new IOException("no municipality called " + town + " in " + uf);
            String municipio = code.substring(0, 6);
            Map<String, Employer> out = new LinkedHashMap<>();
            for (String kind : kinds) {
                String types = BR_TYPES.get(kind); if (types == null) continue;
                for (String t : types.split(",")) {
                    if (out.size() >= max) break;
                    String body = CareerSources.get(address() + "?codigo_municipio=" + municipio + "&codigo_tipo_unidade=" + t + "&limit=" + Math.min(100, max) + "&offset=0");
                    if (body == null) continue;
                    for (JsonNode e : M.readTree(body).path("estabelecimentos")) {
                        if (out.size() >= max) break;
                        String nm = title(e.path("nome_fantasia").asText(e.path("nome_razao_social").asText("")).strip()); if (nm.isEmpty() || out.containsKey(nm)) continue;
                        String sphere = e.path("descricao_esfera_administrativa").asText("").toLowerCase(Locale.ROOT);
                        out.put(nm, new Employer(nm, title(town) + ", " + uf, BR_TYPE_NAMES.getOrDefault(t, "a health establishment") + (sphere.isEmpty() ? "" : ", " + sphere + " administration"), "", address() + "/" + e.path("codigo_cnes").asText("")));
                    }
                }
            }
            return new ArrayList<>(out.values());
        }
    }

    // ---- France: the health and social establishments (FINESS), the schools, and higher education and research ----

    static final class FranceHealth implements Institutions {
        @Override public String name() { return "France's register of health and care establishments (FINESS)"; }
        @Override public String country() { return "FR"; }
        @Override public String address() { return BASE.get("datagouv") + "/fr/datasets/r/2ce43ade-8d2c-4d1d-81da-ca06c82abc68"; }
        @Override public List<String> kinds() { return List.of("hospital", "nursing home", "home health", "pharmacy", "GP practice", "research institute"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place);
            String file = cached("fr-finess.csv", address(), 30);
            if (file == null) throw new IOException("the FINESS file did not come");
            String want = plainUpper(town);
            List<String[]> inTown = new ArrayList<>();
            Set<String> categories = new LinkedHashSet<>();
            for (String line : file.split("\n")) {
                if (!line.startsWith("structureet;")) continue;
                String[] c = line.split(";", -1);
                if (c.length < 28) continue;
                String where = plainUpper(c[15].replaceFirst("^\\d{5}\\s*", "").replaceAll("\\bCEDEX.*$", ""));
                if (!where.equals(want) && !where.startsWith(want + " ")) continue;
                inTown.add(c); categories.add(c[21]);
            }
            if (inTown.isEmpty()) return List.of();
            List<String> chosen = frCategories(new ArrayList<>(categories), kinds, occupation);
            List<Employer> out = new ArrayList<>();
            for (String[] c : inTown) {
                if (out.size() >= max) break;
                if (!chosen.contains(c[21])) continue;
                String nm = title(c[4].isEmpty() ? c[3] : c[4]); if (nm.isEmpty()) continue;
                String status = c[27].isEmpty() ? "" : ", " + c[27].toLowerCase(Locale.ROOT);
                out.add(new Employer(nm, title(town), c[19] + status, "", c[22].matches("\\d{14}") ? "https://annuaire-entreprises.data.gouv.fr/etablissement/" + c[22] : ""));
            }
            return out;
        }
    }

    /** The FINESS categories (as the file names them) that hold such employers, chosen by the model among those present in the town; the ones whose name carries the kind's own word when it names none. */
    static List<String> frCategories(List<String> present, List<String> kinds, String occupation) {
        if (present.isEmpty()) return List.of();
        StringBuilder list = new StringBuilder(); for (String p : present) list.append("- ").append(p).append('\n');
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", "A person is looking for work" + (occupation == null || occupation.isEmpty() ? "" : " as " + occupation) + ", at " + String.join(" or ", kinds) + ". "
                + "France's register of health and care establishments files each one under a category, listed below as the register names them (in French). Which categories hold such employers? Choose from the list only, "
                + "the likeliest first, at most four. Answer with a JSON array of the categories' names, exactly as written, and nothing else.\n\n" + list);
        String said;
        try { said = Career.DRIVE.get().classify(msgs, 200); } catch (RuntimeException e) { said = ""; }
        List<String> out = new ArrayList<>();
        for (JsonNode n : Career.parseArray(said)) { String c = n.asText("").strip(); if (present.contains(c) && !out.contains(c) && out.size() < 4) out.add(c); }
        if (out.isEmpty()) for (String p : present) { String low = p.toLowerCase(Locale.ROOT); if (kinds.contains("hospital") && (low.contains("hospitalier") || low.contains("soins")) || kinds.contains("nursing home") && low.contains("personnes") || kinds.contains("home health") && low.contains("domicile") || kinds.contains("pharmacy") && low.contains("pharmac")) out.add(p); }
        return out;
    }

    static final class FranceSchools implements Institutions {
        @Override public String name() { return "France's school directory (annuaire de l'éducation)"; }
        @Override public String country() { return "FR"; }
        @Override public String address() { return BASE.get("education_fr") + "/api/explore/v2.1/catalog/datasets/fr-en-annuaire-education/records"; }
        @Override public List<String> kinds() { return List.of("school"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place);
            String body = CareerSources.get(address() + "?where=" + enc("nom_commune=\"" + town.replace("\"", "") + "\" and etat=\"OUVERT\"") + "&limit=" + Math.min(100, max) + "&select=" + enc("nom_etablissement,libelle_nature,statut_public_prive,nom_commune,web,identifiant_de_l_etablissement"));
            if (body == null) throw new IOException("no answer");
            JsonNode d = M.readTree(body);
            if (d.has("error_code")) throw new IOException(d.path("message").asText());
            List<Employer> out = new ArrayList<>();
            for (JsonNode r : d.path("results")) {
                if (out.size() >= max) break;
                String nm = r.path("nom_etablissement").asText("").strip(); if (nm.isEmpty()) continue;
                String nature = lower(title(r.path("libelle_nature").asText(""))), status = lower(r.path("statut_public_prive").asText(""));
                String uai = r.path("identifiant_de_l_etablissement").asText("");
                out.add(new Employer(nm, r.path("nom_commune").asText(""), nature + (status.isEmpty() ? "" : ", " + status), "", r.path("web").isTextual() && !r.path("web").asText("").isEmpty() ? r.path("web").asText() : uai.isEmpty() ? "" : BASE.get("education_fr") + "/explore/dataset/fr-en-annuaire-education/table/?q=" + uai));
            }
            return out;
        }
    }

    static final class FranceHigher implements Institutions {
        @Override public String name() { return "France's list of higher education and research institutions"; }
        @Override public String country() { return "FR"; }
        @Override public String address() { return BASE.get("esr_fr") + "/api/explore/v2.1/catalog/datasets/fr-esr-principaux-etablissements-enseignement-superieur/records"; }
        @Override public List<String> kinds() { return List.of("university", "research institute"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String town = town(place);
            String body = CareerSources.get(address() + "?where=" + enc("com_nom like \"" + town.replace("\"", "") + "\"") + "&limit=" + Math.min(100, max) + "&select=" + enc("uo_lib,type_d_etablissement,com_nom,url"));
            if (body == null) throw new IOException("no answer");
            JsonNode d = M.readTree(body);
            if (d.has("error_code")) throw new IOException(d.path("message").asText());
            List<Employer> out = new ArrayList<>();
            for (JsonNode r : d.path("results")) {
                if (out.size() >= max) break;
                String nm = r.path("uo_lib").asText("").strip(); if (nm.isEmpty()) continue;
                List<String> types = new ArrayList<>(); for (JsonNode t : r.path("type_d_etablissement")) types.add(lower(t.asText("")));
                out.add(new Employer(nm, r.path("com_nom").asText(""), types.isEmpty() ? "" : String.join(", ", types), "", r.path("url").asText("")));
            }
            return out;
        }
    }

    // ---- the universities of the world (the Hipolabs list), by country, the town's own first ----

    static final class Universities implements Institutions {
        @Override public String name() { return "the world list of universities"; }
        @Override public String country() { return "*"; }
        @Override public String address() { return BASE.get("universities") + "/search"; }
        @Override public List<String> kinds() { return List.of("university"); }
        @Override public List<Employer> read(String place, List<String> kinds, String occupation, int max) throws IOException {
            String country = countryOf(place), town = town(place);
            if (!COUNTRY_UNI.containsKey(country)) throw new IOException("the list is read by country, and the place names none the library knows");
            String body = cached("universities-" + country + ".json", address() + "?country=" + enc(COUNTRY_UNI.get(country)), 30);
            if (body == null) throw new IOException("no answer");
            String want = plainUpper(town);
            Set<String> regions = new LinkedHashSet<>();   // the parts of the place that are neither the town nor the country: the state or the province
            for (String part : place.split("[,;/]")) { String p = plainUpper(part); if (!p.isEmpty() && !p.equals(want) && !COUNTRY_WORDS.containsKey(part.strip().toLowerCase(Locale.ROOT))) regions.add(p); }
            List<Employer> inTown = new ArrayList<>(), inRegion = new ArrayList<>();
            for (JsonNode u : M.readTree(body)) {
                String nm = u.path("name").asText("").strip(); if (nm.isEmpty()) continue;
                String prov = u.path("state-province").isTextual() ? u.path("state-province").asText("") : "", web = u.path("web_pages").isArray() && u.path("web_pages").size() > 0 ? u.path("web_pages").get(0).asText("") : "";
                Employer e = new Employer(nm, prov.isEmpty() ? COUNTRY_NAMES.get(country) : prov, "a university", "", web);
                if (!want.isEmpty() && plainUpper(nm).contains(want)) inTown.add(e);
                else if (!prov.isEmpty() && regions.contains(plainUpper(prov))) inRegion.add(e);
            }
            List<Employer> out = new ArrayList<>(inTown);
            for (Employer e : inRegion) { if (out.size() >= max) break; out.add(e); }
            return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
        }
    }
}

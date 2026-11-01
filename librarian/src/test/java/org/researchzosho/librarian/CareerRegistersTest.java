package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registers by jurisdiction against stand-in answers: nothing here reaches the web, the box's settings or a model. */
class CareerRegistersTest {
    Function<String, String> savedRaw; BiFunction<String, Map<String, String>, String> savedHeaded; BiFunction<String, String, String> savedOverpass; Function<String, String> savedNominatim; String realHome;
    final List<String> urls = new ArrayList<>(), prompts = new ArrayList<>(), overpass = new ArrayList<>();
    volatile String overpassAnswer;
    final List<Map<String, String>> headers = new ArrayList<>();

    final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText(); prompts.add(p);
            if (p.startsWith("Which country is the place below in?")) return p.contains("Lyon") ? "{\"country\": \"FR\"}" : p.contains("Osaka") ? "{\"country\": \"JP\"}" : "{\"country\": \"other\"}";
            if (p.contains("Which sections are the employers who hire for that work filed under?")) return "[\"J\", \"M\", \"Z\", \"J\"]";
            if (p.contains("Which codes would the employers who hire for that work be filed under?")) return "[\"62012\", \"62020\", \"99999\"]";
            if (p.contains("Give up to six Japanese words")) return "[\"ソフトウェア\", \"情報\"]";
            if (p.contains("Which of them employ people for that work?")) return p.contains("registered nurse") ? "[\"hospital\", \"nursing home\", \"spaceport\"]" : "[]";
            if (p.contains("Which categories hold such employers?")) return "[\"Centres Hospitaliers Régionaux\", \"Centres Hospitaliers\", \"Autres Etablissements de Soins Pluridisciplinaires\", \"Centres Hospitaliers\"]";
            return "[]";
        }
    }

    static final String FR_PAGE = "{\"results\":[{\"siren\":\"111222333\",\"nom_complet\":\"LOGICIELS DU RHONE\",\"activite_principale\":\"62.01Z\",\"section_activite_principale\":\"J\",\"tranche_effectif_salarie\":\"21\","
            + "\"siege\":{\"libelle_commune\":\"PARIS\",\"commune\":\"75108\"},\"matching_etablissements\":[{\"commune\":\"69266\",\"libelle_commune\":\"VILLEURBANNE\",\"activite_principale\":\"62.01Z\"}]},"
            + "{\"siren\":\"444555666\",\"nom_complet\":\"GRAND BUREAU D'ETUDES\",\"activite_principale\":\"71.12B\",\"section_activite_principale\":\"M\",\"tranche_effectif_salarie\":\"53\",\"siege\":{\"libelle_commune\":\"LYON 3EME\",\"commune\":\"69383\"},\"matching_etablissements\":[]}],"
            + "\"total_results\":2,\"total_pages\":1,\"page\":1}";
    static final String UK_ROW = "<tr class=\"govuk-table__row\"><td class=\"govuk-table__cell\"><h2 class=\"govuk-heading-m\"><a class=\"govuk-link\" href=/company/%s target=\"_blank\">%s<span class=\"govuk-visually-hidden\">(link opens a new window)</span></a></h2>"
            + "<p><span class=\"govuk-body\">Active</span><p><ul class=\"govuk-list\"><li>Private limited company</li><li></li><li>%s - Incorporated on 17 March 2021</li><li></li><li>%s</li><li>SIC codes - %s</li></ul></td></tr>";

    @BeforeEach void stub(@TempDir Path tmp) {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        savedRaw = CareerSources.RAW; savedHeaded = CareerSources.RAW_HEADED; CareerSources.POLITE_MS = 0; Career.DRIVE = StandIn::new;
        savedOverpass = CareerRegisters.OVERPASS; overpassAnswer = "{\"elements\":[]}"; savedNominatim = CareerRegisters.NOMINATIM;
        CareerRegisters.OVERPASS = (u, q) -> { overpass.add(u + "\n" + q); return overpassAnswer; };
        CareerRegisters.NOMINATIM = url -> { urls.add(url); return url.contains("q=Nowhere") ? "[]" : "[{\"osm_type\":\"node\",\"osm_id\":1,\"lat\":\"18.52\",\"lon\":\"73.86\",\"category\":\"place\",\"type\":\"city\",\"name\":\"Pune\",\"display_name\":\"Pune, Maharashtra, India\"},"
                + "{\"osm_type\":\"relation\",\"osm_id\":1986140,\"lat\":\"18.52\",\"lon\":\"73.86\",\"category\":\"boundary\",\"type\":\"administrative\",\"name\":\"Pune\",\"display_name\":\"Pune, Maharashtra, India\"}]"; };
        CareerSources.RAW = url -> {
            urls.add(url);
            if (url.startsWith(CareerRegisters.BASE.get("geo"))) return url.contains("nom=Lyon") ? "[{\"code\":\"69123\",\"nom\":\"Lyon\",\"codeDepartement\":\"69\"}]" : "[]";
            if (url.startsWith(CareerRegisters.BASE.get("sirene"))) return url.contains("departement=69") && url.contains("section_activite_principale=J%2CM") ? FR_PAGE : "{\"results\":[],\"total_results\":0,\"total_pages\":0}";
            if (url.startsWith(CareerRegisters.BASE.get("cms"))) {
                if (url.contains("/xubh-q36u/")) return url.contains("citytown&conditions%5B0%5D%5Bvalue%5D=AUSTIN") && url.contains("state&conditions%5B1%5D%5Bvalue%5D=TX") ? "{\"results\":[{\"facility_id\":\"450056\",\"facility_name\":\"ASCENSION SETON MEDICAL CENTER AUSTIN\",\"citytown\":\"AUSTIN\",\"state\":\"TX\",\"hospital_type\":\"Acute Care Hospitals\",\"hospital_ownership\":\"Voluntary non-profit - Private\",\"emergency_services\":\"Yes\"},"
                        + "{\"facility_id\":\"450431\",\"facility_name\":\"ST DAVID'S MEDICAL CENTER\",\"citytown\":\"AUSTIN\",\"state\":\"TX\",\"hospital_type\":\"Acute Care Hospitals\",\"hospital_ownership\":\"Proprietary\",\"emergency_services\":\"Yes\"}],\"count\":2}" : "{\"results\":[],\"count\":0}";
                if (url.contains("/4pq5-n9py/")) return "{\"results\":[{\"cms_certification_number_ccn\":\"455671\",\"provider_name\":\"Austin Wellness & Rehabilitation\",\"citytown\":\"AUSTIN\",\"state\":\"TX\",\"ownership_type\":\"For profit - Corporation\",\"number_of_certified_beds\":\"120\"}],\"count\":1}";
                return "{\"results\":[],\"count\":0}";
            }
            if (url.startsWith(CareerRegisters.BASE.get("urban"))) return url.contains("fips=48") ? "{\"count\":3,\"results\":[{\"ncessch\":\"480000000001\",\"school_name\":\"ROOSTER SPRINGS EL\",\"city_location\":\"AUSTIN\",\"school_level\":1,\"school_type\":1,\"school_status\":1,\"charter\":0,\"enrollment\":827,\"lea_name\":\"DRIPPING SPRINGS ISD\"},"
                    + "{\"ncessch\":\"480000000002\",\"school_name\":\"CROSBYTON EL\",\"city_location\":\"CROSBYTON\",\"school_level\":1,\"school_type\":1,\"school_status\":1,\"charter\":0,\"enrollment\":200,\"lea_name\":\"CROSBYTON CISD\"},"
                    + "{\"ncessch\":\"480000000003\",\"school_name\":\"AUSTIN HIGH SCHOOL\",\"city_location\":\"Austin\",\"school_level\":3,\"school_type\":1,\"school_status\":2,\"charter\":0,\"enrollment\":0,\"lea_name\":\"AUSTIN ISD\"}]}" : "{\"count\":0,\"results\":[]}";
            if (url.startsWith(CareerRegisters.BASE.get("postcodes"))) return "{\"status\":200,\"result\":[{\"name_1\":\"Manchester\",\"local_type\":\"City\",\"outcode\":\"M2\"}]}";
            if (url.startsWith(CareerRegisters.BASE.get("brasilapi"))) return "[{\"nome\":\"BELO HORIZONTE\",\"codigo_ibge\":\"3106200\"},{\"nome\":\"BETIM\",\"codigo_ibge\":\"3106705\"}]";
            if (url.startsWith(CareerRegisters.BASE.get("cnes"))) return url.contains("codigo_municipio=310620") && url.contains("codigo_tipo_unidade=5") ? "{\"estabelecimentos\":[{\"codigo_cnes\":26859,\"nome_fantasia\":\"HOSPITAL FELICIO ROCHO\",\"nome_razao_social\":\"FUNDACAO FELICE ROSSO\",\"descricao_esfera_administrativa\":\"MUNICIPAL\",\"codigo_tipo_unidade\":5}]}" : "{\"estabelecimentos\":[]}";
            if (url.startsWith(CareerRegisters.BASE.get("datagouv"))) return "finess;etalab;111;2026-05-12\nstructureet;690000245;690781810;HCL;HOSPICES CIVILS DE LYON;;;3;QUAI;DES CELESTINS;;;123;69;RHONE;69002 LYON;;;101;Centre Hospitalier Régional (C.H.R.);1101;Centres Hospitaliers Régionaux;26690027300016;8610Z;03;ARS;1;Etablissement public de santé;1901-01-01;;;\n"
                    + "structureet;690000500;690781900;CLINIQUE DU PARC;CLINIQUE DU PARC LYON;;;155;BD;STALINGRAD;;;266;69;RHONE;69006 LYON;;;365;Etablissement de Soins Pluridisciplinaire;1105;Autres Etablissements de Soins Pluridisciplinaires;12345678901234;8610Z;05;;3;Etablissement privé;;;;\n"
                    + "structureet;690000600;690781901;EHPAD LES LILAS;;;;1;R;DES LILAS;;;266;69;RHONE;69003 LYON;;;500;Etablissement d'hébergement pour personnes âgées dépendantes;4401;Etablissements d'Hébergement pour Personnes Agées;;8730A;;;;;;;;\n"
                    + "structureet;690000700;690781902;CH DE VILLEURBANNE;;;;1;R;DE LA GARE;;;266;69;RHONE;69100 VILLEURBANNE;;;355;Centre Hospitalier (C.H.);1102;Centres Hospitaliers;;8610Z;;;;;;;;\n";
            if (url.startsWith(CareerRegisters.BASE.get("education_fr"))) return "{\"total_count\":1,\"results\":[{\"nom_etablissement\":\"Collège Vendôme\",\"libelle_nature\":\"COLLEGE\",\"statut_public_prive\":\"Public\",\"nom_commune\":\"Lyon\",\"web\":\"https://vendome.example/\",\"identifiant_de_l_etablissement\":\"0692338S\"}]}";
            if (url.startsWith(CareerRegisters.BASE.get("esr_fr"))) return "{\"total_count\":1,\"results\":[{\"uo_lib\":\"École normale supérieure de Lyon\",\"type_d_etablissement\":[\"École\",\"Grand établissement\"],\"com_nom\":\"Lyon 7e\",\"url\":\"https://www.ens-lyon.example/\"}]}";
            if (url.startsWith(CareerRegisters.BASE.get("universities"))) return "[{\"name\":\"University of Pune\",\"state-province\":\"Maharashtra\",\"web_pages\":[\"http://www.unipune.example/\"]},{\"name\":\"Mumbai University\",\"state-province\":\"Maharashtra\",\"web_pages\":[\"http://mu.example/\"]},{\"name\":\"Delhi University\",\"state-province\":\"Delhi\",\"web_pages\":[]}]";
            if (url.startsWith(CareerRegisters.BASE.get("companies_house"))) {
                if (!url.contains("page=1")) return "<html><body><table><tbody class=\"govuk-table__body\"></tbody></table></body></html>";
                return "<html><body><p>2,826 results</p><table><tbody class=\"govuk-table__body\">" + String.format(UK_ROW, "13273297", "WITSMATE LIMITED", "13273297", "Mansion House, Manchester Road, Altrincham, Cheshire, England WA14 4RW", "62012")
                        + String.format(UK_ROW, "15156511", "HULEE &amp; SONS LIMITED", "15156511", "1 Deansgate, Manchester, England M3 1AZ", "62012, 62020") + "</tbody></table></body></html>";
            }
            return null;
        };
        CareerSources.RAW_HEADED = (url, h) -> {
            urls.add(url); headers.add(h);
            if (url.startsWith(CareerRegisters.BASE.get("companies_house_api"))) return "{\"items\":[{\"company_name\":\"NORTHERN CODE LTD\",\"company_number\":\"01234567\",\"registered_office_address\":{\"locality\":\"MANCHESTER\"},\"sic_codes\":[\"62012\"]}]}";
            if (url.startsWith(CareerRegisters.BASE.get("ods"))) return url.contains("PostCode=M1&") ? "{\"Organisations\":[{\"Name\":\"MANCHESTER UNIVERSITY NHS FOUNDATION TRUST\",\"OrgId\":\"R0A\",\"Status\":\"Active\",\"PostCode\":\"M13 9WL\",\"PrimaryRoleId\":\"RO197\",\"PrimaryRoleDescription\":\"NHS TRUST\",\"OrgLink\":\"https://directory.spineservices.nhs.uk/ORD/2-0-0/organisations/R0A\"}]}" : "{\"Organisations\":[]}";
            if (url.startsWith(CareerRegisters.BASE.get("gbizinfo"))) return "{\"hojin-infos\":[{\"corporate_number\":\"1120001000001\",\"name\":\"大阪ソフトウェア株式会社\",\"location\":\"大阪府大阪市北区1-1\",\"employee_number\":\"120\",\"business_summary\":\"業務用ソフトウェアの開発\"},"
                    + "{\"corporate_number\":\"1120001000002\",\"name\":\"浪速製パン株式会社\",\"location\":\"大阪府堺市1-1\",\"employee_number\":\"300\",\"business_summary\":\"パンの製造\"}]}";
            return null;
        };
    }

    @AfterEach void back() { CareerRegisters.OVERPASS = savedOverpass; CareerRegisters.NOMINATIM = savedNominatim; CareerSources.RAW = savedRaw; CareerSources.RAW_HEADED = savedHeaded; CareerSources.POLITE_MS = 1000; CareerSources.reset(); Career.DRIVE = Explain::drive; System.setProperty("user.home", realHome); Config.invalidate(); }

    @Test void theCountryComesFromThePlacesOwnWordsElseTheModelsChoiceAmongTheRegistersCountries() {
        assertEquals("FR", CareerRegisters.countryOf("Lyon, France")); assertEquals("UK", CareerRegisters.countryOf("Manchester, England")); assertEquals("JP", CareerRegisters.countryOf("大阪市, 日本"));
        assertTrue(prompts.isEmpty(), "the words said it: no model");
        assertEquals("FR", CareerRegisters.countryOf("Lyon")); assertEquals("US", CareerRegisters.countryOf("Austin, Texas")); assertEquals("BR", CareerRegisters.countryOf("Belo Horizonte, MG, Minas Gerais")); assertEquals("IN", CareerRegisters.countryOf("Pune, India"));
        assertEquals(1, prompts.size(), "a state names its country without the model");
        assertEquals("", CareerRegisters.countryOf("Pune"));
        assertEquals(2, prompts.size());
        assertEquals(List.of("J", "M"), CareerRegisters.sectionsFor("software engineer", "a company"), "only the register's own sections, once each");
    }

    @Test void franceIsReadByDepartementSectionAndSizeBandWithTheRecordOnEachEmployer(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found f = CareerRegisters.read(store, s, "Lyon", "software engineer", "a company", 10, SourceRules.load(store));
        assertEquals(List.of("LOGICIELS DU RHONE", "GRAND BUREAU D'ETUDES"), new ArrayList<>(f.employers().keySet()));
        assertEquals("S-0001\tthe French company register (S-0001), in Villeurbanne, filed under NAF 62.01Z (information and communication: software, IT services, telecoms, publishing, media), 50 to 99 people — https://annuaire-entreprises.data.gouv.fr/entreprise/111222333", f.employers().get("LOGICIELS DU RHONE"), "the establishment in the département, not the head office in Paris");
        assertTrue(f.employers().get("GRAND BUREAU D'ETUDES").contains("in Lyon 3eme, filed under NAF 71.12B") && f.employers().get("GRAND BUREAU D'ETUDES").contains("10,000 people or more"), f.employers().get("GRAND BUREAU D'ETUDES"));
        assertEquals(1, f.sources().size()); Sources.Source src = f.sources().get(0);
        assertEquals("register", src.kind()); assertEquals("employers", src.yields()); assertEquals("Lyon", src.place()); assertEquals("software engineer", src.subject()); assertEquals(2, src.yieldTotal());
        assertTrue(f.notes().get(0).startsWith("the French company register (S-0001) read for Lyon under J (information and communication"), f.notes().get(0));
        CareerRegisters.read(store, s, "Lyon", "software engineer", "a company", "developer tools", 10, SourceRules.load(store));
        assertTrue(prompts.stream().anyMatch(p -> p.contains("looking for work as software engineer, at a company, in developer tools.")), "the field they decided goes into the choice of sections");
        assertTrue(f.notes().get(0).endsWith(": 2 employers"), f.notes().get(0));
        assertTrue(urls.stream().anyMatch(u -> u.contains("tranche_effectif_salarie=12,21,22,31,32,41,42,51,52,53") && u.contains("etat_administratif=A")), "employers of twenty people or more, still in business: " + urls);
    }

    @Test void theUnitedKingdomIsReadFromCompaniesHousesSearchPageWithTheCodesTheModelChoseAmongTheSections(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found f = CareerRegisters.read(store, s, "Manchester, England", "software engineer", "", 10, SourceRules.load(store));
        assertEquals(List.of("Witsmate Limited", "Hulee & Sons Limited"), new ArrayList<>(f.employers().keySet()));
        assertEquals("S-0001\tCompanies House, the UK company register (S-0001), in Altrincham, filed under SIC 62012, private limited company — https://find-and-update.company-information.service.gov.uk/company/13273297", f.employers().get("Witsmate Limited"));
        assertTrue(f.employers().get("Hulee & Sons Limited").startsWith("S-0001\tCompanies House, the UK company register (S-0001), in Manchester, filed under SIC 62012, 62020"), f.employers().get("Hulee & Sons Limited"));
        assertTrue(urls.stream().anyMatch(u -> u.contains("registeredOfficeAddress=Manchester") && u.contains("sicCodes=62012&sicCodes=62020") && !u.contains("99999")), "the codes the model chose among the sections' own, repeated as the page takes them; a code not on the list dropped: " + urls);
        assertEquals("register", f.sources().get(0).kind());
        // with a key, the API instead of the page
        Config.set(CareerRegisters.COMPANIES_HOUSE_KEY, "k3y");
        CareerTargets.Found g = CareerRegisters.read(store, s, "Manchester, England", "software engineer", "", 10, SourceRules.load(store));
        assertEquals(List.of("Northern Code Ltd"), new ArrayList<>(g.employers().keySet()));
        assertTrue(g.employers().get("Northern Code Ltd").startsWith("S-0001\tCompanies House, the UK company register (S-0001), in Manchester, filed under SIC 62012 — "), g.employers().get("Northern Code Ltd"));
        assertTrue(headers.get(headers.size() - 1).get("Authorization").startsWith("Basic "));
    }

    @Test void japanNeedsItsTokenAndThenFiltersThePrefecturesCompaniesByTheirLineOfBusiness(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found f = CareerRegisters.read(store, s, "Osaka", "software engineer", "", 10, SourceRules.load(store));
        assertTrue(f.employers().isEmpty()); assertTrue(f.notes().get(0).startsWith("gBizINFO, Japan's company information is not read: a free gBizINFO API token"), f.notes().get(0));
        Config.set(CareerRegisters.GBIZINFO_TOKEN, "t0ken");
        CareerTargets.Found g = CareerRegisters.read(store, s, "Osaka", "software engineer", "", 10, SourceRules.load(store));
        assertEquals(List.of("大阪ソフトウェア株式会社"), new ArrayList<>(g.employers().keySet()), "the bakery's line of business carries none of the words");
        assertEquals("S-0001\tgBizINFO, Japan's company information (S-0001), in 大阪府 大阪市北区1-1, 業務用ソフトウェアの開発, 120 people — https://info.gbiz.go.jp/hojin/ichiran?hojinBango=1120001000001", g.employers().get("大阪ソフトウェア株式会社"));
        assertTrue(urls.stream().anyMatch(u -> u.contains("prefecture=27") && u.contains("employee_number_from=20")), urls.toString());
        assertEquals("t0ken", headers.get(headers.size() - 1).get("X-hojinInfo-api-token"));
    }

    @Test void aPlaceInACountryWithNoRegisterReaderIsSaidSoAndSmallHelpersHold(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found f = CareerRegisters.read(store, s, "Pune, India", "registered nurse", "a hospital", 10, SourceRules.load(store));
        assertTrue(f.employers().isEmpty(), "the map answered nothing for the stand-in: no employer");
        assertEquals(1, f.sources().size(), "the map was read for the hospitals, since India has no official register the library knows");
        assertEquals(1, f.notes().size()); assertTrue(f.notes().get(0).startsWith("OpenStreetMap, the map's places (© OpenStreetMap contributors) (S-") && f.notes().get(0).contains("read for Pune, India, hospital: 0 employers, 0 of 0 named hospitals on the map"), f.notes().get(0));
        assertEquals("Altrincham", CareerRegisters.ukTown("Mansion House, Manchester Road, Altrincham, Cheshire, England WA14 4RW", "Manchester"));
        assertEquals("Manchester", CareerRegisters.ukTown("", "Manchester")); assertEquals("Manchester", CareerRegisters.ukTown("1 Deansgate, Manchester, England M3 1AZ", "Leeds"));
        assertEquals("Salford", CareerRegisters.ukTown("Unit 4, Trafford Park, Salford, Greater Manchester, England M17 1AA", "Manchester")); assertEquals("Bury", CareerRegisters.ukTown("2 Mill Lane, Bury, Lancashire, England BL9 0AA", "Manchester"));
        assertEquals("Lyon 3eme", CareerRegisters.title("LYON 3EME")); assertEquals("Saint-Etienne", CareerRegisters.title("SAINT-ETIENNE"));
        assertEquals("27", CareerRegisters.prefectureOf("Osaka")); assertEquals("14", CareerRegisters.prefectureOf("横浜市")); assertEquals("", CareerRegisters.prefectureOf("Lyon"));
        assertTrue(CareerRegisters.ukCodes().size() > 700, "the SIC list shipped with the library");
        assertTrue(CareerRegisters.isRegisterAddress("https://recherche-entreprises.api.gouv.fr/search?departement=69")); assertFalse(CareerRegisters.isRegisterAddress("https://cam.masstech.org/membership-information"));
        assertTrue(CareerTargets.media("https://www.youtube.com/watch?v=x")); assertFalse(CareerTargets.media("https://www.lyon-entreprises.com/"));
    }

    @Test void theKindsOfInstitutionComeFromTheWordsOfTheEmployerKindOrTheShapeElseTheModelsChoiceForTheOccupation() {
        assertEquals(List.of("hospital"), CareerRegisters.kindsIn("a hospital")); assertEquals(List.of("hospital"), CareerRegisters.kindsIn("un hôpital ou une clinique"));
        assertEquals(List.of("school", "university"), CareerRegisters.kindsIn("a university or a school")); assertEquals(List.of(), CareerRegisters.kindsIn("a company"));
        assertEquals(List.of("hospital", "nursing home"), CareerRegisters.kindsFor("", "", "registered nurse"), "the model's choice among the kinds, the one not on the list left out");
        int before = prompts.size();
        assertEquals(List.of(), CareerRegisters.kindsFor("a company", "", "software engineer")); assertEquals(List.of("restaurant", "hotel"), CareerRegisters.kindsFor("a restaurant or a hotel", "", "chef"), "the map's kinds count as kinds");
        assertEquals(before + 1, prompts.size(), "a decided kind whose words name no kind here goes to the model with the occupation, which chooses among the list or none; words that name kinds ask nothing");
        assertEquals(List.of("hospital", "nursing home"), CareerRegisters.kindsFor("", "", "registered nurse")); assertEquals(before + 2, prompts.size(), "with no kind decided, the model chooses for the occupation; the decided kind that named no kind asked once above");
        assertEquals(List.of("nursing home", "hospital"), CareerRegisters.kindsFor("a care home", "hospitals and clinics", "nurse"), "the words first, no model");
    }

    @Test void theUnitedStatesHospitalsCareListsAndSchoolsAreReadByTownAndState(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found f = CareerRegisters.read(store, s, "Austin, Texas", "registered nurse", "", 10, SourceRules.load(store));
        assertEquals(List.of("Ascension Seton Medical Center Austin", "St David's Medical Center", "Austin Wellness & Rehabilitation"), new ArrayList<>(f.employers().keySet()));
        assertEquals("S-0001\tthe US hospital and care lists (Medicare) (S-0001), in Austin, TX, acute care hospitals, non-profit, private, with an emergency department — https://www.medicare.gov/care-compare/details/hospital/450056", f.employers().get("Ascension Seton Medical Center Austin"));
        assertTrue(f.employers().get("Austin Wellness & Rehabilitation").contains("a nursing home, for profit, corporation, 120 certified beds — https://www.medicare.gov/care-compare/details/nursing-home/455671"), f.employers().get("Austin Wellness & Rehabilitation"));
        assertEquals(1, f.sources().size()); assertEquals("register", f.sources().get(0).kind()); assertEquals(3, f.sources().get(0).yieldTotal());
        assertEquals("the US hospital and care lists (Medicare) (S-0001) read for Austin, Texas, hospital and nursing home: 3 employers", f.notes().get(0));
        assertTrue(f.notes().stream().noneMatch(n -> n.startsWith("no register of")), f.notes().toString());
        CareerTargets.Found g = CareerRegisters.read(store, s, "Austin, TX", "teacher", "a school", 10, SourceRules.load(store));
        assertEquals(List.of("Rooster Springs El"), new ArrayList<>(g.employers().keySet()), "the town's open schools only, from the state's directory");
        assertTrue(g.employers().get("Rooster Springs El").contains("in Austin, TX, a primary school, of Dripping Springs Isd, 827 pupils — https://nces.ed.gov/ccd/schoolsearch/school_detail.asp?ID=480000000001"), g.employers().get("Rooster Springs El"));
        assertTrue(java.nio.file.Files.isRegularFile(store.root().resolve("catalog/career-cache").resolve("us-schools-TX-" + (java.time.LocalDate.now().getYear() - 2) + ".json")), "the state's directory is kept a month");
    }

    @Test void englandBrazilAndTheWorldListOfUniversitiesAreReadEachByItsOwnKey(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found uk = CareerRegisters.read(store, s, "Manchester, England", "nurse", "a hospital", 10, SourceRules.load(store));
        assertEquals(List.of("Manchester University Nhs Foundation Trust", "Witsmate Limited", "Hulee & Sons Limited"), new ArrayList<>(uk.employers().keySet()), "the NHS directory first, then the company register");
        assertTrue(uk.employers().get("Manchester University Nhs Foundation Trust").startsWith("S-0001\tthe NHS organisation directory (ODS) (S-0001), in M13 9WL, NHS trust — https://directory.spineservices.nhs.uk/ORD/2-0-0/organisations/R0A"), uk.employers().toString());
        assertTrue(urls.stream().anyMatch(u -> u.contains("PostCode=M1&Roles=RO197,RO172&Status=Active")) && urls.stream().anyMatch(u -> u.contains("PostCode=M9&")), "the town's postcode area, digit by digit");
        CareerTargets.Found br = CareerRegisters.read(store, s, "Belo Horizonte, MG, Minas Gerais", "médico", "um hospital", 10, SourceRules.load(store));
        assertEquals(List.of("Hospital Felicio Rocho"), new ArrayList<>(br.employers().keySet()));
        assertTrue(br.employers().get("Hospital Felicio Rocho").contains("in Belo Horizonte, MG, a general hospital, municipal administration — https://apidadosabertos.saude.gov.br/cnes/estabelecimentos/26859"), br.employers().toString());
        CareerTargets.Found in = CareerRegisters.read(store, s, "Pune, Maharashtra, India", "postdoctoral researcher in physics", "a university", 10, SourceRules.load(store));
        assertEquals(List.of("University of Pune", "Mumbai University"), new ArrayList<>(in.employers().keySet()), "the town's own first, then the region's");
        assertTrue(in.employers().get("University of Pune").contains("in Maharashtra, a university — http://www.unipune.example/"), in.employers().toString());
        CareerTargets.Found none = CareerRegisters.read(store, s, "Pune, India", "chef", "a restaurant or a hotel", 10, SourceRules.load(store));
        assertTrue(none.employers().isEmpty());
        assertEquals(1, none.notes().size(), none.notes().toString()); assertTrue(none.notes().get(0).contains("read for Pune, India, restaurant and hotel: 0 employers"), "a kind no official register covers goes to the map: " + none.notes().get(0));
        CareerTargets.Found gap = CareerRegisters.read(store, s, "Pune, India", "nurse", "a hospital", 10, SourceRules.load(store));
        assertTrue(gap.notes().stream().anyMatch(n -> n.contains("read for Pune, India, hospital: 0 employers")), "India has no hospital register the library knows, so the map is read for them: " + gap.notes());
    }

    @Test void franceReadsItsHealthEstablishmentsSchoolsAndHigherEducationBeforeItsCompanyRegister(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerTargets.Found f = CareerRegisters.read(store, s, "Lyon", "infirmier", "un hôpital ou un EHPAD", 10, SourceRules.load(store));
        List<String> names = new ArrayList<>(f.employers().keySet());
        assertEquals(List.of("Hospices Civils De Lyon", "Clinique Du Parc Lyon"), names.subList(0, 2), "the town's establishments in the categories the model chose; Villeurbanne's and the care home's category are out");
        assertTrue(f.employers().get("Hospices Civils De Lyon").contains("in Lyon, Centre Hospitalier Régional (C.H.R.), etablissement public de santé — https://annuaire-entreprises.data.gouv.fr/etablissement/26690027300016"), f.employers().toString());
        assertTrue(prompts.stream().anyMatch(p -> p.contains("Which categories hold such employers?") && p.contains("- Etablissements d'Hébergement pour Personnes Agées") && !p.contains("Villeurbanne")), "the categories present in the town, as the register names them");
        assertTrue(names.containsAll(List.of("LOGICIELS DU RHONE", "GRAND BUREAU D'ETUDES")) && names.indexOf("LOGICIELS DU RHONE") > 1, "the company register's employers after the institutions'");
        CareerTargets.Found e = CareerRegisters.read(store, s, "Lyon", "professeur", "un collège ou une université", 10, SourceRules.load(store));
        assertTrue(e.employers().containsKey("Collège Vendôme") && e.employers().containsKey("École normale supérieure de Lyon"), e.employers().keySet().toString());
        assertTrue(e.employers().get("Collège Vendôme").contains("in Lyon, college, public — https://vendome.example/") && e.employers().get("École normale supérieure de Lyon").contains("in Lyon 7e, école, grand établissement — https://www.ens-lyon.example/"), e.employers().toString());
        assertTrue(CareerRegisters.isRegisterAddress("https://data.cms.gov/provider-data/api/1/datastore/query/xubh-q36u/0") && CareerRegisters.isRegisterAddress("http://universities.hipolabs.com/search?country=India"));
    }

    static final String OSM_PAGE = "{\"elements\":[{\"type\":\"node\",\"id\":11,\"tags\":{\"amenity\":\"restaurant\",\"name\":\"Thousand Oaks\",\"addr:city\":\"Pune\",\"cuisine\":\"indian;chinese\"}},"
            + "{\"type\":\"way\",\"id\":22,\"tags\":{\"amenity\":\"restaurant\",\"name\":\"Asia Kitchen\",\"addr:suburb\":\"Kalyani Nagar\",\"website\":\"mainlandchina.example\",\"cuisine\":\"chinese\"}},"
            + "{\"type\":\"node\",\"id\":33,\"tags\":{\"amenity\":\"cafe\",\"name\":\"Barista\",\"brand\":\"Barista\",\"employees\":\"12\"}},"
            + "{\"type\":\"node\",\"id\":44,\"tags\":{\"amenity\":\"restaurant\"}},"
            + "{\"type\":\"node\",\"id\":55,\"tags\":{\"amenity\":\"fast_food\",\"name\":\"Hotel Durga\",\"contact:website\":\"https://www.hoteldurga.example/\"}}]}";

    @Test void theMapListsThePlacesOfAKindInATownWebsitesFirstThenBrandsAndSaysHowManyThereWere(@TempDir Path tmp) throws Exception {
        overpassAnswer = OSM_PAGE;
        CareerRegisters.MapPlaces map = new CareerRegisters.MapPlaces();
        List<CareerRegisters.Employer> got = map.read("Pune", List.of("restaurant"), "chef", 10);
        assertEquals(List.of("Asia Kitchen", "Hotel Durga", "Barista", "Thousand Oaks"), got.stream().map(CareerRegisters.Employer::name).toList(), "the website holders first, then the brand, then the rest; the unnamed one left out");
        assertEquals("Kalyani Nagar", got.get(0).place()); assertEquals("https://mainlandchina.example", got.get(0).url(), "a bare domain made an address");
        assertEquals("Pune", got.get(2).place(), "no town on the record: the place the map gave"); assertEquals("a café, of Barista", got.get(2).what()); assertEquals("12 employees", got.get(2).size());
        assertEquals("https://www.openstreetmap.org/node/11", got.get(3).url(), "no website: the map's own record"); assertEquals("a restaurant, indian,chinese", got.get(3).what());
        assertEquals("4 of 4 named restaurants, cafés and bars on the map", map.note());
        assertEquals(1, overpass.size()); assertTrue(overpass.get(0).contains("area(3601986140)->.a;") && overpass.get(0).contains("nwr[\"amenity\"~\"^(restaurant|cafe|bar|pub|fast_food|food_court|biergarten|ice_cream)$\"][\"name\"](area.a);") && overpass.get(0).startsWith(CareerRegisters.BASE.get("overpass")), overpass.get(0));
        assertEquals(List.of("Asia Kitchen", "Hotel Durga", "Barista", "Thousand Oaks"), map.read("Pune", List.of("restaurant"), "chef", 10).stream().map(CareerRegisters.Employer::name).toList());
        assertEquals(1, overpass.size(), "the second read comes from the month's cache");
        assertThrows(IOException.class, () -> map.read("Nowhere", List.of("restaurant"), "chef", 10), "a place the map does not know");
    }

    @Test void theMainServerDownTheMirrorAnswersAndTheMapIsReadOnlyForTheKindsNoOfficialRegisterListed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerRegisters.OVERPASS = (u, q) -> { overpass.add(u); return u.startsWith(CareerRegisters.BASE.get("overpass_mirror")) ? OSM_PAGE : null; };
        CareerTargets.Found f = CareerRegisters.readInstitutions(store, s, "Pune", "", List.of("restaurant", "hotel"), "chef", 10, null);
        assertTrue(f.employers().containsKey("Asia Kitchen"), f.employers().keySet().toString());
        assertTrue(f.employers().get("Asia Kitchen").contains("OpenStreetMap, the map's places (© OpenStreetMap contributors) (S-"), f.employers().get("Asia Kitchen"));
        assertTrue(overpass.stream().anyMatch(u -> u.startsWith(CareerRegisters.BASE.get("overpass_mirror"))), "the mirror after the official servers gave nothing: " + overpass);
        assertTrue(f.notes().stream().anyMatch(n -> n.contains("read for Pune, restaurant and hotel:") && n.contains("named restaurants, cafés and bars on the map")), f.notes().toString());
        // the United States: the hospitals come from the Medicare lists, and the map is not asked for them
        overpass.clear();
        CareerTargets.Found us = CareerRegisters.readInstitutions(store, s, "Austin, Texas", "US", List.of("hospital"), "registered nurse", 10, null);
        assertTrue(us.employers().keySet().stream().anyMatch(n -> n.contains("St David's")), us.employers().keySet().toString());
        assertTrue(overpass.isEmpty(), "the official register listed the kind, so the map was not read for it");
        // words are matched as words: a wine bar is a restaurant kind, a barrister is not
        assertEquals(List.of("restaurant"), CareerRegisters.kindsIn("a wine bar in town")); assertEquals(List.of(), CareerRegisters.kindsIn("a barrister's chambers or an innovative company"));
        assertEquals(List.of("law office", "public office"), CareerRegisters.kindsIn("a law firm or the county court"));
    }
}

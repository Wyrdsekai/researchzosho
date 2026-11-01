package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The openings page in tiers: the ones that match the role and the place open first, the near misses and the rest closed, what the decisions set aside closed with its reason, and a big board capped. */
class CareerOpeningsTiersTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode opening(String id, String company, String title, String location, boolean remote) {
        ObjectNode o = M.createObjectNode();
        o.put("id", id).put("company", company).put("title", title).put("location", location).put("remote", remote).put("url", "https://example.org/jobs/" + id)
         .put("posted", "2026-10-20").put("source", "greenhouse").put("listed", "").put("first_seen", "2026-10-31").put("last_seen", "2026-10-31");
        return o;
    }

    @Test void theOpeningsThatMatchTheRoleAndThePlaceComeFirstAndTheRestStayClosed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = Career.provide(store, "I want to build hardware in Boston.", "Jane Doe", false).seeker();
        Map<String, ObjectNode> all = new LinkedHashMap<>();
        all.put("o1", opening("o1", "Formlabs", "Staff Engineer, Hardware", "Somerville, MA", false));          // role and place
        all.put("o2", opening("o2", "Verkada", "Staff Engineer, Firmware", "San Mateo, CA", false));           // the role elsewhere
        all.put("o3", opening("o3", "Okta", "Account Executive", "Boston, MA", false));                         // the place, another role
        all.put("o4", opening("o4", "Okta", "Payroll Specialist", "Sydney, Australia", false));                 // neither
        for (int i = 0; i < 20; i++) all.put("b" + i, opening("b" + i, "Bigboard", "Sales Development Representative " + i, "Tokyo, Japan", false));   // a board of its own
        CareerFind.saveOpenings(store, s, all, CareerFind.mentionsBasis(List.of()));
        CareerCut.set(store, s, Map.of("prefer_titles", "Engineer, Engineering", "prefer_places", "Boston, MA, Massachusetts"));   // guesses only: nothing decided
        CareerFind.View v = CareerFind.view(store, s);
        assertEquals(24, v.passing().size(), "a guess never drops an opening"); assertTrue(v.aside().isEmpty());
        assertEquals(0, CareerCut.tier(v.cut(), v.passing().stream().filter(r -> r.opening().id().equals("o1")).findFirst().get().opening()));
        assertEquals(1, CareerCut.tier(v.cut(), CareerFind.of(all.get("o2")))); assertEquals(1, CareerCut.tier(v.cut(), CareerFind.of(all.get("o3")))); assertEquals(2, CareerCut.tier(v.cut(), CareerFind.of(all.get("o4"))));
        String page = CareerOpeningsPage.body(store, s, true);
        int fit = page.indexOf("<h2>Openings that fit</h2>"), near = page.indexOf("<summary><b>Near misses</b>"), rest = page.indexOf("<summary><b>The rest</b>");
        assertTrue(fit > 0 && near > fit && rest > near, page);
        assertTrue(page.contains("1 opening matches both the role and the place, as the library guesses them until you decide"), page);
        assertTrue(page.substring(fit, near).contains("<details open><summary>Formlabs <span class=\"k\">1</span></summary>") && page.substring(fit, near).contains("Staff Engineer, Hardware"), page.substring(fit, near));
        String nearly = page.substring(near, rest);
        assertTrue(nearly.contains("2 openings: the role elsewhere or remote, or the place with another role") && nearly.contains("<details><summary>Verkada") && nearly.contains("Account Executive") && !nearly.contains("Payroll"), nearly);
        String far = page.substring(rest);
        assertTrue(far.contains("21 openings: neither the role nor the place") && far.contains("Payroll Specialist") && far.contains("<li class=\"k\">and 15 more at Bigboard, on its hiring pages</li>"), far);
        assertEquals(5, far.split("Sales Development Representative", -1).length - 1, "a board of twenty shows its first five");
        assertTrue(page.contains("The role and the place are not decided yet, so the library sorted them by its guesses"), page);
        assertFalse(page.contains("Set aside by what you decided"));
        // the person decides the title: what no longer passes is set aside, closed, each with its reason
        CareerCut.set(store, s, Map.of("titles", "Engineer", "prefer_places", "Boston, MA, Massachusetts"));
        v = CareerFind.view(store, s);
        assertEquals(2, v.passing().size()); assertEquals(22, v.aside().size());
        page = CareerOpeningsPage.body(store, s, true);
        assertTrue(page.contains("<summary><b>Set aside by what you decided</b> <span class=\"k\">22</span></summary>") && page.contains("Payroll Specialist</a>, Okta") && page.contains("— its title has none of the words you want"), page);
        assertFalse(page.contains("not decided yet, so the library sorted them by its guesses"), page);
        assertTrue(page.contains("<b>2 openings fit</b> what you decided"), page);
    }

    @Test void whereThePersonWorksNowIsLeftOutUnlessTheyNameIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path json = tmp.resolve("profile.json"); Files.writeString(json, CareerTest.linkedin(), StandardCharsets.UTF_8);
        Career.Seeker s = Career.provide(store, json.toString(), "Jane Doe", true).seeker();   // the file's latest role runs to now
        List<String> now = Career.currentEmployers(store, s);
        assertEquals(1, now.size(), now.toString());
        String employer = now.get(0);
        Map<String, ObjectNode> all = new LinkedHashMap<>();
        all.put("o1", opening("o1", employer, "Staff Engineer", "Boston, MA", false));
        all.put("o2", opening("o2", "Formlabs", "Staff Engineer", "Somerville, MA", false));
        CareerFind.saveOpenings(store, s, all, CareerFind.mentionsBasis(List.of()));
        CareerCut.set(store, s, Map.of("titles", "Engineer", "places", "Boston, MA, Massachusetts"));
        CareerFind.View v = CareerFind.view(store, s);
        assertEquals(List.of("o2"), v.passing().stream().map(r -> r.opening().id()).toList(), "the current employer's opening is set aside");
        assertTrue(v.aside().get(0).verdict().why().contains("where you work now"), v.aside().get(0).verdict().why());
        // named by the person, it counts
        CareerSearch.addCompany(store, s, employer, "");
        v = CareerFind.view(store, s);
        assertEquals(2, v.passing().size(), "named by the person, it counts");
    }

    @Test void aSearchWithAChangeOfItsOwnIsJudgedByItsOwnCutOnItsPage(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = Career.provide(store, "I want to build hardware in Boston.", "Jane Doe", false).seeker();
        Map<String, ObjectNode> all = new LinkedHashMap<>();
        all.put("o1", opening("o1", "Formlabs", "Staff Engineer, Hardware", "Somerville, MA", false));
        all.put("o2", opening("o2", "Granite State Robotics", "Staff Engineer", "Manchester, NH", false));
        CareerFind.saveOpenings(store, s, all, CareerFind.mentionsBasis(List.of()));
        CareerCut.set(store, s, Map.of("titles", "Engineer", "places", "Boston, MA, Massachusetts"));   // the standing cut: Massachusetts
        CareerFind.View v = CareerFind.view(store, s);
        assertEquals(List.of("o1"), v.passing().stream().map(r -> r.opening().id()).toList()); assertEquals(1, v.aside().size());
        // the run of one search with New Hampshire added for that search only, recorded with its own cut
        CareerCut.Cut own = new CareerCut.Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of("Boston", "MA", "New Hampshire", "NH"), "", List.of(), List.of(), List.of(), 0, 0, "library", "2026-10-31", "this search");
        CareerFind.snapshot(store, s, CareerFind.view(store, s, own, null), own, "and New Hampshire as well");
        String stem = Files.list(CareerFind.dir(store, s).resolve("runs")).findFirst().orElseThrow().getFileName().toString().replaceFirst("\\.json$", "");
        String past = CareerOpeningsPage.body(store, s, true, stem);
        assertTrue(past.contains("<b>For that search only:</b> and New Hampshire as well.") && past.contains("<b>2 openings passed</b>") && past.contains("2 still pass that search's cut") && past.contains("Granite State Robotics"), past);
        assertFalse(past.contains("Set aside by what you decided"), past);
        String now = CareerOpeningsPage.body(store, s, true);
        assertTrue(now.contains("<b>1 opening fits</b> what you decided") && now.contains("Set aside by what you decided"), "the openings as they stand keep the standing cut: " + now);
        String person = CareerPage.body(store, s, true);
        assertTrue(person.contains("<td class=\"k\">and New Hampshire as well</td>"), "the Searches table says what that search changed: " + person);
    }
}

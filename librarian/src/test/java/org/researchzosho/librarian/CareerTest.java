package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CareerProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class CareerTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A stand-in model: the reading cites the facts; the draft of what the person wants is a JSON array with one decided, one guess, the rest open. */
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p0 = m.get(0).path("content").asText();
            if (p0.contains("List the jobs they say they have held")) return p0.contains("senior product manager") ? "[{\"title\":\"senior product manager\",\"employer\":\"Penrose Inc.\",\"start\":\"2019\",\"end\":\"now\",\"place\":\"\"},{\"title\":\"chief executive\",\"employer\":\"\",\"start\":\"2014\",\"end\":\"2019\",\"place\":\"a mid-sized firm\"}]" : "[]";
            return "[{\"dimension\":\"place\",\"state\":\"decided\",\"want\":\"Chicago, or remote\",\"from\":\"told\"},"
                    + "{\"dimension\":\"company size\",\"state\":\"leaning\",\"want\":\"small companies, under fifty\",\"from\":\"E2 E3\"},"
                    + "{\"dimension\":\"domain\",\"state\":\"open\",\"want\":\"\",\"from\":\"\"},"
                    + "{\"dimension\":\"keep out\",\"want\":[\"on-call\"]},"
                    + "{\"dimension\":\"named\",\"want\":[{\"name\":\"Globex\",\"kind\":\"knows people at\"},{\"name\":\"Initech\",\"kind\":\"employer wanted\"}]},"
                    + "{\"dimension\":\"ask\",\"want\":[{\"topic\":\"kind of employer\",\"question\":\"What kind of employer do you want: a company, a firm, a hospital, a lab, a school or a public body?\"},{\"topic\":\"place\",\"question\":\"Where?\"},{\"topic\":\"hats\",\"question\":\"x\"}]}]";
        }
        @Override public String prose(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (!p.contains("the LinkedIn file profile.json")) return "## The arc\nYou lead products now, after running a firm [E1][E2][E3].\n## Questions for you\n1. What drew you from running a firm to product work? (what drives you) [E1]";   // a seeker who only typed: the fixture's checks below are the LinkedIn one's
            assertTrue(p.contains("(headline; the LinkedIn file profile.json): Engineering lead") && p.contains("(role; the LinkedIn file profile.json)"), "the model gets the numbered facts with their source");
            assertTrue(p.contains("Today is " + LocalDate.now() + ".") && p.contains("2016-06 to 2021-03 (4 years 10 months)") && p.contains("2021-04 to now (") && p.contains(" so far)"),
                    "the model is told the date and given each role's length, counted, not left to guess");
            assertTrue(p.indexOf("2012-09 to 2016-06") < p.indexOf("2016-06 to 2021-03") && p.indexOf("2016-06 to 2021-03") < p.indexOf("2021-04 to now"),
                    "the model reads the dated facts oldest first, though the profile lists them newest first");
            assertTrue(p.contains("C1 (counted by the library from the roles' titles): Roles listed: 2, the first from 2016-06; 1 still going."), "the counts go to the model as facts of their own");
            assertTrue(p.contains("building something new of your own") && p.contains("one built around people and teamwork") && p.contains("## Questions for you")
                    && p.contains("none can be answered with yes or no") && p.contains("asking which suited them best and why") && p.contains("the size of the companies")
                    && p.contains("tag (preference)") && !p.contains("the kind)"), "the reading is asked to interpret the choices and to ask as a counsellor would, with plain tags and no placeholder to copy");
            return "## The arc\nYou went from Acme Widgets to Globex, from engineer to lead [E2][E3].\n## What recurs\nSmall companies [E2][E3].\n## What they moved away from\nThe evidence does not say.\n"
                    + "## Where they are now\nAt Globex since 2021 [E3].\n## On the side\nThe evidence does not say.\n## Gaps and unknowns\nNo descriptions of the roles [E2][E3].\n"
                    + "## Possible wants and changes\n1. (company size or stage) Both roles were at small companies; was that chosen? [E2][E3]";
        }
    }

    static String linkedin() {
        return "{\"full_name\":\"Jane Doe\",\"headline\":\"Engineering lead\",\"occupation\":\"Engineering lead at Globex\",\"location_str\":\"Chicago, Illinois\",\"summary\":\"\","
                + "\"experiences\":[{\"starts_at\":{\"day\":1,\"month\":4,\"year\":2021},\"ends_at\":null,\"company\":\"Globex\",\"title\":\"Engineering lead\",\"description\":null,\"location\":\"Remote\"},"
                + "{\"starts_at\":{\"day\":1,\"month\":6,\"year\":2016},\"ends_at\":{\"day\":1,\"month\":3,\"year\":2021},\"company\":\"Acme Widgets\",\"title\":\"Software engineer\",\"description\":\"\",\"location\":\"Chicago\"}],"
                + "\"education\":[{\"starts_at\":{\"day\":1,\"month\":9,\"year\":2012},\"ends_at\":{\"day\":1,\"month\":6,\"year\":2016},\"field_of_study\":\"Computer science\",\"degree_name\":\"BSc\",\"school\":\"State University\",\"description\":\"\"}],"
                + "\"skills\":[\"Java\",\"Distributed systems\",\"Mentoring\"],\"accomplishment_publications\":[],\"accomplishment_patents\":[{\"title\":\"A widget\",\"patent_number\":\"US1\",\"issued_on\":{\"day\":1,\"month\":1,\"year\":2019}}],"
                + "\"recommendations\":[\"Jane ships.\"],\"activities\":[]}";
    }

    @Test
    void aDraftWrittenWithoutItsBracketsOrInsideACodeFenceIsStillRead() {
        assertEquals(2, Career.parseArray("{\"dimension\": \"domain\", \"want\": [\"a\"]}, {\"dimension\": \"keep out\", \"want\": [\"tobacco\"]}").size(), "the objects of an array the model wrote without the brackets");
        assertEquals(1, Career.parseArray("```json\n[{\"dimension\": \"place\"}]\n```").size(), "a code fence round the array");
        assertEquals("place", Career.parseArray("Here: [{\"dimension\": \"place\"}]").get(0).path("dimension").asText(), "words before the array");
        assertEquals(0, Career.parseArray("nothing here").size()); assertEquals(0, Career.parseArray(null).size());
    }

    @Test
    void aSearchWithNoReadingYetWritesTheReadingAndTheDraftFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            Path json = tmp.resolve("profile.json"); Files.writeString(json, linkedin(), StandardCharsets.UTF_8);
            Career.Seeker s = Career.provide(store, json.toString(), "", true).seeker();
            assertTrue(Career.readingKept(store, s).isBlank() && Career.wantsKept(store, s).path("dimensions").isEmpty());
            List<String> phases = new ArrayList<>();
            assertTrue(CareerFind.readFirst(store, s, phases::add));
            assertEquals("Reading your working life first, since no reading was written yet", phases.get(0));
            assertFalse(Career.readingKept(store, s).isBlank(), "the reading is written");
            assertFalse(Career.wantsKept(store, s).path("dimensions").isEmpty(), "and what they want is drafted from it");
            assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("The search wrote the reading of your working life first, since none was written yet, and drafted what you want from it: the guesses it runs on until you decide."));
            assertFalse(CareerFind.readFirst(store, s, phases::add), "with both written, a search writes nothing first");
            LibraryStore empty = new LibraryStore(tmp.resolve("lib2")); empty.init();
            Career.Seeker nobody = Career.provide(empty, "I want to teach.", "Nobody Yet", false).seeker();
            assertTrue(CareerFind.readFirst(empty, nobody, phases::add), "told words are evidence enough for a reading");
        } finally { Career.DRIVE = Explain::drive; }
    }

    @Test
    void aPersonWhoOnlyTypedGetsTheirRolesReadOutOfTheirWordsOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            Career.Seeker s = Career.provide(store, "Je suis senior product manager, avec environ 7 ans d'expérience, dernièrement chez Penrose Inc. Avant, j'ai été chief executive of a mid-sized firm pendant 5 ans.", "Seeker Words", true).seeker();
            assertEquals("", Career.latestTitle(store, s), "words alone give no role fact");
            Career.reading(store, s);
            List<String> roles = new ArrayList<>(); for (JsonNode it : Career.evidence(store, s)) if (it.path("kind").asText().equals("role")) roles.add(it.path("text").asText() + " | " + it.path("by").asText() + " | " + it.path("source").asText());
            assertEquals(2, roles.size(), roles.toString());
            assertTrue(roles.get(0).startsWith("senior product manager at Penrose Inc., 2019 to now | " + Career.ROLES_BY + " | what you said on "), roles.get(0));
            assertEquals("chief executive, 2014 to 2019, a mid-sized firm", roles.get(1).split(" \\| ")[0]);
            assertEquals("senior product manager", Career.latestTitle(store, s), "the role still held");
            int n = Career.evidence(store, s).size();
            Career.reading(store, s);
            assertEquals(n, Career.evidence(store, s).size(), "a text read for roles once is not read again");
            assertFalse(Career.stale(store, s), "the reading counts the roles it read");
        } finally { Career.DRIVE = Explain::drive; }
    }

    @Test
    void whatIsHandedOverBecomesFactsTheReadingCitesThemAndWhatTheyWantHasThreeStates(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path export = tmp.resolve("profile.json"); Files.writeString(export, linkedin(), UTF_8);
        Path resume = tmp.resolve("resume.txt");
        Files.writeString(resume, "Jane Doe\n\nExperience\n\nBuilt the widget pipeline at Acme Widgets, from the first line of code to a team of four.\n\nLeads a team of six at Globex, on the payments platform.", UTF_8);
        Path secret = tmp.resolve("secret.txt"); Files.writeString(secret, "the key is 1234", UTF_8);
        Career.DRIVE = StandIn::new;
        try {
            assertTrue(Career.seekers(store).isEmpty());
            Career.Provided first = Career.provide(store, export.toString(), "", true);
            Career.Seeker s = first.seeker();
            assertEquals("Jane Doe", s.name()); assertEquals("jane-doe", s.slug()); assertEquals("linkedin", first.kind());
            ArrayNode ev = Career.evidence(store, s);
            int n = ev.size();
            assertEquals(first.added(), n);
            assertEquals("E1", ev.get(0).path("id").asText()); assertEquals("Engineering lead, based in Chicago, Illinois", ev.get(0).path("text").asText());
            assertEquals("the LinkedIn file profile.json", ev.get(0).path("source").asText());
            assertTrue(ev.get(1).path("text").asText().contains("Engineering lead at Globex, 2021-04 to now"), ev.get(1).toString());
            assertTrue(ev.get(2).path("text").asText().contains("Software engineer at Acme Widgets, 2016-06 to 2021-03, Chicago"), ev.get(2).toString());
            assertTrue(ev.toString().contains("Skills listed: Java, Distributed systems, Mentoring") && ev.toString().contains("Patent: A widget, US1, 2019-01") && ev.toString().contains("Someone wrote of them: Jane ships."));
            assertTrue(Files.isRegularFile(s.dir(store).resolve("provided").resolve("profile.json")), "the file is kept whole");
            assertEquals(0, Career.provide(store, export.toString(), "", true).added(), "the same file, unchanged, adds nothing");
            assertEquals(n, Career.evidence(store, s).size());
            ArrayNode old = Career.evidence(store, s); for (JsonNode it : old) { ((ObjectNode) it).remove("start"); ((ObjectNode) it).remove("end"); }
            M.writeValue(s.dir(store).resolve("evidence.json").toFile(), old);   // facts as an older library read them, before it kept the dates
            assertEquals(n, Career.provide(store, export.toString(), "", true).added(), "facts an older library read are read again in full");
            assertTrue(Career.evidence(store, s).get(1).has("start"));
            assertEquals("E" + (n + 1), Career.evidence(store, s).get(0).path("id").asText(), "read again, the facts take new numbers, so an old reading never points at the wrong one");
            ev = Career.evidence(store, s);

            // the reading, dated, citing the facts, and in date until more is handed over
            assertFalse(Career.stale(store, s), "no reading, nothing to be out of date");
            String md = Career.reading(store, s);
            assertTrue(md.startsWith("*Read on ") && md.contains(n + " facts.*") && md.contains("[E2][E3]"), md);
            assertEquals(md, Career.readingKept(store, s));
            assertFalse(Career.stale(store, s));
            Career.Provided doc = Career.provide(store, resume.toString(), "", true);
            assertEquals(s.slug(), doc.seeker().slug(), "a second file goes to the one person there is");
            List<String> pieces = new ArrayList<>(); for (JsonNode it : Career.evidence(store, s)) if (it.path("kind").asText().equals("document")) pieces.add(it.path("text").asText());
            assertEquals(List.of("Jane Doe Experience Built the widget pipeline at Acme Widgets, from the first line of code to a team of four.", "Leads a team of six at Globex, on the payments platform."), pieces,
                    "a resume is read in paragraphs the reading can point at, and a short heading stays with what follows it");
            assertTrue(Career.stale(store, s), "more facts than the reading covers");
            assertTrue(Career.text(store, s, "/career").contains("The library has been given more since it wrote this reading."));
            Career.reading(store, s);
            assertFalse(Career.stale(store, s));

            // words, kept as said; an address, kept once
            assertEquals("told", Career.provide(store, "I want to stay in Chicago or work remote, and I refuse on-call.", "", true).kind());
            assertTrue(Files.readString(s.dir(store).resolve("told.md")).contains("refuse on-call"));
            assertEquals(1, Career.provide(store, "https://example.org/jane", "", true).added());
            assertEquals(0, Career.provide(store, "https://example.org/jane", "", true).added(), "the same address again adds nothing");
            assertEquals(1, Career.seekers(store).size(), "the same person throughout, not a second");

            // without leave to read files, a path is kept as words and the file is never opened
            assertEquals("told", Career.provide(store, secret.toString(), "", false).kind());
            assertFalse(Career.evidence(store, s).toString().contains("the key is 1234"));

            // what the person wants: every topic, in the three states; a topic the person set stays theirs through a new draft
            ObjectNode w = Career.wants(store, s);
            assertEquals(Career.DIMENSIONS.size(), w.path("dimensions").size());
            assertEquals("decided", Career.find(w.path("dimensions"), "place").path("state").asText());
            assertEquals("leaning", Career.find(w.path("dimensions"), "company size").path("state").asText());
            assertEquals("open", Career.find(w.path("dimensions"), "pay").path("state").asText());
            assertTrue(Files.readString(s.dir(store).resolve("wants-draft.txt")).contains("\"company size\""), "the model's own answer is kept beside the draft");
            assertEquals(List.of("on-call"), CareerSearch.keepOut(store, s), "what the person said they rule out is kept out from the reading on, before any interview");
            assertEquals(List.of("Initech"), CareerSearch.companies(store, s), "an employer they would want is on the list the search looks at");
            assertEquals(List.of("Globex", "Initech"), CareerSearch.named(store, s).stream().map(x -> x.path("name").asText()).toList(), "every name they gave is kept, with how");
            assertEquals("knows people at", CareerSearch.named(store, s).get(0).path("kind").asText());
            assertEquals(1, w.path("ask").size(), "a question for a topic of the minimum left open; none for place, which is decided, nor for a topic that is not one");
            assertEquals("kind of employer", w.path("ask").get(0).path("topic").asText());
            assertEquals("open", Career.find(w.path("dimensions"), "kind of employer").path("state").asText());
            String drafted = Files.readString(s.dir(store).resolve("log.jsonl"));
            assertTrue(drafted.contains("1 thing you rule out") && drafted.contains("2 names you gave"), "the log says what the draft took from the input: " + drafted);
            if (!Career.readingKept(store, s).isBlank()) {
                ObjectNode iv = CareerInterview.state(store, s);
                CareerInterview.Question firstQ = CareerInterview.next(iv);
                assertEquals("the minimum: kind of employer", firstQ.tag(), "the open topic of the minimum is asked first, whatever the reading asks");
                assertTrue(firstQ.text().startsWith("What kind of employer do you want"));
                assertEquals(iv.path("questions").size(), CareerInterview.state(store, s).path("questions").size(), "asked once, not again on every look");
            }
            Career.setWant(store, s, "pay", "decided", "at least what I earn now");
            w = Career.wants(store, s);
            assertEquals("decided", Career.find(w.path("dimensions"), "pay").path("state").asText(), "a topic the person set stays theirs through a new draft");
            assertEquals("leaning", Career.setWant(store, s, "pace", "guess", "no crunch").path("state").asText(), "guess is the everyday word for leaning");
            assertThrows(IllegalArgumentException.class, () -> Career.setWant(store, s, "hats", "decided", "x"));
            assertThrows(IllegalArgumentException.class, () -> Career.setWant(store, s, "pay", "maybe", "x"));

            // what it prints, in sentences
            assertEquals("The size of the company: a guess. Small companies, under fifty. From facts E2 and E3.", Career.line(Career.find(w.path("dimensions"), "company size")));
            assertEquals("Where you work: decided. Chicago, or remote. From what you said.", Career.line(Career.find(w.path("dimensions"), "place")));
            assertEquals("The pay: decided. At least what I earn now. You set this on " + LocalDate.now() + ".", Career.line(Career.find(w.path("dimensions"), "pay")));
            assertEquals("The area you work in: open.", Career.line(Career.find(w.path("dimensions"), "domain")));
            assertEquals("The culture of the place: open.", Career.line(Career.find(w.path("dimensions"), "culture")), "culture is a topic of its own");
            assertEquals("From the library's count C6.", Career.basis(M.createObjectNode().put("from", "C6")));
            assertEquals("From facts E2 and E9 and the library's counts C1 and C6.", Career.basis(M.createObjectNode().put("from", "E2 C1 E9 C6")));
            String text = Career.text(store, s, "/career");
            assertTrue(text.startsWith("The library holds ") && text.contains(" facts about Jane Doe's work, from the LinkedIn file profile.json, the document resume.txt, what you said on ")
                    && text.contains(" and the page https://example.org/jane.") && text.contains("## The arc") && text.contains("To list the facts, type /career evidence.")
                    && text.contains("type for example: /career want place decided Chicago, or remote.") && text.contains("What changed lately:"), text);
            assertFalse(text.contains("op="), "no protocol words in what a person reads");
            String log = String.join("\n", Career.logTail(store, s, 40));
            assertTrue(log.contains("You handed over the LinkedIn file profile.json. The library read " + n + " facts from it.") && log.contains("You decided the pay: at least what I earn now.")
                    && log.contains("You marked the pace of the work as a guess: no crunch.") && log.contains("The library drafted what you want: 1 decided from what you said, 1 guess for you to confirm, 13 still open, 1 thing you rule out and 2 names you gave."), log);
            String facts = Career.evidenceText(store, s);
            String firstId = Career.evidence(store, s).get(0).path("id").asText();
            assertTrue(facts.contains("From the LinkedIn file profile.json:\n" + firstId + ". Engineering lead, based in Chicago, Illinois") && facts.contains("From the document resume.txt:")
                    && facts.contains("2016-06 to 2021-03 (4 years 10 months), Chicago"), facts);

            // the page
            String page = CareerPage.body(store, s, true);
            assertTrue(page.contains("<details class=\"sec\" id=\"reading\"><summary><h2>The reading</h2></summary>") && page.contains("[E2][E3]") && page.contains("<td>The size of the company</td><td>a guess</td>")
                    && page.contains("name=\"op\" value=\"want\"") && page.contains(" facts the reading rests on</summary>") && page.contains("Tell the library more"), page);
            assertFalse(CareerPage.body(store, s, false).contains("name=\"op\" value=\"want\""), "a reader cannot set a topic");

            // through the protocol, as the owner and as a writer from the network
            LibraryProtocol lp = new LibraryProtocol(store);
            ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "tester").put("runtime", "test");
            assertEquals(1, lp.career(a.put("op", "list")).path("seekers").size());
            assertTrue(lp.career(a.put("op", "read")).path("markdown").asText().contains("## The arc"));
            assertEquals("Saved. Working remotely: decided. Yes. You set this on " + LocalDate.now() + ".",
                    lp.career(a.put("op", "want").put("dimension", "remote").put("state", "decided").put("want", "yes")).path("summary").asText());
            Patrons.set(store, "did:key:writer", "A writer", Patrons.Level.write);
            ObjectNode far = M.createObjectNode(); far.putObject("patron").put("did", "did:key:writer").put("name", "A writer").put("runtime", "http");
            JsonNode farOut = lp.career(far.put("op", "provide").put("what", secret.toString()).put("name", "Jane Doe"));
            assertEquals("told", farOut.path("kind").asText(), "a writer from the network hands over words; a path on this machine is not theirs to read");
            assertFalse(Career.evidence(store, s).toString().contains("the key is 1234"));

            // the chat command
            PrintStream was = System.out; ByteArrayOutputStream out = new ByteArrayOutputStream(); System.setOut(new PrintStream(out, true, UTF_8));
            try {
                assertTrue(new CareerProfile().chatCommand(store, "/career read"));
                assertTrue(new CareerProfile().chatCommand(store, "/career want kind of work guess building things again"));
                assertTrue(new CareerProfile().chatCommand(store, "/career evidence"));
                assertTrue(new CareerProfile().chatCommand(store, "/career nonsense"));
                assertFalse(new CareerProfile().chatCommand(store, "/family tell x"));
            } finally { System.setOut(was); }
            String said = out.toString(UTF_8);
            assertTrue(said.contains("## The arc") && said.contains("Saved. The kind of work: a guess. Building things again. You set this on ") && said.contains("From the LinkedIn file profile.json:")
                    && said.contains("That is not one of the career commands."), said);

            // the field's page: the owner's own box on the page is words, never a path
            CareerProfile cp = new CareerProfile();
            Profile.Page pg = cp.page(store, Patrons.Patron.PERSON, "/career", "GET", Map.of("seeker", "jane-doe"), Map.of());
            assertTrue(pg.title().startsWith("Jane Doe") && pg.body().contains("<details class=\"sec\" id=\"wants\"><summary><h2>What you want</h2></summary>"));
            Profile.Page posted = cp.page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "provide", "seeker", "jane-doe", "text", secret.toString()));
            assertEquals("/career?seeker=jane-doe", posted.redirect());
            assertFalse(Career.evidence(store, s).toString().contains("the key is 1234"), "a path typed into the page is kept as words");
            assertNull(cp.page(store, Patrons.Patron.PERSON, "/elsewhere", "GET", Map.of(), Map.of()));
            assertTrue(cp.predicates().isEmpty(), "the field names no relations, so an ordinary library's map is the same with it on or off");
        } finally { Career.DRIVE = Explain::drive; }
    }

    @Test
    void whatCannotBeReadLeavesNothingAndAnEmptyLibraryHoldsNobody(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertNull(Career.seeker(store, ""));
        assertThrows(IllegalArgumentException.class, () -> Career.provide(store, "   ", "", true));
        Path empty = tmp.resolve("empty.txt"); Files.writeString(empty, "   \n  ", UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Career.provide(store, empty.toString(), "", true));
        assertTrue(e.getMessage().contains("nothing the library can read in empty.txt"), e.getMessage());
        assertTrue(Career.seekers(store).isEmpty(), "a file the library cannot read leaves nothing behind");
        assertEquals("", Career.reading(store, new Career.Seeker("nobody", "Nobody")), "no facts, no reading");
        String page = CareerPage.body(store, null, true);
        assertTrue(page.contains("<input type=\"file\" name=\"file\"") && page.contains("name=\"op\" value=\"provide\""), page);   // the form takes the resume or the LinkedIn file itself now
    }

    @Test
    void aLineThatSizesACompanyTheFactsDoNotSizeIsMarked() {
        ArrayNode facts = M.createArrayNode();
        facts.addObject().put("id", "E1").put("text", "Software engineer at Acme Widgets, 2016-06 to 2021-03, Chicago");
        facts.addObject().put("id", "E2").put("text", "Principal Architect - Big Data and Recommendations at Initech, 2009-05 to 2011-05");
        facts.addObject().put("id", "E3").put("text", "Engineer at a large hospital, 2005-01 to 2009-04");
        String md = "## The arc\nYou started at Acme Widgets, a large corporation [E1].\nYou built big data systems at Initech [E2].\nYou worked at a large hospital [E3].\n"
                + "You led a team of six [E1].\nAcme, a startup [E1] [general knowledge]\n## Gaps and unknowns\nThe evidence does not give the size of Acme, large or small [E1].\n"
                + "## Possible wants and changes\n1. A large enterprise next? (company size or stage) [E1]";
        String marked = Career.markGeneralKnowledge(md, facts);
        assertTrue(marked.contains("You started at Acme Widgets, a large corporation [E1][general knowledge]."), marked);
        assertTrue(marked.contains("You built big data systems at Initech [E2].\n"), "big data is a field, not a size");
        assertTrue(marked.contains("You worked at a large hospital [E3].\n"), "the fact itself says large");
        assertTrue(marked.contains("You led a team of six [E1].\n"));
        assertEquals(1, marked.split("startup \\[E1\\] \\[general knowledge\\]", -1).length - 1, "a line already marked is marked once");
        assertTrue(marked.contains("large or small [E1].\n") && marked.endsWith("(company size or stage) [E1]"), "the gaps and the questions are not marked");
        String asks = Career.markGeneralKnowledge("## What the facts do not say\nThe size of Acme, a large firm or not [E1].\n## Questions for you\n1. Which suited you best, the large firm or the small one? (preference: company size) [E1]", facts);
        assertFalse(asks.contains("general knowledge"), "the sections that ask or say what is missing are left alone, by their new names too");
        String para = Career.markGeneralKnowledge("## What recurs\nYou began at Acme Widgets [E1]. Then you joined a large firm [E1]. You led six people [E1].", facts);
        assertEquals("## What recurs\nYou began at Acme Widgets [E1]. Then you joined a large firm [E1][general knowledge]. You led six people [E1].", para,
                "in a paragraph, only the sentence that sizes a company is marked, beside its own citations");
    }

    @Test
    void theRolesAreCountedByTheirTitles() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        ArrayNode facts = M.createArrayNode();
        facts.addObject().put("id", "E1").put("kind", "role").put("text", "CEO / Co-founder at Widgetly, 2025-09 to now, Boston").put("start", "2025-09").put("end", "now");
        facts.addObject().put("id", "E2").put("kind", "role").put("text", "Advisor at Initech, 2024-08 to now, Boston").put("start", "2024-08").put("end", "now");
        facts.addObject().put("id", "E3").put("kind", "role").put("text", "VP of Engineering at Globex, 2021-09 to 2024-06, Boston").put("start", "2021-09").put("end", "2024-06");
        facts.addObject().put("id", "E4").put("kind", "role").put("text", "Software Engineer III at Acme, 1999-11 to 2005-03, Boston").put("start", "1999-11").put("end", "2005-03");
        facts.addObject().put("id", "E5").put("kind", "skills").put("text", "Skills listed: Java");
        List<String> c = Career.counted(facts, today);
        assertEquals("Roles listed: 4, the first from 1999-11; 2 still going.", c.get(0));
        assertTrue(c.contains("Founding roles, by their titles: 1. CEO / Co-founder at Widgetly (2025-09 to now, 1 year 2 months so far)."), c.toString());
        assertTrue(c.contains("Advising and mentoring roles, by their titles: 1. Advisor at Initech (2024-08 to now, 2 years 3 months so far)."), c.toString());
        assertTrue(c.stream().anyMatch(x -> x.startsWith("Leading roles (manager, lead, director, head, VP, chief), by their titles: 2. VP of Engineering at Globex (2021-09 to 2024-06, 2 years 10 months); CEO / Co-founder at Widgetly")), "oldest first, " + c);
        assertTrue(c.stream().anyMatch(x -> x.startsWith("Making roles") && x.contains(": 1. Software Engineer III at Acme (1999-11 to 2005-03, 5 years 5 months).")), c.toString());
        assertTrue(c.contains("Where the roles were, by the place each gives: Boston 4."), c.toString());
        assertTrue(c.contains("The longest role, leaving out advising and mentoring: Software Engineer III at Acme, 5 years 5 months."), c.toString());
        assertFalse(c.stream().anyMatch(x -> x.startsWith("Of the ")), "two finished roles are too few for a middle length");
        assertTrue(Career.counted(M.createArrayNode(), today).isEmpty(), "no dated roles, nothing counted");
    }

    @Test
    void aSpanIsCountedTheWayLinkedInCountsIt() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        assertEquals("2 years 10 months", Career.length("2021-09", "2024-06", today), "both months count, as LinkedIn shows it");
        assertEquals("1 year 2 months so far", Career.length("2025-09", "now", today));
        assertEquals("10 months so far", Career.length("2026-01", "now", today));
        assertEquals("1 year", Career.length("2020-01", "2020-12", today));
        assertEquals("about 4 years", Career.length("2012", "2016", today));
        assertEquals("", Career.length("?", "2016-06", today), "no dates, no length");
        ObjectNode role = M.createObjectNode().put("text", "Software engineer at Acme Widgets, 2016-06 to 2021-03, Chicago").put("start", "2016-06").put("end", "2021-03");
        assertEquals("Software engineer at Acme Widgets, 2016-06 to 2021-03 (4 years 10 months), Chicago", Career.withLength(role, today));
    }

    @Test
    void aLongDocumentIsCutAndSaysSo() {
        Career.Pieces p = Career.pieces(("x".repeat(400) + "\n\n").repeat(200));
        assertTrue(p.shortened());
        assertTrue(p.list().size() <= Career.DOC_PIECES);
        int total = 0; for (String x : p.list()) total += x.length();
        assertTrue(total <= Career.DOC_CHARS);
        assertFalse(Career.pieces("short\n\nresume").shortened());
    }
}

package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Stopping;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.Fetch;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A collection of pictures (0.8.0), as one of the library's jobs: the person's form ({@link CollectionForm}) held to every picture, and a
 * model choosing where to look and with what words, the way a research run's worker does ({@link Brain#decide} over a table whose
 * {@code search} names its places, {@link PicturePlaces}). Each search's pictures go through the program, cheapest step first: an address
 * already seen; the size and shape read from the file's first bytes, before the rest is downloaded; its format; what the file declares
 * about AI; its record, when the person wants none made by AI; a copy of a picture already kept or already turned down, by the
 * perceptual hash ({@link PictureHash}); then the model that sees, one call a picture, for what it shows and, when the pictures must
 * differ in something, which one it shows; then, when the likeness helper is set up, a picture close to a kept one by its embedding, which
 * the model that sees settles (same picture or not). What passed is kept as its address and a small copy, or at full size when the person
 * asked and the library is theirs alone. The model is told after each search what was kept and why the rest was set aside, and chooses
 * the next search from that.
 *
 * <p>The job ends at the number asked; at the person's own time limit; when it has judged the most a collection of that size is given
 * ({@link CollectionForm#judgeCeiling}); when it is sure there are no more ({@link #SURE_AFTER} searches since the last picture kept, in at
 * least two places, brought nothing new for the judge); or when the model searching calls done, with its reason. Everything is written to the collection's manifest
 * as it goes ({@code catalog/images/collections/<job>/manifest.json}), which the Pictures page shows as a gallery and from which a
 * restarted job goes on.
 */
public final class Collection {
    private Collection() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final String JOB_KIND = "image-collection";
    /** How much of a picture is read before its size is known; the most a collection reads of one picture; the model's look and the kept copy, on the long side. */
    static final int PEEK = 256 * 1024, MOST_BYTES = 40 * 1024 * 1024, LOOK_PX = 1024, THUMB_PX = 480;
    /**
     * The searches in a row that kept nothing new, in at least two places, after which the job is sure there are no more. Calibrated
     * 2026-11-19 on a spread of twelve asks run with no such stop (people in history, an event, places, animals, objects, art styles, a
     * format, sizes and shapes, AI allowed or not, values that must differ): stopped at the first such run of 4, two of them would have lost
     * pictures later searches kept (3 and 5); at 5 none would, and the asks with few to find still stop well before their ceiling.
     */
    static volatile int SURE_AFTER = Integer.getInteger("researchzosho.collect.sure_after", 5);   // the property: a measurement's, never a setting
    /** The embedding's likeness at or over which a picture is shown to the model that sees beside a kept one. Calibrated 2026-11-19 on the pinned pair set. */
    static volatile double CLIP_CLOSE = 0.85;
    /** The most searches one collection makes, whatever else. */
    static final int MOST_SEARCHES = 80;
    /** How many of each list the manifest keeps for the gallery. */
    static final int KEEP_COPIES = 60, KEEP_UNVERIFIED = 40, KEEP_TURNED_DOWN = 300;

    // ---- the seams: a test stands in for each ----

    static volatile Supplier<List<PicturePlaces.Place>> PLACES = PicturePlaces::live;
    @FunctionalInterface interface Looker { Images.Fetched get(String url, int peek, Predicate<byte[]> goOn, int maxBytes) throws Exception; }
    static volatile Looker LOOKER = Collection::lookNow;
    @FunctionalInterface interface Eyes { String see(ModelEntries.Entry eyes, byte[] jpeg, String question); }
    static volatile Eyes EYES = (e, b, q) -> Images.see(e, b, "image/jpeg", q, 300, false);
    @FunctionalInterface interface TwoEyes { String see(ModelEntries.Entry eyes, byte[] first, byte[] second, String question); }
    static volatile TwoEyes TWO_EYES = (e, a, b, q) -> Images.seeTwo(e, a, "image/jpeg", b, "image/jpeg", q, 120);
    static volatile BiFunction<String, String, Researcher.Drive> DRIVES = (drive, model) -> Researcher.watched(drive, model, "collect", Declines.toCrewsLog(null, "collection"));
    static volatile LongSupplier CLOCK = System::currentTimeMillis;
    /** How many pictures are fetched and measured side by side. */
    static volatile int FETCHERS = 3;

    static Images.Fetched lookNow(String url, int peek, Predicate<byte[]> goOn, int maxBytes) throws Exception {
        Fetch.Result r = Fetch.get(url, Images.FETCH_TIMEOUT, Map.of("Accept", "image/*,*/*;q=0.5"), Fetch.Policy.DEFAULT, peek, goOn, maxBytes);
        if (r.status() != 200) throw new IOException("the server answered HTTP " + r.status());
        return new Images.Fetched(r.body(), r.contentType());
    }

    // ---- where a collection lies ----

    static boolean jobName(String job) { return job != null && job.matches("[A-Za-z0-9_-]{1,40}"); }
    /** The time limit the person set for a collection after it was filed, in minutes from its start; 0 for none. */
    static int limitSet(LibraryStore store, String job) {
        try { Path f = dir(store, job).resolve("limit"); return Files.exists(f) ? Integer.parseInt(Files.readString(f, StandardCharsets.UTF_8).strip()) : 0; }
        catch (IOException | RuntimeException e) { return 0; }
    }

    /** Sets that limit: a file of its own beside the manifest, which the running job reads at its next step. */
    static void limit(LibraryStore store, String job, int minutes) throws IOException {
        Path d = dir(store, job);
        Files.createDirectories(d);
        Path tmp = d.resolve(".limit.part");
        Files.writeString(tmp, Integer.toString(minutes), StandardCharsets.UTF_8);
        Files.move(tmp, d.resolve("limit"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Path dir(LibraryStore store, String job) { return store.root().resolve("catalog").resolve("images").resolve("collections").resolve(job); }
    static Path manifestFile(LibraryStore store, String job) { return dir(store, job).resolve("manifest.json"); }
    /** The address the Pictures page shows a collection at. */
    public static String gallery(String job) { return ImagesPage.PATH + "?collection=" + job; }

    /** A collection's manifest, or null when there is none by that job. */
    public static ObjectNode manifest(LibraryStore store, String job) {
        if (!jobName(job)) return null;
        Path p = manifestFile(store, job);
        try { return Files.isRegularFile(p) ? (ObjectNode) M.readTree(Files.readString(p, StandardCharsets.UTF_8)) : null; } catch (IOException | ClassCastException e) { return null; }
    }

    /** The manifest of a collection in the library a conversation's file lies in (its catalog's), or null. */
    static ObjectNode manifestUnder(Path conversation, String job) {
        if (!jobName(job) || conversation == null) return null;
        for (Path p = conversation.toAbsolutePath().getParent(); p != null; p = p.getParent()) {
            if (!"catalog".equals(String.valueOf(p.getFileName()))) continue;
            Path f = p.resolve("images").resolve("collections").resolve(job).resolve("manifest.json");
            try { return Files.isRegularFile(f) ? (ObjectNode) M.readTree(Files.readString(f, StandardCharsets.UTF_8)) : null; } catch (IOException | ClassCastException e) { return null; }
        }
        return null;
    }

    /** Every collection of the library, newest first. */
    public static List<ObjectNode> all(LibraryStore store) {
        List<ObjectNode> out = new ArrayList<>();
        Path root = store.root().resolve("catalog").resolve("images").resolve("collections");
        if (!Files.isDirectory(root)) return out;
        try (var s = Files.list(root)) {
            for (Path d : s.sorted(Comparator.reverseOrder()).toList()) { ObjectNode m = manifest(store, d.getFileName().toString()); if (m != null) out.add(m); }
        } catch (IOException ignored) { }
        out.sort((a, b) -> b.path("started").asText("").compareTo(a.path("started").asText("")));
        return out;
    }

    /** A collection's small copy by its file name, or null. */
    public static Path file(LibraryStore store, String job, String name) {
        if (!jobName(job) || !LibraryStore.safeName(name) || !name.matches("[A-Za-z0-9_.-]{1,80}\\.jpg")) return null;
        Path p = dir(store, job).resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }

    // ---- filing ----

    /** Files a collection as a job, on the form given; the job's id. */
    static String file(LibraryStore store, CollectionForm form, String patronDid) throws IOException {
        ObjectNode args = form.json(M);
        args.put("title", "Pictures: " + Acquisitions.compress(form.words().isEmpty() ? form.ask() : form.words(), 60));
        if (form.minutes() > 0) args.put("max_minutes", form.minutes());
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        return jobs.submit(JOB_KIND, patronDid, args);
    }

    /** Files the keeping of a collection's pictures at full size as a job: in a library of one's own, on the person's word. The job's id. */
    static String fileKeep(LibraryStore store, String job, String patronDid) throws IOException {
        ObjectNode m = manifest(store, job);
        if (m == null) throw new IllegalArgumentException("No collection is filed as " + job + ".");
        if (Hosted.on(store)) throw new IllegalArgumentException(REFUSED_FULL);
        ObjectNode args = M.createObjectNode(); args.put("keep_from", job); args.put("title", "Pictures: keeping " + Acquisitions.compress(m.path("form").path("words").asText(job), 50) + " at full size");
        return new Jobs(store, j -> { throw new IllegalStateException("filed only"); }).submit(JOB_KIND, patronDid, args);
    }

    /** The pictures a collection passed, each fetched again and kept on the shelves at full size with its page and why it passed; the manifest names each one's entry. */
    static String keepFrom(LibraryStore store, String job, Consumer<ObjectNode> progress, BooleanSupplier stop) throws IOException {
        ObjectNode m = manifest(store, job);
        if (m == null) return "No collection is filed as " + job + ".";
        if (Hosted.on(store)) return REFUSED_FULL;
        ModelEntries.Entry eyes = ModelEntries.seeing();
        int kept = 0, failed = 0, n = 0;
        for (JsonNode x : m.path("passed")) {
            if (stop.getAsBoolean()) break;
            n++;
            if (!x.path("entry").asText("").isBlank()) continue;
            try { progress.accept(M.createObjectNode().put("phase", "Keeping picture " + n + " of " + m.path("passed").size() + " at full size")); } catch (RuntimeException ignored) { }
            Images.Found f = new Images.Found(x.path("image").asText(), x.path("page").asText(""), x.path("title").asText(""), x.path("source").asText(""), x.path("width").asInt(0), x.path("height").asInt(0), "");
            try {
                Images.Fetched got = LOOKER.get(f.image(), 0, null, MOST_BYTES);
                if (!Images.sha256(got.bytes()).equals(x.path("sha").asText())) throw new IOException("the file at its address has changed since it was judged");
                Images.Judged v = new Images.Judged(f, Images.Verdict.YES, x.path("why").asText(""), eyes == null ? "" : eyes.model(), m.path("form").path("words").asText(""));
                Images.Kept k = Images.keep(store, f, got, "", "pictures " + job, v, MOST_BYTES);
                ((ObjectNode) x).put("entry", k.id()); kept++;
            } catch (Stopping.Requested st) { throw st; }
            catch (Exception e) { ((ObjectNode) x).put("not_kept", Images.cut(String.valueOf(e.getMessage()), 160)); failed++; }
            AtomicWrite.bytes(manifestFile(store, job), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
        }
        return kept + " of " + m.path("passed").size() + " pictures of the collection " + job + " kept at full size in the library" + (failed == 0 ? "." : "; " + failed + " could not be (the gallery says why each).") + " " + gallery(job);
    }

    // ---- the job ----

    /** The job as the library's runs run it: on the drive and model the job was given, its progress onto its record, under its stop. */
    public static String runJob(LibraryStore store, ObjectNode job, String drive, String model, Consumer<ObjectNode> progress, BooleanSupplier stop) throws IOException { return runJob(store, job, drive, model, progress, stop, null); }

    /**
     * The same, its work told to the job's {@link Checkpoint} each time the manifest is written, so the service knows it goes forward: a job
     * the service stopped three times in a row with nothing new kept is ended as failed, and a collection keeps its work in its manifest.
     */
    public static String runJob(LibraryStore store, ObjectNode job, String drive, String model, Consumer<ObjectNode> progress, BooleanSupplier stop, Checkpoint cp) throws IOException {
        String id = job.path("job_id").asText("");
        if (!jobName(id)) throw new IllegalArgumentException("a collection needs its job's id");
        String from = job.path("args").path("keep_from").asText("");
        if (!from.isEmpty()) return keepFrom(store, from, progress == null ? p -> { } : progress, stop == null ? () -> false : stop);
        CollectionForm form = CollectionForm.fromArgs(job.path("args"));
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes == null) return ModelEntries.noEyes();
        Researcher.Drive seat = DRIVES.apply(drive, model);
        boolean read = false;
        if (form.words().isEmpty() && !form.ask().isEmpty()) { CollectionForm filled = fill(seat, form.ask()); if (filled != null) { form = filled; read = true; } }
        if (form.words().isEmpty()) return "A collection needs to know what the pictures are of; nothing was searched.";
        CollectionForm.Grounded held = form.grounded(form.ask());   // what narrows it, only as the person's own words say it
        form = held.form();
        boolean refused = false;
        if (form.keepFull() && Hosted.on(store)) { form = form.linksOnly(); refused = true; }
        Run r = new Run(store, id, form, eyes, seat, model, job.path("patron").asText(""), progress, stop);
        r.cp = cp;
        r.m.put("form_read", read || r.m.path("form_read").asBoolean(false));
        List<String> dropped = new ArrayList<>(held.dropped());
        for (JsonNode d : job.path("args").path("dropped")) if (!dropped.contains(d.asText())) dropped.add(d.asText());   // what the chat dropped before it asked
        if (!dropped.isEmpty()) { ArrayNode dr = r.m.putArray("dropped"); dropped.forEach(dr::add); }   // the model's readings the person's words do not say, for reading by hand
        if (refused) r.m.put("refused_full", true);
        PicturePlaces.BRAVE_LEFT.set(new int[]{Math.max(0, 10 - r.m.path("brave_calls").asInt(0))});
        try { r.loop(); }
        finally { r.end(); PicturePlaces.BRAVE_LEFT.remove(); }
        return r.summary();
    }

    /** The form read from a request that came as words alone, by the model as the chat's model reads it; null when it gave none. */
    static CollectionForm fill(Researcher.Drive seat, String ask) {
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "system").put("content", "Read the person's request for a collection of pictures and call collect once with the form filled in from it: what the pictures are of, and each other field only where the request says it.");
        msgs.addObject().put("role", "user").put("content", ask);
        ArrayNode tools = M.createArrayNode();
        ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
        fn.put("name", "collect").put("description", "The form of a collection of pictures.");
        ObjectNode params = fn.putObject("parameters").put("type", "object"), props = params.putObject("properties");
        for (var e : CollectionForm.FIELDS.entrySet()) if (!e.getKey().equals("ask")) props.putObject(e.getKey()).put("type", e.getValue()[0]).put("description", e.getValue()[1].replaceFirst("^For collect: ", ""));
        params.putArray("required").add("words").add("kind");
        try {
            ObjectNode reply = seat.decide(msgs, tools, 400, "auto");
            for (JsonNode call : reply.path("tool_calls")) {
                JsonNode a = Researcher.parseArgs(call.path("function").path("arguments"));
                CollectionForm f = CollectionForm.fromArgs(a).withAsk(ask);
                if (!f.words().isEmpty()) return f;
            }
        } catch (Stopping.Requested stop) { throw stop; } catch (RuntimeException e) { return null; }
        return null;
    }

    /** One picture's facts on its way through: the candidate, what set it aside ({@code reason} "" when nothing), the bytes the model looks at and the small copy, its hash, its file's facts, and its record's provenance. */
    record Prepared(PicturePlaces.Candidate c, String reason, String why, PictureFile.Facts facts, byte[] full, String type, byte[] look, byte[] thumb, PictureHash.Print print, String sha, String provenance) {
        static Prepared aside(PicturePlaces.Candidate c, String reason, String why) { return new Prepared(c, reason, why, null, null, "", null, null, null, "", ""); }
    }

    /** The reasons a picture is set aside, as the manifest counts them. */
    static final List<String> REASONS = List.of("seen", "small", "shape", "format", "ai", "unverified", "copy", "no", "unsure", "unjudged", "no_value", "taken", "check", "not_picture", "unreadable", "unfetched");

    /** A judged picture's hash, kept to tell its copies at once: whether it was kept (its number) or turned down. */
    record Judged(PictureHash.Print print, int kept, String thumbFile) { }

    static final class Run {
        final LibraryStore store; final String job; final CollectionForm form; final ModelEntries.Entry eyes; final Researcher.Drive seat; final String model, patron;
        final Consumer<ObjectNode> progress; final BooleanSupplier stop;
        final ObjectNode m;
        final Set<String> seen = new HashSet<>(), shas = new HashSet<>(), values = new LinkedHashSet<>();
        final List<Judged> judged = new ArrayList<>();
        final Map<Integer, byte[]> keptThumbs = new LinkedHashMap<>();
        final long began;
        int dry = 0, unanswered = 0; final Set<String> dryPlaces = new HashSet<>();
        String stopped = "";

        Run(LibraryStore store, String job, CollectionForm form, ModelEntries.Entry eyes, Researcher.Drive seat, String model, String patron, Consumer<ObjectNode> progress, BooleanSupplier stop) throws IOException {
            this.store = store; this.job = job; this.form = form; this.eyes = eyes; this.seat = seat; this.model = model == null ? "" : model; this.patron = patron;
            this.progress = progress == null ? p -> { } : progress; this.stop = stop == null ? () -> false : stop;
            ObjectNode before = manifest(store, job);
            m = before != null ? before : M.createObjectNode();
            if (before == null) {
                m.put("job", job); m.put("patron", patron); m.put("started", Instant.now().toString()); m.put("spent_ms", 0);
                ObjectNode counts = m.putObject("counts"); counts.put("found", 0); counts.put("judged", 0); for (String r : REASONS) counts.put(r, 0);
                m.putObject("places"); m.putArray("searches"); m.putArray("passed"); m.putArray("copies"); m.putArray("unverified"); m.putArray("turned_down"); m.putArray("prints"); m.putArray("seen");
            }
            m.set("form", form.json(M)); m.put("said", form.said(Chrome.ENGLISH)); m.put("name_used", name()); m.put("state", "running"); m.remove("stopped"); m.remove("ended");
            m.put("eyes", eyes.name() + " (" + eyes.model() + ")"); m.put("seat", this.model);
            began = CLOCK.getAsLong() - m.path("spent_ms").asLong(0);   // a job that resumes counts the time it already spent
            for (JsonNode s : m.path("seen")) seen.add(s.asText());
            for (JsonNode p : m.path("passed")) { shas.add(p.path("sha").asText()); if (!p.path("value").asText("").isEmpty()) values.add(p.path("value").asText()); Path t = file(store, job, p.path("thumb").asText("")); if (t != null) keptThumbs.put(p.path("n").asInt(), Files.readAllBytes(t)); }
            for (JsonNode p : m.path("prints")) { Map<String, Long> h = new LinkedHashMap<>(); p.path("hashes").fields().forEachRemaining(e -> h.put(e.getKey(), Long.parseUnsignedLong(e.getValue().asText(), 16))); if (h.containsKey("full")) judged.add(new Judged(new PictureHash.Print(h, p.path("aspect").asDouble(1)), p.path("kept").asInt(0), p.path("thumb").asText(""))); }
            Files.createDirectories(dir(store, job));
            save();
        }

        int kept() { return m.path("passed").size(); }
        long elapsed() { return CLOCK.getAsLong() - began; }
        ObjectNode counts() { return (ObjectNode) m.path("counts"); }
        void count(String reason) { counts().put(reason, counts().path(reason).asInt(0) + 1); }

        /** A time limit the person set after the job started ({@code library_job op=limit}), read from its file now and then; 0 for none. */
        int limitLater = 0; long limitRead = 0;
        int minutes() {
            long now = CLOCK.getAsLong();
            if (limitRead == 0 || now - limitRead > 5_000) {
                limitRead = now;
                int l = limitSet(store, job);
                if (l > 0 && l != limitLater) { limitLater = l; ((ObjectNode) m.path("form")).put("minutes", l); m.put("said", form.withMinutes(l).said(Chrome.ENGLISH)); }
            }
            return limitLater > 0 ? limitLater : form.minutes();
        }

        /** Why the job ends now, or "" when it goes on: the count, the person's stop, the time limit, the ceiling of judged pictures. */
        String ends() {
            if (stopped.equals("eyes")) return "eyes";
            if (kept() >= form.target()) return "count";
            if (stop.getAsBoolean()) return "stop";
            int minutes = minutes();
            if (minutes > 0 && elapsed() >= minutes * 60_000L - 30_000L) return "time";
            if (counts().path("judged").asInt(0) >= form.judgeCeiling()) return "ceiling";
            return "";
        }

        Checkpoint cp;

        void save() throws IOException {
            m.put("spent_ms", elapsed());
            Path f = manifestFile(store, job);
            AtomicWrite.bytes(f, M.writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
            if (cp != null) { cp.put("collection", M.createObjectNode().put("kept", kept()).put("searches", m.path("searches").size()).put("judged", counts().path("judged").asInt(0))); cp.position(null, kept() + " of " + form.target() + " kept, " + m.path("searches").size() + " searches made"); }
        }

        void said(String phase) {
            ObjectNode p = M.createObjectNode();
            long min = elapsed() / 60_000;
            p.put("phase", phase + " · " + kept() + " of " + form.target() + " kept · " + counts().path("judged").asInt(0) + " judged · " + min + (minutes() > 0 ? " of " + minutes() : "") + " min");
            p.put("kept", kept()); p.put("count", form.target()); p.put("at", Instant.now().toString());
            try { progress.accept(p); } catch (RuntimeException ignored) { }
        }

        // ---- the loop: the model chooses each search ----

        List<PicturePlaces.Place> open() {
            List<PicturePlaces.Place> out = new ArrayList<>();
            for (PicturePlaces.Place p : PLACES.get()) if (p.most() <= 0 || m.path("places").path(p.name()).path("calls").asInt(0) < p.most()) out.add(p);
            return out;
        }

        String system() {
            StringBuilder b = new StringBuilder();
            b.append("You gather a collection of pictures for a person. ");
            if (!form.ask().isEmpty()) b.append("Their request, in their own words: «").append(Images.cut(form.ask(), 600)).append("». ");
            b.append("The collection, as they agreed to it: ").append(form.said(Chrome.ENGLISH)).append(".\n");
            b.append("Each call of search looks in one place for pictures that match your words. The library fetches every picture found, measures its size and shape from the file, reads from the file whether AI made it, sets aside copies of pictures it already has, and has a model that sees judge whether it shows ")
             .append(form.judgedAgainst()).append(form.differBy().isEmpty() ? "" : " and which " + form.differBy() + " it shows").append(". It tells you after each search what it kept and why it set the rest aside.\n");
            b.append("The places:\n");
            for (PicturePlaces.Place p : PLACES.get()) b.append("  ").append(p.name()).append(": ").append(p.line()).append('\n');
            b.append("Choose the place and the words of each search from what the earlier searches kept: search for what the collection still lacks, in the places and with the words that keep pictures. page=2 takes the next page of a search that kept pictures. ")
             .append("Words in another language are fine where the pictures are named so. When you are sure no more such pictures can be found, call done with the reason.");
            return b.toString();
        }

        ArrayNode tools(List<PicturePlaces.Place> places) {
            ArrayNode t = M.createArrayNode();
            ObjectNode s = t.addObject().put("type", "function").putObject("function");
            List<String> names = new ArrayList<>(); for (PicturePlaces.Place p : places) names.add(p.name());
            s.put("name", "search").put("description", "Finds pictures in one place for your words; the library judges each and says what it kept. where = " + String.join(" | ", names) + ".");
            ObjectNode sp = s.putObject("parameters").put("type", "object"), props = sp.putObject("properties");
            ObjectNode where = props.putObject("where").put("type", "string").put("description", "The place: " + String.join(", ", names) + ".");
            ArrayNode en = where.putArray("enum"); names.forEach(en::add);
            props.putObject("query").put("type", "string").put("description", "The words to search with.");
            props.putObject("page").put("type", "integer").put("description", "The page of the same search: 2 for the next one.");
            sp.putArray("required").add("where").add("query");
            ObjectNode d = t.addObject().put("type", "function").putObject("function");
            d.put("name", "done").put("description", "End the collection: you are sure no more such pictures can be found. Say why.");
            ObjectNode dp = d.putObject("parameters").put("type", "object"); dp.putObject("properties").putObject("why").put("type", "string").put("description", "Why no more can be found.");
            dp.putArray("required").add("why");
            return t;
        }

        /** The table for the brain's own reading of a decision: one search instrument, its example the person's own words. */
        List<Instrument> table() {
            Instrument search = new Instrument() {
                @Override public String name() { return "search"; }
                @Override public String words() { return "Finds pictures in one place for your words · " + String.join("; ", open().stream().map(p -> p.name() + ": " + p.line()).toList()); }
                @Override public ObjectNode schema(ObjectMapper mm) { return Instruments.schema("where!", "string", "query!", "string", "page", "integer"); }
                @Override public String example() { ObjectNode e = M.createObjectNode(); e.put("where", "web"); e.put("query", form.words()); return e.toString(); }
                @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier st) { return Result.of("search", "", ""); }
            };
            return List.of(search);
        }

        void loop() throws IOException {
            ArrayNode history = M.createArrayNode();
            history.addObject().put("role", "system").put("content", system());
            StringBuilder start = new StringBuilder(form.target() + " pictures are wanted");
            if (m.path("searches").size() > 0) {   // a restarted job: the searches already made, so the model goes on from them
                start.append("; ").append(kept()).append(" kept so far. The searches made before a restart:");
                for (JsonNode s : m.path("searches")) start.append("\n  ").append(s.path("where").asText()).append(" \"").append(s.path("query").asText()).append("\" page ").append(s.path("page").asInt(1)).append(": kept ").append(s.path("kept").asInt(0)).append(" of ").append(s.path("found").asInt(0));
            } else start.append("; none kept yet. Begin with a search.");
            history.addObject().put("role", "user").put("content", start.toString());
            Brain brain = Brain.of(seat, null, model, null, o -> { });
            int quiet = 0;
            said("Starting");
            while (true) {
                String end = ends();
                if (!end.isEmpty()) { stopped = end; return; }
                if (m.path("searches").size() >= MOST_SEARCHES) { stopped = "searches"; return; }
                List<PicturePlaces.Place> places = open();
                if (places.isEmpty()) { stopped = "places"; return; }
                ArrayNode tools = tools(places);
                Brain.Context ctx = new Brain.Context(null, "", List.of(), table(), new Researcher.Budget(0), List.of(), history);
                ObjectNode assistant;
                try { assistant = brain.decide(ctx, Brain.Weight.LIGHT, (h, t, n) -> seat.chat(h, t, n, "auto"), tools, 1500, Set.of()).assistant(); }
                catch (Stopping.Requested st) { stopped = "stop"; return; }
                catch (RuntimeException e) { m.put("model_failed", Images.cut(String.valueOf(e.getMessage()), 200)); stopped = "quiet"; return; }
                if (assistant == null) { stopped = "quiet"; return; }
                history.add(assistant);
                JsonNode calls = assistant.path("tool_calls");
                if (!calls.isArray() || calls.isEmpty()) {
                    if (++quiet >= 2) { stopped = "quiet"; return; }
                    history.addObject().put("role", "user").put("content", "Act by calling a tool: search, or done with the reason.");
                    continue;
                }
                quiet = 0;
                for (JsonNode call : calls) {
                    String name = call.path("function").path("name").asText(""), callId = call.path("id").asText("call");
                    JsonNode a = Researcher.parseArgs(call.path("function").path("arguments"));
                    String observation;
                    if (name.equals("done")) { m.put("done_why", Images.cut(a.path("why").asText(""), 400)); stopped = "done"; history.addObject().put("role", "tool").put("tool_call_id", callId).put("content", "Done."); return; }
                    if (!name.equals("search")) observation = "There is no tool named " + name + ": search, or done with the reason.";
                    else {
                        String where = a.path("where").asText("").strip().toLowerCase(Locale.ROOT), query = a.path("query").asText("").strip();
                        int page = Math.max(1, a.path("page").asInt(1));
                        PicturePlaces.Place place = places.stream().filter(p -> p.name().equals(where)).findFirst().orElse(null);
                        if (place == null) observation = "No place is called \"" + where + "\" here; the places are " + String.join(", ", places.stream().map(PicturePlaces.Place::name).toList()) + ".";
                        else if (query.isEmpty()) observation = "Give the words to search with.";
                        else observation = search(place, query, page);
                    }
                    history.addObject().put("role", "tool").put("tool_call_id", callId).put("content", observation);
                    String end2 = ends();
                    if (!end2.isEmpty()) { stopped = end2; return; }
                    if (sure()) { stopped = "sure"; return; }
                }
            }
        }

        /** Sure there are no more: {@link #SURE_AFTER} searches since the last picture kept brought nothing new for the judge, in at least two places. */
        boolean sure() { return dry >= SURE_AFTER && dryPlaces.size() >= Math.min(2, PLACES.get().size()); }

        // ---- one search ----

        String search(PicturePlaces.Place place, String query, int page) throws IOException {
            said("Searching " + place.name() + " for \"" + Images.cut(query, 60) + "\"");
            ObjectNode places = (ObjectNode) m.path("places");
            ObjectNode tally = places.get(place.name()) instanceof ObjectNode t0 ? t0 : places.putObject(place.name());
            tally.put("calls", tally.path("calls").asInt(0) + 1);
            List<PicturePlaces.Candidate> found;
            String failed = null;
            try {
                if (place.searcher() == null) { found = new ArrayList<>(); for (Images.Found f : Images.shelves(store, query, 60)) found.add(new PicturePlaces.Candidate(f, PicturePlaces.Record.NONE)); }
                else {
                    int[] braveBefore = PicturePlaces.BRAVE_LEFT.get().clone();
                    found = place.searcher().search(query, page, form);
                    if (PicturePlaces.BRAVE_LEFT.get()[0] < braveBefore[0]) m.put("brave_calls", m.path("brave_calls").asInt(0) + 1);
                }
            } catch (Stopping.Requested st) { throw st; }
            catch (Exception e) { found = List.of(); failed = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            Map<String, Integer> here = new LinkedHashMap<>();
            Map<String, String> example = new LinkedHashMap<>();
            List<String> keptHere = new ArrayList<>();
            List<PicturePlaces.Candidate> fresh = new ArrayList<>();
            List<String> freshKeys = new ArrayList<>();
            SourceRules rules = SourceRules.live();
            for (PicturePlaces.Candidate c : found) {
                String key = Images.imageKey(c.found().image());
                if (key.isBlank() || !seen.add(key)) { here.merge("seen", 1, Integer::sum); count("seen"); continue; }
                freshKeys.add(key);   // on the record once the search has gone through them: a restart in the middle looks at the rest again
                if ((c.found().page() != null && !c.found().page().isBlank() && rules.refused(c.found().page())) || rules.refused(c.found().image())) continue;   // the person's own refused sources
                // an icon, a drawing or a document by its address (SearXNG's icon engines answer every picture search with SVG icons: 2,363 of
                // 2,929 candidates for three searches on 2026-11-19, counted then as "a file the library cannot open"): not fetched, said as what it is
                if (Images.notRaster(c.found().image())) { here.merge("not_picture", 1, Integer::sum); example.putIfAbsent("not_picture", "an icon, a drawing or a document, not a picture"); count("not_picture"); continue; }
                fresh.add(c);
            }
            counts().put("found", counts().path("found").asInt(0) + found.size());
            // the search is on the record from the moment it answered, so a restart in the middle of it still shows it
            ObjectNode s = ((ArrayNode) m.path("searches")).addObject();
            s.put("where", place.name()); s.put("query", query); s.put("page", page); s.put("found", found.size()); s.put("fresh", fresh.size()); s.put("kept", 0); s.put("at", Instant.now().toString()); s.put("going", true);
            if (failed != null) s.put("failed", Images.cut(failed, 160));
            save();
            if (form.minWidth() > 0 || form.minHeight() > 0) fresh.sort((x, y) -> Integer.compare(Math.max(y.found().width(), y.found().height()), Math.max(x.found().width(), x.found().height())));   // a claimed size orders the fetching, never passes a picture
            int before = kept(), judgedBefore = counts().path("judged").asInt(0);
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, FETCHERS), r -> { Thread t = new Thread(r, "collect-fetch"); t.setDaemon(true); return t; });
            try {
                // a few fetched ahead, in order: a search that reaches the number early has not downloaded the rest of its pictures
                List<Future<Prepared>> todo = new ArrayList<>();
                int ahead = Math.max(1, FETCHERS) * 2, next = 0;
                for (; next < Math.min(ahead, fresh.size()); next++) { PicturePlaces.Candidate c = fresh.get(next); todo.add(pool.submit(() -> prepare(c))); }
                for (int i = 0; i < todo.size(); i++) {
                    if (!ends().isEmpty()) break;
                    Prepared p;
                    try { p = todo.get(i).get(); } catch (Exception e) { continue; }
                    finally { if (next < fresh.size()) { PicturePlaces.Candidate c = fresh.get(next++); todo.add(pool.submit(() -> prepare(c))); } }
                    String r = decide(p);
                    if (r.isEmpty()) keptHere.add(p.c().found().title().isBlank() ? p.c().found().host() : "\"" + Images.cut(p.c().found().title(), 60) + "\"");
                    else { here.merge(r, 1, Integer::sum); example.putIfAbsent(r, p.why()); }
                }
            } finally { pool.shutdownNow(); }
            int keptNow = kept() - before;
            tally.put("found", tally.path("found").asInt(0) + found.size()); tally.put("kept", tally.path("kept").asInt(0) + keptNow);
            s.put("kept", keptNow); s.remove("going");
            for (String k : freshKeys) ((ArrayNode) m.path("seen")).add(k);
            // a place that did not answer says nothing of whether there are more: it is no evidence for being sure (wallhaven answered 502 once, 2026-11-19);
            // nor does a search whose new pictures were judged and turned down: its words found the wrong pictures, and the model is told so and
            // searches again (the judge's ceiling and the person's time bound that). Evidence that there are no more is a search that brought
            // nothing new for the judge at all. Counted as "kept nothing", a collection of a film character stopped at 4 of 20 after 7 searches,
            // in 5 minutes of the 30 the person gave, when a run of the same request had found 19 (2026-11-19).
            int judgedNow = counts().path("judged").asInt(0) - judgedBefore;
            if (failed == null) { if (keptNow > 0) { dry = 0; dryPlaces.clear(); } else if (judgedNow == 0) { dry++; dryPlaces.add(place.name()); } }
            m.put("dry", dry); m.put("dry_places", dryPlaces.size());
            save();
            return observation(place, query, page, found.size(), fresh.size(), keptHere, here, example, failed);
        }

        String observation(PicturePlaces.Place place, String query, int page, int found, int fresh, List<String> keptHere, Map<String, Integer> here, Map<String, String> example, String failed) {
            StringBuilder b = new StringBuilder(place.name()).append(" \"").append(query).append("\"").append(page > 1 ? " page " + page : "").append(": ");
            if (failed != null) return b.append("the place did not answer (").append(Images.cut(failed, 120)).append("). ").append(status()).toString();
            b.append(found).append(" found, ").append(fresh).append(" new to this collection. ");
            b.append(keptHere.isEmpty() ? "Kept none." : "Kept " + keptHere.size() + ": " + String.join("; ", keptHere.subList(0, Math.min(6, keptHere.size()))) + (keptHere.size() > 6 ? "; …" : "") + ".");
            List<String> aside = new ArrayList<>();
            for (var e : here.entrySet()) {
                if (e.getKey().equals("seen")) continue;
                String ex = example.getOrDefault(e.getKey(), "");
                aside.add(REASON_SAID.getOrDefault(e.getKey(), e.getKey()) + ": " + e.getValue() + (ex.isBlank() ? "" : " (" + Images.cut(ex, 90) + ")"));
            }
            if (!aside.isEmpty()) b.append(" Set aside: ").append(String.join("; ", aside)).append('.');
            return b.append(' ').append(status()).toString();
        }

        String status() {
            String s = "Kept so far: " + kept() + " of " + form.target() + ".";
            if (!form.differBy().isEmpty() && !values.isEmpty()) s += " The " + form.differBy() + " already in the collection: " + String.join("; ", values) + ".";
            int left = form.judgeCeiling() - counts().path("judged").asInt(0);
            if (left < 20) s += " The model that sees can judge " + Math.max(0, left) + " more.";
            return s;
        }

        // ---- one picture, read and measured (side by side with others) ----

        Prepared prepare(PicturePlaces.Candidate c) {
            Images.Found f = c.found();
            String url = f.image();
            byte[] bytes; String type;
            try {
                if (url.startsWith("/images/")) { Images.Fetched got = Images.bytesOf(store, url, false); bytes = got.bytes(); type = got.contentType(); }
                else {
                    String[] early = {null, null};
                    Images.Fetched got = LOOKER.get(url, PEEK, head -> {
                        PictureFile.Facts h = PictureFile.read(head);
                        if (!h.sized()) return true;
                        String fail = form.sizeFails(h.width(), h.height());
                        if (fail != null) { early[0] = fail.contains("where") || fail.contains("not square") ? "shape" : "small"; early[1] = fail; return false; }
                        if (!form.format().isEmpty() && !h.format().isEmpty() && !h.format().equals(form.format())) { early[0] = "format"; early[1] = "a " + h.format().toUpperCase(Locale.ROOT) + " file"; return false; }
                        return true;
                    }, MOST_BYTES);
                    if (early[0] != null) return Prepared.aside(c, early[0], early[1]);
                    bytes = got.bytes(); type = got.contentType();
                }
            } catch (Stopping.Requested st) { throw st; }
            catch (Exception e) { return Prepared.aside(c, "unfetched", e.getMessage() == null ? "it could not be fetched" : Images.cut(e.getMessage(), 120)); }
            if (bytes == null || bytes.length == 0) return Prepared.aside(c, "unfetched", "nothing came back");
            PictureFile.Facts facts = PictureFile.read(bytes);
            if (facts.format().isEmpty()) return Prepared.aside(c, "not_picture", Images.notLookable(new Images.Fetched(bytes, type)));   // a web page came back (a site that refuses others' links to its pictures), an icon, a document
            int w = facts.width(), h = facts.height();
            if (w <= 0 || h <= 0) { int[] d = Images.dimensions(bytes); w = d[0]; h = d[1]; facts = new PictureFile.Facts(w, h, facts.format(), facts.declared()); }
            String fail = form.sizeFails(w, h);
            if (fail != null && (form.minWidth() > 0 || form.minHeight() > 0 || !form.orientation().isEmpty())) return Prepared.aside(c, fail.contains("where") || fail.contains("not square") ? "shape" : "small", fail);
            if (!form.format().isEmpty() && !facts.format().equals(form.format())) return Prepared.aside(c, "format", "a " + facts.format().toUpperCase(Locale.ROOT) + " file");
            if (form.ai().equals(CollectionForm.AI_NO) && facts.declared().ai()) return Prepared.aside(c, "ai", facts.declared().why());
            if (form.ai().equals(CollectionForm.AI_NO) && !c.record().aiSaid().isEmpty()) return Prepared.aside(c, "ai", c.record().aiSaid());
            String provenance = provenance(c, facts);
            BufferedImage small = PictureHash.small(bytes, LOOK_PX);
            if (small == null && f.thumbnail() != null && f.thumbnail().matches("(?i)^https?://.+")) {   // a format the program cannot open (WebP, AVIF): the place's own small copy is looked at
                try { Images.Fetched t = LOOKER.get(f.thumbnail(), 0, null, 8 * 1024 * 1024); small = PictureHash.small(t.bytes(), LOOK_PX); } catch (Stopping.Requested st) { throw st; } catch (Exception ignored) { }
            }
            if (small == null) return Prepared.aside(c, "unreadable", "a " + facts.format().toUpperCase(Locale.ROOT) + " picture the library cannot open");
            byte[] look = PictureHash.jpeg(small, LOOK_PX, 0.85f), thumb = PictureHash.jpeg(small, THUMB_PX, 0.8f);
            if (look == null || thumb == null) return Prepared.aside(c, "unreadable", "it could not be made small");
            String reason = form.ai().equals(CollectionForm.AI_NO) && provenance.isEmpty() ? "unverified" : "";
            return new Prepared(c, reason, reason.isEmpty() ? "" : "no record of who made it or when", facts, form.keepFull() ? bytes : null, type, look, thumb, PictureHash.print(small), Images.sha256(bytes), provenance);
        }

        /** What a picture's record and file say of where it came from, in a few words; "" when they say nothing that shows a person made it. */
        static String provenance(PicturePlaces.Candidate c, PictureFile.Facts facts) {
            PicturePlaces.Record r = c.record();
            if (r.dated() && r.aiSaid().isEmpty() && !facts.declared().ai()) return "a dated record: " + r.date() + (r.author().isBlank() ? "" : ", " + Images.cut(r.author(), 80));
            if (facts.declared().ai()) return "";
            return PicturePlaces.photographerAndYear(c.found().title() + " " + c.found().caption());
        }

        // ---- one picture, decided ----

        /** The decision on one picture: "" when it is kept, else the reason it was set aside. */
        String decide(Prepared p) throws IOException {
            Images.Found f = p.c().found();
            if (!p.reason().isEmpty() && !p.reason().equals("unverified")) { aside(p, p.reason(), p.why()); return p.reason(); }
            if (shas.contains(p.sha())) { aside(p, "copy", "the same file as a kept picture"); return "copy"; }
            // the hash: at or under SURE bits a copy outright; between SURE and SAME a copy only if the model that sees says so, after its
            // look at what the picture shows (a fox in snow and another fox in snow, two photographs, measured 6 bits apart, 2026-11-19)
            Judged near = null; int best = 65;
            for (Judged j : judged) { int d = PictureHash.distance(p.print(), j.print()); if (d < best) { best = d; near = j; } }
            if (near != null && best <= PictureHash.SURE) {
                String how = near.kept() > 0 ? "a copy of picture " + near.kept() + " (" + best + " of 64 bits differ)" : "a copy of a picture already turned down (" + best + " of 64 bits differ)";
                if (near.kept() > 0) copy(p, near.kept(), best + " of 64 bits differ");
                aside(p, "copy", how); return "copy";
            }
            if (p.reason().equals("unverified")) {
                count("unverified");
                ArrayNode u = (ArrayNode) m.path("unverified");
                if (u.size() < KEEP_UNVERIFIED) { String t = "u-" + p.sha().substring(0, 16) + ".jpg"; AtomicWrite.bytes(dir(store, job).resolve(t), p.thumb()); row(u.addObject(), p).put("thumb", t); }
                return "unverified";
            }
            // the model that sees
            count("judged");
            said("Judging \"" + Images.cut(f.title().isBlank() ? f.host() : f.title(), 50) + "\"");
            String q = question(f);
            String answer = EYES.see(eyes, p.look(), q);
            Images.Judged v = Images.verdict(f, form.judgedAgainst(), eyes.model(), firstLine(answer));
            if (Images.unread(v, answer)) { String again = EYES.see(eyes, p.look(), q + Images.ONE_WORD); Images.Judged v2 = Images.verdict(f, form.judgedAgainst(), eyes.model(), firstLine(again)); v = v2.verdict() == Images.Verdict.UNJUDGED ? Images.noVerdictTwice(v, again) : v2; if (v2.verdict() != Images.Verdict.UNJUDGED) answer = again; }
            String thumbFile = p.sha().substring(0, 16) + ".jpg";
            if (v.verdict() == Images.Verdict.UNJUDGED) {   // nothing answered: not a no, and not remembered as turned down; a model that stops answering ends the job
                if (++unanswered >= 3) stopped = "eyes";
                aside(p, "unjudged", v.why()); return "unjudged";
            }
            unanswered = 0;
            if (!v.yes()) {
                remember(p, 0, "");
                String r = v.verdict() == Images.Verdict.UNSURE ? "unsure" : "no";
                aside(p, r, v.why()); return r;
            }
            String value = "";
            if (!form.differBy().isEmpty()) {
                value = valueIn(answer);
                if (value.isEmpty() && (answer == null || !answer.toLowerCase(Locale.ROOT).contains("which:"))) {   // the line left out, not "unknown": asked for once more, alone
                    String again = EYES.see(eyes, p.look(), whichQuestion(f));
                    value = valueIn(again);
                    if (again != null) answer = answer + "\n" + again;
                }
                if (value.isEmpty()) { remember(p, 0, ""); aside(p, "no_value", "the model named no " + form.differBy() + ": \"" + Images.cut(answer, 140) + "\""); return "no_value"; }
                String named = value;
                String taken = values.stream().filter(x -> sameValue(x, named)).findFirst().orElse(null);
                if (taken != null) { remember(p, 0, ""); aside(p, "taken", "another picture already shows " + taken); return "taken"; }
            }
            // close to a kept picture, by the hash or by the embedding: the model that sees settles whether it is the same picture
            Map<Integer, String> close = new LinkedHashMap<>();
            for (Judged j : judged) if (j.kept() > 0) { int d = PictureHash.distance(p.print(), j.print()); if (d <= PictureHash.SAME) close.put(j.kept(), d + " of 64 bits differ"); }
            LikenessHelper.Scorer scorer = LikenessHelper.scorer();
            if (scorer != null) {
                for (var k : keptThumbs.entrySet()) {
                    if (close.containsKey(k.getKey())) continue;
                    double like;
                    try { like = scorer.score(k.getValue(), "image/jpeg", p.thumb(), "image/jpeg").picture(); } catch (IOException | RuntimeException e) { break; }
                    if (like >= CLIP_CLOSE) close.put(k.getKey(), String.format(Locale.ROOT, "likeness %.2f", like));
                }
            }
            for (var k : close.entrySet()) {
                byte[] kept = keptThumbs.get(k.getKey());
                if (kept == null) continue;
                count("compared");
                String same = TWO_EYES.see(eyes, kept, p.thumb(), SAME_PICTURE);
                ArrayNode looks = m.path("compared").isArray() ? (ArrayNode) m.path("compared") : m.putArray("compared");   // each look, for reading by hand
                if (looks.size() < KEEP_COPIES) looks.addObject().put("kept", k.getKey()).put("image", p.c().found().image()).put("how", k.getValue()).put("said", Images.cut(same == null ? "" : same, 160));
                if (same != null && same.strip().toLowerCase(Locale.ROOT).replaceFirst("^[^a-z]+", "").startsWith("same")) {
                    remember(p, 0, "");
                    copy(p, k.getKey(), k.getValue() + "; the model that sees: the same picture");
                    aside(p, "copy", "a copy of picture " + k.getKey() + " (" + k.getValue() + ", the same picture to the model that sees)");
                    return "copy";
                }
            }
            // the one question every picture from the web is asked before the library keeps any copy of it
            if (!form.keepFull()) {
                ContentJudge.Reading check;
                try { check = Images.CHECK.apply(eyes, new Images.Fetched(p.look(), "image/jpeg")); } catch (Stopping.Requested st) { throw st; } catch (RuntimeException e) { check = null; }
                if (check != null && check.leansYes()) { remember(p, 0, ""); aside(p, "check", "the check every picture from the web goes through before it is kept said no"); return "check"; }
            }
            int n = kept() + 1;
            String entry = "";
            if (form.keepFull() && p.full() != null) {
                try { Images.Kept k = Images.keep(store, f, new Images.Fetched(p.full(), p.type()), "", "pictures " + job, v, MOST_BYTES); entry = k.id(); }
                catch (IOException e) { remember(p, 0, ""); aside(p, "check", e.getMessage()); return "check"; }
            }
            AtomicWrite.bytes(dir(store, job).resolve(thumbFile), p.thumb());
            ObjectNode row = row(((ArrayNode) m.path("passed")).addObject(), p);
            if (!name().isEmpty()) row.put("named_by", namedBy(f));
            row.put("n", n); row.put("thumb", thumbFile); row.put("why", v.why().replaceFirst("^[*_\\s.:,;—–-]+", "")); if (!value.isEmpty()) row.put("value", value); if (!entry.isEmpty()) row.put("entry", entry);   // the why without a bold's leftovers ("**Yes.** The picture …")
            shas.add(p.sha()); keptThumbs.put(n, p.thumb()); if (!value.isEmpty()) values.add(value);
            remember(p, n, thumbFile);
            save();
            return "";
        }

        /** A picture's facts as a manifest row: its address, page, title, place, measured size and format, record and provenance. */
        ObjectNode row(ObjectNode o, Prepared p) {
            Images.Found f = p.c().found(); PicturePlaces.Record r = p.c().record();
            o.put("image", f.image()); o.put("page", f.page() == null ? "" : f.page()); o.put("title", f.title() == null ? "" : f.title()); o.put("source", f.source() == null ? "" : f.source());
            if (p.facts() != null) { o.put("width", p.facts().width()); o.put("height", p.facts().height()); o.put("format", p.facts().format()); if (p.facts().declared().ai()) o.put("declared_ai", p.facts().declared().why()); }
            o.put("sha", p.sha());
            if (!r.author().isBlank()) o.put("author", Images.cut(r.author(), 160)); if (!r.date().isBlank()) o.put("date", Images.cut(r.date(), 80)); if (!r.licence().isBlank()) o.put("licence", r.licence());
            if (!p.provenance().isBlank()) o.put("provenance", p.provenance());
            return o;
        }

        void aside(Prepared p, String reason, String why) {
            count(reason);
            ArrayNode t = (ArrayNode) m.path("turned_down");
            if (t.size() >= KEEP_TURNED_DOWN) t.remove(0);
            ObjectNode o = t.addObject(); o.put("image", p.c().found().image()); o.put("page", p.c().found().page() == null ? "" : p.c().found().page()); o.put("reason", reason); o.put("why", why == null ? "" : Images.cut(why, 200));
        }

        void copy(Prepared p, int of, String how) throws IOException {
            ArrayNode c = (ArrayNode) m.path("copies");
            if (c.size() >= KEEP_COPIES) return;
            String t = "c-" + p.sha().substring(0, 16) + ".jpg";
            AtomicWrite.bytes(dir(store, job).resolve(t), p.thumb());
            ObjectNode o = row(c.addObject(), p); o.put("thumb", t); o.put("of", of); o.put("how", how);
        }

        void remember(Prepared p, int keptAs, String thumb) {
            judged.add(new Judged(p.print(), keptAs, thumb));
            ObjectNode o = ((ArrayNode) m.path("prints")).addObject(); o.put("kept", keptAs); o.put("aspect", p.print().aspect()); if (!thumb.isEmpty()) o.put("thumb", thumb);
            ObjectNode h = o.putObject("hashes"); for (var e : p.print().hashes().entrySet()) h.put(e.getKey(), PictureHash.hex(e.getValue()));
        }

        /**
         * Where the picture's own text names the collection's name (its page's title, its address, the engine's caption), by the research
         * pictures' signal ({@link Images#namedBy}); {@link Images#BY_NONE} when it names it nowhere, "" when the collection has no name.
         */
        String namedBy(Images.Found f) { String n = name(); return n.isEmpty() ? "" : Images.namedBy(f, n); }

        /**
         * The name the pictures are of ({@link #nameOf}); "" for none.
         */
        String name() { return nameOf(form); }

        String question(Images.Found f) {
            String title = f.title() == null ? "" : f.title().strip().replaceAll("\\s+", " ");
            String by = namedBy(f);
            StringBuilder q = new StringBuilder();
            // the person's own words first, and they decide: the form is a model's reading of them, and once read "images of a series" as
            // "in the style of the series", which let stock photographs of any neon city through (2026-11-19)
            if (!form.ask().isEmpty()) {
                q.append("A person is collecting pictures and asked, in their own words: «").append(Images.cut(form.ask(), 400)).append("». ");
                q.append("Does this picture show what they asked for, read as: ").append(form.judgedAgainst().replaceAll("[.?!]+$", "")).append("? Where that reading and their own words differ, their own words decide. ");
            } else q.append("Does this picture show ").append(form.judgedAgainst().replaceAll("[.?!]+$", "")).append("? ");
            // the picture alone: a form whose words carried the collection's own rule ("a different fragment in each picture") had a picture of one fragment judged no (2026-11-19)
            q.append("Judge this one picture alone: how many pictures there are, and how they differ from one another, are the library's to keep track of; so are the picture's size, its shape and its file, and whether AI made it. ");
            if (!title.isEmpty()) q.append("The page it stands on is titled \"").append(Images.cut(title, 120)).append("\". ");
            q.append(showsItself(form.kind()));
            // nothing of the picture's own text names it: the closer question, the one the research pictures ask (named by text, or shown itself)
            if (Images.BY_NONE.equals(by)) q.append(" Nothing on this picture's page names ").append(name()).append(": answer yes only when the picture itself shows ").append(name())
                    .append(" — its own people, places, things or scenes — and no when it shows something that only looks like it or shares its style.");
            q.append(" Answer with one word first, yes, no or unsure, then one sentence why that says what you relied on: the page's title, the picture's own words, or what you see.");
            if (!form.differBy().isEmpty()) {   // one fixed word for the line, whatever words the form gave what must differ: the program reads the line by it
                // the name from the picture, its page's title or its address: told only "name the temple in this picture", the model said none
                // of a temple whose page was titled with its name (2026-11-19)
                String page = f.page() == null || !f.page().matches("(?i)^https?://.+") ? "" : " (" + Images.cut(f.page(), 160) + ")";
                q.append(" Then, on a line of its own, write \"Which:\" and name ").append(form.differBy()).append(" in this picture, from what it shows, its page's title or its address").append(page);
                if (!values.isEmpty()) q.append("; when it is one of these, write its name exactly as here: ").append(String.join("; ", values));
                q.append(". Write \"Which: unknown\" when nothing tells which.");
            }
            return q.toString();
        }

        /**
     * The rule a research run's judge has had since 0.7.0 ({@link Images#SHOWS_RULE}), said of a collection: the thing itself, never a likeness
     * of its style. Without it the judge kept stock photographs of a man under neon lights as pictures "of" a television series, for "the
     * aesthetic"; with it, four such photographs were no and four of the series' own wallpapers yes (2026-11-19, by hand), and the pictures of
     * other asks it was tried on kept their verdicts.
     */
    /**
     * The name a collection's pictures are of: the form's own (the request's words for it), else, for a person or a character, the name its
     * words begin with — their capitalised words up to the first that is not ("Hit-Girl" in "Hit-Girl from Kick-Ass", "Ada Lovelace") — and,
     * for anything else, its words when they look like a name and stand in the request (the research pictures' rule, {@link
     * Images#looksLikeAName}: "Altered Carbon"); "" for none. On 2026-11-19 a collection of a film character had no name in its form, the
     * signal did nothing, and photographs of the actress out of the role and a stock photograph of a woman in leather were kept.
     */
    static String nameOf(CollectionForm form) {
        if (!form.name().isEmpty()) return form.name();
        String w = form.words();
        if (form.kind().equals(CollectionForm.PERSON) || form.kind().equals(CollectionForm.CHARACTER)) {
            List<String> lead = new ArrayList<>();
            for (String t : w.split("\\s+")) { if (t.isEmpty() || !Character.isUpperCase(t.codePointAt(0)) && !Character.isDigit(t.codePointAt(0))) break; lead.add(t.replaceAll("[,;:]+$", "")); }
            if (!lead.isEmpty()) return String.join(" ", lead);
            return w.split("\\s+").length <= 3 ? w : "";
        }
        if (!Images.looksLikeAName(w)) return "";
        return form.ask().isEmpty() || CollectionForm.norm(form.ask()).contains(CollectionForm.norm(w)) ? w : "";
    }

    /** What a yes means, by what the pictures are of: a character shown as the character, a person's own likeness, anything else the thing itself. */
    static String showsItself(String kind) {
        return switch (kind) {
            case CollectionForm.CHARACTER -> SHOWS_CHARACTER;
            case CollectionForm.PERSON -> SHOWS_PERSON;
            default -> SHOWS_ITSELF;
        };
    }
    static final String SHOWS_CHARACTER = "A yes means the picture shows this character as the character: in the character's own costume and look, as drawn or rendered, or in the role on screen. "
            + "A photograph of the actor or actress who plays the character out of the role — in their own clothes, at an event, at a photo shoot — is a no, and so is a person whose clothes only resemble the character's. "
            + "The page's title and writing in the picture are evidence you may rely on.";
    static final String SHOWS_PERSON = "A yes means the picture shows this person: their own likeness, in a photograph, a portrait or a role — not someone who resembles them; "
            + "a guess from the style, the period or a motif is a no. The page's title and writing in the picture are evidence you may rely on.";
    static final String SHOWS_ITSELF = "A yes means the picture shows this itself — the person's own likeness, the object itself, the work itself, the scene of the event — not something of the same culture, period or style; "
            + "a guess from the style, the period or a motif is a no. The page's title and writing in the picture are evidence you may rely on.";

    /** The question asked once more when the judge's answer left out its "Which:" line: the name alone (a third of the temples' answers did, 2026-11-19). */
        String whichQuestion(Images.Found f) {
            String title = f.title() == null ? "" : f.title().strip().replaceAll("\\s+", " ");
            String page = f.page() == null || !f.page().matches("(?i)^https?://.+") ? "" : " (" + Images.cut(f.page(), 160) + ")";
            StringBuilder q = new StringBuilder("Name ").append(form.differBy()).append(" in this picture, from what it shows")
                    .append(title.isEmpty() ? "" : ", its page's title \"" + Images.cut(title, 120) + "\"").append(page.isEmpty() ? "" : " or its address" + page);
            if (!values.isEmpty()) q.append("; when it is one of these, write its name exactly as here: ").append(String.join("; ", values));
            return q.append(". Answer with one line, \"Which:\" and the name, or \"Which: unknown\" when nothing tells which.").toString();
        }

    /** The value the judge named for {@code differBy}: the line "Which: a red fox"; "" when it named none. */
        String valueIn(String answer) {
            if (answer == null) return "";
            Matcher mm = Pattern.compile("(?im)^[\\s*_\"'`#-]*which[\\s*_\"'`]*:\\s*(.+?)\\s*$").matcher(answer);
            if (!mm.find()) return "";
            String v = mm.group(1).split(";")[0].replaceAll("^[*_\"'`]+|[*_\"'`.]+$", "").strip();
            return v.matches("(?i)(none|n/?a|unknown|unclear|not shown|no .*)") ? "" : Images.cut(v, 80);
        }

        void end() {
            String st = stopped.isEmpty() ? (stop.getAsBoolean() ? "stop" : "quiet") : stopped;
            m.put("stopped", st); m.put("state", st.equals("stop") ? "stopped" : "done"); m.put("ended", Instant.now().toString());
            try { save(); shelve(); } catch (IOException ignored) { }
            said("Done");
        }

        /** The collection as one entry on the shelves, so a search finds it by its words and a run can cite it. */
        void shelve() {
            if (kept() == 0) return;
            StringBuilder body = new StringBuilder("A collection of pictures: ").append(form.said(Chrome.ENGLISH)).append(".\n\n");
            for (JsonNode p : m.path("passed")) body.append(p.path("n").asInt()).append(". ").append(p.path("title").asText("").isBlank() ? "A picture" : p.path("title").asText()).append(" — ").append(p.path("width").asInt()).append('×').append(p.path("height").asInt())
                    .append(", ").append(p.path("page").asText("").isBlank() ? p.path("image").asText() : p.path("page").asText()).append(" (").append(p.path("image").asText()).append(")\n");
            Map<String, String> more = new LinkedHashMap<>(); more.put("kind", JOB_KIND); more.put("job", job); more.put("gallery", gallery(job));
            Path raw = RawCapture.capture(store, "collection:" + job, body.toString(), "Pictures: " + form.words(), Images.BY, "", "", more);
            if (raw != null) m.put("entry", raw.getFileName().toString());
            try { save(); } catch (IOException ignored) { }
        }

        String summary() { return told(m, Chrome.ENGLISH); }
    }

    static String firstLine(String s) { if (s == null) return null; String t = s.strip(); int i = t.indexOf('\n'); return i < 0 ? t : t.substring(0, i).strip(); }
    static String fold(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("^(?:the|a|an)\\s+", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip(); }

    /**
     * Whether two names the judge gave are one value: the words of one all in the other, after folding ("Main Drive Gear" and "Main Drive
     * Gear (or Fragment A)", "Ghidorah" and "King Ghidorah"); a name written as a list, the first of it. The judge copies the names it is
     * given with words added or dropped, and three pictures of one fragment were kept as three on 2026-11-19.
     */
    static boolean sameValue(String a, String b) {
        Set<String> x = words(a), y = words(b);
        return !x.isEmpty() && !y.isEmpty() && (x.containsAll(y) || y.containsAll(x));
    }
    private static Set<String> words(String s) {
        String first = s == null ? "" : s.split(";")[0];
        Set<String> w = new LinkedHashSet<>();
        for (String t : fold(first).split(" ")) if (!t.isBlank()) w.add(t);
        return w;
    }

    /**
     * What the model that sees is asked of a picture close to a kept one, by the hash or the embedding. Asked whether the second was "the same
     * picture", it answered "different, the second is a tighter crop of the first" of two photographs of one man taken a moment apart; asked
     * this way it said why in the terms that decide it (2026-11-19, by hand, two pairs).
     */
    static final String SAME_PICTURE = "Two pictures follow. Are they one photograph or artwork? One photograph cropped, zoomed, resized, recoloured, mirrored or scanned again is one: answer same. "
            + "Two photographs, taken at another moment, from another angle or at another sitting, are two: answer different. Answer: same or different, then one sentence why.";

    /** The reasons in a few words, as the model and the person read them. */
    static final Map<String, String> REASON_SAID = Map.ofEntries(Map.entry("small", "smaller than asked"), Map.entry("shape", "the wrong shape"), Map.entry("format", "in another format"),
            Map.entry("ai", "made by AI, as its file or record says"), Map.entry("unverified", "not counted, with no record of who made it or when"), Map.entry("copy", "a copy of a picture already kept or turned down"),
            Map.entry("no", "judged not to show it"), Map.entry("unsure", "the model that sees was unsure"), Map.entry("unjudged", "the model that sees gave no answer"), Map.entry("no_value", "with nothing named for what must differ"), Map.entry("taken", "showing what a kept picture already shows"),
            Map.entry("check", "refused by the check every picture from the web goes through"), Map.entry("not_picture", "not a picture: an icon, a drawing, a document or a web page"), Map.entry("unreadable", "a picture in a format the library cannot open"), Map.entry("unfetched", "could not be fetched"));

    // ---- what a finished collection says ----

    /** The end of a collection in one or two sentences, in the person's language: how many of how many, why it ended, what was set aside, where to see them. */
    public static String told(ObjectNode m, Chrome c) {
        JsonNode f = m.path("form");
        int kept = m.path("passed").size(), target = f.path("count").asInt(CollectionForm.DEFAULT_COUNT);
        String words = f.path("words").asText("");
        String why = switch (m.path("stopped").asText("")) {
            case "count" -> c.t("it had the {0} asked for", target);
            case "time" -> c.t("the {0} minutes were up", f.path("minutes").asInt(0));
            case "sure" -> c.t("{0} searches since the last picture kept, in {1} places, found nothing new to judge", SURE_AFTER, Math.max(2, m.path("dry_places").asInt(placesOfLast(m, SURE_AFTER))));
            case "ceiling" -> c.t("it had judged {0} pictures, the most for a collection of this size", m.path("counts").path("judged").asInt(0));
            case "done" -> c.t("the model searching for them was sure there were no more: {0}", m.path("done_why").asText(""));
            case "stop" -> c.t("you stopped it");
            case "searches" -> c.t("it had made {0} searches, the most for one collection", m.path("searches").size());
            case "places" -> c.t("every place it can search had been searched as often as it allows");
            case "eyes" -> c.t("the model that sees pictures stopped answering");
            case "ended" -> c.t("its job ended before it finished; the Runs page says why");
            default -> c.t("the model choosing the searches stopped answering");
        };
        String head = c.t("{0} of {1} pictures of {2} kept: {3}.", kept, target, words, why);
        JsonNode n = m.path("counts");
        List<String> aside = new ArrayList<>();
        for (String r : REASONS) { int k = n.path(r).asInt(0); if (k > 0 && !r.equals("seen")) aside.add(c.t(REASON_SAID.getOrDefault(r, r)) + ": " + k); }
        String set = aside.isEmpty() ? "" : " " + c.t("Set aside: {0}.", String.join("; ", aside));
        String refused = m.path("refused_full").asBoolean(false) ? " " + c.t(REFUSED_FULL) : "";
        return head + set + refused + " " + c.t("See them on the Pictures page: {0}", gallery(m.path("job").asText("")));
    }

    static int placesOfLast(ObjectNode m, int k) {
        JsonNode s = m.path("searches"); Set<String> p = new HashSet<>();
        for (int i = Math.max(0, s.size() - k); i < s.size(); i++) p.add(s.get(i).path("where").asText());
        return p.size();
    }

    /** What a person who asked for full-size files is told when the library is under accounts: the collection is links and small copies. */
    public static final String REFUSED_FULL = "With accounts on, a collection keeps each picture's address and a small copy; the full pictures stay on the sites that publish them.";
}

package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The service's settings that its nodes count and cap by (0.8.0 review, round B, BILL 10): the spending plug-in's address, the paid
 * search places' prices, the model entries (their servers, models and prices) and the model the libraries use. The service writes them
 * into the store at its start and every minute; a node of its own reads them from there at its start and every minute, and they come
 * before what its own settings file says. No key, token or secret is among them: each machine keeps its own.
 */
final class ServiceSettings {
    private ServiceSettings() { }

    private static final Logger LOG = LoggerFactory.getLogger(ServiceSettings.class);

    /** Settings shared by name. */
    static final List<String> NAMED = List.of(PluginSpending.SETTING, "RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL");
    /** The fields of a model entry that are shared: what it is, and what it costs. */
    static final List<String> ENTRY_FIELDS = List.of("DRIVE", "MODEL", "PRICES", "PRICED", "EFFORT", "CAP", "NOTE", "TIER", "SEES");
    /** How often the service writes them, and a node reads them. */
    static volatile long EVERY_MS = 60_000;

    /** Whether a setting's name is a secret's: never shared. */
    static boolean secret(String key) {
        // by what the setting is (its kind), not by words in a name an operator chose: an entry named "token-counter" is priced on every
        // node all the same (round F, money 10); a key of an entry's own is a secret by its field
        Settings.Setting s = Settings.named(key);
        if (s != null) return s.kind() == Settings.Kind.secret;
        String k = key.toUpperCase(Locale.ROOT);
        if (k.startsWith(ModelEntries.PREFIX)) return k.endsWith("_KEY");
        return k.endsWith("_KEY") || k.contains("TOKEN") || k.contains("SECRET") || k.contains("PASSWORD");
    }

    /** What the service shares with its nodes, as its settings say now. */
    static Map<String, String> shared() {
        Map<String, String> m = new TreeMap<>();
        for (String k : NAMED) { String v = Config.explicit(k); if (v != null && !v.isBlank()) m.put(k, v.strip()); }
        for (String k : Config.storedWithPrefix(Settings.SEARCH_PRICE).keySet()) { String v = Config.explicit(k); if (v != null && !v.isBlank() && !secret(k)) m.put(k, v.strip()); }
        for (String k : Config.storedWithPrefix(ModelEntries.PREFIX).keySet()) {
            String field = k.substring(k.lastIndexOf('_') + 1);
            String v = Config.explicit(k);
            if (ENTRY_FIELDS.contains(field) && v != null && !v.isBlank() && !secret(k)) m.put(k, v.strip());
        }
        return m;
    }

    /** The service writes what it shares into the store. */
    static void publish(AccountStore store) throws IOException {
        Config.reloadIfChanged();   // a setting changed while the service runs (settings set, in another process) is shared within a minute
        store.serviceSettings(shared());
    }

    /** A node reads the service's settings from the store, and goes by them from now on. */
    static void read(AccountStore store) throws IOException {
        Map<String, String> m = new TreeMap<>();
        store.serviceSettings().forEach((k, v) -> { if (!secret(k)) m.put(k, v); });
        Config.provide(m);
    }

    /** The service's side, on a thread of its own: what it shares, written now and every minute. */
    static Thread publishing(AccountStore store) {
        return Thread.ofPlatform().daemon().name("service-settings").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { publish(store); } catch (IOException | RuntimeException e) { LOG.warn("The settings the nodes count by could not be written to the store: {}", e.getMessage()); }
                try { Thread.sleep(EVERY_MS); } catch (InterruptedException e) { return; }
            }
        });
    }

    /** A node's side, on a thread of its own: the service's settings read now and every minute. */
    static Thread reading(AccountStore store) {
        return Thread.ofPlatform().daemon().name("service-settings").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { read(store); } catch (IOException | RuntimeException e) { LOG.warn("The service's settings could not be read from the store; the last ones read hold: {}", e.getMessage()); }
                try { Thread.sleep(EVERY_MS); } catch (InterruptedException e) { return; }
            }
        });
    }
}

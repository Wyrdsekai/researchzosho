package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings a node's gates count and cap by are the service's (0.8.0 review, round B, BILL 10): the spending plug-in's address,
 * the paid search places' prices, the model entries and their prices, the model the libraries use. The service writes them into the
 * store; a node reads them from there, whatever its own settings file says. Keys are not among them: each machine keeps its own.
 */
class ServiceSettingsTest {
    @AfterEach void back() throws Exception {
        Config.provide(Map.of());
        for (String k : new String[]{"RESEARCHZOSHO_ACCOUNTS_SPENDING", Settings.SEARCH_PRICE + "BRAVE", ModelEntries.key("hosted", "DRIVE"), ModelEntries.key("hosted", "MODEL"), ModelEntries.key("hosted", "PRICES"), "RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", "RESEARCHZOSHO_BRAVE_KEY"})
            Config.set(k, "");
    }

    @Test
    void aNodeCountsAndCapsByTheServicesSettingsNotItsOwnFile(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            // the service's settings
            Config.set("RESEARCHZOSHO_ACCOUNTS_SPENDING", "http://billing.example:8080");
            Config.set(Settings.SEARCH_PRICE + "BRAVE", "0.012");
            Config.set(ModelEntries.key("hosted", "DRIVE"), "https://api.example.org/v1");
            Config.set(ModelEntries.key("hosted", "MODEL"), "big-model");
            Config.set(ModelEntries.key("hosted", "PRICES"), "3/15");
            Config.set("RESEARCHZOSHO_BRAVE_KEY", "the-services-own-search-key");
            ServiceSettings.publish(store);
            // the node's own file has none of them
            for (String k : new String[]{"RESEARCHZOSHO_ACCOUNTS_SPENDING", Settings.SEARCH_PRICE + "BRAVE", ModelEntries.key("hosted", "DRIVE"), ModelEntries.key("hosted", "MODEL"), ModelEntries.key("hosted", "PRICES"), "RESEARCHZOSHO_BRAVE_KEY"}) Config.set(k, "");
            assertTrue(Uses.builtIn(), "on its own, the node would hold the built-in caps");
            assertEquals(5_000, NodeGates.priceMicros("brave"));
            ServiceSettings.read(store);   // what a node does at its start, and every minute
            assertFalse(Uses.builtIn(), "the service's plug-in answers: the node holds no built-in month");
            assertEquals(12_000, NodeGates.priceMicros("brave"), "the service's search price");
            ModelEntries.Entry e = ModelEntries.get("hosted");
            assertEquals("big-model", e.model());
            assertEquals(3.0, e.cost(1_000_000, 0, 0), 1e-9, "the service's model prices");
            assertNull(Config.get("RESEARCHZOSHO_BRAVE_KEY"), "no key comes through the store");
        }
    }

    /**
     * A setting changed while the service runs, by another process (settings set, from docker compose exec), is shared at the next
     * round: the service reads its settings file again when it changed, not only at its start.
     */
    @Test
    void aSettingChangedWhileTheServiceRunsIsSharedAtTheNextRound(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            Config.set(Settings.SEARCH_PRICE + "BRAVE", "0.012");
            ServiceSettings.publish(store);
            assertEquals("0.012", store.serviceSettings().get(Settings.SEARCH_PRICE + "BRAVE"));
            // another process writes the file: this one's settings are as it read them until it reads the file again
            Path file = Config.userConfigPath();
            Files.writeString(file, Files.readString(file).replace("0.012", "0.034"));
            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000));
            ServiceSettings.publish(store);
            assertEquals("0.034", store.serviceSettings().get(Settings.SEARCH_PRICE + "BRAVE"), "the price as the file says it now");
        }
    }

    /** What the service's host only offers (a {@code _DEFAULT} in its environment) is not the service's setting: a node keeps its own model. */
    @Test
    void aModelTheServicesHostOnlyOffersIsNotShared(@TempDir Path tmp) throws Exception {
        assertNotNull(Config.env("RESEARCHZOSHO_DRIVE" + Config.DEFAULT_SUFFIX), "the suite's own environment offers a model (build.gradle): the case this test is about");
        assertNotNull(Config.get("RESEARCHZOSHO_DRIVE"), "the service would use the offer itself");
        assertFalse(ServiceSettings.shared().containsKey("RESEARCHZOSHO_DRIVE"), "but it does not hand it to its nodes as its setting");
        Config.set("RESEARCHZOSHO_DRIVE", "http://model.example:8080/v1");
        assertEquals("http://model.example:8080", ServiceSettings.shared().get("RESEARCHZOSHO_DRIVE"), "a model it set is shared");
    }
}

package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a library holds is its keeper's (0.8.0, stage 3): the node measures each library's folder once a day and writes it to the
 * keeper's month, and while the keeper's libraries hold as much as the storage cap lets them, nothing more is added to them, whoever
 * asks; reading, and a chat, go on.
 */
class StorageTest {
    AccountStore store;

    @AfterEach void close() { if (store != null) store.close(); }

    long storageRows(String account) throws Exception {
        return store.month(account, AccountStore.month(System.currentTimeMillis())).stream().filter(t -> t.kind().equals("storage")).mapToLong(AccountStore.MonthTotal::quantity).sum();
    }

    @Test
    void eachLibraryIsMeasuredOnceADayAndAddingStopsAtTheKeepersCap(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        Path root = tmp.resolve("accounts");
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountService.Made bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String keeper = ann.account().id(), lib = ann.library().id();
        Path folder = LibraryPlaces.folder(store.library(lib));
        Files.write(Files.createDirectories(LibraryPlaces.library(folder).resolve("raw")).resolve("big.md"), new byte[3_000_000]);
        Node node = new Node(store, "http://127.0.0.1:1", new ChildLibraries(store, "http://127.0.0.1:1"), "node_test", root);
        node.measureWhenDue();
        long end = System.currentTimeMillis() + 10_000;
        while ((store.lastStorage(lib) == null || store.lastStorage(bo.library().id()) == null) && System.currentTimeMillis() < end) Thread.sleep(50);
        long[] measured = store.lastStorage(lib);
        assertTrue(measured != null && measured[1] >= 3_000_000, "the folder's bytes, the document among them");
        long mb = storageRows(keeper);
        assertTrue(mb >= 3, "a day of its megabytes in the keeper's month: " + mb);
        long bos = storageRows(bo.account().id());
        assertTrue(bos >= 1 && bos < 3, "Bo's library is Bo's, and small: " + bos);
        node.measureWhenDue();
        Thread.sleep(300);
        assertEquals(mb, storageRows(keeper), "once a day: measured again only after a day");

        CapsSpending spending = new CapsSpending(store);
        store.setCap(keeper, Caps.STORAGE_MB, 2);
        Spending.Answer add = spending.mayStart(new Spending.Use("use_1", keeper, lib, CapsSpending.ADD, "", "operator", 0, 0, ""));
        assertFalse(add.yes());
        assertEquals(CapsSpending.storageFull(2), add.said());
        for (String kind : List.of(CapsSpending.UPLOAD, SpendPurse.RESEARCH, SpendPurse.TRANSLATION, SpendPurse.PICTURE))
            assertFalse(spending.mayStart(new Spending.Use("use_2", bo.account().id(), lib, kind, "", "operator", 0, 0, "")).yes(), "Bo adding to Ann's library: Ann's storage counts, " + kind);
        assertTrue(spending.mayStart(new Spending.Use("use_3", bo.account().id(), lib, SpendPurse.CHAT, "", "operator", 0, 0, "")).yes(), "a chat adds nothing");
        assertTrue(spending.mayStart(new Spending.Use("use_4", bo.account().id(), bo.library().id(), CapsSpending.ADD, "", "operator", 0, 0, "")).yes(), "Bo's own library is under Bo's cap");
        assertEquals("2 MB", Caps.size(2)); assertEquals("2.0 GB", Caps.size(2_048));
    }
}

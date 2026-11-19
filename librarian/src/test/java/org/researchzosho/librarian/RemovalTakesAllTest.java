package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Removing an account takes every library it owns, in any state, and closes its work before it is gone (round F, the second review, money
 * 5 and 6, proved on c8e057ed): a library it let go stayed aside for its 30 days after the account was removed, a released library kept
 * the account's name as its title for ever, and the uses it had open were closed by the sweep a day later, after the notice that the
 * account was deleted.
 */
class RemovalTakesAllTest {
    AccountStore store;

    @AfterEach void stop() throws Exception { Config.set(AccountService.ROOT, ""); if (store != null) store.close(); }

    @Test
    void whatTheAccountLetGoOrReleasedGoesWithItAndItsOpenWorkIsClosedFirst(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "ann@example.org", false, "the test");
        AccountStore.Library first = ann.library(), notes = AccountService.newLibrary(store, root, ann.account(), "Ann Example's notes");
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        AccountsCli.places(store, notes, "delete", null, false, quiet);   // let go: aside for 30 days
        AccountsCli.places(store, store.library(first.id()), "release", tmp.resolve("released-home"), false, quiet);
        AccountStore.Use open = new AccountStore.Use(AccountStore.id("use_"), ann.account().id(), first.id(), SpendPurse.RESEARCH, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(open);
        AccountActs.remove(store, store.account(ann.account().id()), AccountActs.By.cli());
        assertEquals("removed", store.account(ann.account().id()).state());
        List<AccountStore.Aside> aside = store.asides(notes.id());
        assertTrue(aside.stream().allMatch(a -> a.until() <= System.currentTimeMillis()), "what was set aside for the library it let go is due now: " + aside);
        new LibraryPlaces(store).sweep();
        assertTrue(aside.stream().noneMatch(a -> Files.exists(Path.of(a.path()))), "and gone at the sweep");
        for (AccountStore.Library l : List.of(store.library(first.id()), store.library(notes.id()))) {
            assertFalse(l.title().contains("Ann") || l.name().contains("ann"), "nothing of the account's name: " + l);
            assertEquals("", l.folder());
        }
        assertTrue(store.openUses(ann.account().id()).isEmpty(), "its open work is closed at its removal, not a day later");
    }
}

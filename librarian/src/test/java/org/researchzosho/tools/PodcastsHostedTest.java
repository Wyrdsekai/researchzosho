package org.researchzosho.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.LibrarianDaemon;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Statement;
import org.researchzosho.librarian.Vouching;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An episode's audio in a library under an account service: the address a feed names, and every address the audio is sent on to, is
 * held to the fetcher's rule before it is asked. A library on its own fetches it as it always did.
 */
class PodcastsHostedTest {

    @AfterEach void reset() { Fetch.allowLoopback = false; }

    @Test
    void eachAddressTheAudioIsSentOnToIsHeldToTheFetchersRule(@TempDir Path tmp) throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer here = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        here.createContext("/a.mp3", x -> { asked.incrementAndGet(); byte[] b = "audio".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        here.createContext("/sent-on", x -> { asked.incrementAndGet(); x.getResponseHeaders().set("Location", "http://169.254.169.254/latest/audio.mp3"); x.sendResponseHeaders(302, -1); x.close(); });
        here.start();
        String at = "http://127.0.0.1:" + here.getAddress().getPort();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        try {
            assertEquals(200, Podcasts.fetchAudio(at + "/a.mp3", tmp.resolve("own.mp3")).statusCode(), "a library on its own fetches it");
            assertEquals(1, asked.get());
            KeyPair service = Statement.newKey();
            LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, new Vouching("http://accounts.test", kid -> service.getPublic()));
            try {
                // the first address is on this machine: refused before it is asked
                IOException first = assertThrows(IOException.class, () -> Podcasts.fetchAudio(at + "/a.mp3", tmp.resolve("hosted.mp3")));
                assertTrue(first.getMessage().contains("is not fetched"), first.getMessage());
                assertEquals(1, asked.get(), "nothing was asked");
                // an address that may be asked sends the audio on to one that may not: refused there, at the second hop
                Fetch.allowLoopback = true;   // the stand-in for an address out in the world
                IOException sentOn = assertThrows(IOException.class, () -> Podcasts.fetchAudio(at + "/sent-on", tmp.resolve("hop.mp3")));
                assertTrue(sentOn.getMessage().contains("link-local"), sentOn.getMessage());
                assertEquals(2, asked.get(), "the first address was asked, the one it sent on to was not");
            } finally { d.stop(); }
        } finally { here.stop(0); }
    }
}

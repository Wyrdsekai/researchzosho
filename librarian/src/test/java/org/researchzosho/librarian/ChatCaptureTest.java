package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChatCaptureTest {
    @Test
    void whatACommandPrintsOnItsThreadIsCaughtAndNothingElse() throws Exception {
        assertEquals("one\ntwo\n", ChatCapture.run(() -> { System.out.println("one"); System.out.print("two\n"); return true; }));
        assertNull(ChatCapture.run(() -> { System.out.println("not mine"); return false; }), "a line that was not the command's own gives no reply");
        CountDownLatch inside = new CountDownLatch(1), printed = new CountDownLatch(1);
        Thread other = new Thread(() -> { try { inside.await(); System.out.println("from another thread"); } catch (InterruptedException ignored) { } printed.countDown(); });
        other.start();
        String caught = ChatCapture.run(() -> { inside.countDown(); try { printed.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); } System.out.println("mine"); return true; });
        assertEquals("mine\n", caught, "another thread's printing goes to the console, not into the reply");
        AtomicInteger read = new AtomicInteger(5);
        ChatCapture.run(() -> { read.set(System.in.read()); return true; });
        assertEquals(-1, read.get(), "a command that waits for a typed answer gets none in the browser, and does not hang");
        assertEquals("outer\ninner\n", ChatCapture.run(() -> { System.out.println("outer"); String in = ChatCapture.run(() -> { System.out.println("inner"); return true; }); System.out.print(in); return true; }), "a capture inside a capture keeps each its own");
        assertEquals("/family tell\nline two", ChatCapture.unindent("  /family tell\n  line two\n"));
    }
}

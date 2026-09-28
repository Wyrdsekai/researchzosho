package org.researchzosho.drive;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The time one call to the model may take. Every drive the library uses sends the head of its answer only when the whole answer is ready,
 * so a fixed limit of five minutes was a ceiling on how long an answer could be: at thirty tokens a second, about nine thousand tokens,
 * while a turn may ask for half the model's window. A call may now take as long as reading its prompt and writing max_tokens take at the
 * slowest pace the drive keeps, and never less than RESEARCHZOSHO_DRIVE_TIMEOUT.
 */
class DriveTimeLimitTest {

    @Test
    void aLongAnswerIsGivenTheTimeItTakesAtTheSlowestPaceAndAShortOneTheDrivesOwnLimit() {
        Duration base = Duration.ofSeconds(300);
        // 16,000 tokens at 10 a second, and a prompt of 60,000 characters read at twenty times that pace
        assertEquals(Duration.ofSeconds(1700), DriveClient.limitFor(base, 60_000, 16_000, 10));
        assertEquals(base, DriveClient.limitFor(base, 2_000, 512, 10), "a short answer: the drive's own limit");
        assertEquals(Duration.ofSeconds(1067), DriveClient.limitFor(base, 0, 16_000, 15));
    }

    @Test
    void thePaceIsLearnedFromTheCallsThatCameBack() {
        DriveClient.Pace pace = new DriveClient.Pace();
        assertEquals(DriveClient.DEFAULT_FLOOR, pace.floor(), "until three calls were measured");
        pace.add(28); pace.add(32);
        assertEquals(DriveClient.DEFAULT_FLOOR, pace.floor());
        pace.add(30);
        assertEquals(15.0, pace.floor(), "half the median");
        for (int i = 0; i < 40; i++) pace.add(60);
        assertEquals(30.0, pace.floor(), "the recent calls count, not the first ones");
    }
}

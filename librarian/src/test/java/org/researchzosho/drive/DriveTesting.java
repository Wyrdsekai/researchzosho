package org.researchzosho.drive;

/** The drive's test seams, for tests in other packages. */
public final class DriveTesting {
    private DriveTesting() { }

    /** Sets the waits before asking a model server that answers 503 again, in milliseconds; returns the waits it had, to put back. */
    public static long[] loadingWaits(long... ms) {
        long[] was = DriveClient.loadingWaitsMs;
        DriveClient.loadingWaitsMs = ms.clone();
        return was;
    }
}

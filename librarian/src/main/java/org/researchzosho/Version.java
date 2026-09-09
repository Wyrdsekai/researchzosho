package org.researchzosho;

/** The release version: the jar's manifest says it; a run from the source tree says so instead. */
public final class Version {
    private Version() { }

    public static String string() {
        String v = Version.class.getPackage() == null ? null : Version.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev (from the source tree)" : v;
    }
}

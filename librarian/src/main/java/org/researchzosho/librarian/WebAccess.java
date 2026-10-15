package org.researchzosho.librarian;

import org.researchzosho.Config;

/**
 * Who a browser is. As shipped the pages are OPEN: anyone who can open them can read the library and send
 * questions, as if they were the person who keeps it, and the home page says so with the one command that
 * changes it. {@code researchzosho web signin on} turns the sign-in on: then a browser reads at the library's
 * default level and needs a reader token to send questions. A live setting: the daemon sees it at once.
 */
public final class WebAccess {

    public static final String SIGNIN = "RESEARCHZOSHO_WEB_SIGNIN";
    /** {@code owner} (as shipped): a browser on the library's own computer is the person who keeps it, with no sign-in, the way the command
     *  line there is; {@code signin}: it signs in like any other, for a computer other people use. Read only while the sign-in is on. */
    public static final String LOCAL = "RESEARCHZOSHO_WEB_LOCAL";

    /** Tests set this; null means read the setting. */
    static volatile Boolean OVERRIDE;
    /** Tests set this; null means read the setting. */
    static volatile Boolean LOCAL_OVERRIDE;

    private WebAccess() { }

    public static boolean signInRequired() {
        Boolean o = OVERRIDE;
        return o != null ? o : Config.liveOn(SIGNIN, false);
    }

    /** Whether a browser on this computer is the owner without signing in (the setting {@link #LOCAL}, {@code owner} unless set to {@code signin}). */
    public static boolean localOwner() {
        Boolean o = LOCAL_OVERRIDE;
        if (o != null) return o;
        String v = Config.live(LOCAL);
        return v == null || v.isBlank() || !v.strip().equalsIgnoreCase("signin");
    }

    /** The line the home page and the installer show while the pages are open. */
    public static final String OPEN_NOTICE = "Access is open.";
    public static final String OPEN_HOWTO = "To require sign-in, run: researchzosho web signin on";
}

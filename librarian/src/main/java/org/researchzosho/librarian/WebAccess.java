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

    /** Tests set this; null means read the setting. */
    static volatile Boolean OVERRIDE;

    private WebAccess() { }

    public static boolean signInRequired() {
        Boolean o = OVERRIDE;
        return o != null ? o : Config.liveOn(SIGNIN, false);
    }

    /** The line the home page and the installer show while the pages are open. */
    public static final String OPEN_NOTICE = "Anyone who can open these pages can read the library and send questions.";
    public static final String OPEN_HOWTO = "To ask people to sign in before sending questions, run: researchzosho web signin on";
}

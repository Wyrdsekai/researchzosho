package org.researchzosho;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.SiteList;

/**
 * Before every test in the suite (registered for all of them in META-INF/services, with autodetection on in
 * junit-platform.properties): the content checks use nothing live. The site list is the one bundled with the program, never a
 * download and never the ResearchZosho folder of the machine the suite runs on; every content question is answered no by a stub, so
 * no model is asked; the page check fetches as it always does. A test that checks one of these sets its own.
 */
public final class NoLiveChecks implements BeforeEachCallback {

    /** The stub every content question gets unless a test sets its own: no, for every question. */
    public static final ContentJudge NO = new ContentJudge(null, messages -> "no");

    /**
     * A stub that answers nothing, as a model server that is down or that cannot run the check: what the library does then is not hidden
     * by {@link #NO}, and the tests that set this one show it (UncheckedPagesTest, the page check's tests).
     */
    public static final ContentJudge NOTHING = new ContentJudge(null, messages -> "");

    @Override public void beforeEach(ExtensionContext context) {
        SiteList.use(SiteList.bundled());
        ContentJudge.use(NO);
        ImageText.useChecker(null);
        PageCheck.useGetter(null);
    }
}

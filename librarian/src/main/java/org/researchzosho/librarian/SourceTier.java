package org.researchzosho.librarian;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Source-class trust tiers — wyrdsekai's acquisition shape (paper/wiki/book auto-approve;
 * blog/forum need the steward), adapted to how a research run actually cites: by URL.
 *
 * <p>Mechanical, by host, so the tier is never a model's opinion of a source. The promoting tiers
 * match a host EXACTLY (or as a subdomain of a listed domain, or by a restricted top-level suffix such as
 * .edu / .gov) — never by a substring: "journal" anywhere in a host and a "docs." prefix once promoted
 * my-journal-blog.example and docs.anything into canon past the person (Wyrdsekai, 2026-09-07). It is
 * COMPUTED from a finding's locators on demand rather than stored: a stored tier drifts the
 * moment the host table improves, and a pure function of the locators cannot.
 *
 * <p>The tier does one thing in v1: it gates auto-promotion. An extraction that rests only on
 * blogs, forums or unclassified web stays draft for the person — a note.com post and a Keio
 * journal article no longer carry the same weight (they did on the first live keigo shelf).
 * Nothing is REFUSED by tier: a blog is evidence too; it just does not walk into canon alone.
 */
public enum SourceTier {
    /** Curated reference: encyclopedias, standards bodies, official documentation, government. */
    reference,
    /** Scholarly: journals, preprint servers, university repositories, academic databases. */
    scholarly,
    /** Primary: the thing itself — a code repository, a model card, a dataset record, an official product site. */
    primary,
    /** Personal publishing: blogs, newsletters, developer write-ups. */
    blog,
    /** Discussion: forums, Q&A, social. */
    forum,
    /** Local file the person shelved themselves — trusted as theirs (wyrdsekai's `personal`). */
    personal,
    /** Everything else. */
    web;

    /** Tiers that may carry an extraction into canon without the person. */
    public boolean autoPromotes() {
        return this == reference || this == scholarly || this == primary || this == personal;
    }

    /** The strongest tier among a finding's sources — what its promotion rests on. */
    public static SourceTier strongest(List<Finding.Source> sources) {
        SourceTier best = web;
        for (Finding.Source s : sources) {
            SourceTier t = of(s.locator());
            if (t.ordinal() < best.ordinal()) best = t;
        }
        return best;
    }

    public static SourceTier of(String locator) {
        if (locator == null || locator.isBlank()) return web;
        String l = locator.strip().toLowerCase(Locale.ROOT);
        if (l.startsWith("file://") || l.startsWith("raw/")) return personal;
        if (l.startsWith("cite:")) {
            // an edition citation: scholarly when it names a venue, reference when it names an
            // edition/translation/press (the book tier), web otherwise
            // venue markers, or the journal-and-volume shape "<word> 44," that every citation
            // style shares (日本語と日本語教育 44, / Library and Information Science 35,)
            if (l.matches(".*(journal|proceedings|university|univ\\.|\\bvol\\b|\\bno\\.|\\bpp\\.|thesis|"
                    + "大学|紀要|論文|論集|学会|研究|学報|学刊|期刊|教育|評論|学院).*")
                    || l.matches(".*\\p{L}\\s+\\d{1,3}\\s*[,(:].*")) return scholarly;
            // book/edition markers; 訳 only when it is not the 訳 of 翻訳 (translation-as-topic)
            if (l.matches(".*(trans\\.|translated|\\bed\\.|edition|press|publishing|isbn|books|"
                    + "出版|(?<!翻)訳|全集|文庫).*")) return reference;
            return web;
        }
        String host;
        try {
            host = URI.create(l).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null) return web;
        if (host.startsWith("www.")) host = host.substring(4);
        // the person's own word outranks the table: a trusted host is read as primary, a refused one stays web (and is dropped elsewhere)
        if (SourceRules.live().trusted(host)) return primary;

        // forum / social — checked first: a stackexchange subdomain must not read as "reference"
        if (endsWith(host, "reddit.com", "stackoverflow.com", "stackexchange.com", "superuser.com",
                "serverfault.com", "askubuntu.com", "quora.com", "news.ycombinator.com",
                "x.com", "twitter.com", "bsky.app", "mastodon.social", "discord.com",
                "teratail.com", "okwave.jp", "chiebukuro.yahoo.co.jp", "zhihu.com", "tieba.baidu.com")) {
            return forum;
        }
        // blogs and personal publishing
        if (endsWith(host, "medium.com", "substack.com", "wordpress.com", "blogspot.com",
                "blogger.com", "hatenablog.com", "hatenablog.jp", "hatenadiary.jp", "note.com",
                "qiita.com", "zenn.dev", "dev.to", "hashnode.dev", "ghost.io", "tumblr.com",
                "ameblo.jp", "fc2.com", "livedoor.jp", "csdn.net", "juejin.cn", "cnblogs.com")
                || host.startsWith("blog.") || host.contains(".blog.")) {
            return blog;
        }
        // scholarly
        if (endsWith(host, "arxiv.org", "doi.org", "biorxiv.org", "medrxiv.org", "ssrn.com",
                "semanticscholar.org", "pubmed.ncbi.nlm.nih.gov", "ncbi.nlm.nih.gov",
                "europepmc.org", "scielo.br", "scielo.org", "redalyc.org", "jstor.org",
                "sciencedirect.com", "springer.com", "link.springer.com", "nature.com",
                "science.org", "wiley.com", "onlinelibrary.wiley.com", "tandfonline.com",
                "sagepub.com", "acm.org", "dl.acm.org", "ieee.org", "ieeexplore.ieee.org",
                "aclanthology.org", "openreview.net", "researchgate.net", "academia.edu",
                "jstage.jst.go.jp", "ci.nii.ac.jp", "cinii.ac.jp", "irdb.nii.ac.jp",
                "cnki.net", "core.ac.uk", "hal.science", "philpapers.org", "cambridge.org",
                "oup.com", "academic.oup.com", "mit.edu", "plos.org", "frontiersin.org",
                "mdpi.com", "elsevier.com", "journals.library.ualberta.ca")
                || host.endsWith(".edu") || host.endsWith(".ac.jp") || host.endsWith(".ac.uk")
                || host.endsWith(".ac.kr") || host.endsWith(".edu.cn") || host.endsWith(".edu.tw")
                || host.endsWith(".edu.au")) {
            return scholarly;
        }
        // reference: encyclopedias, standards, official docs, government
        if (endsWith(host, "wikipedia.org", "wikimedia.org", "wiktionary.org", "britannica.com",
                "kotobank.jp", "weblio.jp", "ietf.org", "rfc-editor.org", "w3.org", "iso.org",
                "ecma-international.org", "unicode.org", "readthedocs.io", "readthedocs.org",
                "docs.python.org", "docs.oracle.com", "developer.mozilla.org", "docs.rs",
                "learn.microsoft.com", "developer.apple.com", "developer.android.com",
                "cloud.google.com", "docs.aws.amazon.com", "kubernetes.io", "pytorch.org", "tensorflow.org")
                || host.endsWith(".gov") || host.endsWith(".go.jp") || host.endsWith(".gov.uk")
                || host.endsWith(".gc.ca") || host.endsWith(".europa.eu")) {
            return reference;
        }
        // primary: the artifact itself
        if (endsWith(host, "github.com", "gitlab.com", "codeberg.org", "huggingface.co",
                "pypi.org", "npmjs.com", "crates.io", "mvnrepository.com", "sourceforge.net",
                "gist.github.com", "raw.githubusercontent.com",
                // dataset and research-object records — a dataset is the thing itself
                "zenodo.org", "figshare.com", "datadryad.org", "osf.io", "dataverse.harvard.edu", "pangaea.de",
                "kaggle.com", "data.gov", "data.europa.eu", "archive.org", "protocols.io", "clinicaltrials.gov")) {
            return primary;
        }
        return web;
    }

    private static boolean endsWith(String host, String... domains) {
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }
}

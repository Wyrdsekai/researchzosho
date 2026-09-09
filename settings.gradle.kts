rootProject.name = "researchzosho"

// librarian is the product: the library, The Librarian's desk and crews, the daemon, the command.
// client is the Java SDK for the library protocol — separate because a patron embeds the CLIENT and
// must not drag Lucene, PDFBox or the research runner with it.
include("librarian")
include("client")

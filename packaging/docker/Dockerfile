# ResearchZosho as a service in a container: the released tarball on a Java runtime, the library and the
# settings on volumes, the pages and the MCP endpoint on 4649.
#
#   docker build -f packaging/docker/Dockerfile --build-arg VERSION=0.1.6 -t ghcr.io/wyrdsekai/researchzosho:0.1.6 dist
#     (the build context holds researchzosho-<VERSION>.tar.gz — the release-build output, or the GitHub release asset)
#   docker run -d -p 127.0.0.1:4649:4649 -v $PWD/library:/library -v $PWD/config:/config \
#       -e RESEARCHZOSHO_DRIVE=http://host.docker.internal:8080 ghcr.io/wyrdsekai/researchzosho:0.1.6
#   docker run -i --rm -v $PWD/library:/library ghcr.io/wyrdsekai/researchzosho:0.1.6 mcp   # the MCP server over stdio
#
# The model server is not in here: name it with RESEARCHZOSHO_DRIVE (and the embedder with RESEARCHZOSHO_EMBED).
# docker-compose.yml at the repository root runs this beside a Text Embeddings Inference container.
FROM eclipse-temurin:21-jre-noble
ARG VERSION
LABEL org.opencontainers.image.title="ResearchZosho" \
      org.opencontainers.image.description="A research librarian for the rest of us" \
      org.opencontainers.image.source="https://github.com/Wyrdsekai/researchzosho" \
      org.opencontainers.image.url="https://researchzosho.org" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}" \
      io.modelcontextprotocol.server.name="io.github.wyrdsekai/researchzosho"
# poppler for PDFs (the library uses pdftotext when it is there); curl and tar for `update` and the tests
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils curl ca-certificates tar \
    && rm -rf /var/lib/apt/lists/*
COPY researchzosho-${VERSION}.tar.gz /tmp/
RUN tar xzf /tmp/researchzosho-${VERSION}.tar.gz -C /opt && rm /tmp/researchzosho-${VERSION}.tar.gz \
    && ln -s /opt/researchzosho/bin/researchzosho /usr/local/bin/researchzosho \
    && ln -s /opt/researchzosho/bin/zosho /usr/local/bin/zosho
COPY entrypoint.sh /usr/local/bin/researchzosho-entrypoint
RUN chmod +x /usr/local/bin/researchzosho-entrypoint
ENV RESEARCHZOSHO_LIBRARY=/library \
    RESEARCHZOSHO_CONFIG=/config/config
VOLUME ["/library", "/config"]
EXPOSE 4649
ENTRYPOINT ["researchzosho-entrypoint"]
CMD ["serve", "--host", "0.0.0.0", "--port", "4649"]

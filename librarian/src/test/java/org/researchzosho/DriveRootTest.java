package org.researchzosho;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The OpenAI-style root under a model server's address: the address plus /v1, except Gemini's, whose root carries no /v1 (2026-10-15). */
class DriveRootTest {
    @Test
    void theRootIsTheAddressPlusV1ExceptGeminis() {
        assertEquals("http://localhost:8080/v1", Config.v1("http://localhost:8080"));
        assertEquals("http://localhost:8080/v1", Config.v1("http://localhost:8080/"));
        assertEquals("https://openrouter.ai/api/v1", Config.v1("https://openrouter.ai/api"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", Config.v1("https://generativelanguage.googleapis.com/v1beta/openai/"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", Config.v1(Config.driveBase("https://generativelanguage.googleapis.com/v1beta/openai/")), "the address as pasted keeps its root");
    }
}

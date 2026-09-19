package com.contextflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ModelGatewayTest {
    final ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    AtomicReference<String> response = new AtomicReference<>();
    AtomicReference<String> request = new AtomicReference<>();
    MockEnvironment env;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer fake-test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        env = new MockEnvironment().withProperty("contextflow.vector.enabled", "true").withProperty("contextflow.vector.api-key", "fake-test-key")
                .withProperty("contextflow.vector.embedding-url", base + "/embeddings").withProperty("contextflow.vector.model", "test-embed")
                .withProperty("contextflow.vector.jdbc-url", "jdbc:postgresql://127.0.0.1:1/missing").withProperty("contextflow.vector.username", "test")
                .withProperty("contextflow.vector.dimensions", "3").withProperty("contextflow.llm.enabled", "true")
                .withProperty("contextflow.llm.api-key", "fake-test-key").withProperty("contextflow.llm.base-url", base).withProperty("contextflow.llm.model", "test-chat");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void embeddingProtocolAndDimensionValidation() throws Exception {
        response.set("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        var gateway = new ModelGateway(env, mapper);
        assertArrayEquals(new float[]{.1f,.2f,.3f}, gateway.embed("充电问题"));
        assertEquals(3, mapper.readTree(request.get()).path("dimensions").asInt());
        assertEquals("test-embed", mapper.readTree(request.get()).path("model").asText());
        response.set("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");
        assertEquals("EMBEDDING_DIMENSION", assertThrows(DiagnosisService.Failure.class, () -> gateway.embed("x")).code);
    }
    List<Contracts.Source> evidence() {
        return List.of(new Contracts.Source("DOC-C1", "手册", "M", 2020, 2025, 1, "检查连接器。", ""));
    }
    void answer(String answer, List<String> ids, String finish) throws Exception {
        response.set(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content",
                mapper.writeValueAsString(Map.of("answer", answer, "citationIds", ids))))))));
    }
    @Test void llmGeneratesAnswerNotDocumentSelection() throws Exception {
        answer("根据资料检查连接器。[DOC-C1]", List.of("DOC-C1"), "stop");
        var generated = new ModelGateway(env, mapper).generate("如何检查", evidence());
        assertEquals("根据资料检查连接器。[DOC-C1]", generated.answer());
        assertEquals(List.of("DOC-C1"), generated.citationIds());
        assertEquals("json_object", mapper.readTree(request.get()).path("response_format").path("type").asText());
    }
    @Test void illegalReferencesUncitedParagraphAndTruncationRefuse() throws Exception {
        var gateway = new ModelGateway(env, mapper);
        answer("事实[OTHER]", List.of("OTHER"), "stop");
        assertThrows(DiagnosisService.Failure.class, () -> gateway.generate("q", evidence()));
        answer("事实[DOC-C1]\n无引用事实", List.of("DOC-C1"), "stop");
        assertThrows(DiagnosisService.Failure.class, () -> gateway.generate("q", evidence()));
        answer("事实[DOC-C1]", List.of("DOC-C1"), "length");
        assertThrows(DiagnosisService.Failure.class, () -> gateway.generate("q", evidence()));
        response.set("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not-json\"}}]}");
        assertThrows(Exception.class, () -> gateway.generate("q", evidence()));
    }
    @Test void rrfUsesBothRanksK60DeterministicAndTop5() {
        var result = CorpusService.fuse(List.of("A", "B", "C", "D", "E", "F"), List.of("B", "A", "G"));
        assertEquals(5, result.size());
        assertEquals("A", result.getFirst().chunkId());
        assertEquals(1, result.getFirst().bm25Rank());
        assertEquals(2, result.getFirst().vectorRank());
        assertEquals(1.0/61 + 1.0/62, result.getFirst().rrfScore(), 1e-12);
    }
    @Test void enabledVectorMissingDatabaseDoesNotFallBack() throws Exception {
        env.withProperty("contextflow.retrieval.mode", "hybrid");
        response.set("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}");
        try (var corpus = new CorpusService(mapper, env)) {
            assertThrows(Exception.class, () -> corpus.retrieve("Sedan", 2023, "充电"));
            assertEquals(false, corpus.readiness().get("ready"));
            assertFalse(mapper.writeValueAsString(corpus.readiness()).contains("fake-test-key"));
        }
    }
    @Test void importsStableChunksAndDoesNotMixBuiltinEvaluation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("corpus.json");
        Files.writeString(file, mapper.writeValueAsString(evidence()));
        var imported = new MockEnvironment().withProperty("contextflow.corpus-path", file.toString())
                .withProperty("contextflow.retrieval.mode", "hybrid").withProperty("contextflow.llm.enabled", "false");
        try (var corpus = new CorpusService(mapper, imported)) {
            assertTrue(corpus.external());
            assertTrue(corpus.version().startsWith("imported-"));
            assertEquals("DOC-C1", corpus.search("M", 2023, "连接器").getFirst().docId());
            assertEquals(0, corpus.evaluate("offline").total());
            var service = new DiagnosisService(corpus, imported);
            var result = service.execute(service.newRun(new Contracts.DiagnosisRequest("M", 2023, "连接器", null), ignored -> {}));
            assertEquals("error", result.status());
            assertTrue(result.message().contains("LLM_DISABLED"));
        }
    }
    @Test void realNetworkTimeoutIsBounded() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            try { Thread.sleep(600); exchange.sendResponseHeaders(200, -1); } catch (Exception ignored) {} finally { exchange.close(); }
        });
        env.withProperty("contextflow.network-timeout-ms", "100");
        long start = System.nanoTime();
        assertThrows(Exception.class, () -> new ModelGateway(env, mapper).embed("test"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
    }
}

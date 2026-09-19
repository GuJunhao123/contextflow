package com.contextflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.contextflow.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosisServiceTest {
    CorpusService corpus;
    DiagnosisService service;
    HttpServer server;
    AtomicInteger calls = new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        corpus = new CorpusService(new ObjectMapper(), new MockEnvironment().withProperty("contextflow.llm.enabled", "false"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            var mapper = new ObjectMapper();
            var payload = mapper.readTree(exchange.getRequestBody());
            String prompt = payload.path("messages").get(0).path("content").asText();
            assertTrue(prompt.contains("排查步骤"));
            String id = mapper.readTree(payload.path("messages").get(1).path("content").asText()).path("evidence").get(0).path("docId").asText();
            String answer = "诊断依据：提示需要核查。[" + id + "]\n排查步骤：记录提示并联系专业人员。[" + id + "]\n建议结论：尚不能确认故障部件。[" + id + "]";
            byte[] bytes = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", mapper.writeValueAsString(Map.of("answer", answer, "citationIds", List.of(id))))))));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        service = new DiagnosisService(corpus, new MockEnvironment().withProperty("contextflow.llm.enabled", "true")
                .withProperty("contextflow.llm.api-key", "test-key").withProperty("contextflow.llm.model", "test-model")
                .withProperty("contextflow.llm.base-url", "http://127.0.0.1:" + server.getAddress().getPort()));
    }
    @AfterEach void close() throws Exception { server.stop(0); corpus.close(); }
    Result diagnose(String model, Integer year, String question, String scenario) {
        return service.execute(service.newRun(new DiagnosisRequest(model, year, question, scenario), ignored -> {}));
    }
    @Test void allScenariosCallLlmOnceAndKeepCitations() {
        for (var scenario : corpus.scenarios()) {
            var result = diagnose(scenario.model(), scenario.year(), scenario.question(), "normal");
            assertEquals("answer", result.status(), result.message());
            assertEquals("llm", result.mode());
            assertEquals(1, result.citations().size());
            assertTrue(result.answer().contains("建议结论"));
            assertEquals(List.of("input_check", "lucene_search", "evidence_check", "generate", "citation"), result.trace().stream().map(Trace::node).toList());
            assertTrue(result.trace().get(1).detail().contains("candidateSource=fixed-evidence"));
            assertFalse(result.trace().get(1).detail().contains("RRF"));
        }
        assertEquals(8, calls.get());
    }
    @Test void modelInputCasePreservesEvidence() throws Exception {
        for (String mode : List.of("fixed-evidence", "hybrid")) {
            try (var retrievalCorpus = new CorpusService(new ObjectMapper(), new MockEnvironment()
                    .withProperty("contextflow.retrieval.mode", mode))) {
                var expected = retrievalCorpus.retrieve("Sedan", 2023, "交流慢充充电中断");
                assertFalse(expected.top5().isEmpty());
                for (String model : List.of("sedan", " SEDAN ")) {
                    assertEquals(expected, retrievalCorpus.retrieve(model, 2023, "交流慢充充电中断"));
                }
                assertTrue(expected.top5().stream().allMatch(hit ->
                        retrievalCorpus.source(hit.chunkId()).orElseThrow().model().equals("Sedan")));
            }
        }
        var standard = diagnose("Sedan", 2023, "交流慢充充电中断", null);
        var lowercase = diagnose("sedan", 2023, "交流慢充充电中断", null);
        assertEquals("answer", standard.status());
        assertEquals("answer", lowercase.status());
        assertFalse(standard.citations().isEmpty());
        assertEquals(standard.citations(), lowercase.citations());
    }
    @Test void noEvidenceNeverCallsModel() {
        var result = diagnose("Sedan", 2023, "交流慢充充电中断怎么办", null);
        assertEquals("no_evidence", result.status());
        assertEquals("", result.answer());
        assertTrue(result.citations().isEmpty());
        assertEquals(0, calls.get());
    }
    @Test void disabledAndMissingConfigurationNeverProduceTemplate() {
        for (var env : List.of(new MockEnvironment().withProperty("contextflow.llm.enabled", "false"), new MockEnvironment().withProperty("contextflow.llm.enabled", "true"))) {
            var unavailable = new DiagnosisService(corpus, env);
            var result = unavailable.execute(unavailable.newRun(new DiagnosisRequest("Sedan", 2023, "交流慢充充电中断", null), ignored -> {}));
            assertEquals("error", result.status());
            assertEquals("", result.answer());
            assertTrue(result.message().contains(env.getProperty("contextflow.llm.enabled").equals("false") ? "LLM_DISABLED" : "CONFIG_MISSING"));
        }
    }
    @Test void missingVehicleAsksWithoutRetrieval() {
        for (var result : List.of(diagnose(null, 2023, "充电", null), diagnose("Sedan", null, "充电", null))) {
            assertEquals("ask_user", result.status());
            assertEquals(1, result.trace().size());
        }
    }
    @Test void toolsValidateWhitelistAndBoundedCorrection() {
        var source = corpus.all().getFirst();
        assertThrows(IllegalArgumentException.class, () -> corpus.readSourceChunk("shell", source.docId(), source.page(), Set.of(source.docId())));
        assertThrows(IllegalArgumentException.class, () -> corpus.readSourceChunk("readSourceChunk", source.docId(), source.page() + 1, Set.of(source.docId())));
        assertThrows(IllegalArgumentException.class, () -> corpus.readSourceChunk("readSourceChunk", source.docId(), source.page(), Set.of()));
        assertEquals(source.citation(), corpus.readSourceChunk("readSourceChunk", source.docId(), source.page(), Set.of(source.docId())));
        var repaired = diagnose("Sedan", 2023, "交流慢充充电中断", "tool_repair");
        assertEquals("answer", repaired.status());
        assertEquals(1, repaired.trace().stream().filter(t -> t.node().equals("tool_validation")).count());
        var exhausted = diagnose("Sedan", 2023, "交流慢充充电中断", "tool_repair_exhausted");
        assertEquals("error", exhausted.status());
        assertTrue(exhausted.message().contains("TOOL_ARGUMENTS_EXHAUSTED"));
        assertEquals(3, exhausted.trace().stream().filter(t -> t.node().equals("tool_validation")).count());
    }
    @Test void invalidInputsDeadlineAndExplicitFailures() throws Exception {
        assertEquals("error", diagnose("Sedan", 2023, " ", null).status());
        assertEquals("error", diagnose("Sedan", 2023, "x".repeat(2001), null).status());
        assertEquals("error", diagnose("Sedan", 1800, "充电", null).status());
        assertEquals("error", diagnose("Sedan", 2023, "充电", "unknown").status());
        for (String scenario : List.of("timeout", "tool_error")) assertTrue(diagnose("Sedan", 2023, "充电", scenario).message().contains("INJECTED"));
        var shortService = new DiagnosisService(corpus, new MockEnvironment().withProperty("contextflow.llm.enabled", "false").withProperty("contextflow.timeout-ms", "100"));
        var run = shortService.newRun(new DiagnosisRequest("Sedan", 2023, "充电", null), ignored -> {});
        Thread.sleep(130);
        assertTrue(shortService.execute(run).message().contains("TIMEOUT"));
    }
    @Test void evaluationIncludesPreparedAndUncoveredQuestionsWithoutClamping() throws Exception {
        for (int run = 0; run < 3; run++) {
            var result = corpus.evaluate("llm");
            assertEquals(12, result.total());
            assertEquals(8, result.cases().stream().filter(CaseResult::hit).count());
            assertEquals(4, result.cases().stream().filter(c -> c.actual().isEmpty()).count());
            assertEquals(8.0 / 12, result.hitAt5());
            assertEquals(8.0 / 12, result.recallAt5());
            assertTrue(result.hitAt5() <= 0.8);
            assertEquals(result.cases().stream().filter(CaseResult::hit).count() / (double) result.total(), result.hitAt5());
            assertEquals(result.cases().stream().mapToDouble(CaseResult::recall).average().orElseThrow(), result.recallAt5());
            for (var item : result.cases()) {
                long matched = item.gold().stream().distinct().filter(item.actual()::contains).count();
                assertEquals(matched > 0, item.hit());
                assertEquals(matched / (double) new HashSet<>(item.gold()).size(), item.recall());
            }
            for (var scenario : corpus.scenarios()) {
                assertTrue(result.cases().stream().anyMatch(c -> c.question().equals(scenario.question()) && c.hit()));
            }
            assertTrue(result.dataset().contains("TSB证据匹配验证"));
        }
        assertEquals(0, calls.get());
    }
}

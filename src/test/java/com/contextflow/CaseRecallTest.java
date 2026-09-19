package com.contextflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CaseRecallTest {
    @TempDir Path temp;

    @Test void fixedEvidencePreservesPreparedOrderWithoutScores() throws Exception {
        var mapper = new ObjectMapper();
        List<CorpusService.RecallCase> prepared;
        try (var input = new ClassPathResource("case-recall.json").getInputStream()) {
            prepared = mapper.readValue(input, new TypeReference<>() {});
        }
        try (var corpus = new CorpusService(mapper, new MockEnvironment().withProperty("contextflow.llm.enabled", "false"))) {
            assertEquals(8, prepared.size());
            for (var item : prepared) {
                var result = corpus.retrieve(" " + item.model().toLowerCase(Locale.ROOT) + " ", item.year(), " " + item.question() + " ");
                assertEquals("fixed-evidence", result.mode());
                assertEquals("fixed-evidence", result.candidateSource());
                assertEquals(item.id(), result.matchedCase());
                assertEquals(item.candidates(), result.top5().stream().map(CorpusService.Ranked::chunkId).toList());
                assertFalse(result.top5().isEmpty());
                assertTrue(result.bm25().isEmpty());
                assertTrue(result.vector().isEmpty());
                for (var ranked : result.top5()) {
                    assertNull(ranked.bm25Rank());
                    assertNull(ranked.vectorRank());
                    assertEquals(0, ranked.rrfScore());
                }
            }
            var unknown = corpus.retrieve("Sedan", 2023, "充电");
            assertEquals("fixed-evidence", unknown.candidateSource());
            assertNull(unknown.matchedCase());
            assertTrue(unknown.top5().isEmpty());
            assertTrue(corpus.retrieve("Sedan", 2022, "交流慢充充电中断").top5().isEmpty());
            assertTrue(corpus.retrieve("SUV", 2023, "交流慢充充电中断").top5().isEmpty());
        }
    }

    @Test void legacyHybridRequiresExplicitMode() throws Exception {
        var env = new MockEnvironment().withProperty("contextflow.retrieval.mode", "hybrid")
                .withProperty("contextflow.llm.enabled", "false").withProperty("contextflow.case-recall.enabled", "false");
        try (var corpus = new CorpusService(new ObjectMapper(), env)) {
            var result = corpus.retrieve("Sedan", 2023, "交流慢充充电中断");
            assertEquals("offline-bm25", result.mode());
            assertFalse(result.bm25().isEmpty());
            assertTrue(result.vector().isEmpty());
        }
        env.withProperty("contextflow.vector.enabled", "true");
        try (var corpus = new CorpusService(new ObjectMapper(), env)) {
            assertEquals("hybrid-pgvector-bm25", corpus.retrievalMode());
            assertEquals("CONFIG_MISSING", assertThrows(DiagnosisService.Failure.class,
                    () -> corpus.retrieve("Sedan", 2023, "交流慢充充电中断")).code);
        }
    }

    @Test void configuredCandidatesAreFilteredAndIndependentOfEvaluationGold() throws Exception {
        var mapper = new ObjectMapper();
        Path cases = temp.resolve("cases.json"), evaluation = temp.resolve("evaluation.json");
        List<String> candidates;
        try (var corpus = new CorpusService(mapper, new MockEnvironment())) {
            candidates = List.of(
                    corpus.all().stream().filter(s -> s.model().equals("Sedan") && s.yearFrom() <= 2022 && s.title().contains("事件记录")).findFirst().orElseThrow().docId(),
                    corpus.all().stream().filter(s -> s.model().equals("Sedan") && s.yearFrom() > 2022).findFirst().orElseThrow().docId(),
                    corpus.all().stream().filter(s -> s.model().equals("SUV")).findFirst().orElseThrow().docId(),
                    corpus.all().stream().filter(s -> s.model().equals("Sedan") && s.title().contains("慢充中断")).findFirst().orElseThrow().docId());
        }
        Files.writeString(cases, mapper.writeValueAsString(List.of(new CorpusService.RecallCase("custom", "过滤", "Sedan", 2022, "充电", candidates))));
        Files.writeString(evaluation, "[]");
        var env = new MockEnvironment().withProperty("contextflow.case-recall.path", cases.toString())
                .withProperty("contextflow.evaluation-path", evaluation.toString()).withProperty("contextflow.llm.enabled", "false");
        try (var corpus = new CorpusService(mapper, env)) {
            assertEquals(List.of(candidates.get(0), candidates.get(3)), corpus.retrieve("Sedan", 2022, "充电").top5().stream().map(CorpusService.Ranked::chunkId).toList());
            assertEquals(0, corpus.evaluate("unavailable").total());
        }
        Files.writeString(cases, Files.readString(cases).replace(candidates.getFirst(), "UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> new CorpusService(mapper, env));
        Files.writeString(cases, mapper.writeValueAsString(List.of(new CorpusService.RecallCase(
                "too-many", "过多", "Sedan", 2023, "充电", Collections.nCopies(31, candidates.getFirst())))));
        assertThrows(IllegalArgumentException.class, () -> new CorpusService(mapper, env));
    }

    @Test void fixedModeNeverInitializesIndexesOrProbesEmbedding() throws Exception {
        var env = new MockEnvironment().withProperty("contextflow.vector.enabled", "true")
                .withProperty("contextflow.llm.enabled", "false");
        try (var corpus = new CorpusService(new ObjectMapper(), env)) {
            for (String name : List.of("analyzer", "directory", "reader", "searcher", "vectors")) {
                var field = CorpusService.class.getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(corpus), name);
            }
            assertEquals("ready", corpus.rebuild().get("status"));
            assertEquals("fixed-evidence", corpus.readiness().get("retrieval"));
            assertEquals(false, corpus.readiness().get("ready"));
            assertFalse(corpus.retrieve("Sedan", 2023, "交流慢充充电中断").top5().isEmpty());
            assertEquals(8, corpus.scenarios().size());
            assertEquals(20, corpus.all().size());
            for (var source : corpus.all()) {
                var origin = corpus.provenance().get(source.docId());
                assertEquals("authored-tsb", origin.kind());
                assertNull(origin.url());
                assertTrue(origin.applicability().contains("自编TSB"));
                assertTrue(origin.applicability().contains("非正式厂商资料"));
            }
            String publicData = new ObjectMapper().writeValueAsString(List.of(corpus.all(), corpus.scenarios(), corpus.provenance()));
            assertFalse(publicData.toLowerCase().contains("tesla"));
            assertFalse(publicData.contains("MODEL3"));
        }
    }

    @Test void bundledReferencesAndLocatorsAreConsistent() throws Exception {
        var mapper = new ObjectMapper();
        try (var corpus = new CorpusService(mapper, new MockEnvironment());
             var input = new ClassPathResource("provenance.json").getInputStream()) {
            Map<String, CorpusService.Provenance> origins = mapper.readValue(input, new TypeReference<>() {});
            Set<String> ids = new HashSet<>();
            Set<Integer> pages = new HashSet<>();
            for (var source : corpus.all()) {
                assertTrue(source.docId().matches("CF-TSB-\\d{3}"));
                int number = Integer.parseInt(source.docId().substring(7));
                assertTrue(number >= 1 && number <= 200);
                assertTrue(ids.add(source.docId()));
                assertTrue(source.page() >= 1 && source.page() <= 200);
                assertTrue(pages.add(source.page()));
                var origin = origins.get(source.docId());
                assertNotNull(origin);
                assertEquals(source.title(), origin.title());
                assertEquals(source.docId() + "，第" + source.page() + "页", origin.locator());
            }
            assertEquals(ids, origins.keySet());
            var numbers = ids.stream().map(id -> Integer.parseInt(id.substring(7))).sorted().toList();
            for (int i = 1; i < numbers.size(); i++) assertTrue(numbers.get(i) - numbers.get(i - 1) > 1);
            assertTrue(numbers.getFirst() <= 20 && numbers.getLast() >= 180);
            assertTrue(Collections.min(pages) <= 20 && Collections.max(pages) >= 180);
            var result = corpus.evaluate("unavailable");
            for (var item : result.cases()) {
                assertFalse(item.gold().isEmpty());
                assertTrue(ids.containsAll(item.gold()));
                assertTrue(ids.containsAll(item.actual()));
            }
        }
    }
}

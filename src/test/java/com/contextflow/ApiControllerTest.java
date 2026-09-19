package com.contextflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiControllerTest {
    private CorpusService corpus;
    private ApiController controller;
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        var env = new MockEnvironment().withProperty("contextflow.llm.enabled", "false");
        corpus = new CorpusService(new ObjectMapper(), env);
        controller = new ApiController(corpus, new DiagnosisService(corpus, env));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler(controller)).build();
    }

    @AfterEach void close() throws Exception { controller.shutdown(); corpus.close(); }

    @Test void metaCorpusSourceAndEvaluation() throws Exception {
        mvc.perform(get("/api/meta")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("unavailable"))
                .andExpect(jsonPath("$.corpusSize").value(20)).andExpect(jsonPath("$.limits.queueCapacity").value(32))
                .andExpect(jsonPath("$.caseRecallEnabled").value(true));
        mvc.perform(get("/api/corpus")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(20));
        var source = corpus.all().getFirst();
        mvc.perform(get("/api/source/" + source.docId())).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(source.page()));
        mvc.perform(get("/api/source/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.requestId").isString());
        for (int run = 0; run < 3; run++) {
            mvc.perform(post("/api/evaluation")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(12))
                    .andExpect(jsonPath("$.hitAt5").value(8.0 / 12)).andExpect(jsonPath("$.recallAt5").value(8.0 / 12))
                    .andExpect(jsonPath("$.cases[?(@.hit == true)]").value(org.hamcrest.Matchers.hasSize(8)));
        }
    }

    @Test void readinessRetrievalAndExplicitRebuild() throws Exception {
        mvc.perform(get("/api/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.retrieval").value("fixed-evidence"));
        mvc.perform(get("/api/retrieval").param("model", "Sedan").param("year", "2023").param("question", "交流慢充充电中断"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidateLimit").value(5)).andExpect(jsonPath("$.rrfK").value(0))
                .andExpect(jsonPath("$.top5[0].bm25Rank").isEmpty()).andExpect(jsonPath("$.top5[0].vectorRank").isEmpty())
                .andExpect(jsonPath("$.top5[0].rrfScore").value(0)).andExpect(jsonPath("$.top5[0].chunkId").value(
                        corpus.search("Sedan", 2023, "交流慢充充电中断").getFirst().docId()))
                .andExpect(jsonPath("$.vector.length()").value(0)).andExpect(jsonPath("$.bm25.length()").value(0))
                .andExpect(jsonPath("$.candidateSource").value("fixed-evidence")).andExpect(jsonPath("$.matchedCase").value("cf-charge"));
        mvc.perform(post("/api/index/rebuild")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("fixed-evidence"));
    }

    @Test void scenariosAndProvenanceExposeServerConfiguration() throws Exception {
        mvc.perform(get("/api/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8)).andExpect(jsonPath("$[0].id").value("cf-charge"))
                .andExpect(jsonPath("$[0].model").value("Sedan")).andExpect(jsonPath("$[0].year").value(2023))
                .andExpect(jsonPath("$[0].question").isString()).andExpect(jsonPath("$[0].candidates").doesNotExist());
        var source = corpus.all().getFirst();
        String originPath = "$['" + source.docId() + "']";
        mvc.perform(get("/api/provenance")).andExpect(status().isOk())
                .andExpect(jsonPath(originPath + ".kind").value("authored-tsb"))
                .andExpect(jsonPath(originPath + ".url").isEmpty())
                .andExpect(jsonPath(originPath + ".locator").value(source.docId() + "，第" + source.page() + "页"))
                .andExpect(jsonPath(originPath + ".applicability").value(org.hamcrest.Matchers.containsString("非正式厂商资料")));
    }

    @Test void diagnosisAndMalformedJson() throws Exception {
        mvc.perform(post("/api/diagnosis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"Sedan\",\"year\":2023,\"question\":\"交流慢充充电中断\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("LLM_DISABLED")))
                .andExpect(jsonPath("$.answer").value("")).andExpect(jsonPath("$.citations.length()").value(0))
                .andExpect(jsonPath("$.trace[0].node").value("input_check"));
        mvc.perform(post("/api/diagnosis").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("error"));
    }

    @Test void sseHasJsonNodeAndTerminalResult() throws Exception {
        var pending = mvc.perform(post("/api/diagnosis/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"充电中断\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:node")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:result")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ask_user")));
    }
}

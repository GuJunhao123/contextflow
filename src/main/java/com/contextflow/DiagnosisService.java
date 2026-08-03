package com.contextflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.contextflow.Contracts.*;
import static org.bsc.langgraph4j.StateGraph.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

@Service
public class DiagnosisService {
    private final CorpusService corpus;
    private final ModelGateway gateway;
    private final long timeoutMs;

    public DiagnosisService(CorpusService corpus, Environment env) {
        this.corpus = corpus;
        gateway = new ModelGateway(env, new ObjectMapper());
        timeoutMs = Math.max(100, Math.min(120000, env.getProperty("contextflow.timeout-ms", Long.class, 120000L)));
    }
    public String mode() { return gateway.enabled("llm") ? "llm" : "unavailable"; }
    public long timeoutMs() { return timeoutMs; }
    public final class Run {
        final String id = UUID.randomUUID().toString();
        final DiagnosisRequest request;
        final long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        final List<Trace> trace = new CopyOnWriteArrayList<>();
        final Consumer<Trace> listener;
        List<Source> hits = List.of();
        List<Source> cited = List.of();
        List<Citation> citations = List.of();
        String status = "answer";
        String message = "依据TSB诊断资料提供建议；引用校验不代表诊断已确认，请由专业人员核实。禁止自行拆修高压或带电部件。";
        String answer = "";
        int modelCalls;
        Run(DiagnosisRequest request, Consumer<Trace> listener) { this.request = request; this.listener = listener; }
        public String id() { return id; }
        void check() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new Failure("TIMEOUT", "诊断超时，已停止等待。");
        }
        void call() {
            check();
            if (++modelCalls > (corpus.fixedEvidence() ? 1 : 2)) throw new Failure("CALL_BUDGET", "已达到单次诊断模型调用上限，不自动重试。");
        }
        void emit(Trace item) { trace.add(item); listener.accept(item); }
        public Result error(String code, String text) {
            List<Trace> snapshot = new ArrayList<>(trace);
            snapshot.add(new Trace("error", "error", 0, code));
            return new Result(id, "error", code + ": " + text, "", List.of(), List.copyOf(snapshot), mode());
        }
        Result result() { return new Result(id, status, message, answer, citations, List.copyOf(trace), mode()); }
    }
    public Run newRun(DiagnosisRequest request, Consumer<Trace> listener) { return new Run(request, listener); }
    public static class Failure extends RuntimeException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    @FunctionalInterface private interface Step { String execute() throws Exception; }
    private Map<String, Object> node(Run run, String name, Step action) throws Exception {
        run.check();
        long start = System.nanoTime();
        try {
            String detail = action.execute(); run.check();
            run.emit(new Trace(name, "ok", (System.nanoTime() - start) / 1000000, detail));
            return Map.of("stage", name);
        } catch (Exception e) {
            run.emit(new Trace(name, "error", (System.nanoTime() - start) / 1000000, e instanceof Failure f ? f.code : "NODE_FAILURE"));
            if (e instanceof Failure f) throw f;
            // LangGraph logs node failures: never let provider/driver exception messages reach its logger.
            throw new Failure("DEPENDENCY_ERROR", "外部依赖调用失败，未生成建议。");
        }
    }
    public Result execute(Run run) {
        try {
            var graph = new StateGraph<>(AgentState::new)
                    .addNode("input_check", node_async(state -> node(run, "input_check", () -> validate(run))))
                    .addNode("lucene_search", node_async(state -> node(run, "lucene_search", () -> {
                        if ("timeout".equals(run.request.scenario())) {
                            run.emit(new Trace("injection", "injected", 0, "演练注入：timeout，非真实外部事故"));
                            throw new Failure("TIMEOUT_INJECTED", "演练注入超时，非真实外部事故。");
                        }
                        if ("tool_error".equals(run.request.scenario())) throw new Failure("TOOL_ERROR_INJECTED", "演练注入工具异常，非真实外部事故。");
                        if (!corpus.fixedEvidence() && corpus.gateway.enabled("vector")) run.call();
                        var retrieval = corpus.retrieve(run.request.model(), run.request.year(), run.request.question());
                        run.hits = retrieval.top5().stream().map(r -> corpus.source(r.chunkId()).orElseThrow()).toList();
                        return corpus.fixedEvidence()
                                ? "按车型、年份及完整问题匹配TSB证据；candidateSource=fixed-evidence；有序证据=" + run.hits.size()
                                : "检索模式=" + retrieval.mode() + "；candidateSource=" + retrieval.candidateSource()
                                + "；词法候选=" + retrieval.bm25().size() + "；第二路候选=" + retrieval.vector().size() + "；证据=" + run.hits.size();
                    })))
                    .addNode("evidence_check", node_async(state -> node(run, "evidence_check", () -> {
                        if (run.hits.isEmpty()) {
                            run.status = "no_evidence";
                            run.message = "当前语料中无匹配证据，不能据此诊断或编造维修建议。请联系专业人员。";
                        }
                        return run.status;
                    })))
                    .addNode("generate", node_async(state -> node(run, "generate", () -> generate(run))))
                    .addNode("citation", node_async(state -> node(run, "citation", () -> cite(run))))
                    .addEdge(START, "input_check")
                    .addConditionalEdges("input_check", edge_async(state -> "answer".equals(run.status) ? "continue" : "stop"), Map.of("continue", "lucene_search", "stop", END))
                    .addEdge("lucene_search", "evidence_check")
                    .addConditionalEdges("evidence_check", edge_async(state -> "answer".equals(run.status) ? "continue" : "stop"), Map.of("continue", "generate", "stop", END))
                    .addEdge("generate", "citation").addEdge("citation", END).compile();
            for (var ignored : graph.stream(Map.of())) run.check();
            return run.result();
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && !(cause instanceof Failure)) cause = cause.getCause();
            if (cause instanceof Failure f) return run.error(f.code, f.getMessage());
            return run.error("DEPENDENCY_ERROR", "诊断执行失败，未生成建议。请使用requestId排查。");
        }
    }
    private String validate(Run run) {
        var r = run.request;
        if (r == null || r.question() == null || r.question().isBlank()) throw new Failure("INVALID_INPUT", "question不能为空。");
        if (r.question().length() > 2000 || (r.model() != null && r.model().length() > 64) || (r.year() != null && (r.year() < 1900 || r.year() > 2100)))
            throw new Failure("INVALID_INPUT", "问题最多2000字符、车型最多64字符、年份范围1900至2100。");
        if (r.scenario() != null && !Set.of("normal", "timeout", "tool_error", "tool_repair", "tool_repair_exhausted").contains(r.scenario()))
            throw new Failure("INVALID_SCENARIO", "不支持该演练场景。");
        if (r.model() == null || r.model().isBlank() || r.year() == null) {
            run.status = "ask_user"; run.message = "请补充车型和年份（例如Sedan、2023），再进行检索。";
        }
        return run.status;
    }
    private String generate(Run run) throws Exception {
        if (gateway.enabled("llm")) {
            run.call();
            var generated = gateway.generate(run.request.question(), run.hits);
            run.answer = generated.answer();
            run.cited = generated.citationIds().stream().map(id -> run.hits.stream().filter(s -> s.docId().equals(id)).findFirst().orElseThrow()).toList();
            return "已由模型基于证据生成诊断依据、排查步骤与建议结论；逐段引用校验通过，请专业人员核实";
        }
        throw new Failure("LLM_DISABLED", "诊断生成服务配置不可用，请启用并配置模型服务后重试。");
    }
    private String cite(Run run) {
        Set<String> allowed = new HashSet<>(run.hits.stream().map(Source::docId).toList());
        boolean repair = "tool_repair".equals(run.request.scenario());
        boolean exhausted = "tool_repair_exhausted".equals(run.request.scenario());
        for (int correction = 0; correction <= 2; correction++) {
            try {
                List<Citation> citations = new ArrayList<>();
                for (Source selected : run.cited) {
                    int page = exhausted || (repair && correction == 0) ? -1 : selected.page();
                    citations.add(corpus.readSourceChunk("readSourceChunk", selected.docId(), page, allowed));
                }
                run.citations = List.copyOf(citations);
                return "引用资料已通过文档及页码核验；修正次数=" + correction;
            } catch (IllegalArgumentException e) {
                run.emit(new Trace("tool_validation", "rejected", 0, "演练参数校验拒绝；attempt=" + (correction + 1) + "；最多2次修正"));
                if (correction == 2) throw new Failure("TOOL_ARGUMENTS_EXHAUSTED", "工具参数修正两次后仍不合法，终止。");
            }
        }
        throw new IllegalStateException();
    }
}

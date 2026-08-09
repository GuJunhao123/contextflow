package com.contextflow;

import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.contextflow.Contracts.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final CorpusService corpus;
    private final DiagnosisService diagnosis;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);

    public ApiController(CorpusService corpus, DiagnosisService diagnosis) {
        this.corpus = corpus;
        this.diagnosis = diagnosis;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of("mode", diagnosis.mode(), "corpusSize", corpus.all().size(), "corpusVersion", corpus.version(),
                "limits", Map.of("workers", 8, "queueCapacity", 32, "timeoutMs", diagnosis.timeoutMs(),
                        "topK", 5, "questionMaxLength", 2000, "modelMaxLength", 64, "maxToolCorrections", 2),
                "retrieval", corpus.fixedEvidence() ? "fixed-evidence；按车型、年份和完整问题匹配已编排的TSB证据，按资料顺序返回至多5条；未匹配不返回证据；不计算词法、向量或融合分数。" : corpus.retrievalMode() + "；按车型年份过滤并返回至多5条证据。",
                "generation", "llm".equals(diagnosis.mode()) ? "模型依据证据生成简要诊断依据、排查步骤和建议结论，每段保留引用；引用校验不代表诊断已确认。" : "诊断生成服务配置不可用。", 
                "caseRecallEnabled", corpus.caseRecallEnabled(),
                "scenarios", List.of("normal", "timeout", "tool_error", "tool_repair", "tool_repair_exhausted"),
                "notice", "TSB诊断资料用于辅助专业核查，资料元信息见/api/provenance。请核对车型、年份及实际车辆状态；涉及制动、高压或过热告警应安全停用并联系专业人员，不得自行拆修带电部件。");
    }

    @GetMapping("/readiness")
    public Object readiness() {
        return waitFor(corpus::readiness, diagnosis.newRun(null, ignored -> {}));
    }

    @GetMapping("/retrieval")
    public Object retrieval(@RequestParam String model, @RequestParam int year, @RequestParam String question) {
        return waitFor(() -> corpus.retrieve(model, year, question), diagnosis.newRun(null, ignored -> {}));
    }

    @PostMapping("/index/rebuild")
    public Object rebuild() {
        return waitFor(corpus::rebuild, diagnosis.newRun(null, ignored -> {}));
    }

    @GetMapping("/scenarios")
    public List<CorpusService.Scenario> scenarios() { return corpus.scenarios(); }

    @GetMapping("/provenance")
    public Map<String, CorpusService.Provenance> provenance() { return corpus.provenance(); }

    @GetMapping("/corpus")
    public List<Source> corpus() { return corpus.all(); }

    @GetMapping("/source/{docId}")
    public ResponseEntity<?> source(@PathVariable String docId) {
        return corpus.source(docId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(error("NOT_FOUND", "不存在该服务端文档。")));
    }

    @PostMapping("/diagnosis")
    public Result diagnose(@RequestBody DiagnosisRequest request) {
        var run = diagnosis.newRun(request, ignored -> {});
        return waitFor(() -> diagnosis.execute(run), run);
    }

    @PostMapping("/evaluation")
    public Object evaluation() {
        var run = diagnosis.newRun(null, ignored -> {});
        return waitFor(() -> corpus.evaluate(diagnosis.mode()), run);
    }

    private <T> T waitFor(Callable<T> action, DiagnosisService.Run run) {
        Future<T> future;
        try { future = workers.submit(action); }
        catch (RejectedExecutionException e) { throw new ApiFailure(503, run.error("OVERLOADED", "8个工作线程和32个排队位已满。")); }
        try { return future.get(diagnosis.timeoutMs(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) {
            future.cancel(true);
            workers.purge();
            throw new ApiFailure(504, run.error("TIMEOUT", "请求超时，已取消等待。"));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiFailure(503, run.error("INTERRUPTED", "请求中断。"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DiagnosisService.Failure f)
                throw new ApiFailure("INVALID_INPUT".equals(f.code) ? 400 : 503, run.error(f.code, f.getMessage()));
            throw new ApiFailure(503, run.error("DEPENDENCY_ERROR", "诊断依赖暂不可用，请稍后重试。"));
        }
    }

    @PostMapping(value = "/diagnosis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody DiagnosisRequest request) {
        SseEmitter emitter = new SseEmitter(diagnosis.timeoutMs() + 1000);
        StreamSession session = new StreamSession(emitter);
        var run = diagnosis.newRun(request, trace -> session.node(trace));
        emitter.onCompletion(session::cancel);
        emitter.onTimeout(() -> session.finish("error", run.error("TIMEOUT", "SSE请求超时。")));
        emitter.onError(ignored -> session.cancel());
        FutureTask<Void> task = new FutureTask<>(() -> {
            Result result = diagnosis.execute(run);
            session.finish("error".equals(result.status()) ? "error" : "result", result);
            return null;
        });
        session.task.set(task);
        try {
            workers.execute(task);
            var timer = deadlines.schedule(() -> session.finish("error", run.error("TIMEOUT", "请求超时，已取消等待。")),
                    diagnosis.timeoutMs(), TimeUnit.MILLISECONDS);
            session.timer.set(timer);
            if (session.finished) timer.cancel(false);
        } catch (RejectedExecutionException e) {
            session.finish("error", run.error("OVERLOADED", "8个工作线程和32个排队位已满。"));
        }
        return emitter;
    }

    private final class StreamSession {
        final SseEmitter emitter;
        final AtomicReference<Future<?>> task = new AtomicReference<>();
        final AtomicReference<Future<?>> timer = new AtomicReference<>();
        volatile boolean finished;
        StreamSession(SseEmitter emitter) { this.emitter = emitter; }
        synchronized void node(Trace trace) {
            if (finished) return;
            try { emitter.send(SseEmitter.event().name("node").data(trace, MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException e) { cancel(); }
        }
        synchronized void finish(String event, Result result) {
            if (finished) return;
            finished = true;
            try { emitter.send(SseEmitter.event().name(event).id(result.requestId()).data(result, MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException ignored) { /* Client disconnected. */ }
            finally {
                if ("error".equals(event)) cancelTask();
                Future<?> timeout = timer.get();
                if (timeout != null) timeout.cancel(false);
                emitter.complete();
            }
        }
        synchronized void cancel() {
            finished = true;
            cancelTask();
            Future<?> timeout = timer.get();
            if (timeout != null) timeout.cancel(false);
        }
        void cancelTask() {
            Future<?> work = task.get();
            if (work != null && !work.isDone()) work.cancel(true);
            workers.purge();
        }
    }

    Result error(String code, String message) { return diagnosis.newRun(null, ignored -> {}).error(code, message); }

    static class ApiFailure extends RuntimeException {
        final int status;
        final Result result;
        ApiFailure(int status, Result result) { this.status = status; this.result = result; }
    }

    @PreDestroy
    public void shutdown() {
        deadlines.shutdownNow();
        workers.shutdownNow();
    }
}

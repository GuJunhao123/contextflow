package com.contextflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.contextflow.Contracts.*;

@Service
public class CorpusService implements AutoCloseable {
    private final List<Source> sources;
    private final List<EvaluationCase> evaluation;
    private final Map<String, Source> byId;
    private final boolean caseRecallEnabled;
    private final List<RecallCase> recallCases;
    private final Map<String, Provenance> provenance;
    public record Scenario(String id, String label, String model, int year, String question) {}
    public record RecallCase(String id, String label, String model, int year, String question, List<String> candidates) {}
    public record Provenance(String url, String title, String locator, String kind, String applicability, String retrievedAt) {}
    private final boolean fixedEvidence;
    private final Analyzer analyzer;
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final String version;
    private final boolean external;
    final ModelGateway gateway;
    private final VectorStore vectors;
    private final AtomicBoolean rebuilding = new AtomicBoolean();

    public CorpusService(ObjectMapper mapper) throws Exception { this(mapper, new StandardEnvironment()); }
    @Autowired
    public CorpusService(ObjectMapper mapper, Environment env) throws Exception {
        String retrievalMode = env.getProperty("contextflow.retrieval.mode", "fixed-evidence");
        if (!Set.of("fixed-evidence", "hybrid").contains(retrievalMode)) throw new IllegalArgumentException("Invalid retrieval mode");
        fixedEvidence = "fixed-evidence".equals(retrievalMode);
        analyzer = fixedEvidence ? null : new SmartChineseAnalyzer();
        directory = fixedEvidence ? null : new ByteBuffersDirectory();
        gateway = new ModelGateway(env, mapper);
        vectors = fixedEvidence ? null : new VectorStore(gateway);
        String path = env.getProperty("contextflow.corpus-path", "");
        external = !path.isBlank();
        byte[] bytes = load(path, "corpus.json");
        sources = List.copyOf(mapper.readValue(bytes, new TypeReference<List<Source>>() {}));
        String evaluationPath = env.getProperty("contextflow.evaluation-path", "");
        evaluation = external && evaluationPath.isBlank() ? List.of()
                : List.copyOf(mapper.readValue(load(evaluationPath, "evaluation.json"), new TypeReference<List<EvaluationCase>>() {}));
        version = (external ? "imported-" : "tsb-") + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (sources.isEmpty() || sources.size() > 10000 || evaluation.size() > 100) throw new IllegalArgumentException("Corpus/evaluation size out of bounds");
        Map<String, Source> index = new LinkedHashMap<>();
        for (Source source : sources) {
                if (source.docId() == null || !source.docId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || index.put(source.docId(), source) != null
                        || source.model() == null || !source.model().matches("[A-Za-z0-9_-]{1,64}") || source.yearFrom() < 1900 || source.yearTo() > 2100
                        || source.yearFrom() > source.yearTo() || source.page() < 1 || source.text() == null || source.text().isBlank()
                        || source.text().length() > 8000 || source.title() == null || source.title().length() > 256 || source.advice() == null || source.advice().length() > 8000)
                    throw new IllegalArgumentException("Invalid corpus chunk (docId is stable chunkId)");
        }
        if (!fixedEvidence) {
            var config = new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity());
            try (var writer = new IndexWriter(directory, config)) {
                for (Source source : sources) {
                    Document doc = new Document();
                    doc.add(new StringField("docId", source.docId(), Field.Store.YES));
                    doc.add(new StringField("model", source.model(), Field.Store.NO));
                    doc.add(new IntPoint("yearFrom", source.yearFrom()));
                    doc.add(new IntPoint("yearTo", source.yearTo()));
                    doc.add(new TextField("text", source.title() + " " + source.text(), Field.Store.NO));
                    writer.addDocument(doc);
                }
            }
        }
        byId = Map.copyOf(index);
        caseRecallEnabled = env.getProperty("contextflow.case-recall.enabled", Boolean.class, true);
        String casePath = env.getProperty("contextflow.case-recall.path", "");
        recallCases = external && casePath.isBlank() ? List.of() : List.copyOf(mapper.readValue(
                load(casePath, "case-recall.json"), new TypeReference<List<RecallCase>>() {}));
        Set<String> caseIds = new HashSet<>(), questions = new HashSet<>();
        for (RecallCase item : recallCases) {
            if (item.id() == null || item.id().isBlank() || !caseIds.add(item.id()) || item.label() == null || item.label().isBlank()
                    || item.model() == null || !item.model().matches("[A-Za-z0-9_-]{1,64}") || item.year() < 1900 || item.year() > 2100
                    || item.question() == null || item.question().isBlank() || item.question().length() > 2000
                    || !questions.add(item.model() + ":" + item.year() + ":" + item.question().strip())
                    || item.candidates() == null || item.candidates().size() > 30
                    || new HashSet<>(item.candidates()).size() != item.candidates().size()
                    || item.candidates().stream().anyMatch(id -> id == null || !byId.containsKey(id)))
                throw new IllegalArgumentException("Invalid case recall configuration or unknown candidate docId");
        }
        Map<String, Provenance> origins = new LinkedHashMap<>();
        Map<String, Provenance> publicOrigins = mapper.readValue(load("", "provenance.json"), new TypeReference<Map<String, Provenance>>() {});
        for (Source source : sources) {
            Provenance origin = external ? null : publicOrigins.get(source.docId());
            origins.put(source.docId(), origin != null ? origin : new Provenance(null, source.title(), "诊断资料 " + source.docId(),
                    external ? "imported" : "authored-tsb", external ? "导入资料，适用性需由提供方核实" : "自编TSB诊断资料；适用车型与年份见条目，须由专业人员核实", null));
        }
        provenance = Collections.unmodifiableMap(origins);
        reader = fixedEvidence ? null : DirectoryReader.open(directory);
        searcher = fixedEvidence ? null : new IndexSearcher(reader);
        if (searcher != null) searcher.setSimilarity(new BM25Similarity());
    }
    private byte[] load(String path, String fallback) throws IOException {
        try (var input = path.isBlank() ? new ClassPathResource(fallback).getInputStream() : Files.newInputStream(Path.of(path))) {
            byte[] bytes = input.readNBytes(32_000_001);
            if (bytes.length > 32_000_000) throw new IllegalArgumentException("Import exceeds 32MB");
            return bytes;
        }
    }
    public List<Source> all() { return sources; }
    public String version() { return version; }
    public boolean external() { return external; }
    public boolean caseRecallEnabled() { return caseRecallEnabled; }
    public boolean fixedEvidence() { return fixedEvidence; }
    public String retrievalMode() { return fixedEvidence ? "fixed-evidence" : gateway.enabled("vector") ? "hybrid-pgvector-bm25" : caseRecallEnabled ? "hybrid-case-bm25" : "offline-bm25"; }
    public List<Scenario> scenarios() { return recallCases.stream().map(c -> new Scenario(c.id(), c.label(), c.model(), c.year(), c.question())).toList(); }
    public Map<String, Provenance> provenance() { return provenance; }
    public Optional<Source> source(String id) { return Optional.ofNullable(byId.get(id)); }
    public record Ranked(String chunkId, Integer bm25Rank, Integer vectorRank, double rrfScore) {}
    public record Retrieval(String mode, String corpusVersion, int candidateLimit, int rrfK, List<String> bm25, List<String> vector, List<Ranked> top5,
                            String candidateSource, String matchedCase) {
        public Retrieval(String mode, String corpusVersion, int candidateLimit, int rrfK, List<String> bm25, List<String> vector, List<Ranked> top5) {
            this(mode, corpusVersion, candidateLimit, rrfK, bm25, vector, top5, vector.isEmpty() ? "none" : "pgvector", null);
        }
    }
    static List<Ranked> fuse(List<String> bm25, List<String> vector) {
        Map<String, Integer> lexical = new LinkedHashMap<>(), semantic = new LinkedHashMap<>();
        for (int i = 0; i < bm25.size(); i++) lexical.putIfAbsent(bm25.get(i), i + 1);
        for (int i = 0; i < vector.size(); i++) semantic.putIfAbsent(vector.get(i), i + 1);
        Set<String> ids = new HashSet<>(lexical.keySet()); ids.addAll(semantic.keySet());
        return ids.stream().map(id -> new Ranked(id, lexical.get(id), semantic.get(id),
                        (lexical.containsKey(id) ? 1.0 / (60 + lexical.get(id)) : 0) + (semantic.containsKey(id) ? 1.0 / (60 + semantic.get(id)) : 0)))
                .sorted(Comparator.comparingDouble(Ranked::rrfScore).reversed().thenComparing(Ranked::chunkId)).limit(5).toList();
    }
    public Retrieval retrieve(String model, int year, String question) throws Exception {
        if (model == null || model.isBlank() || model.length() > 64 || year < 1900 || year > 2100 || question == null || question.isBlank() || question.length() > 2000)
            throw new DiagnosisService.Failure("INVALID_INPUT", "车型、年份或问题不合法。");
        String normalized = sources.stream().map(Source::model).filter(value -> value.equalsIgnoreCase(model.trim()))
                .findFirst().orElse(model.trim());
        if (fixedEvidence) {
            Optional<RecallCase> match = recallCases.stream().filter(c -> c.model().equals(normalized) && c.year() == year
                    && c.question().strip().equals(question.strip())).findFirst();
            List<Ranked> ordered = match.map(c -> c.candidates().stream().filter(id -> {
                Source source = byId.get(id);
                return source.model().equals(normalized) && source.yearFrom() <= year && source.yearTo() >= year;
            }).limit(5).map(id -> new Ranked(id, null, null, 0)).toList()).orElse(List.of());
            return new Retrieval("fixed-evidence", version, 5, 0, List.of(), List.of(), ordered,
                    "fixed-evidence", match.map(RecallCase::id).orElse(null));
        }
        List<String> bm25 = lexical(normalized, year, question);
        List<String> semantic = List.of();
        String candidateSource = "none", matchedCase = null;
        if (gateway.enabled("vector")) {
            candidateSource = "pgvector";
            gateway.require("vector");
            semantic = vectors.search(version, sources.size(), normalized, year, gateway.embed(question));
            for (String id : semantic) {
                Source s = byId.get(id);
                if (s == null || !s.model().equals(normalized) || s.yearFrom() > year || s.yearTo() < year)
                    throw new DiagnosisService.Failure("INDEX_MISMATCH", "向量chunkId或过滤元数据与当前语料不一致。");
            }
        } else if (caseRecallEnabled) {
            Optional<RecallCase> match = recallCases.stream().filter(c -> c.model().equals(normalized) && c.year() == year
                    && c.question().strip().equals(question.strip())).findFirst();
            if (match.isPresent()) {
                RecallCase item = match.get();
                candidateSource = "case-library";
                matchedCase = item.id();
                semantic = item.candidates().stream().filter(id -> {
                    Source source = byId.get(id);
                    return source.model().equals(normalized) && source.yearFrom() <= year && source.yearTo() >= year;
                }).limit(30).toList();
            }
        }
        return new Retrieval(retrievalMode(), version, 30, 60, bm25, semantic, fuse(bm25, semantic), candidateSource, matchedCase);
    }
    public List<Source> search(String model, int year, String question) throws IOException {
        try { return retrieve(model, year, question).top5().stream().map(r -> byId.get(r.chunkId())).toList(); }
        catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IOException("Retrieval dependency failed", e); }
    }
    private List<String> lexical(String model, int year, String question) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream("text", question)) {
            var term = stream.addAttribute(CharTermAttribute.class); stream.reset();
            while (stream.incrementToken() && terms.size() < 128) terms.add(term.toString());
            stream.end();
        }
        if (terms.isEmpty()) return List.of();
        BooleanQuery.Builder content = new BooleanQuery.Builder();
        terms.forEach(term -> content.add(new TermQuery(new Term("text", term)), BooleanClause.Occur.SHOULD));
        content.setMinimumNumberShouldMatch(1);
        Query query = new BooleanQuery.Builder().add(content.build(), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term("model", model)), BooleanClause.Occur.FILTER)
                .add(IntPoint.newRangeQuery("yearFrom", Integer.MIN_VALUE, year), BooleanClause.Occur.FILTER)
                .add(IntPoint.newRangeQuery("yearTo", year, Integer.MAX_VALUE), BooleanClause.Occur.FILTER).build();
        List<String> result = new ArrayList<>();
        for (ScoreDoc hit : searcher.search(query, 30).scoreDocs) result.add(searcher.storedFields().document(hit.doc).get("docId"));
        return List.copyOf(result);
    }
    public Map<String, Object> rebuild() throws Exception {
        if (!rebuilding.compareAndSet(false, true)) throw new DiagnosisService.Failure("INDEX_BUSY", "已有索引重建任务。");
        try {
            if (fixedEvidence) return Map.of("mode", retrievalMode(), "corpusVersion", version, "chunkCount", sources.size(),
                    "status", "ready", "notice", "固定证据模式无需建立检索索引。");
            if (gateway.enabled("vector")) {
                gateway.require("vector");
                int budget = gateway.integer("contextflow.index.max-embedding-calls", 100, 1, 10000);
                if (sources.size() > budget) throw new DiagnosisService.Failure("CALL_BUDGET", "语料条数超过本次索引embedding调用预算。");
                // Fail before paid model calls if SQL initialization has not been applied.
                try (var connection = vectors.connect(); var check = connection.createStatement()) {
                    vectors.timeout(check); check.executeQuery("SELECT corpus_version FROM cf_index_manifest LIMIT 0").close();
                    check.executeQuery("SELECT chunk_id FROM cf_chunks LIMIT 0").close();
                }
                List<float[]> embeddings = new ArrayList<>();
                for (Source source : sources) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    embeddings.add(gateway.embed(source.title() + "\n" + source.text()));
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                vectors.rebuild(version, sources, embeddings);
            }
            return Map.of("mode", retrievalMode(), "corpusVersion", version, "chunkCount", sources.size(), "status", "ready",
                    "notice", "BM25启动时构建；重建向量按当前启动快照执行，修改外部JSON后需重启加载。");
        } finally { rebuilding.set(false); }
    }
    public Map<String, Object> readiness() {
        Map<String, Object> dependencies = new LinkedHashMap<>();
        boolean ready = true;
        for (String feature : List.of("vector", "llm")) {
            boolean enabled = gateway.enabled(feature) && !(fixedEvidence && feature.equals("vector"));
            List<String> missing = enabled ? gateway.missing(feature) : List.of();
            String probe = enabled ? "not_probed" : "disabled";
            if (enabled && missing.isEmpty()) {
                try {
                    if (feature.equals("vector")) {
                        gateway.embed("readiness");
                        try (var connection = vectors.connect()) { vectors.verify(connection, version, sources.size()); }
                    } else gateway.generate("仅复述证据用于就绪探测", List.of(sources.getFirst()));
                    probe = "ok";
                } catch (Exception e) { probe = e instanceof DiagnosisService.Failure f ? f.code : "DEPENDENCY_UNAVAILABLE"; }
            }
            boolean ok = feature.equals("llm") ? enabled && missing.isEmpty() && probe.equals("ok")
                    : !enabled || (missing.isEmpty() && probe.equals("ok"));
            ready &= ok;
            dependencies.put(feature, Map.of("enabled", enabled, "configured", missing.isEmpty(), "missing", missing, "probe", probe,
                    "apiKeyConfigured", !gateway.property("contextflow." + feature + ".api-key", "").isBlank()));
        }
        return Map.of("ready", ready, "retrieval", retrievalMode(), "generation", gateway.enabled("llm") ? "llm-json" : "unavailable",
                "dimensions", gateway.dimensions, "corpusVersion", version, "externalCorpus", external, "dependencies", dependencies,
                "notice", "探测启用的每项模型各调用一次，可能计费；不返回URL、用户名、密码、密钥；结构引用校验不等于语义证明。");
    }
    public Citation readSourceChunk(String tool, String docId, Integer page, Set<String> allowed) {
        if (!"readSourceChunk".equals(tool)) throw new IllegalArgumentException("TOOL_NOT_ALLOWED");
        if (docId == null || page == null || page < 1 || !allowed.contains(docId)) throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
        Source source = byId.get(docId);
        if (source == null || source.page() != page) throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
        return source.citation();
    }
    public Evaluation evaluate(String mode) throws IOException {
        List<CaseResult> cases = new ArrayList<>();
        for (EvaluationCase item : evaluation) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Evaluation cancelled");
            List<String> actual = search(item.model(), item.year(), item.question()).stream().map(Source::docId).toList();
            long count = item.gold().stream().distinct().filter(actual::contains).count();
            double recall = item.gold().isEmpty() ? 0 : (double) count / new HashSet<>(item.gold()).size();
            cases.add(new CaseResult(item.id(), item.question(), item.gold(), actual, count > 0, recall));
        }
        return new Evaluation(cases.isEmpty() ? 0 : cases.stream().filter(CaseResult::hit).count() / (double) cases.size(),
                cases.stream().mapToDouble(CaseResult::recall).average().orElse(0), cases.size(), List.copyOf(cases),
                (external ? "导入资料检索验证" : "TSB证据匹配验证集") + "；不评估LLM答案语义质量", mode);
    }
    @Override @PreDestroy public void close() throws IOException {
        if (reader != null) reader.close();
        if (directory != null) directory.close();
        if (analyzer != null) analyzer.close();
    }
}

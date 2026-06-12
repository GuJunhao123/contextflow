package com.contextflow;

import java.util.List;

public final class Contracts {
    private Contracts() {}

    public record DiagnosisRequest(String model, Integer year, String question, String scenario) {}
    public record Citation(String docId, String title, int page, String text) {}
    public record Trace(String node, String status, long durationMs, String detail) {}
    public record Result(String requestId, String status, String message, String answer,
                         List<Citation> citations, List<Trace> trace, String mode) {}
    public record Source(String docId, String title, String model, int yearFrom, int yearTo,
                         int page, String text, String advice) {
        Citation citation() { return new Citation(docId, title, page, text); }
    }
    public record EvaluationCase(String id, String model, int year, String question, List<String> gold) {}
    public record CaseResult(String id, String question, List<String> gold, List<String> actual,
                             boolean hit, double recall) {}
    public record Evaluation(double hitAt5, double recallAt5, int total, List<CaseResult> cases,
                             String dataset, String mode) {}
}

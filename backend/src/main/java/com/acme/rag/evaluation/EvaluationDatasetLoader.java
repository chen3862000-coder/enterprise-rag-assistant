package com.acme.rag.evaluation;

import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Component
public final class EvaluationDatasetLoader {
    public static final String DATASET_VERSION = "2026-10-m3-v1";
    private final ObjectMapper mapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "ObjectMapper is a shared Spring service dependency; this loader exposes no mapper reference")
    public EvaluationDatasetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EvaluationDataset load() {
        try (InputStream stream = new ClassPathResource("evals/dataset.jsonl").getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<EvaluationScenario> scenarios = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    scenarios.add(mapper.readValue(line, EvaluationScenario.class));
                }
            }
            validate(scenarios);
            return new EvaluationDataset(DATASET_VERSION, scenarios);
        } catch (IOException | JacksonException exception) {
            throw new IllegalStateException("Unable to load evaluation dataset", exception);
        }
    }

    private static void validate(List<EvaluationScenario> scenarios) {
        Set<String> ids = new HashSet<>();
        for (EvaluationScenario scenario : scenarios) {
            validateScenario(scenario, ids);
        }
        if (scenarios.size() != 52) {
            throw new IllegalStateException("Expected 52 evaluation scenarios, found " + scenarios.size());
        }
        Map<EvaluationCategory, Long> counts = scenarios.stream()
                .collect(Collectors.groupingBy(EvaluationScenario::category, Collectors.counting()));
        Map<EvaluationCategory, Long> expected = Map.of(
                EvaluationCategory.FACT_LOOKUP, 12L,
                EvaluationCategory.MULTI_DOCUMENT_QA, 8L,
                EvaluationCategory.KEYWORD_EXACT_MATCH, 8L,
                EvaluationCategory.PERMISSION_ISOLATION, 10L,
                EvaluationCategory.UNANSWERABLE, 8L,
                EvaluationCategory.DOCUMENT_FRESHNESS, 6L);
        if (!counts.equals(expected)) {
            throw new IllegalStateException("Evaluation scenario category counts do not match the dataset specification");
        }
    }

    private static void validateScenario(EvaluationScenario scenario, Set<String> ids) {
        if (scenario.id() == null || !ids.add(scenario.id()) || scenario.query() == null
                || scenario.query().isBlank() || scenario.user() == null) {
            throw new IllegalStateException("Evaluation dataset has missing or duplicate scenario fields");
        }
    }
}

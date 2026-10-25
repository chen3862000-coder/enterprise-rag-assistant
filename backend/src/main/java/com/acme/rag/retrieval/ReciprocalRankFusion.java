package com.acme.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ReciprocalRankFusion {
    private final int constant;
    private final int resultDepth;

    public ReciprocalRankFusion(
            @Value("${app.retrieval.rrf-constant:60}") int constant,
            @Value("${app.retrieval.fused-depth:8}") int resultDepth) {
        if (constant < 1 || resultDepth < 1) {
            throw new IllegalArgumentException("RRF settings must be positive");
        }
        this.constant = constant;
        this.resultDepth = resultDepth;
    }

    public List<RankedChunk> fuse(List<List<RetrievalCandidate>> rankings) {
        Map<String, Accumulator> fused = new HashMap<>();
        for (List<RetrievalCandidate> ranking : rankings) {
            for (int index = 0; index < ranking.size(); index++) {
                RetrievalCandidate candidate = ranking.get(index);
                Accumulator accumulator = fused.computeIfAbsent(candidate.chunkId(), ignored -> new Accumulator(candidate));
                accumulator.score += 1.0 / (constant + index + 1);
                accumulator.vectorSimilarity = Math.max(accumulator.vectorSimilarity,
                        candidate.vectorMatch() ? candidate.score() : 0.0);
                accumulator.keywordScore = Math.max(accumulator.keywordScore,
                        candidate.keywordMatch() ? candidate.score() : 0.0);
                accumulator.exactMatch |= candidate.exactMatch();
            }
        }
        List<RankedChunk> results = new ArrayList<>();
        fused.values().stream()
                .sorted(Comparator.comparingDouble((Accumulator value) -> value.score).reversed()
                        .thenComparing(value -> value.candidate.chunkId()))
                .limit(resultDepth)
                .forEach(value -> results.add(toRanked(value)));
        return List.copyOf(results);
    }

    private static RankedChunk toRanked(Accumulator value) {
        RetrievalCandidate candidate = value.candidate;
        return new RankedChunk(candidate.chunkId(), candidate.versionId(), candidate.documentId(), candidate.title(),
                candidate.version(), candidate.section(), candidate.text(), value.score,
                value.vectorSimilarity, value.keywordScore, value.exactMatch);
    }

    private static final class Accumulator {
        private final RetrievalCandidate candidate;
        private double score;
        private double vectorSimilarity;
        private double keywordScore;
        private boolean exactMatch;

        private Accumulator(RetrievalCandidate candidate) {
            this.candidate = candidate;
        }
    }
}

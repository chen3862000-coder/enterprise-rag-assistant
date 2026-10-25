package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvaluationDatasetLoaderTest {
    @Test
    void loadsVersionedFiftySixScenarioMixAndUniqueIds() {
        EvaluationDataset dataset = new EvaluationDatasetLoader(new ObjectMapper()).load();

        assertThat(dataset.version()).isEqualTo("2026-10-p1-v3");
        assertThat(dataset.scenarios()).hasSize(56).extracting(EvaluationScenario::id).doesNotHaveDuplicates();
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.FACT_LOOKUP).hasSize(12);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.MULTI_DOCUMENT_QA).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.KEYWORD_EXACT_MATCH).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.PERMISSION_ISOLATION).hasSize(10);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.UNANSWERABLE).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.DOCUMENT_FRESHNESS).hasSize(6);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.HARD_NEGATIVE).hasSize(4);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.PERMISSION_ISOLATION
                        && scenario.expectedDocumentIds().isEmpty())
                .allSatisfy(scenario -> assertThat(scenario.evidenceGateExpected()).isFalse());
        assertThat(dataset.scenarios()).filteredOn(EvaluationScenario::abstentionExpected).hasSize(21);
        assertThat(dataset.scenarios()).extracting(EvaluationScenario::id)
                .contains("rag-053", "rag-054", "rag-055", "rag-056");
    }
}

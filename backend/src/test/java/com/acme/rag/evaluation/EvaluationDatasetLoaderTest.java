package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvaluationDatasetLoaderTest {
    @Test
    void loadsVersionedSixtyThreeScenarioMixAndUniqueIds() {
        EvaluationDataset dataset = new EvaluationDatasetLoader(new ObjectMapper()).load();

        assertThat(dataset.version()).isEqualTo("2026-10-p1-v5");
        assertThat(dataset.scenarios()).hasSize(63).extracting(EvaluationScenario::id).doesNotHaveDuplicates();
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.FACT_LOOKUP).hasSize(12);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.MULTI_DOCUMENT_QA).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.KEYWORD_EXACT_MATCH).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.PERMISSION_ISOLATION).hasSize(12);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.UNANSWERABLE).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.DOCUMENT_FRESHNESS).hasSize(6);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.HARD_NEGATIVE).hasSize(9);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.PERMISSION_ISOLATION
                        && scenario.expectedDocumentIds().isEmpty())
                .allSatisfy(scenario -> assertThat(scenario.evidenceGateExpected()).isFalse());
        assertThat(dataset.scenarios()).filteredOn(EvaluationScenario::evidenceGateExpected)
                .extracting(EvaluationScenario::id)
                .contains("rag-004", "rag-005", "rag-007", "rag-008", "rag-023", "rag-028");
        assertThat(dataset.scenarios()).filteredOn(EvaluationScenario::abstentionExpected).hasSize(28);
        assertThat(dataset.scenarios()).extracting(EvaluationScenario::id)
                .contains("rag-053", "rag-054", "rag-055", "rag-056", "rag-057", "rag-058", "rag-059", "rag-060", "rag-061", "rag-062", "rag-063");
        assertThat(dataset.scenarios()).allSatisfy(scenario ->
                assertThat(scenario.shouldPassEvidenceGate()).isNotNull());
    }

    @Test
    void loadsSeparateVersionedHeldoutSetWithFortyDisjointIds() {
        EvaluationDatasetLoader loader = new EvaluationDatasetLoader(new ObjectMapper());
        EvaluationDataset development = loader.load();
        EvaluationDataset heldout = loader.loadHeldout();

        assertThat(heldout.version()).isEqualTo("heldout-paraphrases-v3");
        assertThat(heldout.scenarios()).hasSize(40).extracting(EvaluationScenario::id).doesNotHaveDuplicates();
        assertThat(heldout.scenarios()).extracting(EvaluationScenario::id)
                .doesNotContainAnyElementsOf(development.scenarios().stream().map(EvaluationScenario::id).toList());
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.FACT_LOOKUP).hasSize(19);
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.MULTI_DOCUMENT_QA).hasSize(2);
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.KEYWORD_EXACT_MATCH).hasSize(3);
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.PERMISSION_ISOLATION).hasSize(2);
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.UNANSWERABLE).hasSize(2);
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.DOCUMENT_FRESHNESS).hasSize(2);
        assertThat(heldout.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.HARD_NEGATIVE).hasSize(10);
    }
}

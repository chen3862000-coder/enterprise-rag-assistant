package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvaluationDatasetLoaderTest {
    @Test
    void loadsExactVersionedFiftyTwoScenarioMix() {
        EvaluationDataset dataset = new EvaluationDatasetLoader(new ObjectMapper()).load();

        assertThat(dataset.version()).isEqualTo(EvaluationDatasetLoader.DATASET_VERSION);
        assertThat(dataset.scenarios()).hasSize(52).extracting(EvaluationScenario::id).doesNotHaveDuplicates();
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.FACT_LOOKUP).hasSize(12);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.MULTI_DOCUMENT_QA).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.KEYWORD_EXACT_MATCH).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.PERMISSION_ISOLATION).hasSize(10);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.UNANSWERABLE).hasSize(8);
        assertThat(dataset.scenarios()).filteredOn(scenario -> scenario.category() == EvaluationCategory.DOCUMENT_FRESHNESS).hasSize(6);
    }
}

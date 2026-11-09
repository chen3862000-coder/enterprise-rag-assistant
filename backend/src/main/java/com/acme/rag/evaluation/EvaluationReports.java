package com.acme.rag.evaluation;

/** Independent development and held-out reports; metrics are never pooled across datasets. */
public record EvaluationReports(EvaluationRunResult development, EvaluationRunResult heldout) { }

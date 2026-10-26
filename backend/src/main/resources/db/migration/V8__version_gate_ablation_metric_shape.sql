UPDATE evaluation_runs
SET gate_ablation_accuracy = (
    SELECT jsonb_object_agg(
        policy,
        CASE jsonb_typeof(metric)
            WHEN 'number' THEN jsonb_build_object(
                'truePass', NULL,
                'falseAbstention', NULL,
                'falseAdmission', NULL,
                'trueAbstention', NULL,
                'accuracy', metric,
                'falseAbstentionRate', NULL,
                'falseAdmissionRate', NULL)
            ELSE metric
        END)
    FROM jsonb_each(evaluation_runs.gate_ablation_accuracy) AS entry(policy, metric)
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_each(evaluation_runs.gate_ablation_accuracy) AS entry(policy, metric)
    WHERE jsonb_typeof(metric) = 'number'
);

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { EvaluationWorkspace } from './EvaluationWorkspace'

vi.mock('../api', () => ({
  api: {
    evaluations: vi.fn(),
    runEvaluation: vi.fn(),
    evaluation: vi.fn(),
  },
}))

import { api } from '../api'

describe('EvaluationWorkspace', () => {
  beforeEach(() => vi.clearAllMocks())

  it('labels gate and citation checks without implying answer quality', async () => {
    vi.mocked(api.evaluations).mockResolvedValue([])
    render(<EvaluationWorkspace />)
    expect(await screen.findByText(/No measured runs yet/)).toBeInTheDocument()
    expect(screen.getByText(/not a generated-answer benchmark/i)).toBeInTheDocument()
    expect(screen.getByText(/shared runtime sufficiency policy/i)).toBeInTheDocument()
  })

  it('shows only scores returned from persisted evaluation runs', async () => {
    vi.mocked(api.evaluations).mockResolvedValue([{
      runId: 'run-1', requestedBy: 'carol', datasetVersion: 'dataset-v1', method: 'OFFLINE_RETRIEVAL',
      embeddingModelId: 'test-model', scenarioCount: 56,
      retrievalConfiguration: { candidateDepth: 10 },
      metrics: { recallAt5: 0.75, meanReciprocalRank: 0.6, aclLeakageCount: 0, freshnessAccuracy: 1, citationIdValidationAccuracy: 1, evidenceGateAccuracy: 0.8, gateAblationMetrics: { CURRENT_AND: { truePass: 4, falseAbstention: 1, falseAdmission: 1, trueAbstention: 4, accuracy: 0.8, falseAbstentionRate: 0.2, falseAdmissionRate: 0.2 }, VECTOR_ONLY: { truePass: 5, falseAbstention: 0, falseAdmission: 2, trueAbstention: 3, accuracy: 0.8, falseAbstentionRate: 0, falseAdmissionRate: 0.4 } }, prefixShadowMetrics: { available: true, recallAt5: 0.8, meanReciprocalRank: 0.7, vectorGateAccuracy: 0.9, falseAbstentionCount: 2, falseAdmissionCount: 1 } },
      status: 'SUCCEEDED', startedAt: new Date().toISOString(), completedAt: new Date().toISOString(),
    }])
    render(<EvaluationWorkspace />)
    await waitFor(() => expect(screen.getAllByText('75.0%')).toHaveLength(2))
    expect(screen.getByText(/VECTOR_ONLY: false abstention 0.0%, false admission 40.0%/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('dataset-v1'))
    expect(api.evaluation).toHaveBeenCalledWith('run-1')
  })
})

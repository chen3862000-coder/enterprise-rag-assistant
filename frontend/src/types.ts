export type Identity = {
  username: string
  roles: string[]
  documentAdmin: boolean
}

export type Citation = {
  citationId: string
  documentId: string
  documentTitle: string
  version: number
  chunkId: string
  section?: string
}

export type ChatResponse = {
  answer: string
  citations: Citation[]
  traceId: string
  groundingStatus: 'CITATIONS_PRESENT' | 'RETRIEVAL_ONLY' | 'INSUFFICIENT_EVIDENCE'
}

export type SourcePreview = {
  chunkId: string
  documentId: string
  documentTitle: string
  version: number
  section?: string
  text: string
}

export type DocumentSummary = {
  id: string
  title: string
  department: string
  classification: string
  status: 'ACTIVE' | 'DISABLED' | 'DELETED'
  currentVersion: number | null
  indexStatus: string | null
  updatedAt: string | null
}

export type DocumentVersion = {
  id: string
  version: number
  contentHash: string
  sourceFilename: string
  effectiveAt: string | null
  indexStatus: string
  embeddingModelId: string
  embeddingDimensions: number
  createdAt: string
  active: boolean
}

export type IngestionResult = {
  documentId: string
  versionId: string
  jobId: string | null
  version: number
  chunkCount: number
  duplicate: boolean
  metadataUpdated: boolean
}

export type EvaluationMetrics = {
  recallAt5: number
  meanReciprocalRank: number
  aclLeakageCount: number
  freshnessAccuracy: number
  citationIdValidationAccuracy: number
  evidenceGateAccuracy: number
  evidenceGateFalseAbstentionRate: number | null
  evidenceGateFalseAdmissionRate: number | null
  releaseQualityPassed: boolean | null
  gateAblationMetrics: Record<string, {
    truePass: number | null
    falseAbstention: number | null
    falseAdmission: number | null
    trueAbstention: number | null
    accuracy: number
    falseAbstentionRate: number | null
    falseAdmissionRate: number | null
  }>
  prefixShadowMetrics: {
    available: boolean
    recallAt5: number
    meanReciprocalRank: number
    vectorGateAccuracy: number
    falseAbstentionCount: number
    falseAdmissionCount: number
  }
}

export type EvaluationSummary = {
  runId: string
  requestedBy: string
  datasetVersion: string
  method: string
  embeddingModelId: string
  retrievalConfiguration: Record<string, unknown>
  scenarioCount: number
  metrics: EvaluationMetrics
  status: string
  startedAt: string
  completedAt: string
}

export type EvaluationRun = EvaluationSummary & {
  scenarios: Array<{
    scenarioId: string
    category: string
    expectedDocumentIds: string[]
    retrievedDocumentIds: string[]
    missingDocumentIds: string[]
    forbiddenDocumentIds: string[]
    expectedVersion: number | null
    retrievedVersions: Record<string, number>
    reciprocalRank: number
    recallAt5: number
    aclLeakage: boolean
    freshnessCorrect: boolean
    citationIdValidationCorrect: boolean
    evidenceGateCorrect: boolean
    gateReason: string
    expectedRelevantCandidate: boolean
    expectedEvidenceGate: boolean
    gateDiagnostics: {
      candidateCount: number
      bestVectorSimilarity: number
      bestKeywordScore: number
      bestExpectedVectorSimilarity: number
      bestExpectedKeywordScore: number
      minimumLexicalCoverage: number
      bestLexicalCoverage: number
      bestExpectedLexicalCoverage: number
      expectedExactMatch: boolean
      sensitiveRequest: boolean
      intentPolicyRejection: boolean
      roleScopeRejected: boolean
      vectorPassingCandidateCount: number
      keywordPassingCandidateCount: number
      jointPassingCandidateCount: number
      ablationDecisions: Record<string, boolean>
      prefixShadow: {
        retrievedDocumentIds: string[]
        recallAt5: number
        reciprocalRank: number
        vectorGatePassed: boolean
        bestExpectedCosine: number
      }
    }
    passed: boolean
  }>
}

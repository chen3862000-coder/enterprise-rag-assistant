ALTER TABLE document_chunks
    ADD COLUMN technical_identifiers text[] NOT NULL DEFAULT ARRAY[]::text[],
    ADD COLUMN lexical_terms text[] NOT NULL DEFAULT ARRAY[]::text[];

CREATE INDEX document_chunks_technical_identifiers_idx
    ON document_chunks USING gin(technical_identifiers);
CREATE INDEX document_chunks_lexical_terms_idx
    ON document_chunks USING gin(lexical_terms);

UPDATE document_chunks c
SET technical_identifiers = identifiers.values
FROM (
    SELECT chunk.id,
           coalesce(array_agg(DISTINCT lower(coalesce(m[1], m[2], m[3], m[4], m[5])))
               FILTER (WHERE coalesce(m[1], m[2], m[3], m[4], m[5]) IS NOT NULL), ARRAY[]::text[]) AS values
    FROM document_chunks chunk
    LEFT JOIN LATERAL regexp_matches(
        chunk.chunk_text,
        '(?:\m(?:EQErr|def|function)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(|\m([A-Z][A-Z0-9_]{5,})\M|\m([A-Z]{2,}[A-Z0-9]*-[0-9]{2,})\M|\m(?:HTTP|status|error|code)\s*[-:]?\s*([0-9]{3})\M|\m(X-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+)\M)',
        'g'
    ) AS m ON true
    GROUP BY chunk.id
) identifiers
WHERE c.id = identifiers.id;

UPDATE document_chunks c
SET lexical_terms = terms.values
FROM (
    SELECT chunk.id,
           coalesce(array_agg(DISTINCT term), ARRAY[]::text[]) AS values
    FROM document_chunks chunk
    LEFT JOIN LATERAL (
        SELECT token AS term
        FROM regexp_split_to_table(lower(coalesce(chunk.heading_path,'') || ' ' || chunk.chunk_text), '[^a-z0-9]+') token
        WHERE length(token)>2
        UNION
        SELECT substring(regexp_match[1],position,2) AS term
        FROM regexp_matches(
            lower(coalesce(chunk.heading_path,'') || ' ' || chunk.chunk_text),
            '([ぁ-ヿ㐀-䶿一-鿿가-힣]+)', 'g'
        ) AS match_rows(regexp_match)
        CROSS JOIN LATERAL generate_series(1,greatest(length(match_rows.regexp_match[1])-1,1)) position
    ) terms ON true
    GROUP BY chunk.id
) terms
WHERE c.id=terms.id;

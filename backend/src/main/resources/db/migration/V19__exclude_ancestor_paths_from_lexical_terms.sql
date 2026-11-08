UPDATE document_chunks c
SET lexical_terms = terms.values
FROM (
    SELECT chunk.id,
           coalesce(array_agg(DISTINCT term), ARRAY[]::text[]) AS values
    FROM document_chunks chunk
    LEFT JOIN LATERAL (
        SELECT token AS term
        FROM regexp_split_to_table(
            lower(regexp_replace(regexp_replace(
                split_part(regexp_replace(coalesce(chunk.heading_path, ''), '^.*/', ''), ' > ', 1),
                '\.(files|html?|htc|js|css)(/.*)?$', '', 'i'),
                '^(tabstrip|index|contents)$', '', 'i') || ' ' || chunk.chunk_text),
            '[^a-z0-9]+'
        ) token
        WHERE length(token) > 2
        UNION
        SELECT substring(captured.run, position, 2) AS term
        FROM regexp_matches(
            lower(regexp_replace(regexp_replace(
                split_part(regexp_replace(coalesce(chunk.heading_path, ''), '^.*/', ''), ' > ', 1),
                '\.(files|html?|htc|js|css)(/.*)?$', '', 'i'),
                '^(tabstrip|index|contents)$', '', 'i') || ' ' || chunk.chunk_text),
            '([ぁ-ヿ㐀-䶿一-鿿가-힣]+)',
            'g'
        ) AS runs(captures)
        CROSS JOIN LATERAL (SELECT captures[1] AS run) AS captured
        CROSS JOIN LATERAL generate_series(1, greatest(length(captured.run) - 1, 1)) AS position
    ) AS terms ON true
    GROUP BY chunk.id
) AS terms
WHERE c.id = terms.id;

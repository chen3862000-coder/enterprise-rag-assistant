CREATE FUNCTION populate_document_chunk_search_terms()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    searchable_text text;
BEGIN
    searchable_text := lower(
        regexp_replace(
            regexp_replace(
                split_part(regexp_replace(coalesce(NEW.heading_path, ''), '^.*/', ''), ' > ', 1),
                '\.(files|html?|htc|js|css)(/.*)?$', '', 'i'),
            '^(tabstrip|index|contents)$', '', 'i') || ' ' || NEW.chunk_text
    );

    SELECT coalesce(array_agg(DISTINCT lower(coalesce(match[1], match[2], match[3], match[4], match[5]))), ARRAY[]::text[])
    INTO NEW.technical_identifiers
    FROM regexp_matches(NEW.chunk_text,
        '(?:\m(?:EQErr|def|function)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(|\m([A-Z][A-Z0-9_]{5,})\M|\m([A-Z]{2,}[A-Z0-9]*-[0-9]{2,})\M|\m(?:HTTP|status|error|code)\s*[-:]?\s*([0-9]{3})\M|\m(X-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+)\M)',
        'g') AS captures(match);

    SELECT coalesce(array_agg(DISTINCT term ORDER BY term), ARRAY[]::text[])
    INTO NEW.lexical_terms
    FROM (
        SELECT token AS term
        FROM regexp_split_to_table(searchable_text, '[^a-z0-9]+') token
        WHERE length(token) > 2
        UNION
        SELECT substring(captured.run, position, 2) AS term
        FROM regexp_matches(searchable_text, '([ぁ-ヿ㐀-䶿一-鿿가-힣]+)', 'g') AS matches(captures)
        CROSS JOIN LATERAL (SELECT captures[1] AS run) AS captured
        CROSS JOIN LATERAL generate_series(1, greatest(length(captured.run) - 1, 1)) AS position
    ) AS terms;

    RETURN NEW;
END;
$$;

CREATE TRIGGER document_chunks_populate_search_terms
BEFORE INSERT OR UPDATE OF chunk_text, heading_path ON document_chunks
FOR EACH ROW
EXECUTE FUNCTION populate_document_chunk_search_terms();

UPDATE document_chunks
SET chunk_text = chunk_text;

package com.acme.rag.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class RetrievalIntentPolicy {
    private static final Pattern TECHNICAL_ID = Pattern.compile("(?U)(?=[\\p{Alnum}_./:-]*[0-9])[\\p{Alnum}_./:-]{2,}");
    private static final Pattern TECHNICAL_COMPARISON = Pattern.compile("(?i)\\b(?:compare|versus|vs|difference|between)\\b");
    private static final Pattern NUMBERED_HTTP_STATUS = Pattern.compile("(?i)\\b(?:http|status|error|code)\\s*[-:]?\\s*[0-9]{3}\\b");
    private static final Pattern FUTURE_SPECULATION = Pattern.compile(
            "(?i)\\b(?:will|would|plan to|plans to|expect to)\\b.{0,80}\\b(?:20[3-9][0-9]|next year|future)\\b");
    private static final Pattern EXECUTIVE_IDENTITY = Pattern.compile(
            "(?i)\\b(?:name of (?:the )?(?:acme'?s? )?(?:ceo|chief executive)|who is (?:the )?(?:acme'?s? )?(?:ceo|chief executive))\\b");
    private static final Pattern CONTRACTUAL_PENALTY = Pattern.compile(
            "(?i)\\b(?:contractual|contract)\\b.{0,80}\\b(?:penalty|penalties|damages)\\b|\\b(?:penalty|penalties)\\b.{0,80}\\b(?:uptime|availability|sla)\\b");
    private static final Pattern SENSITIVE_VALUE_REQUEST = Pattern.compile(
            "(?i)\\b(?:what|which|show|reveal|provide|tell me|give me|state|list|identify)\\b.{0,120}\\b(?:password|credential|secret|private key|token|salary|acquisition price|home address)\\b|"
                    + "\\b(?:password|credential|secret|private key|token|salary|acquisition price|home address)\\b.{0,80}\\b(?:value|content|it is|was|exposed|used)\\b");
    private static final Pattern POLICY_GUIDANCE = Pattern.compile(
            "(?i)^\\s*(?:what|which)\\s+(?:is|are)\\s+(?:the\\s+)?(?:policy|policies|standard|standards|guidance|requirements?)\\b"
                    + ".{0,100}\\b(?:storing|handling|rotation|rotating|protecting|managing)\\b"
                    + ".{0,40}\\b(?:secret|credential|password|token)s?\\b");
    private static final Pattern CUSTOMER_IDENTIFIER = Pattern.compile("(?i)\\bcustomer[-_][0-9]{2,}\\b");
    private static final Pattern CONTRACTOR_BENEFIT = Pattern.compile(
            "(?i)\\bcontractors?\\b.{0,70}\\b(?:leave|benefits?|receive|eligible|allowance|days?)\\b|"
                    + "\\b(?:leave|benefits?|allowance)\\b.{0,70}\\bcontractors?\\b");
    private static final Pattern HISTORICAL_POLICY = Pattern.compile(
            "(?i)\\b(?:previous|prior|old|former|before the change|used to)\\b.{0,80}\\b(?:policy|limit|allowance|procedure|standard)\\b");
    private static final Pattern HR_SCOPE = Pattern.compile(
            "(?i)\\b(?:annual leave|leave allowance|leave days|remote work|employee benefits|benefits onboarding|employee onboarding)\\b");
    private static final Pattern ENGINEERING_SCOPE = Pattern.compile(
            "(?i)\\b(?:gateway|workspace service|deployment|rollback|roll back|release readiness|response header|request identifier|enterprise api (?:request )?limit)\\b|\\bGW-[0-9]{3}\\b");
    private static final Pattern SECURITY_SCOPE = Pattern.compile(
            "(?i)\\b(?:security incident|incident response|security escalation|access permissions|access review|security controls?)\\b");

    String rejection(String query, List<RankedChunk> exactMatches, Set<String> roles) {
        String unsupported = unsupportedRequest(query);
        if (unsupported != null) {
            return unsupported;
        }
        String specialized = specializedRejection(query, exactMatches);
        if (specialized != null) {
            return specialized;
        }
        if (!TECHNICAL_ID.matcher(query).find()) {
            return missingScope(query, roles);
        }
        if (exactMatches.isEmpty() && hasExactlyOneTechnicalIdentifier(query) && queryTerms(query).size() <= 4) {
            return "UNMATCHED_TECHNICAL_IDENTIFIER";
        }
        return missingScope(query, roles);
    }

    List<RankedChunk> exactIdentifierMatches(String query, List<RankedChunk> chunks) {
        if (containsSensitiveTerm(query)) {
            return List.of();
        }
        Set<String> identifiers = new HashSet<>();
        var matcher = TECHNICAL_ID.matcher(query);
        while (matcher.find()) {
            String identifier = matcher.group().replaceFirst("\\.+$", "");
            if (identifier.isEmpty()) {
                continue;
            }
            boolean hasLetter = identifier.codePoints().anyMatch(Character::isLetter);
            if (hasLetter || isNamedNumericCode(query, identifier)) {
                identifiers.add(identifier.toLowerCase(Locale.ROOT));
            }
        }
        if (identifiers.size() != 1) {
            return List.of();
        }
        return chunks.stream().filter(RankedChunk::exactMatch)
                .filter(chunk -> identifiers.stream().anyMatch(identifier -> containsExactIdentifier(chunk.text(), identifier)))
                .toList();
    }

    static boolean containsSensitiveTerm(String query) {
        return SENSITIVE_VALUE_REQUEST.matcher(query).find() && !POLICY_GUIDANCE.matcher(query).find();
    }

    private static String unsupportedRequest(String query) {
        if (containsSensitiveTerm(query)) {
            return "SENSITIVE_REQUEST";
        }
        if (FUTURE_SPECULATION.matcher(query).find()) {
            return "UNSUPPORTED_FUTURE_SPECULATION";
        }
        if (EXECUTIVE_IDENTITY.matcher(query).find()) {
            return "UNSUPPORTED_EXECUTIVE_IDENTITY";
        }
        if (CONTRACTUAL_PENALTY.matcher(query).find()) {
            return "UNSUPPORTED_CONTRACTUAL_PENALTY";
        }
        return null;
    }

    private static String specializedRejection(String query, List<RankedChunk> exactMatches) {
        if (CUSTOMER_IDENTIFIER.matcher(query).find() && exactMatches.isEmpty()) {
            return "UNMATCHED_CUSTOMER_IDENTIFIER";
        }
        if (CONTRACTOR_BENEFIT.matcher(query).find()) {
            return "CONTRACTOR_POLICY_UNAVAILABLE";
        }
        if (HISTORICAL_POLICY.matcher(query).find()) {
            return "HISTORICAL_VERSION_UNAVAILABLE";
        }
        return isMultiIdentifierComparison(query) ? "MULTI_IDENTIFIER_COMPARISON" : null;
    }

    private static String missingScope(String query, Set<String> roles) {
        String requiredScope = identifierScope(query, roles);
        if (requiredScope != null) {
            return requiredScope;
        }
        String departmentalScope = departmentalScope(query, roles);
        return departmentalScope != null ? departmentalScope : securityScope(query, roles);
    }

    private static String identifierScope(String query, Set<String> roles) {
        if (TECHNICAL_ID.matcher(query).find() && query.toUpperCase(Locale.ROOT).contains("IR-")
                && !roles.contains("SECURITY_ADMIN")) {
            return "REQUIRED_SECURITY_SCOPE_UNAVAILABLE";
        }
        return null;
    }

    private static String departmentalScope(String query, Set<String> roles) {
        if (HR_SCOPE.matcher(query).find() && !roles.contains("HR")) {
            return "REQUIRED_HR_SCOPE_UNAVAILABLE";
        }
        if (ENGINEERING_SCOPE.matcher(query).find() && !roles.contains("ENGINEERING")) {
            return "REQUIRED_ENGINEERING_SCOPE_UNAVAILABLE";
        }
        return null;
    }

    private static String securityScope(String query, Set<String> roles) {
        return SECURITY_SCOPE.matcher(query).find() && !roles.contains("SECURITY_ADMIN")
                ? "REQUIRED_SECURITY_SCOPE_UNAVAILABLE" : null;
    }

    private static boolean isMultiIdentifierComparison(String query) {
        return TECHNICAL_COMPARISON.matcher(query).find() && technicalIdentifierCount(query) > 1;
    }

    private static boolean hasExactlyOneTechnicalIdentifier(String query) {
        var matcher = TECHNICAL_ID.matcher(query);
        return matcher.find() && !matcher.find();
    }

    private static int technicalIdentifierCount(String query) {
        int count = 0;
        var matcher = TECHNICAL_ID.matcher(query);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static Set<String> queryTerms(String query) {
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(term -> term.length() > 2).collect(java.util.stream.Collectors.toSet());
    }

    private static boolean containsExactIdentifier(String text, String identifier) {
        Pattern token = Pattern.compile("(?<![\\p{Alnum}_./:-])" + Pattern.quote(identifier)
                + "(?:\\.+(?=$|[^\\p{Alnum}_/:.-]))?(?![\\p{Alnum}_./:-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
        return token.matcher(text).find();
    }

    private static boolean isNamedNumericCode(String query, String identifier) {
        return !identifier.codePoints().anyMatch(Character::isLetter)
                && NUMBERED_HTTP_STATUS.matcher(query).find();
    }
}

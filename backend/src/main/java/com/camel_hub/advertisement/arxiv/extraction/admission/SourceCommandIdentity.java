package com.camel_hub.advertisement.arxiv.extraction.admission;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Identity codec v1. Payload validation precedes default materialization and hashing. */
public final class SourceCommandIdentity {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final String ARXIV = "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})";
    private SourceCommandIdentity() { }

    public static String fingerprint(String payload) { return sha256(canonicalEncode(validatedPayload(payload))); }

    public static ObjectNode validatedPayload(String raw) {
        JsonNode parsed = parse(raw);
        fields(parsed, Set.of("parserVersion", "targets"));
        require(parsed.get("parserVersion").isTextual() && parsed.get("parserVersion").textValue().matches("[A-Za-z0-9._-]{1,50}"));
        JsonNode targets = parsed.get("targets");
        require(targets.isArray() && targets.size() >= 1 && targets.size() <= 100);
        Set<String> papers = new HashSet<>(), arxivIds = new HashSet<>();
        ObjectNode result = parsed.deepCopy();
        for (JsonNode target : result.get("targets")) {
            require(target.isObject());
            var names = new HashSet<String>(); target.fieldNames().forEachRemaining(names::add);
            require(names.equals(Set.of("paperId", "arxivId")) || names.equals(Set.of("paperId", "arxivId", "metadataAuthors")));
            require(target.get("paperId").isTextual());
            String paper = target.get("paperId").textValue();
            require(paper.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
            paper = UUID.fromString(paper).toString();
            ((ObjectNode) target).put("paperId", paper);
            require(target.get("arxivId").isTextual());
            String arxiv = target.get("arxivId").textValue();
            require(arxiv.length() <= 48 && arxiv.matches(ARXIV) && papers.add(paper) && arxivIds.add(arxiv));
            if (!target.has("metadataAuthors")) ((ObjectNode) target).putArray("metadataAuthors");
            JsonNode authors = target.get("metadataAuthors");
            require(authors.isArray() && authors.size() <= 500);
            for (JsonNode author : authors) require(author.isTextual() && safeText(author.textValue(), 300));
        }
        return result;
    }

    static boolean safeText(String text, int max) {
        utf8(text);
        return !text.isEmpty() && text.length() <= max
                && text.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                && text.codePoints().noneMatch(c -> Character.getType(c) == Character.CONTROL);
    }

    static JsonNode parse(String raw) {
        try { utf8(raw); return JSON.readTree(raw); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid source JSON"); }
    }

    static void fields(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject());
        var actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }

    static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid source command");
    }

    static byte[] utf8(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)));
            else require(!Character.isLowSurrogate(c));
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] canonicalEncode(JsonNode value) {
        var out = new ByteArrayOutputStream(); encode(value, out); return out.toByteArray();
    }

    private static void encode(JsonNode value, ByteArrayOutputStream out) {
        if (value.isNull()) out.write('n');
        else if (value.isBoolean()) { out.write('b'); out.write(value.booleanValue() ? 1 : 0); }
        else if (value.isTextual() || value.isIntegralNumber()) {
            out.write(value.isTextual() ? 's' : 'i');
            byte[] bytes = utf8(value.isTextual() ? value.textValue() : value.bigIntegerValue().toString());
            length(out, bytes.length); out.writeBytes(bytes);
        } else if (value.isArray()) {
            out.write('a'); length(out, value.size()); value.forEach(child -> encode(child, out));
        } else if (value.isObject()) {
            out.write('o'); length(out, value.size());
            var names = new ArrayList<String>(); value.fieldNames().forEachRemaining(names::add);
            names.sort((a, b) -> Arrays.compareUnsigned(utf8(a), utf8(b)));
            for (String name : names) { encode(JSON.getNodeFactory().textNode(name), out); encode(value.get(name), out); }
        } else throw new IllegalArgumentException("Unsupported identity value");
    }

    private static void length(ByteArrayOutputStream out, int value) { out.writeBytes(ByteBuffer.allocate(4).putInt(value).array()); }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

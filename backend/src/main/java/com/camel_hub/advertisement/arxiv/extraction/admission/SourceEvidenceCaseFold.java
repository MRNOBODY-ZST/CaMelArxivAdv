package com.camel_hub.advertisement.arxiv.extraction.admission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Full default Unicode 15.0 folding, matching the pinned Python 3.12 worker.
 * Comparison copies only: no normalization or mutation of stored result text.
 * Data: https://www.unicode.org/Public/15.0.0/ucd/CaseFolding.txt
 * Original Unicode notice is retained beside the data in /unicode/LICENSE.txt.
 */
final class SourceEvidenceCaseFold {
    private static final String RESOURCE = "/unicode/CaseFolding-15.0.0.txt";
    private static final String SHA256 = "cdd49e55eae3bbf1f0a3f6580c974a0263cb86a6a08daa10fbf705b4808a56f7";
    private static final Map<Integer, String> MAPPINGS = load();

    private SourceEvidenceCaseFold() { }

    static String fold(String value) {
        var result = new StringBuilder(value.length());
        value.codePoints().forEach(codepoint -> {
            String mapped = MAPPINGS.get(codepoint);
            if (mapped == null) result.appendCodePoint(codepoint);
            else result.append(mapped);
        });
        return result.toString();
    }

    private static Map<Integer, String> load() {
        try (var input = SourceEvidenceCaseFold.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("CASEFOLD_RESOURCE_MISSING");
            byte[] bytes = input.readAllBytes();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!SHA256.equals(actual)) throw new IllegalStateException("CASEFOLD_RESOURCE_INVALID");
            var mappings = new HashMap<Integer, String>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                String data = line.split("#", 2)[0].trim();
                if (data.isEmpty()) continue;
                String[] fields = data.split(";");
                String status = fields[1].trim();
                if (!status.equals("C") && !status.equals("F")) continue; // Default full fold excludes S/T.
                var mapped = new StringBuilder();
                for (String codepoint : fields[2].trim().split(" +")) mapped.appendCodePoint(Integer.parseInt(codepoint, 16));
                if (mappings.put(Integer.parseInt(fields[0].trim(), 16), mapped.toString()) != null)
                    throw new IllegalStateException("CASEFOLD_RESOURCE_DUPLICATE");
            }
            return Map.copyOf(mappings);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure); // No lowercase or empty-map fallback.
        }
    }
}

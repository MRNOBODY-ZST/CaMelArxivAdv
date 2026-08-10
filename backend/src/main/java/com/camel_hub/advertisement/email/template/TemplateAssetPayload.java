package com.camel_hub.advertisement.email.template;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public record TemplateAssetPayload(String contentType, byte[] bytes, String extension) {

	public static TemplateAssetPayload validate(String rawContentType, byte[] bytes, int maxBytes) {
		if (bytes == null || bytes.length == 0) {
			throw new TemplateValidationException("Template image is empty");
		}
		if (bytes.length > maxBytes) {
			throw new TemplateValidationException("Template image exceeds the configured size limit");
		}
		String contentType = rawContentType == null ? "" : rawContentType.split(";", 2)[0]
				.strip().toLowerCase(Locale.ROOT);
		return switch (contentType) {
			case "image/png" -> require(contentType, bytes, "png",
					startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}));
			case "image/jpeg" -> require(contentType, bytes, "jpg",
					bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
							&& (bytes[2] & 0xff) == 0xff);
			case "image/gif" -> require(contentType, bytes, "gif",
					startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
							|| startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII)));
			case "image/webp" -> require(contentType, bytes, "webp",
					bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 12).equals("WEBP"));
			default -> throw new TemplateValidationException("Template image type is not supported");
		};
	}

	private static TemplateAssetPayload require(String contentType, byte[] bytes, String extension, boolean valid) {
		if (!valid) throw new TemplateValidationException("Template image signature does not match its media type");
		return new TemplateAssetPayload(contentType, bytes, extension);
	}

	private static boolean startsWith(byte[] source, byte[] expected) {
		return source.length >= expected.length
				&& Arrays.equals(Arrays.copyOfRange(source, 0, expected.length), expected);
	}

	private static String ascii(byte[] source, int start, int end) {
		return new String(source, start, end - start, StandardCharsets.US_ASCII);
	}
}

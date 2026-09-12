package com.camel_hub.advertisement.email.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Email layout only: no CSS URLs, functions, positioning, hidden content or external resources. */
final class EmailInlineStyles {
	private static final String LENGTH = "(?:0|[0-9]{1,4}(?:\\.[0-9]{1,3})?(?:px|em|rem|%))";
	private static final String COLOR = "(?:#[0-9a-f]{3}|#[0-9a-f]{6}|transparent|black|white|inherit)";
	private static final Pattern SPACING = Pattern.compile(LENGTH + "(?: +" + LENGTH + "){0,3}");
	private static final Pattern MARGIN = Pattern.compile("(?:" + LENGTH + "|auto)(?: +(?:" + LENGTH + "|auto)){0,3}");
	private static final Pattern BORDER = Pattern.compile("(?:0|none|" + LENGTH + " (?:solid|dashed|dotted) " + COLOR + ")");
	private static final Set<String> SIDES = Set.of("top", "right", "bottom", "left");

	private EmailInlineStyles() { }

	static String sanitize(String raw) {
		if (raw.length() > 8_192) return "";
		List<String> accepted = new ArrayList<>();
		for (String declaration : raw.split(";")) {
			int colon = declaration.indexOf(':');
			if (colon < 1) continue;
			String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
			String original = declaration.substring(colon + 1).strip();
			String value = original.toLowerCase(Locale.ROOT);
			// Escape sequences, comments and functions can disguise executable or network CSS.
			if (value.isEmpty() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl)
					|| value.matches(".*[\\\\()@{}<>!/:].*")) continue;
			if (valid(property, value)) accepted.add(property + ":" + original);
		}
		return String.join(";", accepted);
	}

	private static boolean valid(String property, String value) {
		return switch (property) {
			case "color", "background-color" -> value.matches(COLOR);
			case "font-family" -> value.matches("[a-z][a-z0-9 ,'\\\"-]{0,150}")
					|| value.matches("['\\\"][a-z][a-z0-9 ,'\\\"-]{0,150}");
			case "font-size", "letter-spacing", "width", "max-width", "height" -> value.matches(LENGTH);
			case "font-weight" -> value.matches("normal|bold|[1-9]00");
			case "font-style" -> value.matches("normal|italic");
			case "line-height" -> value.matches("normal|[0-9](?:\\.[0-9]{1,3})?") || value.matches(LENGTH);
			case "text-align" -> value.matches("left|center|right");
			case "text-decoration" -> value.matches("none|underline|line-through");
			case "vertical-align" -> value.matches("top|middle|bottom|baseline");
			case "padding", "border-radius" -> SPACING.matcher(value).matches();
			case "margin" -> MARGIN.matcher(value).matches();
			case "border" -> BORDER.matcher(value).matches();
			case "border-collapse" -> value.matches("collapse|separate");
			case "border-spacing" -> value.matches(LENGTH + "(?: +" + LENGTH + ")?");
			case "word-break" -> value.matches("normal|break-word|break-all");
			case "overflow-wrap" -> value.matches("normal|break-word|anywhere");
			default -> side(property, "padding-") && value.matches(LENGTH)
					|| side(property, "margin-") && value.matches(LENGTH + "|auto")
					|| side(property, "border-") && BORDER.matcher(value).matches();
		};
	}

	private static boolean side(String property, String prefix) {
		return property.startsWith(prefix) && SIDES.contains(property.substring(prefix.length()));
	}
}

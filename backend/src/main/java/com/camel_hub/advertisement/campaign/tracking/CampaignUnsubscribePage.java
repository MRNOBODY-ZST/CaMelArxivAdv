package com.camel_hub.advertisement.campaign.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Public, deliberately non-personalized pages: no capability or email address enters the markup. */
final class CampaignUnsubscribePage {

	// Centered CTA and footer structure adapted from the bundled Tailwind template library.
	// The public callback remains standalone HTML, without JavaScript or remote style dependencies.
	private static final String STYLE = """
			:root {
			  color-scheme: light dark;
			  --canvas: #f5f4ed;
			  --surface: #fffef9;
			  --ink: #193c32;
			  --muted: #58665f;
			  --line: #dedfd4;
			  --soft: #eaf0e6;
			  --accent: #956043;
			  --button: #234e3e;
			  --button-hover: #153c2d;
			  --button-ink: #fffef9;
			  --focus: #956043;
			}
			* { box-sizing: border-box; }
			html { min-height: 100%; }
			body {
			  margin: 0;
			  min-width: 0;
			  min-height: 100vh;
			  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
			  color: var(--ink);
			  background: var(--canvas);
			  line-height: 1.6;
			  -webkit-font-smoothing: antialiased;
			}
			a { color: inherit; text-underline-offset: 4px; }
			a:hover { text-decoration: underline; }
			a:focus-visible, button:focus-visible {
			  outline: 3px solid var(--focus);
			  outline-offset: 5px;
			}
			.site-header {
			  display: flex;
			  align-items: center;
			  justify-content: space-between;
			  gap: 16px;
			  max-width: 1120px;
			  margin: 0 auto;
			  padding: 28px 32px;
			}
			.brand {
			  display: inline-flex;
			  align-items: center;
			  gap: 11px;
			  min-height: 44px;
			  font-size: 21px;
			  font-weight: 700;
			  letter-spacing: -.6px;
			  text-decoration: none;
			}
			.brand-mark { width: 30px; height: 30px; flex: none; }
			.header-caption, .eyebrow {
			  font-size: 11px;
			  font-weight: 650;
			  letter-spacing: 1.7px;
			  text-transform: uppercase;
			}
			.header-caption { color: var(--muted); }
			main { padding: 36px 24px 16px; }
			.preference-panel {
			  max-width: 680px;
			  margin: 0 auto;
			  padding: 52px 56px 36px;
			  border: 1px solid var(--line);
			  border-radius: 28px;
			  background: var(--surface);
			  text-align: center;
			  box-shadow: 0 16px 50px -36px #193c3240;
			}
			.status-icon {
			  display: grid;
			  place-items: center;
			  width: 72px;
			  height: 72px;
			  margin: 0 auto 28px;
			  border: 1px solid var(--line);
			  border-radius: 22px;
			  background: var(--soft);
			}
			.status-icon svg { width: 30px; height: 30px; }
			.eyebrow { margin: 0 0 16px; color: var(--accent); }
			h1 {
			  margin: 0;
			  font-family: Georgia, "Times New Roman", serif;
			  font-size: clamp(32px, 5vw, 43px);
			  font-weight: 400;
			  letter-spacing: -1.3px;
			  line-height: 1.14;
			  text-wrap: balance;
			}
			.description {
			  max-width: 480px;
			  margin: 22px auto 0;
			  color: var(--muted);
			  font-size: 17px;
			  line-height: 1.75;
			  text-wrap: pretty;
			}
			.actions { margin-top: 32px; }
			form { margin: 0; }
			.primary-action {
			  display: inline-flex;
			  align-items: center;
			  justify-content: center;
			  min-height: 52px;
			  max-width: 100%;
			  padding: 13px 28px;
			  border: 1px solid transparent;
			  border-radius: 10px;
			  background: var(--button);
			  color: var(--button-ink);
			  font: inherit;
			  font-size: 15px;
			  font-weight: 650;
			  line-height: 1.5;
			  cursor: pointer;
			  text-decoration: none;
			}
			.primary-action:hover { background: var(--button-hover); text-decoration: none; }
			.secondary-action {
			  display: inline-flex;
			  align-items: center;
			  justify-content: center;
			  min-height: 44px;
			  margin-top: 10px;
			  padding: 8px 4px;
			  font-size: 14px;
			  font-weight: 550;
			  text-decoration: none;
			}
			.preference-note {
			  margin: 28px 0 0;
			  padding-top: 24px;
			  border-top: 1px solid var(--line);
			  color: var(--muted);
			  font-size: 13px;
			  line-height: 1.7;
			  text-wrap: pretty;
			}
			.preference-note strong { display: block; margin-bottom: 3px; color: var(--ink); font-weight: 600; }
			footer { padding: 18px 24px 40px; color: var(--muted); text-align: center; font-size: 12px; }
			footer p { margin: 0; }
			footer a { display: inline-flex; align-items: center; min-height: 44px; text-decoration: none; }
			@media (max-width: 560px) {
			  .site-header { padding: 18px 20px; }
			  .header-caption { display: none; }
			  main { padding: 20px 16px 12px; }
			  .preference-panel { padding: 36px 24px 28px; border-radius: 22px; }
			  .description { font-size: 16px; }
			  .primary-action { width: 100%; padding-inline: 16px; }
			  .status-icon { margin-bottom: 24px; }
			  footer { padding-bottom: 28px; }
			}
			@media (prefers-color-scheme: dark) {
			  :root {
			    --canvas: #111d18;
			    --surface: #192820;
			    --ink: #f0f3e7;
			    --muted: #b2c0b4;
			    --line: #35493b;
			    --soft: #263d2e;
			    --accent: #d6ad89;
			    --button: #d9e8cb;
			    --button-hover: #edf6e4;
			    --button-ink: #183323;
			    --focus: #d6ad89;
			  }
			  .preference-panel { box-shadow: none; }
			}
			@media (forced-colors: active) {
			  .primary-action, .preference-panel, .status-icon { border-color: ButtonText; }
			}
			""";

	private static final String STYLE_HASH = styleHash();

	enum State { CONFIRMATION, COMPLETE, RETRY }

	private CampaignUnsubscribePage() { }

	static String contentSecurityPolicy() {
		return "default-src 'none'; style-src 'sha256-" + STYLE_HASH
				+ "'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";
	}

	static byte[] render(State state) {
		String heading = switch (state) {
			case CONFIRMATION -> "Your inbox. Your choice.";
			case COMPLETE -> "Request received.";
			case RETRY -> "Let\u2019s try that again.";
		};
		String description = switch (state) {
			case CONFIRMATION -> "Prefer not to hear from us? Confirm below to stop future research "
					+ "outreach from Camel Hub. No sign-in is needed.";
			case COMPLETE -> "Thank you. Your request has been received. You can close this page now.";
			case RETRY -> "We couldn\u2019t complete your request right now. Your unsubscribe preference "
					+ "has not been confirmed. Please try again.";
		};
		String note = switch (state) {
			case CONFIRMATION -> "<strong>Nothing changes until you confirm.</strong>"
					+ "Opening this page does not unsubscribe you.";
			case COMPLETE -> "<strong>Your privacy comes first.</strong>"
					+ "We do not display or confirm email addresses on this page.";
			case RETRY -> "<strong>No sign-in required.</strong>"
					+ "If the problem continues, please return to this link later.";
		};
		String icon = switch (state) {
			case CONFIRMATION -> "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"3\"/>"
					+ "<path d=\"m4 7 8 6 8-6\"/>";
			case COMPLETE -> "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"m8 12 3 3 5-6\"/>";
			case RETRY -> "<path d=\"M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1\"/>";
		};
		String actions = state == State.COMPLETE
				? "<a class=\"primary-action\" href=\"https://api.camel-hub.com/\" rel=\"noreferrer\">Visit Camel Hub</a>"
				: "<form method=\"post\" action=\"\"><button class=\"primary-action\" type=\"submit\">"
						+ (state == State.CONFIRMATION ? "Unsubscribe from research emails" : "Try again")
						+ "</button></form><a class=\"secondary-action\" href=\"https://api.camel-hub.com/\" rel=\"noreferrer\">"
						+ (state == State.CONFIRMATION ? "Cancel and visit Camel Hub" : "Visit Camel Hub") + "</a>";
		String html = """
				<!doctype html>
				<html lang="en">
				<head>
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<meta name="robots" content="noindex, nofollow">
				<meta name="referrer" content="no-referrer">
				<title>Mail preferences | Camel Hub</title>
				""" + "<style>" + STYLE + "</style>" + """
				</head>
				<body>
				<header class="site-header">
				  <a class="brand" href="https://api.camel-hub.com/" rel="noreferrer" aria-label="Camel Hub home">
				    <svg class="brand-mark" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true" focusable="false">
				      <path d="m9 9 14 7L9 23"/><circle cx="8" cy="8" r="3.5"/><circle cx="24" cy="16" r="3.5"/><circle cx="8" cy="24" r="3.5"/>
				    </svg>
				    <span>Camel Hub</span>
				  </a>
				  <span class="header-caption">Research partnerships</span>
				</header>
				<main id="main-content">
				  <section class="preference-panel" aria-labelledby="preference-heading">
				    <div class="status-icon" aria-hidden="true">
				      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" focusable="false">
				""" + icon + """
				      </svg>
				    </div>
				    <p class="eyebrow">Mail preferences</p>
				""" + "<h1 id=\"preference-heading\">" + heading + "</h1>"
				+ "<p class=\"description\">" + description + "</p>"
				+ "<div class=\"actions\">" + actions + "</div>"
				+ "<p class=\"preference-note\">" + note + "</p>" + """
				  </section>
				</main>
				<footer>
				  <p>Camel Hub &middot; Research partnerships</p>
				  <a href="https://api.camel-hub.com/" rel="noreferrer">api.camel-hub.com</a>
				</footer>
				</body>
				</html>
				""";
		return html.getBytes(StandardCharsets.UTF_8);
	}

	private static String styleHash() {
		try {
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
					.digest(STYLE.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new ExceptionInInitializerError(impossible);
		}
	}
}

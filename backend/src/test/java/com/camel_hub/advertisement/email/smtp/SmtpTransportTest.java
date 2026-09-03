package com.camel_hub.advertisement.email.smtp;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.AttemptStatus;
import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryModels.TransportStage;
import com.camel_hub.advertisement.campaign.safety.CampaignSafetySigner;
import com.camel_hub.advertisement.campaign.tracking.CampaignTrackingSigner;
import com.camel_hub.advertisement.email.tracking.MailTrackingSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpTransportTest {

	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";
	private static final String MESSAGE_ID = "<recipient-1@delivery.camel-arxiv.invalid>";

	@Test
	void reportsAcceptedDataWithStableMessageAndCorrelationHeaders() throws Exception {
		withPeer(PeerBehavior.reply("250 2.0.0 queued"), exchange -> {
			SmtpTransport.SmtpOutcome outcome = exchange.transport().sendDetailed(
					exchange.account(), message());

			assertThat(outcome.status()).isEqualTo(AttemptStatus.SMTP_ACCEPTED);
			assertThat(outcome.stage()).isEqualTo(TransportStage.POST_DATA);
			assertThat(outcome.responseCode()).isEqualTo(250);
			assertThat(outcome.responseSummary()).isEqualTo("250 2.0.0 queued");
			assertThat(exchange.capturedData()).contains(
					"Message-ID: " + MESSAGE_ID,
					"X-CaMel-Correlation-Id: delivery-recipient-1",
					"List-Unsubscribe: <https://tracking.example.test/u/opaque>",
					"List-Unsubscribe-Post: List-Unsubscribe=One-Click",
					"Content-Type: multipart/alternative; ");
		});

		withPeer(PeerBehavior.reply("250 queued"), exchange ->
				exchange.transport().send(exchange.account(), legacyMessage()));
	}

	@Test
	void exposesOnlyExplicitFourHundredResponseAsRetryable() throws Exception {
		withPeer(PeerBehavior.dataReply("450 4.2.0\tmailbox\u0007 busy"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.status()).isEqualTo(AttemptStatus.TEMPORARY_FAILURE);
							assertThat(error.stage()).isEqualTo(TransportStage.DATA);
							assertThat(error.responseCode()).isEqualTo(450);
							assertThat(error.responseSummary()).doesNotContain("\t", "\u0007")
									.hasSizeLessThanOrEqualTo(500);
							assertThat(error.retryable()).isTrue();
						}));
	}

	@Test
	void treatsExplicitFiveHundredResponseAsPermanent() throws Exception {
		withPeer(PeerBehavior.dataReply("550 5.1.1 rejected"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
							assertThat(error.stage()).isEqualTo(TransportStage.DATA);
							assertThat(error.responseCode()).isEqualTo(550);
							assertThat(error.retryable()).isFalse();
						}));
	}

	@Test
	void followsNestedMessagingExceptionChainForExplicitRcptFailure() throws Exception {
		withPeer(PeerBehavior.rcptReply("450 4.1.1 recipient temporarily unavailable"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.status()).isEqualTo(AttemptStatus.TEMPORARY_FAILURE);
							assertThat(error.stage()).isEqualTo(TransportStage.RCPT_TO);
							assertThat(error.responseCode()).isEqualTo(450);
							assertThat(error.retryable()).isTrue();
						}));
	}

	@Test
	void distinguishesMailFromAndDataCommandRejectionsBeforeMessageDataBegins() throws Exception {
		withPeer(PeerBehavior.mailFromReply("550 5.7.1 sender rejected"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
							assertThat(error.stage()).isEqualTo(TransportStage.MAIL_FROM);
							assertThat(error.responseCode()).isEqualTo(550);
						}));

		withPeer(PeerBehavior.dataCommandReply("451 4.3.0 DATA unavailable"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.status()).isEqualTo(AttemptStatus.TEMPORARY_FAILURE);
							assertThat(error.stage()).isEqualTo(TransportStage.DATA);
							assertThat(error.responseCode()).isEqualTo(451);
						}));
	}

	@Test
	void rejectsUnsafeOrInjectedExtensionHeadersBeforeConnecting() {
		assertThatThrownBy(() -> new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid", "<p>body</p>", "body",
				"delivery-recipient-1", "approved@example.invalid", MESSAGE_ID,
				Map.of("Bcc", "private@example.invalid")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid", "<p>body</p>", "body",
				"delivery-recipient-1", "approved@example.invalid", MESSAGE_ID,
				Map.of("List-Unsubscribe", "<https://tracking.example.test/u/opaque>\r\nBcc: injected@example.invalid")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"From", "To", "Subject", "Message-ID", "X-CaMel-Correlation-Id", "X-Unknown"})
	void rejectsEveryProtectedOrUnknownHeader(String name) {
		assertThatThrownBy(() -> new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid", "<p>body</p>", "body",
				"delivery-recipient-1", "approved@example.invalid", MESSAGE_ID,
				Map.of(name, "safe-looking-value")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void canonicalizesAllowedHeaderNamesAndRejectsCaseVariantDuplicates() {
		SmtpTransport.OutboundMessage canonicalized = new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid", "<p>body</p>", "body",
				"delivery-recipient-1", "approved@example.invalid", MESSAGE_ID,
				Map.of("list-unsubscribe", "<https://tracking.example.test/u/opaque>"));
		assertThat(canonicalized.headers()).containsOnlyKeys("List-Unsubscribe");

		java.util.Map<String, String> duplicate = new java.util.LinkedHashMap<>();
		duplicate.put("List-Unsubscribe", "<https://tracking.example.test/u/one>");
		duplicate.put("list-unsubscribe", "<https://tracking.example.test/u/two>");
		assertThatThrownBy(() -> new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid", "<p>body</p>", "body",
				"delivery-recipient-1", "approved@example.invalid", MESSAGE_ID, duplicate))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void classifiesAuthenticationAndTlsNegotiationFailuresAsPermanent() throws Exception {
		withPeer(PeerBehavior.authReply("535 5.7.8 credentials rejected"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(
						authenticatedAccount(exchange.account().port()), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.category())
									.as("stage=%s code=%s summary=%s", error.stage(), error.responseCode(), error.responseSummary())
									.isEqualTo(
									SmtpTransportException.FailureCategory.AUTHENTICATION_FAILED);
							assertThat(error.stage()).isEqualTo(TransportStage.AUTH);
							assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
							assertThat(error.retryable()).isFalse();
						}));

		withPeer(PeerBehavior.reply("250 queued"), exchange -> {
			SmtpTransport liveTransport = transport(true);
			SmtpRepository.SmtpAccountRecord startTls = account(
					exchange.account().port(), SmtpModels.TlsMode.STARTTLS_REQUIRED, null, null, null);
			assertThatThrownBy(() -> liveTransport.sendDetailed(startTls, message()))
					.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
						assertThat(error.category()).isEqualTo(SmtpTransportException.FailureCategory.TLS_FAILURE);
						assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
						assertThat(error.retryable()).isFalse();
					});
		});
	}

	@Test
	void distinguishesEhloFailureAndBodyDisconnectAfterDataStart() throws Exception {
		withPeer(PeerBehavior.ehloReply("550 5.5.1 EHLO denied"), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.stage()).isEqualTo(TransportStage.EHLO);
							assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
						}));

		withPeer(PeerBehavior.disconnectMidBody(), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.stage()).isEqualTo(TransportStage.POST_DATA);
							assertThat(error.status()).isEqualTo(AttemptStatus.OUTCOME_UNKNOWN);
							assertThat(error.retryable()).isFalse();
						}));
	}

	@Test
	void redactsAddressesSecretsControlsAndTruncatesSafeSummary() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		UUID recipient = UUID.fromString("41000000-0000-0000-0000-00000000000a");
		Instant expiry = Instant.ofEpochSecond(1_900_000_000L);
		String openCapability = signer.issueOpen(recipient, expiry);
		String clickCapability = signer.issueClick(recipient,
				UUID.fromString("51000000-0000-0000-0000-00000000000b"), expiry);
		String unsubscribeCapability = signer.issueUnsubscribe(recipient, expiry);
		String raw = "550 victim.person@research.test token=opaque-secret "
				+ "用户@例子.公司 victim@[127.0.0.1] \"quoted user\"@research.test "
				+ "https://tracking.example.test/u/signedOpaqueValue Bearer bearer-secret-value "
				+ "Authorization: Bearer auth-secret-value Authorization: Basic basic-secret-value "
				+ openCapability + " " + clickCapability + " " + unsubscribeCapability + " "
				+ "x".repeat(800) + "\r\n";
		SmtpTransportException error = new SmtpTransportException(
				SmtpTransportException.FailureCategory.SMTP_REJECTED,
				AttemptStatus.PERMANENT_FAILURE, TransportStage.RCPT_TO, 550, raw, false);

		assertThat(error.responseSummary()).hasSize(500)
				.doesNotContain(
						"victim.person@research.test", "opaque-secret", "signedOpaqueValue",
						"用户@例子.公司", "victim@[127.0.0.1]", "quoted user", "bearer-secret-value",
						"auth-secret-value", "basic-secret-value", openCapability, clickCapability,
						unsubscribeCapability, "campaign-open:v1", "campaign-click:v1",
						"campaign-unsubscribe:v1", "\r", "\n")
				.contains("[redacted-address]", "token=[redacted]", "[redacted-url]", "Bearer [redacted]");
	}

	@Test
	void redactsEachExactCampaignCapabilityWhenImmediatelyFollowedByCommonPunctuation() {
		CampaignTrackingSigner signer = new CampaignTrackingSigner(KEY);
		CampaignSafetySigner safetySigner = new CampaignSafetySigner(KEY);
		MailTrackingSigner testMailSigner = new MailTrackingSigner(KEY);
		UUID recipient = UUID.fromString("41000000-0000-0000-0000-00000000000a");
		Instant expiry = Instant.ofEpochSecond(1_900_000_000L);
		List<String> capabilities = List.of(
				signer.issueOpen(recipient, expiry),
				signer.issueClick(recipient,
						UUID.fromString("51000000-0000-0000-0000-00000000000b"), expiry),
				signer.issueUnsubscribe(recipient, expiry),
				safetySigner.issueOpen(recipient, expiry),
				safetySigner.issueClick(recipient,
						UUID.fromString("51000000-0000-0000-0000-00000000000b"), expiry),
				safetySigner.issueUnsubscribe(recipient, expiry),
				testMailSigner.issue(recipient, expiry),
				testMailSigner.issueClick(recipient,
						UUID.fromString("51000000-0000-0000-0000-00000000000b"), expiry));

		for (String capability : capabilities) {
			for (String punctuation : List.of(".", ":", "-", ",", ";", "!", "?", ")", "]", "}")) {
				String sanitized = SmtpTransportException.sanitize("450 rejected " + capability + punctuation);
				assertThat(sanitized).as(capability.substring(0, capability.indexOf('.')) + punctuation)
						.doesNotContain(capability, "campaign-open:v1", "campaign-click:v1",
								"campaign-unsubscribe:v1", "campaign-safety-open:v1",
								"campaign-safety-click:v1", "campaign-safety-unsubscribe:v1")
						.contains("[redacted-capability]" + punctuation);
			}
		}
	}

	@Test
	void redactsPercentEncodedSafetyCapabilitiesFromProviderResponses() {
		UUID message = UUID.fromString("41000000-0000-0000-0000-00000000000a");
		UUID link = UUID.fromString("51000000-0000-0000-0000-00000000000b");
		Instant expiry = Instant.ofEpochSecond(1_900_000_000L);
		CampaignTrackingSigner production = new CampaignTrackingSigner(KEY);
		CampaignSafetySigner safety = new CampaignSafetySigner(KEY);
		MailTrackingSigner testMail = new MailTrackingSigner(KEY);
		for (String capability : List.of(
				production.issueOpen(message, expiry), production.issueClick(message, link, expiry),
				production.issueUnsubscribe(message, expiry), safety.issueOpen(message, expiry),
				safety.issueClick(message, link, expiry), safety.issueUnsubscribe(message, expiry),
				testMail.issue(message, expiry), testMail.issueClick(message, link, expiry))) {
			String encoded = capability.replace("-", "%2D").replace(":", "%3A").replace(".", "%2E");
			encoded = encoded.replace("%", "%25");

			String sanitized = SmtpTransportException.sanitize("450 rejected " + encoded);

			assertThat(sanitized).as(capability.substring(0, capability.indexOf('.')))
					.doesNotContain(encoded, capability, "campaign", "%25", "v1c%", "v1%")
					.contains("[redacted-capability]");

			String compatibilityEncoded = compatibilityPercentEncoded(capability);
			assertThat(SmtpTransportException.sanitize("450 rejected " + compatibilityEncoded))
					.as("compatibility-percent " + capability.substring(0, capability.indexOf('.')))
					.doesNotContain(compatibilityEncoded, capability)
					.contains("[redacted-capability]");
		}
		String wrapped = "X" + testMail.issue(message, expiry);
		assertThat(SmtpTransportException.sanitize("450 rejected " + wrapped))
				.doesNotContain(wrapped).contains("X[redacted-capability]");
	}

	@Test
	void treatsDisconnectAfterDataAsTerminalUnknownOutcome() throws Exception {
		withPeer(PeerBehavior.disconnectAfterData(), exchange ->
				assertThatThrownBy(() -> exchange.transport().sendDetailed(exchange.account(), message()))
						.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
							assertThat(error.status()).isEqualTo(AttemptStatus.OUTCOME_UNKNOWN);
							assertThat(error.stage()).isEqualTo(TransportStage.POST_DATA);
							assertThat(error.responseCode()).isNull();
							assertThat(error.retryable()).isFalse();
						}));
	}

	@Test
	void keepsPreDataConnectionFailureDefiniteAndNonRetryable() throws Exception {
		int unavailablePort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			unavailablePort = socket.getLocalPort();
		}
		SmtpTransport transport = transport();
		SmtpRepository.SmtpAccountRecord account = account(unavailablePort);

		assertThatThrownBy(() -> transport.sendDetailed(account, message()))
				.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
					assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
					assertThat(error.stage()).isEqualTo(TransportStage.CONNECT);
					assertThat(error.retryable()).isFalse();
				});
	}

	@Test
	void classifiesUnreadableSmtpCredentialAsPermanentConfigurationFailureBeforeConnect() {
		SmtpRepository.SmtpAccountRecord corrupted = account(
				2525, SmtpModels.TlsMode.PLAIN_LOCAL_ONLY, "fixture-user",
				new byte[16], new byte[12]);

		assertThatThrownBy(() -> transport().sendDetailed(corrupted, message()))
				.isInstanceOfSatisfying(SmtpTransportException.class, error -> {
					assertThat(error.category())
							.isEqualTo(SmtpTransportException.FailureCategory.CONFIGURATION_FAILURE);
					assertThat(error.status()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
					assertThat(error.stage()).isEqualTo(TransportStage.CONNECT);
					assertThat(error.retryable()).isFalse();
				});
	}

	private void withPeer(PeerBehavior behavior, Consumer<Exchange> assertion) throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var capturedData = new java.util.concurrent.CopyOnWriteArrayList<String>();
			var peer = executor.submit(() -> {
				try (var socket = listener.accept();
					 var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
					 var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
					socket.setSoTimeout(5_000);
					reply(writer, "220 localhost test SMTP");
					String command;
					while ((command = reader.readLine()) != null) {
					if ((command.startsWith("EHLO ") || command.startsWith("HELO "))
							&& behavior.ehloReply() != null) {
						reply(writer, behavior.ehloReply());
					}
					else if (command.startsWith("AUTH ") && behavior.authReply() != null) {
						reply(writer, behavior.authReply());
					}
					else if (command.startsWith("MAIL FROM:") && behavior.mailFromReply() != null) {
						reply(writer, behavior.mailFromReply());
					}
					else if (command.startsWith("RCPT TO:") && behavior.rcptReply() != null) {
						reply(writer, behavior.rcptReply());
					}
					else if (command.equals("DATA")) {
						if (behavior.dataCommandReply() != null) {
							reply(writer, behavior.dataCommandReply());
							continue;
						}
						reply(writer, "354 End with a dot");
						while ((command = reader.readLine()) != null && !command.equals(".")) {
							capturedData.add(command);
							if (behavior.disconnectDuringBody()) return;
						}
						if (behavior.disconnect()) return;
						reply(writer, behavior.dataReply());
					}
					else if (command.equals("QUIT")) {
						reply(writer, "221 bye");
						return;
					}
					else reply(writer, "250 localhost");
				}
			}
				catch (Exception error) {
					throw new AssertionError(error);
				}
			});

			assertion.accept(new Exchange(transport(), account(listener.getLocalPort()), capturedData));
			peer.get(6, TimeUnit.SECONDS);
		}
	}

	private SmtpTransport transport() {
		return transport(false);
	}

	private String compatibilityPercentEncoded(String value) {
		StringBuilder encoded = new StringBuilder(value.length() * 3);
		for (byte octet : value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
			int unsigned = Byte.toUnsignedInt(octet);
			encoded.append('％').append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)))
					.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
		}
		return encoded.toString();
	}

	private SmtpTransport transport(boolean liveAllowed) {
		SmtpProperties properties = new SmtpProperties(liveAllowed, Set.of("localhost"), Duration.ofMillis(500),
				Duration.ofMillis(500), Duration.ofMillis(500), KEY);
		return new SmtpTransport(new SmtpSecretCrypto(KEY), new SmtpPolicy(properties), properties);
	}

	private SmtpRepository.SmtpAccountRecord account(int port) {
		return account(port, SmtpModels.TlsMode.PLAIN_LOCAL_ONLY, null, null, null);
	}

	private SmtpRepository.SmtpAccountRecord account(
			int port, SmtpModels.TlsMode tlsMode, String username, byte[] ciphertext, byte[] nonce
	) {
		return new SmtpRepository.SmtpAccountRecord(UUID.randomUUID(), "SMTP fixture", "localhost", port,
				tlsMode, username, ciphertext, nonce, "sender@example.invalid", "Sender",
				"reply@example.invalid", 2, 10, 30, 10, true, null, null, null, 0, null,
				Instant.now(), Instant.now());
	}

	private SmtpRepository.SmtpAccountRecord authenticatedAccount(int port) {
		SmtpSecretCrypto.EncryptedSecret encrypted = new SmtpSecretCrypto(KEY).encrypt("secret".toCharArray());
		return account(port, SmtpModels.TlsMode.PLAIN_LOCAL_ONLY,
				"fixture-user", encrypted.ciphertext(), encrypted.nonce());
	}

	private SmtpTransport.OutboundMessage message() {
		return new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid",
				"<p>body</p>", "body", "delivery-recipient-1", "approved@example.invalid", MESSAGE_ID,
					Map.of(
							"List-Unsubscribe", "<https://tracking.example.test/u/opaque>",
							"List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
	}

	private SmtpTransport.OutboundMessage legacyMessage() {
		return new SmtpTransport.OutboundMessage(
				"qa@example.invalid", "QA", "Sender", "reply@example.invalid",
				"<p>body</p>", "body", UUID.randomUUID().toString());
	}

	private void reply(PrintWriter writer, String line) {
		for (String responseLine : line.split("\\n")) {
			writer.print(responseLine + "\r\n");
		}
		writer.flush();
	}

	private record Exchange(
			SmtpTransport transport, SmtpRepository.SmtpAccountRecord account, List<String> capturedData
	) { }

	private record PeerBehavior(
			String ehloReply, String authReply, String mailFromReply, String rcptReply,
			String dataCommandReply, String dataReply, boolean disconnect, boolean disconnectDuringBody
	) {
		static PeerBehavior reply(String line) {
			return dataReply(line);
		}

		static PeerBehavior dataReply(String line) {
			return new PeerBehavior(null, null, null, null, null, line, false, false);
		}

		static PeerBehavior mailFromReply(String line) {
			return new PeerBehavior(null, null, line, null, null, null, false, false);
		}

		static PeerBehavior rcptReply(String line) {
			return new PeerBehavior(null, null, null, line, null, null, false, false);
		}

		static PeerBehavior dataCommandReply(String line) {
			return new PeerBehavior(null, null, null, null, line, null, false, false);
		}

		static PeerBehavior disconnectAfterData() {
			return new PeerBehavior(null, null, null, null, null, null, true, false);
		}

		static PeerBehavior disconnectMidBody() {
			return new PeerBehavior(null, null, null, null, null, null, false, true);
		}

		static PeerBehavior ehloReply(String line) {
			return new PeerBehavior(line, null, null, null, null, null, false, false);
		}

		static PeerBehavior authReply(String line) {
			return new PeerBehavior("250-localhost\n250 AUTH PLAIN LOGIN", line,
					null, null, null, null, false, false);
		}
	}
}

package com.camel_hub.advertisement.email.tracking;

import com.camel_hub.advertisement.email.smtp.SmtpModels;
import com.camel_hub.advertisement.email.smtp.SmtpPolicy;
import com.camel_hub.advertisement.email.smtp.SmtpProperties;
import com.camel_hub.advertisement.email.smtp.SmtpRepository;
import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTrackingSmtpTransportTest {
	private static final String KEY = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

	@Test
	void explicitNegativeSmtpResponseIsADefiniteRejection() throws Exception {
		withSmtpPeer("550 Message rejected", transport -> assertThatThrownBy(transport::send)
				.isInstanceOfSatisfying(SmtpTransportException.class,
						error -> assertThat(error.category()).isEqualTo(SmtpTransportException.FailureCategory.SMTP_REJECTED)));
	}

	@Test
	void disconnectAfterReceivingDataIsAnUncertainOutcomeNotASmtpRejection() throws Exception {
		withSmtpPeer(null, transport -> assertThatThrownBy(transport::send)
				.isInstanceOfSatisfying(SmtpTransportException.class,
						error -> assertThat(error.category()).isEqualTo(SmtpTransportException.FailureCategory.UNEXPECTED_FAILURE)));
	}

	@Test
	void aSuccessfulDataReplyRemainsAcceptedWhenThePeerClosesAtQuit() throws Exception {
		withSmtpPeer("250 Message accepted", SendAttempt::send);
	}

	private void withSmtpPeer(String dataReply, java.util.function.Consumer<SendAttempt> assertion) throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var peer = executor.submit(() -> {
				try (var socket = listener.accept();
					 var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
					 var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
					socket.setSoTimeout(5000);
					reply(writer, "220 localhost test SMTP");
					String command;
					while ((command = reader.readLine()) != null) {
						if (command.equals("DATA")) {
							reply(writer, "354 End with a dot");
							while ((command = reader.readLine()) != null && !command.equals(".")) { }
							if (dataReply == null) return;
							reply(writer, dataReply);
						}
						else if (command.equals("QUIT")) return;
						else reply(writer, "250 localhost");
					}
				}
				catch (Exception error) {
					throw new AssertionError(error);
				}
			});
			var properties = new SmtpProperties(false, Set.of("localhost"), Duration.ofSeconds(2),
					Duration.ofSeconds(2), Duration.ofSeconds(2), KEY);
			var transport = new SmtpTransport(new SmtpSecretCrypto(KEY), new SmtpPolicy(properties), properties);
			var account = new SmtpRepository.SmtpAccountRecord(UUID.randomUUID(), "SMTP fixture", "localhost", listener.getLocalPort(),
					SmtpModels.TlsMode.PLAIN_LOCAL_ONLY, null, null, null, "sender@example.invalid", "Sender", "reply@example.invalid",
					10, 100, 1000, 50, true, null, null, null, 0, null, Instant.now(), Instant.now());
			var message = new SmtpTransport.OutboundMessage("qa@example.invalid", "QA", "Sender", "reply@example.invalid",
					"<p>body</p>", "body", UUID.randomUUID().toString());
			assertion.accept(() -> transport.send(account, message));
			peer.get(6, TimeUnit.SECONDS);
		}
	}

	private void reply(PrintWriter writer, String line) {
		writer.print(line + "\r\n");
		writer.flush();
	}

	@FunctionalInterface
	private interface SendAttempt { void send(); }
}

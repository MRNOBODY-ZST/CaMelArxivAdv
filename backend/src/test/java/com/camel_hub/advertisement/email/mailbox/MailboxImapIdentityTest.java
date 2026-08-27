package com.camel_hub.advertisement.email.mailbox;

import com.camel_hub.advertisement.email.smtp.SmtpSecretCrypto;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MailboxImapIdentityTest {

	@Test
	void identifiesTheClientBeforeOpeningAnImapMailbox() throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			CompletableFuture<Boolean> identified = new CompletableFuture<>();
			Thread.ofVirtual().start(() -> serve(listener, identified));
			SmtpSecretCrypto crypto = new SmtpSecretCrypto(Base64.getEncoder().encodeToString(
					"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
			var secret = crypto.encrypt("test-only".toCharArray());
			MailboxProperties properties = new MailboxProperties(false, Set.of("127.0.0.1"),
					Duration.ofSeconds(3), Duration.ofSeconds(3), 10);
			MailboxTransport transport = new MailboxTransport(crypto, new MailboxPolicy(properties), properties);
			var account = new MailboxRepository.MailboxAccountRecord(
					UUID.randomUUID(), "Test mailbox", MailboxModels.Protocol.IMAP,
					"127.0.0.1", listener.getLocalPort(), MailboxModels.TlsMode.PLAIN_LOCAL_ONLY,
					"test@example.org", secret.ciphertext(), secret.nonce(), "INBOX", true,
					null, null, null, 0, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now());

			assertThat(transport.preview(account, 1)).isEmpty();
			assertThat(identified.get(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static void serve(ServerSocket listener, CompletableFuture<Boolean> result) {
		try (var socket = listener.accept();
			 var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
			socket.setSoTimeout(5_000);
			writer.print("* OK [CAPABILITY IMAP4rev1 ID] Test ready\r\n");
			writer.flush();
			boolean identified = false;
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(" ", 3);
				String tag = parts[0];
				String command = parts[1].toUpperCase(java.util.Locale.ROOT);
				String response = switch (command) {
					case "CAPABILITY" -> "* CAPABILITY IMAP4rev1 ID\r\n" + tag + " OK CAPABILITY\r\n";
					case "LOGIN" -> tag + " OK [CAPABILITY IMAP4rev1 ID] LOGIN\r\n";
					case "ID" -> {
						identified = line.contains("CaMel") && !line.contains("test-only") && !line.contains("@");
						yield "* ID NIL\r\n" + tag + " OK ID\r\n";
					}
					case "LIST" -> "* LIST () \"/\" \"INBOX\"\r\n" + tag + " OK LIST\r\n";
					case "EXAMINE", "SELECT" -> identified
							? "* FLAGS (\\Seen)\r\n* 0 EXISTS\r\n* 0 RECENT\r\n"
								+ "* OK [UIDVALIDITY 1]\r\n" + tag + " OK [READ-ONLY] EXAMINE\r\n"
							: tag + " NO Client identity required\r\n";
					case "LOGOUT" -> "* BYE closing\r\n" + tag + " OK LOGOUT\r\n";
					default -> tag + " OK done\r\n";
				};
				writer.print(response);
				writer.flush();
				if (command.equals("LOGOUT")) break;
			}
			result.complete(identified);
		}
		catch (Exception exception) {
			result.completeExceptionally(exception);
		}
	}
}

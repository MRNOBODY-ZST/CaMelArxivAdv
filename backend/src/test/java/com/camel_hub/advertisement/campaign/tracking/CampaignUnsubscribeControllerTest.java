package com.camel_hub.advertisement.campaign.tracking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CampaignUnsubscribeControllerTest {

	@Test
	void openingAnyLinkAndHeadRemainNonMutatingAndNeverEchoTheCapability() {
		CampaignCallbackNamespace namespace = mock(CampaignCallbackNamespace.class);
		WebTestClient client = client(namespace);
		String token = "campaign-unsubscribe:v1.private-capability";
		String expected = client.get().uri("/u/{token}", token).exchange().expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectHeader().valueEquals("Pragma", "no-cache")
				.expectHeader().valueEquals("Expires", "0")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer")
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
				.expectHeader().valueEquals("X-Robots-Tag", "noindex, nofollow")
				.expectHeader().valueEquals("Content-Security-Policy", CampaignUnsubscribePage.contentSecurityPolicy())
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(expected).contains("Your inbox. Your choice.").doesNotContain(token, "/u/");
		for (String path : new String[]{"/u/invalid-link", "/u", "/u/malformed/multiple-segments"}) {
			client.get().uri(path).exchange().expectStatus().isOk().expectBody(String.class)
					.value(body -> assertThat(body).isEqualTo(expected));
			client.head().uri(path).exchange().expectStatus().isOk()
					.expectHeader().valueEquals("Content-Security-Policy", CampaignUnsubscribePage.contentSecurityPolicy())
					.expectBody().isEmpty();
		}
		client.head().uri("/u/{token}", token).exchange().expectStatus().isOk().expectBody().isEmpty();
		verifyNoInteractions(namespace);
	}

	@Test
	void validInvalidAndRfc8058PostsShareTheSameGenericResult(CapturedOutput output) {
		CampaignCallbackNamespace namespace = mock(CampaignCallbackNamespace.class);
		when(namespace.unsubscribe(any(), any())).thenReturn(Mono.just(false));
		String validToken = "campaign-unsubscribe:v1.private-valid-capability";
		when(namespace.unsubscribe(eq(validToken), any())).thenReturn(Mono.just(true));
		WebTestClient client = client(namespace);
		String expected = client.post().uri("/u/invalid-link").exchange().expectStatus().isOk()
				.expectHeader().valueEquals("Content-Security-Policy", CampaignUnsubscribePage.contentSecurityPolicy())
				.expectBody(String.class).returnResult().getResponseBody();
		client.post().uri("/u/{token}", validToken)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.bodyValue("List-Unsubscribe=One-Click").exchange().expectStatus().isOk()
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectBody(String.class).value(body -> assertThat(body).isEqualTo(expected));
		assertThat(expected).contains("Request received.", "Your request has been received.")
				.doesNotContain(validToken, "invalid-link", "<form", "/u/");
		verify(namespace).unsubscribe(eq(validToken), any());
		assertThat(output.getAll()).doesNotContain(validToken);
	}

	@Test
	void unavailableStorageReturnsTheBrandedPageAndAWorkingRetryWithoutLeakingDetails(CapturedOutput output) {
		CampaignCallbackNamespace namespace = mock(CampaignCallbackNamespace.class);
		String token = "campaign-unsubscribe:v1.private-error-capability";
		when(namespace.unsubscribe(any(), any()))
				.thenReturn(Mono.error(new IllegalStateException("private storage details")));
		client(namespace).post().uri("/u/{token}", token).exchange().expectStatus().isEqualTo(503)
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
				.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
				.expectHeader().valueEquals("Referrer-Policy", "no-referrer")
				.expectHeader().valueEquals("Content-Security-Policy", CampaignUnsubscribePage.contentSecurityPolicy())
				.expectBody(String.class).value(body -> assertThat(body)
						.contains("Camel Hub", "has not been confirmed", "<form method=\"post\" action=\"\">", "Try again")
						.doesNotContain(token, "private storage details"));
		assertThat(output.getAll()).contains("Campaign unsubscribe persistence failed")
				.doesNotContain(token, "private storage details");
	}

	@Test
	void unsupportedMethodsCannotTriggerAnUnsubscribe() {
		CampaignCallbackNamespace namespace = mock(CampaignCallbackNamespace.class);
		WebTestClient client = client(namespace);
		for (HttpMethod method : new HttpMethod[]{HttpMethod.DELETE, HttpMethod.PUT, HttpMethod.PATCH}) {
			client.method(method).uri("/u/private-capability").exchange().expectStatus().isEqualTo(405)
					.expectHeader().valueEquals("Allow", "GET,HEAD,POST")
					.expectHeader().valueMatches("Cache-Control", ".*no-store.*")
					.expectBody().isEmpty();
		}
		verifyNoInteractions(namespace);
	}

	private WebTestClient client(CampaignCallbackNamespace namespace) {
		return WebTestClient.bindToController(new CampaignUnsubscribeController(namespace)).build();
	}
}

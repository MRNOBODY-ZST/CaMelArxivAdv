package com.camel_hub.advertisement.identity.api;

import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/auth")
public class CurrentUserController {

	@GetMapping("/me")
	@PreAuthorize("isAuthenticated()")
	Mono<AuthDtos.CurrentUserResponse> me(Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
			return Mono.error(new IllegalStateException("Authenticated identity is unavailable"));
		}
		return Mono.just(AuthDtos.CurrentUserResponse.from(user));
	}
}

package com.camel_hub.advertisement.common.api;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.arxiv.client.ArxivDependencyException;
import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchConflictException;
import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchNotFoundException;
import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchValidationException;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportValidationException;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionConflictException;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionNotFoundException;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionValidationException;
import com.camel_hub.advertisement.arxiv.paper.PaperNotFoundException;
import com.camel_hub.advertisement.analytics.AnalyticsValidationException;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.observability.TraceIdWebFilter;
import com.camel_hub.advertisement.common.security.ClientAddressResolver;
import com.camel_hub.advertisement.contact.ContactConflictException;
import com.camel_hub.advertisement.contact.ContactNotFoundException;
import com.camel_hub.advertisement.contact.ContactValidationException;
import com.camel_hub.advertisement.campaign.SegmentNotFoundException;
import com.camel_hub.advertisement.campaign.SegmentValidationException;
import com.camel_hub.advertisement.campaign.CampaignNotFoundException;
import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.campaign.PersonalizationUnavailableException;
import com.camel_hub.advertisement.email.mailbox.MailboxConflictException;
import com.camel_hub.advertisement.email.mailbox.MailboxNotFoundException;
import com.camel_hub.advertisement.email.mailbox.MailboxTransportException;
import com.camel_hub.advertisement.email.mailbox.MailboxValidationException;
import com.camel_hub.advertisement.email.smtp.SmtpConflictException;
import com.camel_hub.advertisement.email.smtp.SmtpNotFoundException;
import com.camel_hub.advertisement.email.smtp.SmtpTransportException;
import com.camel_hub.advertisement.email.smtp.SmtpValidationException;
import com.camel_hub.advertisement.email.template.TemplateConflictException;
import com.camel_hub.advertisement.email.template.TemplateNotFoundException;
import com.camel_hub.advertisement.email.template.TemplateValidationException;
import com.camel_hub.advertisement.email.tracking.MailTrackingNotFoundException;
import com.camel_hub.advertisement.email.tracking.MailTrackingValidationException;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.camel_hub.advertisement.identity.service.AuthenticationFailedException;
import com.camel_hub.advertisement.identity.service.AdministrationConflictException;
import com.camel_hub.advertisement.identity.service.AdministrationNotFoundException;
import com.camel_hub.advertisement.identity.service.AdministrationValidationException;
import com.camel_hub.advertisement.identity.service.InvalidRefreshTokenException;
import com.camel_hub.advertisement.identity.service.LoginRateLimitedException;
import com.camel_hub.advertisement.identity.service.PasswordPolicyViolationException;
import com.camel_hub.advertisement.job.service.InvalidJobStateException;
import com.camel_hub.advertisement.job.service.JobConflictException;
import com.camel_hub.advertisement.job.service.JobNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private final ObjectProvider<AuditService> auditServiceProvider;
	private final ObjectProvider<SensitiveValueHasher> hasherProvider;

	public GlobalExceptionHandler(
			ObjectProvider<AuditService> auditServiceProvider,
			ObjectProvider<SensitiveValueHasher> hasherProvider
	) {
		this.auditServiceProvider = auditServiceProvider;
		this.hasherProvider = hasherProvider;
	}

	@ExceptionHandler(WebExchangeBindException.class)
	ResponseEntity<ApiError> handleValidation(WebExchangeBindException exception, ServerWebExchange exchange) {
		Map<String, List<String>> fieldErrors = new LinkedHashMap<>();
		exception.getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage() == null
						? "invalid value"
						: error.getDefaultMessage()))
				.forEach(violation -> fieldErrors.merge(
						violation.field(),
						List.of(violation.message()),
						(left, right) -> {
							var merged = new java.util.ArrayList<>(left);
							merged.addAll(right);
							return List.copyOf(merged);
						}));
		return response(
				exchange,
				HttpStatus.BAD_REQUEST,
				"validation_error",
				"Validation failed",
				"Request contains invalid fields",
				fieldErrors);
	}

	@ExceptionHandler(ServerWebInputException.class)
	ResponseEntity<ApiError> handleInvalidInput(ServerWebInputException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_request", "Invalid request",
				"Request body or parameters could not be read", Map.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	Mono<ResponseEntity<ApiError>> handleAccessDenied(AccessDeniedException exception, ServerWebExchange exchange) {
		ResponseEntity<ApiError> denied = response(
				exchange, HttpStatus.FORBIDDEN, "access_denied", "Access denied",
				"You do not have permission to perform this operation", Map.of());
		return auditAccessDenied(exchange).thenReturn(denied);
	}

	private Mono<Void> auditAccessDenied(ServerWebExchange exchange) {
		if (RequestContextSupport.isCapabilityRequest(exchange) || auditServiceProvider == null || hasherProvider == null) {
			return Mono.empty();
		}
		AuditService auditService = auditServiceProvider.getIfAvailable();
		SensitiveValueHasher hasher = hasherProvider.getIfAvailable();
		if (auditService == null || hasher == null) {
			return Mono.empty();
		}
		return authenticatedUser(exchange)
				.flatMap(user -> {
					String ipAddress = ClientAddressResolver.resolve(exchange.getRequest());
					String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
					String summary = userAgent == null
							? "unknown"
							: userAgent.substring(0, Math.min(255, userAgent.length()));
					String resource = exchange.getRequest().getMethod().name() + " "
							+ RequestContextSupport.safePath(exchange);
					return auditService.record(new AuditEvent(
							user.id(), "AUTHORIZATION_DENIED", "HTTP_ENDPOINT", resource,
							hasher.hash(ipAddress), summary, TraceIdWebFilter.traceId(exchange),
							Map.of(), Map.of("status", "DENIED"), AuditResult.DENIED, "ACCESS_DENIED"));
				})
				.onErrorResume(auditFailure -> {
					LOGGER.warn("Controller authorization denial audit could not be recorded", auditFailure);
					return Mono.empty();
				});
	}

	private Mono<AuthenticatedUser> authenticatedUser(ServerWebExchange exchange) {
		Mono<AuthenticatedUser> exchangeUser = exchange.getPrincipal()
				.flatMap(principal -> asAuthenticatedUser(principal));
		Mono<AuthenticatedUser> contextUser = ReactiveSecurityContextHolder.getContext()
				.map(context -> context.getAuthentication())
				.flatMap(this::asAuthenticatedUser);
		return exchangeUser.switchIfEmpty(contextUser);
	}

	private Mono<AuthenticatedUser> asAuthenticatedUser(Object principal) {
		Object candidate = principal instanceof Authentication authentication
				? authentication.getPrincipal()
				: principal;
		return candidate instanceof AuthenticatedUser user ? Mono.just(user) : Mono.empty();
	}

	@ExceptionHandler(AuthenticationFailedException.class)
	ResponseEntity<ApiError> handleAuthenticationFailed(
			AuthenticationFailedException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.UNAUTHORIZED, "authentication_failed", "Authentication failed",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(LoginRateLimitedException.class)
	ResponseEntity<ApiError> handleLoginRateLimited(
			LoginRateLimitedException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.TOO_MANY_REQUESTS, "login_rate_limited", "Login rate limited",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(InvalidRefreshTokenException.class)
	ResponseEntity<ApiError> handleInvalidRefreshToken(
			InvalidRefreshTokenException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.UNAUTHORIZED, "invalid_session", "Invalid session",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(PasswordPolicyViolationException.class)
	ResponseEntity<ApiError> handlePasswordPolicyViolation(
			PasswordPolicyViolationException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "password_policy_violation", "Password rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AdministrationValidationException.class)
	ResponseEntity<ApiError> handleAdministrationValidation(
			AdministrationValidationException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_operation", "Operation rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AdministrationConflictException.class)
	ResponseEntity<ApiError> handleAdministrationConflict(
			AdministrationConflictException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "resource_conflict", "Resource conflict",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AdministrationNotFoundException.class)
	ResponseEntity<ApiError> handleAdministrationNotFound(
			AdministrationNotFoundException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "resource_not_found", "Resource not found",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception, ServerWebExchange exchange) {
		HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
		HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
		String detail = exception.getReason() == null ? resolved.getReasonPhrase() : exception.getReason();
		return response(exchange, resolved, "request_rejected", resolved.getReasonPhrase(), detail, Map.of());
	}

	@ExceptionHandler(SourceExtractionValidationException.class)
	ResponseEntity<ApiError> handleSourceExtractionValidation(
			SourceExtractionValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_source_extraction",
				"Source extraction rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SourceExtractionNotFoundException.class)
	ResponseEntity<ApiError> handleSourceExtractionNotFound(
			SourceExtractionNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "paper_not_found",
				"Paper not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SourceExtractionConflictException.class)
	ResponseEntity<ApiError> handleSourceExtractionConflict(
			SourceExtractionConflictException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "source_extraction_conflict",
				"Source extraction conflict", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ContactNotFoundException.class)
	ResponseEntity<ApiError> handleContactNotFound(
			ContactNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "contact_not_found",
				"Contact not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SegmentNotFoundException.class)
	ResponseEntity<ApiError> handleSegmentNotFound(
			SegmentNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "segment_not_found",
				"Segment not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SegmentValidationException.class)
	ResponseEntity<ApiError> handleSegmentValidation(
			SegmentValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_segment",
				"Segment rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(CampaignNotFoundException.class)
	ResponseEntity<ApiError> handleCampaignNotFound(
			CampaignNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "campaign_not_found",
				"Campaign not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(CampaignValidationException.class)
	ResponseEntity<ApiError> handleCampaignValidation(
			CampaignValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_campaign",
				"Campaign rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(PersonalizationUnavailableException.class)
	ResponseEntity<ApiError> handlePersonalizationUnavailable(
			PersonalizationUnavailableException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.SERVICE_UNAVAILABLE, "personalization_unavailable",
				"Personalization unavailable", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ContactConflictException.class)
	ResponseEntity<ApiError> handleContactConflict(
			ContactConflictException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "contact_conflict",
				"Contact conflict", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ContactValidationException.class)
	ResponseEntity<ApiError> handleContactValidation(
			ContactValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_contact_verification",
				"Contact verification rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(JobNotFoundException.class)
	ResponseEntity<ApiError> handleJobNotFound(JobNotFoundException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.NOT_FOUND, "job_not_found", "Job not found",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler({JobConflictException.class, InvalidJobStateException.class})
	ResponseEntity<ApiError> handleJobConflict(RuntimeException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.CONFLICT, "job_conflict", "Job command rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SavedSearchNotFoundException.class)
	ResponseEntity<ApiError> handleSavedSearchNotFound(
			SavedSearchNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "saved_search_not_found", "Saved search not found",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SavedSearchValidationException.class)
	ResponseEntity<ApiError> handleSavedSearchValidation(
			SavedSearchValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_saved_search", "Saved search rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SavedSearchConflictException.class)
	ResponseEntity<ApiError> handleSavedSearchConflict(
			SavedSearchConflictException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "saved_search_conflict", "Saved search conflict",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ArxivImportValidationException.class)
	ResponseEntity<ApiError> handleArxivImportValidation(
			ArxivImportValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_arxiv_import", "arXiv import rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(PaperNotFoundException.class)
	ResponseEntity<ApiError> handlePaperNotFound(PaperNotFoundException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.NOT_FOUND, "paper_not_found", "Paper not found",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(AnalyticsValidationException.class)
	ResponseEntity<ApiError> handleAnalyticsValidation(
			AnalyticsValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_analytics_filter",
				"Analytics filter rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(TemplateValidationException.class)
	ResponseEntity<ApiError> handleTemplateValidation(
			TemplateValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_email_template",
				"Email template rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(TemplateNotFoundException.class)
	ResponseEntity<ApiError> handleTemplateNotFound(
			TemplateNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "email_template_not_found",
				"Email template not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(TemplateConflictException.class)
	ResponseEntity<ApiError> handleTemplateConflict(
			TemplateConflictException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "email_template_conflict",
				"Email template conflict", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SmtpValidationException.class)
	ResponseEntity<ApiError> handleSmtpValidation(
			SmtpValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_smtp_account",
				"SMTP account rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SmtpNotFoundException.class)
	ResponseEntity<ApiError> handleSmtpNotFound(
			SmtpNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "smtp_account_not_found",
				"SMTP account not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SmtpConflictException.class)
	ResponseEntity<ApiError> handleSmtpConflict(
			SmtpConflictException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "smtp_account_conflict",
				"SMTP account conflict", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(SmtpTransportException.class)
	ResponseEntity<ApiError> handleSmtpTransport(
			SmtpTransportException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_GATEWAY, "smtp_test_failed",
				"SMTP operation failed", "SMTP failure category: " + exception.category().name(), Map.of());
	}

	@ExceptionHandler(MailTrackingValidationException.class)
	ResponseEntity<ApiError> handleMailTrackingValidation(MailTrackingValidationException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_mail_tracking", "Mail tracking request rejected",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(MailTrackingNotFoundException.class)
	ResponseEntity<ApiError> handleMailTrackingNotFound(MailTrackingNotFoundException exception, ServerWebExchange exchange) {
		return response(exchange, HttpStatus.NOT_FOUND, "mail_send_record_not_found", "Mail send record not found",
				exception.getMessage(), Map.of());
	}

	@ExceptionHandler(MailboxValidationException.class)
	ResponseEntity<ApiError> handleMailboxValidation(
			MailboxValidationException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_REQUEST, "invalid_mailbox_account",
				"Mailbox account rejected", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(MailboxNotFoundException.class)
	ResponseEntity<ApiError> handleMailboxNotFound(
			MailboxNotFoundException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.NOT_FOUND, "mailbox_account_not_found",
				"Mailbox account not found", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(MailboxConflictException.class)
	ResponseEntity<ApiError> handleMailboxConflict(
			MailboxConflictException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.CONFLICT, "mailbox_account_conflict",
				"Mailbox account conflict", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(MailboxTransportException.class)
	ResponseEntity<ApiError> handleMailboxTransport(
			MailboxTransportException exception, ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.BAD_GATEWAY, "mailbox_operation_failed",
				"Mailbox operation failed", "Mailbox failure category: " + exception.category().name(), Map.of());
	}

	@ExceptionHandler(ArxivDependencyException.class)
	ResponseEntity<ApiError> handleArxivDependency(
			ArxivDependencyException exception,
			ServerWebExchange exchange
	) {
		return response(exchange, HttpStatus.SERVICE_UNAVAILABLE,
				"arxiv_unavailable", "arXiv unavailable",
				"The arXiv request could not be completed safely; retry later", Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception exception, ServerWebExchange exchange) {
		if (RequestContextSupport.isCapabilityRequest(exchange)) {
			// Exception messages and reactive checkpoints can contain the capability URL.
			LOGGER.error("Unhandled callback exception traceId={}", TraceIdWebFilter.traceId(exchange));
		}
		else {
			LOGGER.error("Unhandled API exception traceId={} path={}", TraceIdWebFilter.traceId(exchange),
					RequestContextSupport.safePath(exchange), exception);
		}
		return response(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error",
				"The request could not be completed", Map.of());
	}

	private ResponseEntity<ApiError> response(
			ServerWebExchange exchange,
			HttpStatus status,
			String type,
			String title,
			String detail,
			Map<String, List<String>> fieldErrors
	) {
		String traceId = TraceIdWebFilter.traceId(exchange);
		ApiError error = new ApiError(
				type,
				title,
				status.value(),
				RequestContextSupport.isCapabilityRequest(exchange) ? status.getReasonPhrase() : detail,
				RequestContextSupport.safePath(exchange),
				traceId,
				fieldErrors);
		return ResponseEntity.status(status).body(error);
	}
}

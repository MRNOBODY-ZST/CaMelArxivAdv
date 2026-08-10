package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.common.api.RequestContextSupport;
import com.camel_hub.advertisement.email.smtp.SmtpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("api")
@RequestMapping("/api/v1/templates")
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TemplateController {

	private final TemplateService service;
	private final TemplateMailService mailService;

	public TemplateController(TemplateService service, TemplateMailService mailService) {
		this.service = service;
		this.mailService = mailService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('template:read')")
	Mono<PageResponse<TemplateService.TemplateView>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize
	) {
		return service.list(page, pageSize);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('template:read')")
	Mono<TemplateService.TemplateView> get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<TemplateService.TemplateView> create(
			@Valid @RequestBody TemplateDtos.UpsertRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.create(RequestContextSupport.actorId(principal), request.command(),
				RequestContextSupport.context(exchange));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<TemplateService.TemplateView> update(
			@PathVariable UUID id, @Valid @RequestBody TemplateDtos.UpdateRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.update(RequestContextSupport.actorId(principal), id, request.expectedLockVersion(),
				request.template().command(), RequestContextSupport.context(exchange));
	}

	@PostMapping("/preview")
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<TemplateService.PreviewView> preview(@Valid @RequestBody TemplateDtos.PreviewRequest request) {
		return Mono.fromSupplier(() -> service.preview(request.template().command(), request.variables()));
	}

	@GetMapping("/{id}/versions")
	@PreAuthorize("hasAuthority('template:read')")
	Mono<List<TemplateService.TemplateVersionView>> versions(@PathVariable UUID id) {
		return service.versions(id);
	}

	@PostMapping("/{id}/versions/{versionNumber}/restore")
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<TemplateService.TemplateView> restore(
			@PathVariable UUID id, @PathVariable @Min(1) int versionNumber,
			@Valid @RequestBody TemplateDtos.RestoreRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.restore(RequestContextSupport.actorId(principal), id, versionNumber,
				request.expectedLockVersion(), RequestContextSupport.context(exchange));
	}

	@PostMapping("/{id}/copy")
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<TemplateService.TemplateView> copy(
			@PathVariable UUID id, @Valid @RequestBody TemplateDtos.CopyRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return service.copy(RequestContextSupport.actorId(principal), id, request.name(),
				RequestContextSupport.context(exchange));
	}

	@PostMapping("/{id}/test-send")
	@PreAuthorize("hasAuthority('template:manage') and hasAuthority('smtp:manage')")
	Mono<SmtpService.TestResult> testSend(
			@PathVariable UUID id, @Valid @RequestBody TemplateDtos.TestSendRequest request,
			Principal principal, ServerWebExchange exchange
	) {
		return mailService.sendTest(RequestContextSupport.actorId(principal), id, request.smtpAccountId(),
				request.recipient(), request.variables(), RequestContextSupport.context(exchange));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('template:manage')")
	Mono<Void> delete(
			@PathVariable UUID id, @RequestParam @Min(0) long expectedLockVersion,
			Principal principal, ServerWebExchange exchange
	) {
		return service.delete(RequestContextSupport.actorId(principal), id, expectedLockVersion,
				RequestContextSupport.context(exchange));
	}
}

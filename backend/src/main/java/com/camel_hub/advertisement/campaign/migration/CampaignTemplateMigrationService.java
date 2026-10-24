package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.audit.*;
import com.camel_hub.advertisement.campaign.*;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationRepository.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.*;

/** One source transaction; no workflow, send preparation, network adapter or outbox dependency. */
public final class CampaignTemplateMigrationService {
    private final CampaignTemplateMigrationRepository repository;
    private final CampaignRepository campaigns;
    private final OutreachTemplateCampaignRepository metadata;
    private final OutreachTemplateContentPolicy contentPolicy;
    private final AuditService audit;
    private final TransactionalOperator transactions;
    private final boolean enabled;
    private final CampaignTemplateMigrationPolicy policy=new CampaignTemplateMigrationPolicy();

    public CampaignTemplateMigrationService(CampaignTemplateMigrationRepository repository,CampaignRepository campaigns,OutreachTemplateCampaignRepository metadata,OutreachTemplateContentPolicy contentPolicy,AuditService audit,TransactionalOperator transactions,boolean enabled) {
        this.repository=repository;this.campaigns=campaigns;this.metadata=metadata;this.contentPolicy=contentPolicy;this.audit=audit;this.transactions=transactions;this.enabled=enabled;
    }
    public Mono<MigrationPreview> preview(UUID source,UUID actor,PreviewCommand command){
        return Mono.defer(()->{
            requireEnabled();requireIds(source,actor);
            return requireAuthority(repository.readAuthorized(actor,true)).then(repository.readSnapshot(source,command)).map(snapshot->{
                var reasons=policy.blockers(snapshot);
                return new MigrationPreview(source,snapshot.campaign().lockVersion(),snapshot.template().id(),snapshot.template().versionId(),snapshot.template().lockVersion(),policy.membershipDigest(snapshot),
                        policy.canonicalRecipients(command.recipientIds()),policy.selectionCounts(snapshot),reasons.isEmpty(),reasons);
            });
        });
    }
    public Mono<MigrationResult> migrate(UUID source,UUID actor,AuthenticationRequestContext context,MigrationCommand command){
        return Mono.defer(()->{
            requireEnabled();requireIds(source,actor);
            if(command==null||context==null||context.traceId()==null||context.traceId().length()>64||command.expectedLockVersion()<0||command.expectedTemplateLockVersion()<0
                    ||command.targetTemplateId()==null||command.targetTemplateVersionId()==null||command.expectedMembershipDigest()==null||!command.expectedMembershipDigest().matches("[0-9a-f]{64}"))
                return Mono.error(new CampaignValidationException("Migration command is invalid"));
            policy.validateKey(command.idempotencyKey());policy.canonicalRecipients(command.recipientIds());
            return requireAuthority(repository.authorized(actor,true)).then(repository.lockKey(actor,command.idempotencyKey()))
                    .then(repository.storedProof(actor,command.idempotencyKey()).flatMap(proof->{
                        if(!proof.sourceId().equals(source)||!proof.requestDigest().equals(policy.requestDigest(source,actor,command,proof.membershipDigest())))
                            return Mono.error(new CampaignConflictException("Idempotency key was already used for a different request"));
                        return repository.findByKey(actor,command.idempotencyKey());
                    }).switchIfEmpty(Mono.defer(()->repository.lockSnapshot(source,command).flatMap(snapshot->{
                        if(!policy.membershipDigest(snapshot).equals(command.expectedMembershipDigest()))return Mono.error(new CampaignConflictException("Migration membership changed; refresh and retry"));
                        return commit(source,actor,context,command,snapshot);
                    }))));
        }).as(transactions::transactional);
    }
    private Mono<MigrationResult> commit(UUID source,UUID actor,AuthenticationRequestContext context,MigrationCommand command,MigrationSnapshot snapshot){
        var reasons=policy.blockers(snapshot);
        if(!reasons.isEmpty())return finish(actor,context,command,snapshot,result(source,command,null,"BLOCKED",reasons));
        if(snapshot.recipients().isEmpty())return repository.emptyPristineDraft(source).flatMap(pristine->{
            var result=result(source,command,null,pristine?"CONFIG_UPDATED":"NO_MAIL",List.of());
            return (pristine?repository.updateEmptyConfiguration(source,command):Mono.<Void>empty()).then(finish(actor,context,command,snapshot,result));
        });
        // Validate captured public inputs before any target writes. Low/unmapped identities remain neutral.
        List<CampaignRepository.FrozenProductionRecipient> sources=snapshot.recipients().stream().map(this::renderSource).toList();
        return campaigns.createPinnedDraft(actor,snapshot).flatMap(target->{
            var result=result(source,command,target,"MIGRATED",List.of());
            var papers=snapshot.recipients().stream().map(FrozenRecipient::paperId).distinct().sorted().toList();
            return campaigns.preparePaperContactGeneration(target,result.id(),"TEMPLATE",OutreachTemplateContentPolicy.VERSION,papers,sources.size())
                    .flatMap(prepared->prepared?metadata.lockContext(target).switchIfEmpty(Mono.error(new CampaignValidationException("Pinned target context is unavailable"))):Mono.error(new CampaignConflictException("Target generation could not be frozen")))
                    .flatMap(renderContext->repository.saveResult(actor,command,policy.requestDigest(source,actor,command,snapshot),result)
                            .thenMany(Flux.range(0,sources.size()).concatMap(i->campaigns.copyFrozenRecipient(target,sources.get(i)).flatMap(copy->{
                                var draft=contentPolicy.render(renderContext.template(),copy.target());
                                byte[] sourceHash=contentPolicy.sourceHash(renderContext.template(),copy),contentHash=contentPolicy.contentHash(draft);
                                return campaigns.storeGenerated(target,copy.target().recipientId(),result.id(),draft)
                                        .flatMap(saved->saved?metadata.saveReview(target,copy.target().recipientId(),sourceHash,contentHash):Mono.error(new CampaignConflictException("Target render could not be stored")))
                                        .then(repository.insertTransfer(result.id(),snapshot.recipients().get(i).id(),copy.target().recipientId(),sourceHash,contentHash))
                                        .then(repository.saveProvenance(result.id(),snapshot.recipients().get(i)));
                            }))).then(campaigns.refreshGenerationState(target,result.id()))
                            .then(repository.advanceSource(source,command.expectedLockVersion())).then(record(actor,context,result)).thenReturn(result));
        });
    }
    private CampaignRepository.FrozenProductionRecipient renderSource(FrozenRecipient r){
        var frozen=r.frozenTarget();var captured=r.source();
        var target=new CampaignRepository.QueuedTarget(r.id(),captured.identityTrusted()?captured.authorName():null,
                frozen.paperTitle(),missing(frozen.paperAbstract(),captured.paperAbstract()),missing(frozen.arxivId(),captured.arxivId()),
                frozen.category(),captured.identityTrusted()?captured.organization():null);
        contentPolicy.validateSource(target);
        return new CampaignRepository.FrozenProductionRecipient(r.contactId(),r.paperId(),captured.identityTrusted()?captured.mappedAuthorId():null,HexFormat.of().parseHex(r.emailHmac()),"PENDING",0,true,target);
    }
    private String missing(String frozen,String captured){return frozen==null||frozen.isBlank()?captured:frozen;}
    private MigrationResult result(UUID source,MigrationCommand command,UUID target,String status,List<MigrationReason> reasons){return new MigrationResult(UUID.randomUUID(),source,target,command.targetTemplateId(),command.targetTemplateVersionId(),command.recipientIds().size(),target==null?0:command.recipientIds().size(),status,reasons);}
    private Mono<MigrationResult> finish(UUID actor,AuthenticationRequestContext context,MigrationCommand command,MigrationSnapshot snapshot,MigrationResult result){return repository.saveResult(actor,command,policy.requestDigest(result.sourceCampaignId(),actor,command,snapshot),result).then(record(actor,context,result)).thenReturn(result);}
    private Mono<Void> record(UUID actor,AuthenticationRequestContext context,MigrationResult result){
        return audit.record(new AuditEvent(actor,"campaign.template_migration","campaign",result.sourceCampaignId().toString(),null,context.userAgentSummary(),context.traceId(),Map.of(),
                Map.of("migrationId",result.id(),"status",result.status(),"selectedCount",result.selectedCount(),"migratedCount",result.migratedCount()),AuditResult.SUCCESS,null));
    }
    public Mono<MigrationResult> get(UUID source,UUID id,UUID actor){return Mono.defer(()->{requireIds(source,actor);return requireAuthority(repository.authorized(actor,false)).then(repository.find(source,id,actor));}).as(transactions::transactional);}
    public Mono<MigrationResult> getByKey(UUID source,UUID actor,String key){return Mono.defer(()->{requireIds(source,actor);policy.validateKey(key);return requireAuthority(repository.authorized(actor,false)).then(repository.findByKey(actor,key)).flatMap(result->result.sourceCampaignId().equals(source)?Mono.just(result):Mono.error(new CampaignNotFoundException("Migration was not found")));}).as(transactions::transactional);}
    private Mono<Void> requireAuthority(Mono<Boolean> permission){return permission.flatMap(allowed->allowed?Mono.empty():Mono.error(new AccessDeniedException("Migration permission is no longer available")));}
    private void requireEnabled(){if(!enabled)throw new CampaignValidationException("Template migrations are disabled until delivery fences are verified");}
    private void requireIds(UUID source,UUID actor){if(source==null||actor==null)throw new CampaignValidationException("Source and actor are required");}
}

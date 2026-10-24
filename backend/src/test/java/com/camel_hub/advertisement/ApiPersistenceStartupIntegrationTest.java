package com.camel_hub.advertisement;

import com.camel_hub.advertisement.arxiv.client.ArxivLegacyClient;
import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionRepository;
import com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionMailbox;
import com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionRepository;
import com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionService;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationService;
import com.camel_hub.advertisement.campaign.partner.PartnerInviteClient;
import com.camel_hub.advertisement.email.mailbox.MailboxTransport;
import com.camel_hub.advertisement.email.smtp.SmtpTransport;
import com.camel_hub.advertisement.email.template.TemplateAssetObjectStore;
import com.camel_hub.advertisement.job.persistence.JobRepository;
import com.camel_hub.advertisement.outreach.schedule.WeeklyScheduleService;
import com.camel_hub.advertisement.system.personalization.PersonalizationProbe;
import com.camel_hub.advertisement.system.personalization.WeeklyTrendClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Characterization of the real API assembly, not persistence-disabled context coverage. */
@SpringBootTest(classes = AdvertisementApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=api", "app.persistence.enabled=true", "spring.flyway.enabled=true",
                "app.arxiv.source-admission.enabled=false", "app.campaign.template-migrations.enabled=false",
                "app.live-smtp-allowed=false", "app.runtime.live-smtp-allowed=false", "app.runtime.public-mailbox-allowed=false",
                "app.smtp.live-allowed=false", "app.mailbox.public-allowed=false", "app.mail-tracking.enabled=false",
                "app.campaign-delivery.enabled=false", "app.campaign-safety.enabled=false", "app.campaign-inbound.enabled=false",
                "app.partner-invite.enabled=false", "app.personalization.enabled=false",
                "app.auth.bootstrap-admin.username=", "app.auth.bootstrap-admin.email=", "app.auth.bootstrap-admin.password=",
                "app.personalization-settings.api-origin=https://startup-fixture.invalid/v1",
                "app.personalization-settings.api-key=", "app.personalization-settings.service-token=",
                "app.arxiv.legacy-base-url=https://127.0.0.1:1/legacy", "app.arxiv.oai-base-url=https://127.0.0.1:1/oai",
                "app.arxiv.official-hosts=127.0.0.1", "app.template.assets.endpoint=http://127.0.0.1:1",
                "app.template.assets.access-key=synthetic-startup", "app.template.assets.secret-key=synthetic-startup-only",
                "app.template.assets.public-base-url=http://127.0.0.1", "app.mail-tracking.public-base-url=http://127.0.0.1"
        })
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = {"auto.create.topics.enable=false", "group.initial.rebalance.delay.ms=0"})
@Testcontainers
@DirtiesContext
class ApiPersistenceStartupIntegrationTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("api_startup").withUsername("synthetic").withPassword("test-only");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.0.2-alpine").withExposedPorts(6379);
    private static final Set<String> STATIC_TABLES = Set.of("flyway_schema_history", "permissions", "roles", "role_permissions",
            "arxiv_groups", "arxiv_archives", "arxiv_categories", "arxiv_category_snapshots", "data_retention_policies");

    @DynamicPropertySource static void ownedProperties(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")) {
            result.next(); assertThat(result.getLong(1)).as("new owned database before application migrations").isZero();
        }
        registry.add("spring.flyway.url", PG::getJdbcUrl);
        registry.add("spring.flyway.user", PG::getUsername);
        registry.add("spring.flyway.password", PG::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + PG.getHost() + ":" + PG.getFirstMappedPort() + "/api_startup");
        registry.add("spring.r2dbc.username", PG::getUsername);
        registry.add("spring.r2dbc.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
        String[] keys = {"app.auth.signing-key-base64", "app.auth.fingerprint-hmac-key-base64",
                "app.contact.data-protection.encryption-key-base64", "app.contact.data-protection.email-hmac-key-base64",
                "app.template.assets.signing-key-base64"};
        for (int i = 0; i < keys.length; i++) {
            String value = Base64.getEncoder().encodeToString(String.valueOf((char) ('A' + i)).repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            registry.add(keys[i], () -> value);
            if (i == 2) {
                registry.add("app.smtp.encryption-key-base64", () -> value);
                registry.add("app.personalization-settings.encryption-key-base64", () -> value);
            }
        }
    }

    @Autowired ApplicationContext context;
    @Autowired DatabaseClient db;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired EmbeddedKafkaBroker broker;
    @MockitoSpyBean SmtpTransport smtp;
    @MockitoSpyBean MailboxTransport mailbox;
    @MockitoSpyBean ArxivLegacyClient source;
    @MockitoSpyBean WeeklyTrendClient trend;
    @MockitoSpyBean PersonalizationProbe provider;
    @MockitoSpyBean TemplateAssetObjectStore assets;
    @MockitoSpyBean WeeklyScheduleService weekly;
    @MockitoSpyBean KafkaTemplate<?, ?> kafka;

    @Test @Timeout(90)
    void realApiMigratesAndBecomesReadyWithoutBusinessWorkAcrossWeeklyInterval() throws Exception {
        for (Class<?> type : new Class<?>[]{DatabaseClient.class, TransactionalOperator.class, SourceAdmissionRepository.class,
                JobRepository.class, SourceExtractionRepository.class, CampaignTemplateMigrationService.class}) {
            assertThat(context.getBeansOfType(type)).as(type.getSimpleName()).hasSize(1);
        }
        assertThat(context.getBeansOfType(SourceAdmissionMailbox.class)).isEmpty();
        assertThat(context.getBeansOfType(SourceAdmissionService.class)).isEmpty();
        assertThat(context.getBeansOfType(PartnerInviteClient.class)).isEmpty();
        assertThat(db.sql("SELECT count(*) AS count FROM flyway_schema_history WHERE success AND version='34'")
                .map((row, metadata) -> row.get("count", Long.class)).one().block()).isEqualTo(1L);
        var before = businessCounts();
        assertThat(before).containsKeys("source_command_authorities", "source_command_issues", "jobs", "outbox_messages", "campaigns",
                "delivery_attempts", "smtp_accounts", "mailbox_accounts", "outreach_schedules", "outreach_schedule_occurrences");
        assertThat(before.values()).allMatch(count -> count == 0L);
        assertThat(redis.execute(connection -> connection.ping()).blockFirst(Duration.ofSeconds(5))).isEqualTo("PONG");
        assertTopicsEmpty();
        String port = context.getEnvironment().getRequiredProperty("local.server.port");
        // Supplementary private-harness control, not a default network operation or business request.
        // The focused sandbox run sets this flag in the actual test JVM; normal tests do not probe egress.
        if (Boolean.getBoolean("startup.verify-egress-barrier")) {
            try (var local = new Socket()) {
                local.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 1000);
                assertThat(local.isConnected()).isTrue();
            }
            try (var denied = new Socket()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> denied.connect(new InetSocketAddress("192.0.2.1", 9), 1000))
                        .isInstanceOf(java.net.SocketException.class).hasMessageContaining("Operation not permitted");
            }
            System.out.println("startup_test_jvm_egress_control loopback=allowed test_net=denied");
        }
        try (var http = HttpClient.newHttpClient()) {
            for (String endpoint : new String[]{"/actuator/health/readiness", "/actuator/health"}) {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"status\":\"UP\"");
            }
        }
        // Default weekly poll is 30 seconds; retain real scheduling and ready callbacks.
        TimeUnit.SECONDS.sleep(31);
        verify(weekly, atLeast(2)).advanceOne();
        assertThat(businessCounts()).isEqualTo(before);
        assertTopicsEmpty();
        verifyNoInteractions(smtp, mailbox, source, trend, provider, assets);
        assertThat(mockingDetails(kafka).getInvocations()).noneMatch(call -> call.getMethod().getName().startsWith("send"));
    }

    private Map<String, Long> businessCounts() {
        var tables = db.sql("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name")
                .map((row, metadata) -> row.get("table_name", String.class)).all().collectList().block();
        var counts = new LinkedHashMap<String, Long>();
        for (String table : tables) {
            assertThat(table).matches("[a-z_]+");
            if (!STATIC_TABLES.contains(table)) counts.put(table, db.sql("SELECT count(*) AS count FROM " + table)
                    .map((row, metadata) -> row.get("count", Long.class)).one().block());
        }
        return counts;
    }

    private void assertTopicsEmpty() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            var names = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            assertThat(names).contains("camel.arxiv.jobs.v1", "camel.arxiv.results.v1");
            var ends = new LinkedHashMap<TopicPartition, OffsetSpec>();
            for (var topic : admin.describeTopics(names).allTopicNames().get(10, TimeUnit.SECONDS).values()) {
                for (var partition : topic.partitions()) ends.put(new TopicPartition(topic.name(), partition.partition()), OffsetSpec.latest());
            }
            assertThat(admin.listOffsets(ends).all().get(10, TimeUnit.SECONDS).values()).allMatch(offset -> offset.offset() == 0L);
        }
    }
}

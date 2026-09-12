package com.camel_hub.advertisement.email.smtp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpQuotaPolicyTest {
	@Test
	void calendarMonthIncludesMidnightAndReleasesAtNextUtcMonthIncludingLeapYear() {
		Instant now = Instant.parse("2028-02-29T12:00:00Z");
		var history = List.of(new SmtpQuotaPolicy.Reservation(Instant.parse("2028-02-01T00:00:00Z"), "research.test"));
		assertThat(SmtpQuotaPolicy.capacityRelease(account(1), "research.test", history, now))
				.isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
		assertThat(SmtpQuotaPolicy.capacityRelease(account(1), "research.test", history,
				Instant.parse("2028-03-01T00:00:00Z"))).isNull();
	}

	@Test
	void calendarRolloverCannotEraseTheStillActiveRollingDailyLimit() {
		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		Instant priorMonthSend = now.minusSeconds(60);
		var history = List.of(new SmtpQuotaPolicy.Reservation(priorMonthSend, "research.test"));
		assertThat(SmtpQuotaPolicy.historyCutoff(account(1), now)).isEqualTo(now.minus(Duration.ofDays(1)));
		assertThat(SmtpQuotaPolicy.capacityRelease(account(1), "research.test", history, now))
				.isEqualTo(priorMonthSend.plus(Duration.ofDays(1)));
	}

	@Test
	void lastDayOfDecemberSaturatedMonthReleasesInTheNewYear() {
		Instant now = Instant.parse("2026-12-31T12:00:00Z");
		assertThat(SmtpQuotaPolicy.capacityRelease(account(1), "research.test", List.of(
				new SmtpQuotaPolicy.Reservation(Instant.parse("2026-12-10T12:00:00Z"), "research.test")), now))
				.isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
	}

	private SmtpRepository.SmtpAccountRecord account(Integer monthly) {
		return new SmtpRepository.SmtpAccountRecord(UUID.randomUUID(), "Quota test", "mailpit", 1025,
				SmtpModels.TlsMode.PLAIN_LOCAL_ONLY, null, null, null, "sender@example.test", "Sender",
				"reply@example.test", 1, 1, 1, monthly, 1, true, null, null, null, 0, null, null, null);
	}
}

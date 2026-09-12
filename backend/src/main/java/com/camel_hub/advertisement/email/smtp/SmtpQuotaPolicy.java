package com.camel_hub.advertisement.email.smtp;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared account capacity; a reservation is committed before SMTP I/O starts. */
public final class SmtpQuotaPolicy {
	private SmtpQuotaPolicy() { }

	public static Instant monthStart(Instant now) {
		return now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate()
				.atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	public static Instant historyCutoff(SmtpRepository.SmtpAccountRecord account, Instant now) {
		Instant day = now.minus(Duration.ofDays(1));
		Instant month = monthStart(now);
		return account.perMonthLimit() != null && month.isBefore(day) ? month : day;
	}

	public static Instant capacityRelease(SmtpRepository.SmtpAccountRecord account, String recipientDomain,
			List<Reservation> reservations, Instant now) {
		List<Instant> releases = new ArrayList<>();
		addRelease(releases, reservations, account.perMinuteLimit(), Duration.ofMinutes(1), now, null);
		addRelease(releases, reservations, account.perHourLimit(), Duration.ofHours(1), now, null);
		addRelease(releases, reservations, account.perDayLimit(), Duration.ofDays(1), now, null);
		addRelease(releases, reservations, account.perDomainHourLimit(), Duration.ofHours(1), now, recipientDomain);
		if (account.perMonthLimit() != null) {
			Instant start = monthStart(now);
			long used = reservations.stream().filter(item -> !item.startedAt().isBefore(start)).count();
			if (used >= account.perMonthLimit()) {
				releases.add(start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
			}
		}
		return releases.stream().max(Comparator.naturalOrder()).orElse(null);
	}

	private static void addRelease(List<Instant> releases, List<Reservation> reservations, int limit,
			Duration window, Instant now, String domain) {
		List<Instant> inWindow = reservations.stream()
				.filter(item -> item.startedAt().isAfter(now.minus(window)))
				.filter(item -> domain == null || domain.equalsIgnoreCase(item.domain()))
				.map(Reservation::startedAt).sorted().toList();
		if (inWindow.size() >= limit) releases.add(inWindow.get(inWindow.size() - limit).plus(window));
	}

	public record Reservation(Instant startedAt, String domain) { }
}

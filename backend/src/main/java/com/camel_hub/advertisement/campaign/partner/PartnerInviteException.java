package com.camel_hub.advertisement.campaign.partner;

/** Deliberately carries no remote response, URL, bearer, mailbox, or cause. */
public final class PartnerInviteException extends RuntimeException {
    public enum Reason { DISABLED, HELD, EXPIRED, CLAIMED, INVALID_INPUT, UNAVAILABLE }

    private final Reason reason;

    public PartnerInviteException(Reason reason) {
        super("PARTNER_INVITE_" + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public String reasonCode() { return reason.name(); }
}

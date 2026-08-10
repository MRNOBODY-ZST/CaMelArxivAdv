package com.camel_hub.advertisement.identity.security;

public final class Permission {

	public static final String USER_READ = "user:read";
	public static final String USER_CREATE = "user:create";
	public static final String USER_UPDATE = "user:update";
	public static final String USER_DISABLE = "user:disable";
	public static final String ROLE_READ = "role:read";
	public static final String ROLE_MANAGE = "role:manage";
	public static final String PAPER_READ = "paper:read";
	public static final String PAPER_IMPORT = "paper:import";
	public static final String PAPER_DELETE = "paper:delete";
	public static final String CONTACT_READ_MASKED = "contact:read_masked";
	public static final String CONTACT_READ_FULL = "contact:read_full";
	public static final String CONTACT_VERIFY = "contact:verify";
	public static final String CONTACT_EXPORT = "contact:export";
	public static final String JOB_MANAGE = "job:manage";
	public static final String TEMPLATE_READ = "template:read";
	public static final String TEMPLATE_MANAGE = "template:manage";
	public static final String SMTP_READ = "smtp:read";
	public static final String SMTP_MANAGE = "smtp:manage";
	public static final String CAMPAIGN_READ = "campaign:read";
	public static final String CAMPAIGN_CREATE = "campaign:create";
	public static final String CAMPAIGN_APPROVE = "campaign:approve";
	public static final String CAMPAIGN_SEND = "campaign:send";
	public static final String CAMPAIGN_PAUSE = "campaign:pause";
	public static final String ANALYTICS_READ = "analytics:read";
	public static final String AUDIT_READ = "audit:read";
	public static final String SYSTEM_MANAGE = "system:manage";

	private Permission() {
	}
}

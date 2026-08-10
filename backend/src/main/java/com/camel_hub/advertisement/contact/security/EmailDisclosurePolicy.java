package com.camel_hub.advertisement.contact.security;

import com.camel_hub.advertisement.identity.security.Permission;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public final class EmailDisclosurePolicy {

	public String disclose(String email, Set<String> permissions) {
		if (permissions.contains(Permission.CONTACT_READ_FULL)) {
			return email;
		}
		if (!permissions.contains(Permission.CONTACT_READ_MASKED)) {
			throw new AccessDeniedException("Contact email access is not permitted");
		}
		int separator = email.lastIndexOf('@');
		if (separator <= 0 || separator == email.length() - 1) {
			return "***";
		}
		String localPart = email.substring(0, separator);
		String visible = localPart.substring(0, Math.min(2, localPart.length()));
		return visible + "***" + email.substring(separator);
	}
}

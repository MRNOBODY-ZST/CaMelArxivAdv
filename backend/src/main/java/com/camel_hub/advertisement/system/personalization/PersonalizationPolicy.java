package com.camel_hub.advertisement.system.personalization;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
final class PersonalizationPolicy {
    static String origin(String value) {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || !host.contains(".") && !host.contains(":")) throw new IllegalArgumentException();
            if (host.matches("[0-9.]+") || host.contains(":")) {
                InetAddress address = InetAddress.getByName(host);
                if (!publicAddress(address)) throw new IllegalArgumentException();
                if (host.contains(":")) host = address instanceof java.net.Inet6Address
                        ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
            }
            String path = uri.getRawPath().replaceAll("/+$", "");
            if (path.isEmpty()) path = "/v1";
            if (path.contains("..") || path.contains("%") || path.contains("\\")) throw new IllegalArgumentException();
            return "https://" + host + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort()) + path;
        } catch (Exception ignored) { throw new IllegalArgumentException("API origin must be a public HTTPS endpoint"); }
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress(); int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) return !(a == 0 || a >= 224 || a == 100 && c >= 64 && c <= 127 || a == 169 && c == 254 || a == 192 && (c == 0 || c == 168 || c == 88 && (b[2] & 255) == 99) || a == 198 && (c == 18 || c == 19 || c == 51) || a == 203 && c == 0);
        // Only globally routable unicast, excluding protocol assignments, documentation and transition ranges.
        return (a & 0xe0) == 0x20 && !(a == 0x20 && c == 1 && ((b[2] & 255) < 0x20 || (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)) && !(a == 0x20 && c == 2) && !(a == 0x3f && c == 0xff && (b[2] & 0xf0) == 0);
    }
}

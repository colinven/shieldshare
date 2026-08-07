package net.shieldshare.shieldshare.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Turns a request into a key that a rate limit bucket is keyed on.
 */
@Component
public class ClientIpResolver {

    static final String UNRESOLVABLE_KEY = "unresolvable";

    public String resolve(HttpServletRequest request) {
        return normalize(extractRawIp(request));
    }

    /**
     * IPv4 addresses get keyed on the full address. IPv6 addresses get keyed on the /64 prefix,
     * because the low 64 bits are picked by the host rather assigned, and are rotated on
     * most modern OS.
     */
    private String normalize(String rawIp) {
        if (!isIpLiteral(rawIp)) {
            return UNRESOLVABLE_KEY;
        }
        try {
            InetAddress address = InetAddress.getByName(rawIp);
            if (!(address instanceof Inet6Address)) {
                return address.getHostAddress();
            }
            byte[] prefix = address.getAddress();
            Arrays.fill(prefix, 8, 16, (byte) 0);
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            return UNRESOLVABLE_KEY;
        }
    }

    /**
     * Currently, the app is going to be deployed on Railway with no CDN, so the IP
     * that Spring Boot hands us is the one we use. However, if in the future we decide
     * to add Cloudflare, we'll change this method to read the IP from the
     * CF-Connecting-IP header.
    */
    private String extractRawIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Guards {@link InetAddress#getByName(String)} from seeing a host name and issuing a DNS lookup.
     */
    private boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == '.' || c == ':');
    }
}

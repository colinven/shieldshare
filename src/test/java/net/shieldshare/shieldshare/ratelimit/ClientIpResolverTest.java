package net.shieldshare.shieldshare.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    private String resolve(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return resolver.resolve(request);
    }

    @Test
    void keepsAnIpv4AddressWhole() {
        assertThat(resolve("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void givesDistinctIpv4AddressesDistinctKeys() {
        assertThat(resolve("203.0.113.7")).isNotEqualTo(resolve("203.0.113.8"));
    }

    /*
     * The whole point of the /64 collapse. A host rotates the low 64 bits of its address for free,
     * so two addresses that differ only there must land in the same bucket.
     */
    @Test
    void collapsesTwoAddressesInTheSameSlashSixtyFourOntoOneKey() {
        String first = resolve("2001:db8:1234:5678:a00c:42ff:fe00:9e1f");
        String second = resolve("2001:db8:1234:5678:ffff:ffff:ffff:ffff");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void separatesAddressesInDifferentSlashSixtyFours() {
        String first = resolve("2001:db8:1234:5678::1");
        String second = resolve("2001:db8:1234:9999::1");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void marksTheCollapsedKeyAsAPrefix() {
        assertThat(resolve("2001:db8:1234:5678::1")).endsWith("/64");
    }

    /*
     * A hostname must never reach InetAddress.getByName, or resolving a bucket key would trigger a
     * DNS lookup on the request path. Everything unparseable shares one bucket, which is the
     * strictest safe default.
     */
    @Test
    void routesUnparseableAddressesToASharedBucket() {
        assertThat(resolve("evil.example.com")).isEqualTo(resolve("not an address"));
        assertThat(resolve("evil.example.com")).isEqualTo("unresolvable");
    }

    @Test
    void routesAMissingRemoteAddressToTheSharedBucket() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);
        assertThat(resolver.resolve(request)).isEqualTo("unresolvable");
    }
}

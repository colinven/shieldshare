package net.shieldshare.shieldshare.dto.response;

import net.shieldshare.shieldshare.config.AppProperties;

import java.util.List;

/**
 * @param maxContentBytes plaintext cap, so the client can reject an oversized secret before sending
 * @param ttlOptionsSeconds the only TTL values a client is allowed to request
 */
public record ClientConfigResponse(long maxContentBytes, List<Integer> ttlOptionsSeconds) {

    /**
     * Picks the client-facing values out of the full config tree. Kept here rather than in the
     * controller so the choice of what is public lives next to the shape it produces.
     */
    public static ClientConfigResponse from(AppProperties props) {
        return new ClientConfigResponse(props.sizeCaps().maxContentBytes(), props.ttlOptionsSeconds());
    }
}

package net.shieldshare.shieldshare.filter.payloadsize;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.IOException;

public class SizeLimitedServletRequest extends HttpServletRequestWrapper {
    private final ServletInputStream limitedInputStream;

    protected SizeLimitedServletRequest(HttpServletRequest request, final long maxBytes) throws IOException {
        super(request);
        this.limitedInputStream = new SizeLimitedServletInputStream(request.getInputStream(), maxBytes);
    }

    @Override
    public ServletInputStream getInputStream() {
        return limitedInputStream;
    }
}

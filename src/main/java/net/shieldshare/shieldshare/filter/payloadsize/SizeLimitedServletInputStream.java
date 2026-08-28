package net.shieldshare.shieldshare.filter.payloadsize;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import lombok.NonNull;
import net.shieldshare.shieldshare.exception.OversizedPayloadException;

import java.io.IOException;

public class SizeLimitedServletInputStream extends ServletInputStream {
    private final ServletInputStream wrapped;
    private final long max;
    private long count;

    protected SizeLimitedServletInputStream(ServletInputStream wrapped, long max) {
        this.wrapped = wrapped;
        this.max = max;
    }

    @Override
    public int read() throws IOException {
        int b = wrapped.read();
        if (b != -1 && ++count > max) throw new OversizedPayloadException("Request exceeds size limit");
        return b;
    }

    @Override
    public int read(byte @NonNull [] b, int off, int len) throws IOException {
        int n = wrapped.read(b, off, len);
        if (n > 0 && (count += n) > max) throw new OversizedPayloadException("Request exceeds size limit");
        return n;
    }

    @Override
    public boolean isFinished() {
        return wrapped.isFinished();
    }

    @Override
    public boolean isReady() {
        return wrapped.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
        wrapped.setReadListener(listener);
    }
}

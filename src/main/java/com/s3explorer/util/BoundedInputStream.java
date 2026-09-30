package com.s3explorer.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Limits reads to a fixed number of bytes; used to stream one multipart part from a file. */
public final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    public BoundedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    /** Opens {@code length} bytes of {@code file} starting at {@code offset}. */
    public static InputStream openRange(Path file, long offset, long length) {
        try {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            channel.position(offset);
            return new BoundedInputStream(Channels.newInputStream(channel), length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = super.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int n = super.read(b, off, (int) Math.min(len, remaining));
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(Math.min(n, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}

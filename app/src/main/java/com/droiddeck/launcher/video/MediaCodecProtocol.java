package com.droiddeck.launcher.video;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public final class MediaCodecProtocol {
    public static final int MAGIC = 0x44444d43;
    public static final int VERSION = 1;
    public static final int MAX_AU = 16 * 1024 * 1024;
    public static final int MAX_PENDING = 32;
    public static final int UNSUPPORTED = 1;
    public static final int REFERENCE_LOST = 2;
    public static final int PROTOCOL = 3;
    public static final int INTERNAL = 4;

    public interface IoDeadline {
        void beforeIo(long absoluteNanos) throws IOException;
    }

    public interface Factory {
        Capability probe() throws Failure;
        Codec open(Format format) throws Failure;
    }

    public interface Codec {
        Capability capability();
        boolean submit(long timestamp, boolean keyframe, byte[] bytes, int offset, int length) throws Failure;
        Batch poll() throws Failure;
        boolean drain() throws Failure;
        void close() throws Failure;
    }

    public static final class Capability {
        public final String name;
        public final boolean hardware;
        public final boolean software;

        public Capability(String name, boolean hardware, boolean software) {
            this.name = name;
            this.hardware = hardware;
            this.software = software;
        }
    }

    public static final class Format {
        public final int width;
        public final int height;
        public final int range;

        public Format(int width, int height, int range) throws Failure {
            if (width <= 0 || height <= 0 || width > 2560 || height > 1440
                    || ((width | height) & 1) != 0 || range < 0 || range > 1) {
                throw new Failure(UNSUPPORTED, "v1 requires even H264 NV12 SDR dimensions up to 2560x1440");
            }
            this.width = width;
            this.height = height;
            this.range = range;
        }
    }

    public static final class Frame {
        public final long timestamp;
        public final byte[] y;
        public final byte[] uv;

        public Frame(long timestamp, byte[] y, byte[] uv) {
            this.timestamp = timestamp;
            this.y = y;
            this.uv = uv;
        }
    }

    public static final class Batch {
        public final List<Frame> frames;
        public final boolean eos;

        public Batch(List<Frame> frames, boolean eos) {
            this.frames = frames;
            this.eos = eos;
        }
    }

    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public final int code;

        public Failure(int code, String reason) {
            super(reason);
            this.code = code;
        }
    }

    private final Factory factory;
    private final LongSupplier clock;
    private final Map<Long, Long> pending = new HashMap<>();
    private Codec codec;
    private Format format;
    private boolean opened;
    private boolean closed;
    private boolean failed;
    private boolean firstInput = true;
    private boolean draining;
    private boolean eosQueued;
    private boolean drained;
    private long drainStarted;

    public MediaCodecProtocol(Factory factory) {
        this(factory, System::nanoTime);
    }

    public MediaCodecProtocol(Factory factory, LongSupplier clock) {
        this.factory = factory;
        this.clock = clock;
    }

    public void serve(InputStream input, OutputStream output, IoDeadline io) throws IOException {
        try {
            while (true) {
                long started = clock.getAsLong();
                long deadline = started + 1_000_000_000L;
                byte[] header = new byte[16];
                if (!read(input, header, 0, header.length, deadline, io, true)) return;
                ByteBuffer h = ByteBuffer.wrap(header);
                int magic = h.getInt();
                int version = h.getInt();
                int kind = h.getInt();
                int length = h.getInt();
                if (kind == 1 || kind == 2) deadline = started + 2_000_000_000L;
                if (magic != MAGIC || version != VERSION || !validLength(kind, length)) {
                    error(output, new Failure(PROTOCOL, "invalid DDMC header or payload length"), deadline, io);
                    return;
                }
                byte[] payload = new byte[length];
                read(input, payload, 0, length, deadline, io, false);
                prepare(deadline, io);
                try {
                    dispatch(kind, payload, output, deadline, io);
                } catch (Failure failure) {
                    failed = true;
                    error(output, failure, deadline, io);
                    if (failure.code != REFERENCE_LOST) return;
                }
            }
        } finally {
            release();
        }
    }

    private static boolean validLength(int kind, int length) {
        if (kind == 2) return length == 12;
        if (kind == 3) return length >= 13 && length <= MAX_AU + 12;
        return kind >= 1 && kind <= 6 && length == 0;
    }

    static void requireColor(int actual, int expected, String key) throws Failure {
        if (actual != 0 && actual != expected) {
            throw new Failure(UNSUPPORTED, "hardware output contradicts negotiated " + key);
        }
    }

    private void dispatch(int kind, byte[] bytes, OutputStream output, long deadline, IoDeadline io)
            throws Failure, IOException {
        if (kind == 6) {
            release();
            closed = true;
            writeHeader(output, 104, 0, deadline, io);
            return;
        }
        if (closed || failed) throw new Failure(PROTOCOL, "connection accepts CLOSE only after close or failure");
        if (kind == 1) {
            capability(output, 101, factory.probe(), deadline, io);
            return;
        }
        if (kind == 2) {
            if (opened) throw new Failure(PROTOCOL, "one decoder lifetime per connection");
            ByteBuffer b = ByteBuffer.wrap(bytes);
            format = new Format(b.getInt(), b.getInt(), b.getInt());
            codec = factory.open(format);
            opened = true;
            capability(output, 102, codec.capability(), deadline, io);
            return;
        }
        if (!opened) throw new Failure(PROTOCOL, "OPEN required before codec operation");
        if (kind == 3 && draining) throw new Failure(PROTOCOL, "SUBMIT after DRAIN");
        if (kind == 5 && !draining) {
            draining = true;
            drainStarted = clock.getAsLong();
        }
        if (draining && !drained && clock.getAsLong() - drainStarted >= 500_000_000L) {
            throw new Failure(REFERENCE_LOST, "codec EOS drain exceeded 500 ms");
        }
        if (draining && !drained) prepare(Math.min(deadline, drainStarted + 500_000_000L), io);
        checkProgress();
        List<Frame> frames = new ArrayList<>(2);
        Batch batch = drained ? new Batch(frames, true) : codec.poll();
        retire(batch, frames);
        drained = batch.eos;
        int status = batch.eos ? 1 : 0;
        if (kind == 3) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            long timestamp = b.getLong();
            int flags = b.getInt();
            if (timestamp < 0 || (flags & ~1) != 0 || pending.containsKey(timestamp)) {
                throw new Failure(PROTOCOL, "invalid flags, timestamp or duplicate pending PTS");
            }
            if (firstInput && ((flags & 1) == 0 || !hasInitialParameterSets(bytes, 12))) {
                throw new Failure(REFERENCE_LOST, "first H264 keyframe must include in-band SPS, PPS and IDR");
            }
            if (pending.size() >= MAX_PENDING) throw new Failure(REFERENCE_LOST, "codec pending PTS limit reached");
            if (codec.submit(timestamp, (flags & 1) != 0, bytes, 12, bytes.length - 12)) {
                pending.put(timestamp, clock.getAsLong());
                firstInput = false;
            } else {
                status = 2;
            }
        }
        if (draining && !eosQueued) eosQueued = codec.drain();
        if (draining && status != 1 && clock.getAsLong() - drainStarted >= 500_000_000L) {
            throw new Failure(REFERENCE_LOST, "codec EOS drain exceeded 500 ms");
        }
        if (status == 1 && !pending.isEmpty()) throw new Failure(REFERENCE_LOST, "EOS left unmatched pending timestamps");
        result(output, status, frames, deadline, io);
    }

    private void retire(Batch batch, List<Frame> frames) throws Failure {
        if (batch.frames.size() > 2) throw new Failure(INTERNAL, "codec exceeded reply frame limit");
        for (Frame frame : batch.frames) {
            if (frame.y.length != format.width * format.height
                    || frame.uv.length != format.width * format.height / 2) {
                throw new Failure(UNSUPPORTED, "codec changed visible frame dimensions");
            }
            if (pending.remove(frame.timestamp) == null) throw new Failure(REFERENCE_LOST, "unmatched or duplicate output PTS");
            frames.add(frame);
        }
        if (batch.eos && !draining) throw new Failure(REFERENCE_LOST, "unexpected decoder EOS");
    }

    private void checkProgress() throws Failure {
        long now = clock.getAsLong();
        for (long started : pending.values()) {
            if (now - started >= 2_000_000_000L) throw new Failure(REFERENCE_LOST, "codec input unretired for two seconds");
        }
    }

    private void release() throws IOException {
        if (codec == null) return;
        try {
            codec.close();
            codec = null;
            pending.clear();
        } catch (Failure failure) {
            throw new IOException("hardware codec release failed", failure);
        }
    }

    private void capability(OutputStream output, int kind, Capability capability, long deadline, IoDeadline io)
            throws Failure, IOException {
        byte[] name = capability.name.getBytes(StandardCharsets.UTF_8);
        if (!capability.hardware || capability.software || name.length < 1 || name.length > 256) {
            throw new Failure(UNSUPPORTED, "only explicitly hardware accelerated codecs are allowed");
        }
        byte[] payload = ByteBuffer.allocate(12 + name.length).putInt(1).putInt(0).putInt(name.length).put(name).array();
        writeHeader(output, kind, payload.length, deadline, io);
        write(output, payload, deadline, io);
    }

    private void result(OutputStream output, int status, List<Frame> frames, long deadline, IoDeadline io) throws IOException {
        int length = 8;
        for (Frame frame : frames) length += 16 + frame.y.length + frame.uv.length;
        writeHeader(output, 103, length, deadline, io);
        write(output, ByteBuffer.allocate(8).putInt(status).putInt(frames.size()).array(), deadline, io);
        for (Frame frame : frames) {
            write(output, ByteBuffer.allocate(16).putLong(frame.timestamp).putInt(frame.y.length).putInt(frame.uv.length).array(), deadline, io);
            write(output, frame.y, deadline, io);
            write(output, frame.uv, deadline, io);
        }
    }

    private void error(OutputStream output, Failure failure, long deadline, IoDeadline io) throws IOException {
        String reason = failure.getMessage() == null ? "bridge failure" : failure.getMessage();
        if (reason.length() > 256) reason = reason.substring(0, 256);
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        writeHeader(output, 199, 4 + bytes.length, deadline, io);
        write(output, ByteBuffer.allocate(4).putInt(failure.code).array(), deadline, io);
        write(output, bytes, deadline, io);
    }

    private void writeHeader(OutputStream output, int kind, int length, long deadline, IoDeadline io) throws IOException {
        write(output, ByteBuffer.allocate(16).putInt(MAGIC).putInt(VERSION).putInt(kind).putInt(length).array(), deadline, io);
    }

    private boolean read(InputStream input, byte[] bytes, int offset, int length, long deadline,
                         IoDeadline io, boolean eofAllowed) throws IOException {
        int read = 0;
        while (read < length) {
            prepare(deadline, io);
            int count = input.read(bytes, offset + read, Math.min(length - read, 65536));
            if (count < 0) {
                if (read == 0 && eofAllowed) return false;
                throw new EOFException("partial DDMC record");
            }
            if (count == 0) continue;
            read += count;
        }
        return true;
    }

    private void write(OutputStream output, byte[] bytes, long deadline, IoDeadline io) throws IOException {
        for (int offset = 0; offset < bytes.length; offset += 65536) {
            prepare(deadline, io);
            output.write(bytes, offset, Math.min(bytes.length - offset, 65536));
        }
    }

    private void prepare(long deadline, IoDeadline io) throws IOException {
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) throw new SocketTimeoutException("DDMC exchange deadline exceeded");
        io.beforeIo(deadline);
    }

    static boolean hasInitialParameterSets(byte[] bytes, int offset) {
        boolean sps = false;
        boolean pps = false;
        boolean idr = false;
        for (int i = offset; i + 3 < bytes.length; i++) {
            if (bytes[i] != 0 || bytes[i + 1] != 0) continue;
            int nal = -1;
            if (bytes[i + 2] == 1) nal = i + 3;
            else if (i + 4 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1) nal = i + 4;
            if (nal < 0) continue;
            int type = bytes[nal] & 31;
            if (type == 7) sps = true;
            if (type == 8) pps = true;
            if (type == 5) idr = true;
        }
        return sps && pps && idr;
    }
}

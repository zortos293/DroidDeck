package com.droiddeck.launcher.video;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class MediaCodecFakeServer {
    public static final class FakeFactory implements MediaCodecProtocol.Factory {
        public int closes;
        public int active;
        public int maxActive;

        @Override public MediaCodecProtocol.Capability probe() {
            return new MediaCodecProtocol.Capability("fake.hardware.avc", true, false);
        }

        @Override public MediaCodecProtocol.Codec open(MediaCodecProtocol.Format format) throws MediaCodecProtocol.Failure {
            if (active != 0) throw new MediaCodecProtocol.Failure(MediaCodecProtocol.REFERENCE_LOST, "fake hardware still allocated");
            active++;
            maxActive = Math.max(maxActive, active);
            return new MediaCodecProtocol.Codec() {
                private final ArrayDeque<MediaCodecProtocol.Frame> pending = new ArrayDeque<>();
                private boolean draining;
                private boolean closed;

                @Override public MediaCodecProtocol.Capability capability() { return probe(); }

                @Override public boolean submit(long timestamp, boolean keyframe, byte[] bytes, int offset, int length) {
                    byte[] y = new byte[format.width * format.height];
                    byte[] uv = new byte[y.length / 2];
                    for (int i = 0; i < y.length; i++) y[i] = (byte) (timestamp + i);
                    for (int i = 0; i < uv.length; i++) uv[i] = (byte) (128 + i);
                    pending.add(new MediaCodecProtocol.Frame(timestamp, y, uv));
                    return true;
                }

                @Override public MediaCodecProtocol.Batch poll() {
                    List<MediaCodecProtocol.Frame> frames = new ArrayList<>();
                    while (!pending.isEmpty() && frames.size() < 2) frames.add(pending.remove());
                    return new MediaCodecProtocol.Batch(frames, draining && pending.isEmpty());
                }

                @Override public boolean drain() { draining = true; return true; }
                @Override public void close() {
                    if (closed) return;
                    closed = true;
                    closes++;
                    active--;
                    pending.clear();
                    System.out.println("RELEASED active=" + active + " max_active=" + maxActive + " closes=" + closes);
                    System.out.flush();
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        Files.deleteIfExists(path);
        FakeFactory factory = new FakeFactory();
        int connections = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        if (connections <= 0) throw new IllegalArgumentException("connection count must be positive");
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(path));
            System.out.println("READY " + path);
            System.out.flush();
            for (int connection = 0; connection < connections; connection++) {
                try (SocketChannel client = listener.accept()) {
                    new MediaCodecProtocol(factory).serve(Channels.newInputStream(client), Channels.newOutputStream(client), deadline -> {});
                } catch (java.io.IOException failure) {
                    System.err.println(failure.getMessage());
                }
            }
            System.out.println("STOPPED active=" + factory.active + " max_active=" + factory.maxActive + " closes=" + factory.closes);
        } finally {
            Files.deleteIfExists(path);
        }
    }
}

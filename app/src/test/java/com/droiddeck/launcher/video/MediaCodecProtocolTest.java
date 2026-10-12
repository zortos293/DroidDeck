package com.droiddeck.launcher.video;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class MediaCodecProtocolTest {
    private static final byte[] KEYFRAME = {0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3};

    private static class Fake implements MediaCodecProtocol.Factory, MediaCodecProtocol.Codec {
        final ArrayDeque<MediaCodecProtocol.Frame> pictures = new ArrayDeque<>();
        int opens;
        int closes;
        int submissions;
        int drains;
        boolean hold;
        boolean busy;
        boolean draining;
        boolean software;
        boolean releaseFails;
        boolean unmatched;
        boolean wrongSize;

        @Override public MediaCodecProtocol.Capability probe() {
            return new MediaCodecProtocol.Capability("fake.hardware.avc", !software, software);
        }
        @Override public MediaCodecProtocol.Codec open(MediaCodecProtocol.Format format) { opens++; return this; }
        @Override public MediaCodecProtocol.Capability capability() { return probe(); }
        @Override public boolean submit(long timestamp, boolean keyframe, byte[] bytes, int offset, int length) {
            if (busy) return false;
            submissions++;
            pictures.add(new MediaCodecProtocol.Frame(unmatched ? timestamp + 1 : timestamp,
                    new byte[wrongSize ? 3 : 4], new byte[2]));
            return true;
        }
        @Override public MediaCodecProtocol.Batch poll() {
            List<MediaCodecProtocol.Frame> frames = new ArrayList<>();
            if (!hold) while (!pictures.isEmpty() && frames.size() < 2) frames.add(pictures.removeLast());
            return new MediaCodecProtocol.Batch(frames, draining && pictures.isEmpty());
        }
        @Override public boolean drain() { drains++; draining = true; hold = false; return true; }
        @Override public void close() throws MediaCodecProtocol.Failure {
            if (releaseFails) throw new MediaCodecProtocol.Failure(MediaCodecProtocol.INTERNAL, "release failed");
            closes++;
        }
    }

    private static byte[] request(int kind, byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MediaCodecProtocol.MAGIC);
        data.writeInt(1);
        data.writeInt(kind);
        data.writeInt(bytes.length);
        data.write(bytes);
        return output.toByteArray();
    }

    private static byte[] empty(int kind) throws IOException { return request(kind, new byte[0]); }
    private static byte[] open() throws IOException { return request(2, ByteBuffer.allocate(12).putInt(2).putInt(2).putInt(0).array()); }
    private static byte[] submit(long pts) throws IOException {
        return request(3, ByteBuffer.allocate(12 + KEYFRAME.length).putLong(pts).putInt(1).put(KEYFRAME).array());
    }

    private static byte[] join(byte[]... requests) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] request : requests) all.write(request);
        return all.toByteArray();
    }

    private static List<byte[]> responses(byte[] output) throws IOException {
        List<byte[]> result = new ArrayList<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(output));
        while (in.available() > 0) {
            assertEquals(MediaCodecProtocol.MAGIC, in.readInt());
            assertEquals(1, in.readInt());
            int kind = in.readInt();
            int length = in.readInt();
            byte[] payload = new byte[length + 4];
            ByteBuffer.wrap(payload).putInt(kind);
            in.readFully(payload, 4, length);
            result.add(payload);
        }
        return result;
    }

    private static List<byte[]> run(Fake codec, byte[]... input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MediaCodecProtocol(codec).serve(new ByteArrayInputStream(join(input)), output, millis -> {});
        return responses(output.toByteArray());
    }

    private static int word(byte[] response, int offset) { return ByteBuffer.wrap(response).getInt(offset); }

    @Test public void probeIsStrictAndDoesNotAllocateCodec() throws Exception {
        Fake codec = new Fake();
        List<byte[]> output = run(codec, empty(1));
        assertEquals(101, word(output.get(0), 0));
        assertEquals(1, word(output.get(0), 4));
        assertEquals(0, word(output.get(0), 8));
        assertEquals(0, codec.opens);
    }

    @Test public void rejectsSoftwareAdvertisement() throws Exception {
        Fake codec = new Fake();
        codec.software = true;
        List<byte[]> output = run(codec, empty(1));
        assertEquals(199, word(output.get(0), 0));
        assertEquals(MediaCodecProtocol.UNSUPPORTED, word(output.get(0), 4));
    }

    @Test public void submitIsDelayedUntilPollAndPreservesPts() throws Exception {
        Fake codec = new Fake();
        List<byte[]> output = run(codec, open(), submit(123456789L), empty(4), empty(6));
        assertEquals(0, word(output.get(1), 8));
        assertEquals(1, word(output.get(2), 8));
        assertEquals(123456789L, ByteBuffer.wrap(output.get(2)).getLong(12));
        assertEquals(104, word(output.get(3), 0));
        assertEquals(1, codec.closes);
    }

    @Test public void busyDoesNotConsumeInput() throws Exception {
        Fake codec = new Fake();
        codec.busy = true;
        List<byte[]> output = run(codec, open(), submit(1), empty(4), empty(6));
        assertEquals(2, word(output.get(1), 4));
        assertEquals(0, codec.submissions);
        assertEquals(0, word(output.get(2), 8));
    }

    @Test public void acceptsOneByteReads() throws Exception {
        Fake codec = new Fake();
        byte[] input = join(open(), submit(5), empty(4), empty(6));
        FilterInputStream slow = new FilterInputStream(new ByteArrayInputStream(input)) {
            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                return super.read(bytes, off, Math.min(len, 1));
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MediaCodecProtocol(codec).serve(slow, output, millis -> {});
        assertEquals(4, responses(output.toByteArray()).size());
    }

    @Test public void partialRecordInvalidatesStreamAndReleases() throws Exception {
        Fake codec = new Fake();
        byte[] encoded = submit(3);
        assertThrows(EOFException.class, () -> new MediaCodecProtocol(codec).serve(
                new ByteArrayInputStream(join(open(), Arrays.copyOf(encoded, encoded.length - 1))),
                new ByteArrayOutputStream(), millis -> {}));
        assertEquals(1, codec.closes);
    }

    @Test public void rejectsOversizedHeaderBeforePayloadAllocation() throws Exception {
        byte[] header = ByteBuffer.allocate(16).putInt(MediaCodecProtocol.MAGIC).putInt(1).putInt(3)
                .putInt(MediaCodecProtocol.MAX_AU + 13).array();
        List<byte[]> output = run(new Fake(), header);
        assertEquals(MediaCodecProtocol.PROTOCOL, word(output.get(0), 4));
    }

    @Test public void rejectsInvalidMagicVersionKindsAndControlLengths() throws Exception {
        for (byte[] header : new byte[][]{
                ByteBuffer.allocate(16).putInt(0).putInt(1).putInt(1).putInt(0).array(),
                ByteBuffer.allocate(16).putInt(MediaCodecProtocol.MAGIC).putInt(2).putInt(1).putInt(0).array(),
                ByteBuffer.allocate(16).putInt(MediaCodecProtocol.MAGIC).putInt(1).putInt(7).putInt(0).array(),
                ByteBuffer.allocate(16).putInt(MediaCodecProtocol.MAGIC).putInt(1).putInt(4).putInt(1).array()}) {
            assertEquals(MediaCodecProtocol.PROTOCOL, word(run(new Fake(), header).get(0), 4));
        }
    }

    @Test public void rejectsPollBeforeOpenAndRepeatedOpen() throws Exception {
        assertEquals(MediaCodecProtocol.PROTOCOL, word(run(new Fake(), empty(4)).get(0), 4));
        assertEquals(MediaCodecProtocol.PROTOCOL, word(run(new Fake(), open(), open()).get(1), 4));
    }

    @Test public void rejectsUnsupportedDimensionsAndRange() throws Exception {
        for (int[] values : new int[][]{{0, 2, 0}, {3, 2, 0}, {2562, 1440, 0}, {2, 1442, 0}, {2, 2, 2}}) {
            byte[] format = ByteBuffer.allocate(12).putInt(values[0]).putInt(values[1]).putInt(values[2]).array();
            assertEquals(MediaCodecProtocol.UNSUPPORTED, word(run(new Fake(), request(2, format)).get(0), 4));
        }
    }

    @Test public void refusesDuplicatePendingPtsAndSignedOverflow() throws Exception {
        Fake codec = new Fake();
        codec.hold = true;
        assertEquals(MediaCodecProtocol.PROTOCOL, word(run(codec, open(), submit(1), submit(1)).get(2), 4));
        assertEquals(MediaCodecProtocol.PROTOCOL, word(run(new Fake(), open(), submit(-1)).get(1), 4));
    }

    @Test public void requiresInBandParameterSetsAndIdr() throws Exception {
        byte[] delta = request(3, ByteBuffer.allocate(16).putLong(0).putInt(0).putInt(0x00000141).array());
        List<byte[]> output = run(new Fake(), open(), delta, empty(6));
        assertEquals(MediaCodecProtocol.REFERENCE_LOST, word(output.get(1), 4));
        assertEquals(104, word(output.get(2), 0));
    }

    @Test public void rejectsUnmatchedOutputButAllowsCheckedClose() throws Exception {
        Fake codec = new Fake();
        codec.unmatched = true;
        List<byte[]> output = run(codec, open(), submit(1), empty(4), empty(6));
        assertEquals(MediaCodecProtocol.REFERENCE_LOST, word(output.get(2), 4));
        assertEquals(104, word(output.get(3), 0));
    }

    @Test public void rejectsChangedPixelSize() throws Exception {
        Fake codec = new Fake();
        codec.wrongSize = true;
        assertEquals(MediaCodecProtocol.UNSUPPORTED, word(run(codec, open(), submit(1), empty(4)).get(2), 4));
    }

    @Test public void retiresOutOfOrderPtsAndBoundsReplyToTwoFrames() throws Exception {
        Fake codec = new Fake();
        codec.hold = true;
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        MediaCodecProtocol protocol = new MediaCodecProtocol(codec);
        byte[] bytes = join(open(), submit(10), submit(20), empty(5), empty(4), empty(6));
        protocol.serve(new ByteArrayInputStream(bytes), response, millis -> {});
        List<byte[]> output = responses(response.toByteArray());
        assertEquals(2, word(output.get(4), 8));
        assertEquals(20L, ByteBuffer.wrap(output.get(4)).getLong(12));
        assertEquals(10L, ByteBuffer.wrap(output.get(4)).getLong(34));
        assertEquals(1, word(output.get(4), 4));
        assertEquals(1, codec.drains);
    }

    @Test public void rejectsPendingLedgerOverflow() throws Exception {
        Fake codec = new Fake();
        codec.hold = true;
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write(open());
        for (int i = 0; i < 33; i++) input.write(submit(i));
        input.write(empty(6));
        List<byte[]> output = run(codec, input.toByteArray());
        assertEquals(MediaCodecProtocol.REFERENCE_LOST, word(output.get(33), 4));
        assertEquals(32, codec.submissions);
        assertEquals(104, word(output.get(34), 0));
    }

    @Test public void rejectsSubmitAfterDrain() throws Exception {
        List<byte[]> output = run(new Fake(), open(), empty(5), submit(1));
        assertEquals(MediaCodecProtocol.PROTOCOL, word(output.get(2), 4));
    }

    @Test public void closeIsIdempotentAndNoNewDecoderCanOpen() throws Exception {
        Fake codec = new Fake();
        List<byte[]> output = run(codec, open(), empty(6), empty(6), open());
        assertEquals(104, word(output.get(1), 0));
        assertEquals(104, word(output.get(2), 0));
        assertEquals(MediaCodecProtocol.PROTOCOL, word(output.get(3), 4));
        assertEquals(1, codec.closes);
        assertEquals(1, codec.opens);
    }

    @Test public void releaseFailureNeverSendsCloseAcknowledgement() throws Exception {
        Fake codec = new Fake();
        codec.releaseFails = true;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> new MediaCodecProtocol(codec).serve(
                new ByteArrayInputStream(join(open(), empty(6))), output, millis -> {}));
        assertEquals(1, responses(output.toByteArray()).size());
    }

    @Test public void absoluteDeadlineDoesNotResetForPartialIo() throws Exception {
        AtomicLong clock = new AtomicLong();
        ByteArrayInputStream input = new ByteArrayInputStream(empty(1)) {
            @Override public synchronized int read(byte[] bytes, int off, int len) {
                clock.addAndGet(400_000_000L);
                return super.read(bytes, off, Math.min(len, 1));
            }
        };
        assertThrows(SocketTimeoutException.class, () -> new MediaCodecProtocol(new Fake(), clock::get)
                .serve(input, new ByteArrayOutputStream(), millis -> {}));
    }

    @Test public void unspecifiedColorRetainsContractButContradictionsFail() throws Exception {
        MediaCodecProtocol.requireColor(0, 2, "range");
        MediaCodecProtocol.requireColor(2, 2, "range");
        MediaCodecProtocol.Failure failure = assertThrows(MediaCodecProtocol.Failure.class,
                () -> MediaCodecProtocol.requireColor(1, 2, "range"));
        assertEquals(MediaCodecProtocol.UNSUPPORTED, failure.code);
    }

    @Test public void oldUnretiredInputIsReferenceLossAndCanClose() throws Exception {
        AtomicLong clock = new AtomicLong();
        Fake codec = new Fake();
        codec.hold = true;
        int firstSubmitEnd = 16 + 12 + "fake.hardware.avc".length() + 24;
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                if (size() == firstSubmitEnd) clock.set(2_000_000_000L);
            }
        };
        new MediaCodecProtocol(codec, clock::get).serve(new ByteArrayInputStream(join(open(), submit(1), empty(4), empty(6))),
                output, millis -> {});
        List<byte[]> replies = responses(output.toByteArray());
        assertEquals(MediaCodecProtocol.REFERENCE_LOST, word(replies.get(2), 4));
        assertEquals(104, word(replies.get(3), 0));
    }

    @Test public void drainDeadlineIsTotalAcrossPolls() throws Exception {
        AtomicLong clock = new AtomicLong();
        Fake codec = new Fake() {
            @Override public boolean drain() { drains++; return false; }
        };
        int firstDrainEnd = 16 + 12 + "fake.hardware.avc".length() + 24;
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                if (size() == firstDrainEnd) clock.set(500_000_000L);
            }
        };
        new MediaCodecProtocol(codec, clock::get).serve(new ByteArrayInputStream(join(open(), empty(5), empty(4), empty(6))),
                output, millis -> {});
        List<byte[]> replies = responses(output.toByteArray());
        assertEquals(MediaCodecProtocol.REFERENCE_LOST, word(replies.get(2), 4));
        assertEquals(104, word(replies.get(3), 0));
    }

    @Test public void closeResponseIsWrittenAfterCodecRelease() throws Exception {
        Fake codec = new Fake();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                if (length == 16 && ByteBuffer.wrap(bytes, offset, length).getInt(offset + 8) == 104) {
                    assertEquals(1, codec.closes);
                }
                super.write(bytes, offset, length);
            }
        };
        new MediaCodecProtocol(codec).serve(new ByteArrayInputStream(join(open(), empty(6))), output, millis -> {});
        assertEquals(104, word(responses(output.toByteArray()).get(1), 0));
    }

    @Test public void emptyProbeAndCloseArmDeadlineBeforeNativeOperation() throws Exception {
        AtomicLong armed = new AtomicLong();
        Fake codec = new Fake() {
            @Override public MediaCodecProtocol.Capability probe() {
                assertEquals(2_000_000_000L, armed.get());
                return super.probe();
            }

            @Override public void close() throws MediaCodecProtocol.Failure {
                assertEquals(1_000_000_000L, armed.get());
                super.close();
            }
        };
        new MediaCodecProtocol(codec, () -> 0L).serve(new ByteArrayInputStream(join(empty(1), open(), empty(6))),
                new ByteArrayOutputStream(), armed::set);
        assertEquals(1, codec.closes);
    }
}

package com.droiddeck.launcher.video;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

public class Nv12PackerTest {
    @Test public void planarWithPaddingAndBufferPositions() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{99, 1, 2, 88, 3, 4});
        y.position(1);
        Nv12Packer.Pixels pixels = Nv12Packer.pack(2, 2, 0, 0,
                new Nv12Packer.Plane(y, 3, 1),
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[]{5}), 1, 1),
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[]{6}), 1, 1));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, pixels.y);
        assertArrayEquals(new byte[]{5, 6}, pixels.uv);
    }

    @Test public void aliasedNv21PlanesAreNormalizedToNv12() {
        ByteBuffer chroma = ByteBuffer.wrap(new byte[]{10, 20, 30, 40});
        ByteBuffer u = chroma.duplicate();
        u.position(1);
        Nv12Packer.Pixels pixels = Nv12Packer.pack(4, 2, 0, 0,
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[8]), 4, 1),
                new Nv12Packer.Plane(u, 4, 2),
                new Nv12Packer.Plane(chroma, 4, 2));
        assertArrayEquals(new byte[]{20, 10, 40, 30}, pixels.uv);
    }

    @Test public void croppedEvenOriginAndTruncatedTrailingPadding() {
        byte[] y = new byte[24];
        for (int i = 0; i < y.length; i++) y[i] = (byte) i;
        Nv12Packer.Pixels pixels = Nv12Packer.pack(2, 2, 2, 2,
                new Nv12Packer.Plane(ByteBuffer.wrap(y), 6, 1),
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[]{9, 8, 7, 6, 5}), 3, 1),
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}), 3, 1));
        assertArrayEquals(new byte[]{14, 15, 20, 21}, pixels.y);
        assertArrayEquals(new byte[]{5, 5}, pixels.uv);
    }

    @Test public void rejectsMissingFinalSample() {
        assertThrows(IllegalArgumentException.class, () -> Nv12Packer.pack(2, 2, 0, 0,
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[3]), 2, 1),
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[1]), 1, 1),
                new Nv12Packer.Plane(ByteBuffer.wrap(new byte[1]), 1, 1)));
    }

    @Test public void rejectsOddCropAndInvalidStrides() {
        Nv12Packer.Plane plane = new Nv12Packer.Plane(ByteBuffer.wrap(new byte[16]), 4, 1);
        assertThrows(IllegalArgumentException.class, () -> Nv12Packer.pack(2, 2, 1, 0, plane, plane, plane));
        Nv12Packer.Plane invalid = new Nv12Packer.Plane(ByteBuffer.wrap(new byte[16]), 0, 1);
        assertThrows(IllegalArgumentException.class, () -> Nv12Packer.pack(2, 2, 0, 0, invalid, plane, plane));
    }

    @Test public void rejectsOverflowingCropBeforeAllocation() {
        Nv12Packer.Plane plane = new Nv12Packer.Plane(ByteBuffer.wrap(new byte[16]), Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> Nv12Packer.pack(2, 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1,
                plane, plane, plane));
    }

    @Test public void respectsBufferLimitAndDoesNotChangePosition() {
        ByteBuffer y = ByteBuffer.wrap(new byte[8]);
        y.limit(3);
        Nv12Packer.Plane plane = new Nv12Packer.Plane(y, 2, 1);
        assertThrows(IllegalArgumentException.class, () -> Nv12Packer.pack(2, 2, 0, 0, plane, plane, plane));
        org.junit.Assert.assertEquals(0, y.position());
    }
}

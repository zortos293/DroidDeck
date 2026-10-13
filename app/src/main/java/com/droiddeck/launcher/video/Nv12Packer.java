package com.droiddeck.launcher.video;

import java.nio.ByteBuffer;

public final class Nv12Packer {
    private Nv12Packer() {}

    public static final class Plane {
        final ByteBuffer bytes;
        final int rowStride;
        final int pixelStride;

        public Plane(ByteBuffer bytes, int rowStride, int pixelStride) {
            this.bytes = bytes.duplicate();
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }
    }

    public static final class Pixels {
        public final byte[] y;
        public final byte[] uv;

        Pixels(byte[] y, byte[] uv) {
            this.y = y;
            this.uv = uv;
        }
    }

    public static Pixels pack(int width, int height, int left, int top, Plane... planes) {
        if (width <= 0 || height <= 0 || width > 2560 || height > 1440
                || ((width | height | left | top) & 1) != 0 || left < 0 || top < 0
                || planes.length != 3) {
            throw new IllegalArgumentException("invalid even YUV420 crop or plane count");
        }
        validate(planes[0], left, top, width, height);
        validate(planes[1], left / 2, top / 2, width / 2, height / 2);
        validate(planes[2], left / 2, top / 2, width / 2, height / 2);
        byte[] y = new byte[width * height];
        byte[] uv = new byte[width * height / 2];
        Plane luma = planes[0];
        for (int row = 0; row < height; row++) {
            int source = offset(luma, left, top + row);
            if (luma.pixelStride == 1) {
                ByteBuffer bytes = luma.bytes.duplicate();
                bytes.position(source);
                bytes.get(y, row * width, width);
            } else {
                for (int col = 0; col < width; col++) {
                    y[row * width + col] = luma.bytes.get(source + col * luma.pixelStride);
                }
            }
        }
        for (int row = 0; row < height / 2; row++) {
            int u = offset(planes[1], left / 2, top / 2 + row);
            int v = offset(planes[2], left / 2, top / 2 + row);
            for (int col = 0; col < width / 2; col++) {
                int destination = row * width + col * 2;
                uv[destination] = planes[1].bytes.get(u + col * planes[1].pixelStride);
                uv[destination + 1] = planes[2].bytes.get(v + col * planes[2].pixelStride);
            }
        }
        return new Pixels(y, uv);
    }

    private static void validate(Plane plane, int left, int top, int width, int height) {
        if (plane.rowStride <= 0 || plane.pixelStride <= 0) {
            throw new IllegalArgumentException("nonpositive YUV plane stride");
        }
        long rowEnd = ((long) left + width - 1) * plane.pixelStride + 1;
        long end = plane.bytes.position() + ((long) top + height - 1) * plane.rowStride + rowEnd;
        if (rowEnd > plane.rowStride || end > plane.bytes.limit()) {
            throw new IllegalArgumentException("YUV plane sample exceeds row or buffer bounds");
        }
    }

    private static int offset(Plane plane, int x, int y) {
        return (int) (plane.bytes.position() + (long) y * plane.rowStride + (long) x * plane.pixelStride);
    }
}

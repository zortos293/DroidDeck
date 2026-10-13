package com.droiddeck.launcher.video;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class AndroidMediaCodec implements MediaCodecProtocol.Factory {
    private static final Semaphore ALLOCATION = new Semaphore(1);
    private static final String MIME = "video/avc";

    @Override public MediaCodecProtocol.Capability probe() throws MediaCodecProtocol.Failure {
        return identity(select(1280, 720));
    }

    @Override public MediaCodecProtocol.Codec open(MediaCodecProtocol.Format format) throws MediaCodecProtocol.Failure {
        MediaCodecInfo info = select(format.width, format.height);
        try {
            if (!ALLOCATION.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                throw new MediaCodecProtocol.Failure(MediaCodecProtocol.REFERENCE_LOST, "hardware decoder still allocated");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MediaCodecProtocol.Failure(MediaCodecProtocol.REFERENCE_LOST, "hardware allocation wait interrupted");
        }
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createByCodecName(info.getName());
            MediaCodecProtocol.Capability actual = identity(codec.getCodecInfo());
            MediaFormat config = MediaFormat.createVideoFormat(MIME, format.width, format.height);
            config.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            config.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            config.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            config.setInteger(MediaFormat.KEY_COLOR_RANGE, range(format));
            config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MediaCodecProtocol.MAX_AU);
            codec.configure(config, null, null, 0);
            codec.start();
            Log.i("MediaCodecBridge", "decoder=" + actual.name + " hardware=true software=false copied-NV12 "
                    + format.width + "x" + format.height + " range=" + format.range);
            return new Decoder(codec, actual, format);
        } catch (IOException | RuntimeException | MediaCodecProtocol.Failure failure) {
            if (codec != null) {
                try {
                    codec.release();
                    ALLOCATION.release();
                } catch (RuntimeException releaseFailure) {
                    throw new MediaCodecProtocol.Failure(MediaCodecProtocol.INTERNAL, "hardware release failed after configure failure");
                }
            } else {
                ALLOCATION.release();
            }
            if (failure instanceof MediaCodecProtocol.Failure) throw (MediaCodecProtocol.Failure) failure;
            throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "hardware configure failed: " + failure.getClass().getSimpleName());
        }
    }

    private static MediaCodecInfo select(int width, int height) throws MediaCodecProtocol.Failure {
        if (Build.VERSION.SDK_INT < 29) {
            throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "strict hardware classification requires Android API 29+");
        }
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !info.isHardwareAccelerated() || info.isSoftwareOnly()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!MIME.equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    boolean flexible = false;
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible = true;
                    }
                    if (flexible && caps.getVideoCapabilities() != null
                            && caps.getVideoCapabilities().isSizeSupported(width, height)) return info;
                }
            }
        } catch (RuntimeException failure) {
            throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "hardware enumeration failed: " + failure.getClass().getSimpleName());
        }
        throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "no hardware H264 decoder with flexible YUV420 and requested size");
    }

    private static MediaCodecProtocol.Capability identity(MediaCodecInfo info) throws MediaCodecProtocol.Failure {
        if (Build.VERSION.SDK_INT < 29 || info.isEncoder() || !info.isHardwareAccelerated() || info.isSoftwareOnly()) {
            throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "selected codec is not strictly hardware accelerated");
        }
        return new MediaCodecProtocol.Capability(info.getName(), true, false);
    }

    private static int range(MediaCodecProtocol.Format format) {
        return format.range == 0 ? MediaFormat.COLOR_RANGE_LIMITED : MediaFormat.COLOR_RANGE_FULL;
    }

    private static final class Decoder implements MediaCodecProtocol.Codec {
        private final MediaCodec codec;
        private final MediaCodecProtocol.Capability identity;
        private final MediaCodecProtocol.Format format;
        private boolean released;
        private boolean eos;

        Decoder(MediaCodec codec, MediaCodecProtocol.Capability identity, MediaCodecProtocol.Format format) {
            this.codec = codec;
            this.identity = identity;
            this.format = format;
        }

        @Override public MediaCodecProtocol.Capability capability() { return identity; }

        @Override public boolean submit(long timestamp, boolean keyframe, byte[] bytes, int offset, int length)
                throws MediaCodecProtocol.Failure {
            try {
                int index = codec.dequeueInputBuffer(0);
                if (index < 0) return false;
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < length) {
                    throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "access unit exceeds actual hardware input buffer capacity");
                }
                buffer.clear();
                buffer.put(bytes, offset, length);
                codec.queueInputBuffer(index, 0, length, timestamp, keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                return true;
            } catch (RuntimeException failure) {
                throw codecFailure(failure);
            }
        }

        @Override public MediaCodecProtocol.Batch poll() throws MediaCodecProtocol.Failure {
            List<MediaCodecProtocol.Frame> frames = new ArrayList<>(2);
            try {
                for (int attempts = 0; attempts < 8 && frames.size() < 2 && !eos; attempts++) {
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int index = codec.dequeueOutputBuffer(info, 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        validateColor(codec.getOutputFormat());
                        continue;
                    }
                    if (index < 0) continue;
                    Image image = null;
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            validateColor(codec.getOutputFormat(index));
                            image = codec.getOutputImage(index);
                            if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
                                throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "hardware output is not readable eight-bit YUV420 Image");
                            }
                            Rect crop = image.getCropRect();
                            Image.Plane[] planes = image.getPlanes();
                            if (crop.width() != format.width || crop.height() != format.height
                                    || crop.left < 0 || crop.top < 0 || crop.right > image.getWidth()
                                    || crop.bottom > image.getHeight() || planes.length != 3) {
                                throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "hardware output crop or plane count changed");
                            }
                            Nv12Packer.Pixels pixels = Nv12Packer.pack(format.width, format.height, crop.left, crop.top,
                                    plane(planes[0]), plane(planes[1]), plane(planes[2]));
                            frames.add(new MediaCodecProtocol.Frame(info.presentationTimeUs, pixels.y, pixels.uv));
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                    } finally {
                        try {
                            if (image != null) image.close();
                        } finally {
                            codec.releaseOutputBuffer(index, false);
                        }
                    }
                }
                return new MediaCodecProtocol.Batch(frames, eos);
            } catch (IllegalArgumentException failure) {
                throw new MediaCodecProtocol.Failure(MediaCodecProtocol.UNSUPPORTED, "invalid hardware YUV image layout");
            } catch (RuntimeException failure) {
                throw codecFailure(failure);
            }
        }

        private static Nv12Packer.Plane plane(Image.Plane plane) {
            return new Nv12Packer.Plane(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride());
        }

        private void validateColor(MediaFormat output) throws MediaCodecProtocol.Failure {
            require(output, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            require(output, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            require(output, MediaFormat.KEY_COLOR_RANGE, range(format));
        }

        private static void require(MediaFormat output, String key, int expected) throws MediaCodecProtocol.Failure {
            if (output.containsKey(key)) MediaCodecProtocol.requireColor(output.getInteger(key), expected, key);
        }

        @Override public boolean drain() throws MediaCodecProtocol.Failure {
            try {
                int index = codec.dequeueInputBuffer(0);
                if (index < 0) return false;
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return true;
            } catch (RuntimeException failure) {
                throw codecFailure(failure);
            }
        }

        @Override public void close() throws MediaCodecProtocol.Failure {
            if (released) return;
            try {
                codec.release();
                released = true;
                ALLOCATION.release();
            } catch (RuntimeException failure) {
                throw new MediaCodecProtocol.Failure(MediaCodecProtocol.INTERNAL, "hardware codec release failed");
            }
        }

        private static MediaCodecProtocol.Failure codecFailure(RuntimeException failure) {
            int code = failure instanceof MediaCodec.CodecException
                    ? MediaCodecProtocol.REFERENCE_LOST : MediaCodecProtocol.INTERNAL;
            return new MediaCodecProtocol.Failure(code, "hardware codec failed: " + failure.getClass().getSimpleName());
        }
    }
}

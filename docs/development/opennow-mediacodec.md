# OpenNOW MediaCodec compatibility backend

This experiment allows OpenNOW's Qt/native Linux client inside DroidDeck to decode H.264 on an Android hardware codec. Decoded pictures are copied into NV12, transferred through an app-private Unix socket, and uploaded by OpenNOW's existing native Vulkan CPU-plane producer. Hardware decode does not mean zero-copy presentation.

The feature is off by default. It supports even dimensions up to 2560x1440, 8-bit 4:2:0 SDR, BT.709 and explicit limited or full range. It does not support HEVC, AV1, HDR, 10-bit, 4:4:4, protected content or a software MediaCodec substitute. Android API 29 or later is required for strict public hardware classification. Device throughput, readable vendor output and Odin2 Portal hardware operation still require on-device validation.

## Enable the experiment

Create the marker at the top level of Android shared storage, then start a new DroidDeck session:

```sh
adb shell touch /sdcard/droiddeck-enable-mediacodec
```

DroidDeck starts a session-owned Java listener before the guest process and exports `OPENNOW_MEDIACODEC_SOCKET` at the same app-private pathname used on Android. `LinuxRuntime` already binds the app's files directory at its host path. No new `/system` mount, executable helper or presenter window is introduced. The listener accepts only the DroidDeck UID, with at most two workers and one configured hardware codec across all component generations.

Inside the guest, explicitly select the backend for the OpenNOW launch:

```sh
OPENNOW_NATIVE_VIDEO_BACKEND=mediacodec /path/to/opennow-qt
```

Use the OpenNOW setting `Video backend: Auto`, codec H.264, 8-bit 4:2:0 and HDR off. The environment overrides Auto for this launch only. DroidDeck never sets `OPENNOW_NATIVE_VIDEO_BACKEND` globally. OpenNOW must perform a real capability PROBE; the socket variable alone does not prove hardware availability.

Remove the marker and start a new session to disable the Android bridge:

```sh
adb shell rm /sdcard/droiddeck-enable-mediacodec
```

The bridge emits the exact decoder name, hardware/software flags, dimensions and range through the `MediaCodecBridge` Android log tag. Corroborate this name with the device's codec-service diagnostics. Android hardware classification is manufacturer-provided and does not itself prove performance or hardware use.

## Lifecycle and errors

The Android connection worker owns every codec call and output Image. `MediaCodec.createByCodecName` selects the enumerated hardware name; no type-based software fallback is allowed. The first submitted keyframe must contain actual in-band H.264 SPS, PPS and IDR. Subsequent outputs retain the submitted microsecond timestamp exactly, including out-of-order output. Duplicate unresolved timestamps, unmatched output and 32 unretired inputs fail explicitly.

Each exchange has a fixed absolute deadline across chunked reads and writes. OPEN/PROBE have two seconds, ordinary exchanges and CLOSE one second, and EOS drain a total 500 ms. One component watchdog checks each connection's absolute deadline every 10 ms and closes expired sockets independently of their workers. Socket send/receive timeouts alone are insufficient because Android's native write-all loop can make repeated partial progress within one write. Watchdog scheduling adds normal OS scheduling tolerance to the cutoff and cannot cancel a blocked native codec call. No socket frames are written unsolicited. Copied output is at most two tightly packed NV12 frames per response. Images and codec output indexes are released before a response can block on its peer. A two-second unretired input is reference loss.

CLOSE is acknowledged only after actual `codec.release()` completes. During a rapid DroidDeck session restart, a new codec worker waits at most 500 ms for the previous worker to release the process-wide allocation permit. The gate stays occupied if release fails or hangs. OpenNOW must not open a replacement until its checked close succeeds. Android native codec calls may be uncancellable; stopping a session closes listener/client sockets but never waits for a codec worker on the main thread. Closing an old component removes only its unique directory, so it cannot unlink a new generation's endpoint.

DroidDeck suspend invalidates active bridge sockets and prevents new accepted connections while the guest is frozen. The listener becomes available again on resume. Socket loss can be terminal for the current OpenNOW decoder because EOF is not a release acknowledgement. Reconnect OpenNOW if it cannot recover through its checked same-backend path. This implementation does not claim automatic suspend recovery. Fullscreen, overlays and ordinary activity/PiP transitions must not invalidate the bridge.

## Protocol v1

Every integer is big-endian. Each record starts with four u32 fields: magic `0x44444d43`, version `1`, kind, payload length. One request is outstanding per connection, which can contain at most one decoder lifetime. Unknown headers, partial records and excessive lengths invalidate the connection.

| Request | Payload | Response |
| --- | --- | --- |
| 1 PROBE | Empty | 101 CAPABILITY |
| 2 OPEN | Width u32, height u32, range u32, 0 limited or 1 full | 102 OPENED |
| 3 SUBMIT | Timestamp u64, flags u32 with only bit 0 keyframe allowed, then 1..16 MiB complete Annex-B H.264 AU | 103 RESULT |
| 4 POLL | Empty | 103 RESULT |
| 5 DRAIN | Empty, queue EOS once, then repeat POLL | 103 RESULT |
| 6 CLOSE | Empty | 104 CLOSED with empty payload, after release |

CAPABILITY and OPENED contain hardware u32 `1`, software u32 `0`, UTF-8 name length u32 and 1..256 codec-name bytes. PROBE checks strict hardware identity, `video/avc`, flexible YUV420 and 1280x720 support without allocating a codec. OPEN additionally checks the requested size and actual configure/start. Both remain evidence of API acceptance, not evidence of decoded pixels or acceptable throughput.

RESULT contains status u32, frame count u32, then at most two frames. Status is `0` accepted/normal, `1` EOS drained, or `2` input busy and not consumed. Each frame contains timestamp u64, Y length u32, UV length u32, then Y and UV bytes. The lengths equal width*height and width*height/2 exactly. Input Busy can still return previously decoded output; retry only the unaccepted AU after polling.

ERROR kind 199 contains code u32 and 1..1024 UTF-8 reason bytes. Codes are 1 Unsupported, 2 ReferenceLost, 3 Protocol, 4 Internal. ReferenceLost permits a subsequent CLOSE so the client can establish the release barrier. Deterministic unsupported/protocol errors terminate the stream rather than selecting software.

`MediaCodecProtocol` owns the production framing/state machine but has no Android dependencies. Its `Factory` and `Codec` interfaces provide the pure JVM seam. `Nv12Packer` accepts planes with their actual position, limit, row stride, pixel stride and Image crop. It handles planar/aliased chroma without guessing NV12/NV21 or copying inaccessible trailing padding. Contradictory output color, changed crop/dimensions and non-eight-bit Images are explicit errors. Absent or zero-valued output color keys retain the explicitly negotiated contract; zero means unspecified, not a contradictory range/matrix/transfer. AOSP's `ColorUtils.cpp` has static assertions that unspecified range, transfer and standard all equal zero.

## Local verification

Pure production protocol and pixel tests can run with JDK 17+ and JUnit 4 without loading Android:

```sh
mkdir -p /tmp/mediacodec-tests
javac -cp "$JUNIT_JAR:$HAMCREST_JAR" -d /tmp/mediacodec-tests \
  app/src/main/java/com/droiddeck/launcher/video/MediaCodecProtocol.java \
  app/src/main/java/com/droiddeck/launcher/video/ExchangeDeadline.java \
  app/src/main/java/com/droiddeck/launcher/video/Nv12Packer.java \
  app/src/test/java/com/droiddeck/launcher/video/*.java
java -cp "/tmp/mediacodec-tests:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore \
  com.droiddeck.launcher.video.MediaCodecProtocolTest \
  com.droiddeck.launcher.video.ExchangeDeadlineTest \
  com.droiddeck.launcher.video.Nv12PackerTest
```

The deterministic JDK Unix-socket interop server uses the production handler:

```sh
java -cp /tmp/mediacodec-tests \
  com.droiddeck.launcher.video.MediaCodecFakeServer /tmp/mediacodec-test.sock 3
```

The optional last argument is the number of accepted connections before clean exit. It prints READY, RELEASED after each fake codec release, and STOPPED with active/max-active counts. Pixels are deterministic: Y byte i is `(timestamp+i)%256`, UV byte i is `(128+i)%256`. This fake never claims real hardware validation. Its JDK listener exists only in test sources.

Before shipping a hardware claim, test the actual vendor Image output, row/crop handling, sustained decode/copy/upload timing, socket reachability through proot, stop/start races, stuck release, suspend/reconnect, and Qt fullscreen/overlay behavior on Odin2 Portal. This cloud implementation test cannot substitute for those checks.

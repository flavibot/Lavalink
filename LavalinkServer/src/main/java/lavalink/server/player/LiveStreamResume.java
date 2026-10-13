package lavalink.server.player;

import io.prometheus.client.Counter;

import java.util.Arrays;

/**
 * FlaviBot fork (`lavalink.server.liveStreamResume`): the settings of the live
 * stream resume that the fork's copy of lavaplayer's PersistentHttpStream
 * performs (src/main/java/com/sedmelluq/.../tools/io/PersistentHttpStream.java).
 *
 * A live radio (Icecast, SHOUTcast, AzuraCast) is an HTTP response with no
 * Content-Length that never ends. When something on the way closes it anyway
 * (an HTTP proxy that ends every tunnel after ~2 minutes, a load balancer, a
 * server restart), upstream lavaplayer reads the end of the body as the end of
 * the track: TrackEnd "finished", or a TrackException for a chunked body cut
 * mid-chunk. The stream copy instead reopens the same URL through the same
 * HTTP client (same proxy and credentials) and finds where the old connection
 * stopped inside the new one's burst-on-connect, so the decoder reads one
 * unbroken byte stream: no rewind, no gap, no broken frame for any container.
 *
 * The values here are static because the streams are created by sources and
 * plugins (obsidian's HTTP source) that know nothing about Spring; the
 * application sets them once at startup (AudioPlayerConfiguration).
 */
public final class LiveStreamResume {
    /** Off restores upstream's behaviour: the end of a live body ends the track. */
    private static volatile boolean enabled = true;
    /**
     * A connection must have stayed open this long before its end counts as a
     * cut: a body that a server closes sooner (a finite file read fast, a
     * playlist, a server that hangs up at once) ends as upstream does, so the
     * stream never loops on a server that keeps closing.
     */
    private static volatile long minConnectionMs = 10_000;
    /** How many of the last bytes heard are looked up in the new connection. */
    private static volatile int needleBytes = 4096;
    /** How long the new connection's first bytes are scanned for the needle. */
    private static volatile long scanMs = 1_500;
    /** How many of the new connection's first bytes are scanned at most. */
    private static volatile int maxScanBytes = 1024 * 1024;
    /** Reopen attempts per cut (a refused or failed request is retried). */
    private static volatile int attempts = 3;
    /** Wait before the 2nd, 3rd... attempt. */
    private static volatile long[] backoffMs = {300, 800};
    /**
     * The whole resume (every attempt and its scan) stays under this, well
     * below the frame buffer (5 s) and lavaplayer's stuck threshold (10 s).
     */
    private static volatile long deadlineMs = 4_500;

    private static final Counter RESUMES = Counter.build()
        .name("lavalink_live_stream_resumes_total")
        .help("Live HTTP streams whose connection ended, by outcome: resumed, no_overlap, failed")
        .labelNames("result")
        .register();

    private LiveStreamResume() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static long getMinConnectionMs() {
        return minConnectionMs;
    }

    public static void setMinConnectionMs(long value) {
        minConnectionMs = Math.max(0, value);
    }

    public static int getNeedleBytes() {
        return needleBytes;
    }

    public static void setNeedleBytes(int value) {
        needleBytes = Math.max(64, value);
    }

    public static long getScanMs() {
        return scanMs;
    }

    public static void setScanMs(long value) {
        scanMs = Math.max(1, value);
    }

    public static int getMaxScanBytes() {
        return maxScanBytes;
    }

    public static void setMaxScanBytes(int value) {
        maxScanBytes = Math.max(needleBytes, value);
    }

    public static int getAttempts() {
        return attempts;
    }

    public static void setAttempts(int value) {
        attempts = Math.max(1, value);
    }

    /** The wait before attempt [attempt] (2 or more); the last value repeats. */
    public static long backoffBefore(int attempt) {
        long[] waits = backoffMs;
        if (attempt < 2 || waits.length == 0) {
            return 0;
        }
        return waits[Math.min(attempt - 2, waits.length - 1)];
    }

    public static void setBackoffMs(long... values) {
        backoffMs = values.clone();
    }

    public static long getDeadlineMs() {
        return deadlineMs;
    }

    public static void setDeadlineMs(long value) {
        deadlineMs = Math.max(1, value);
    }

    /** Back to the defaults above (tests). */
    public static void reset() {
        enabled = true;
        minConnectionMs = 10_000;
        needleBytes = 4096;
        scanMs = 1_500;
        maxScanBytes = 1024 * 1024;
        attempts = 3;
        backoffMs = new long[]{300, 800};
        deadlineMs = 4_500;
    }

    public static void record(String result) {
        RESUMES.labels(result).inc();
    }

    /**
     * The index of the first occurrence of [needle] in data[0, length) that
     * starts at or after [from], or -1.
     */
    public static int indexOf(byte[] data, int length, byte[] needle, int from) {
        int last = length - needle.length;
        byte first = needle[0];

        for (int i = Math.max(0, from); i <= last; i++) {
            if (data[i] == first
                && Arrays.mismatch(data, i, i + needle.length, needle, 0, needle.length) < 0) {
                return i;
            }
        }

        return -1;
    }
}

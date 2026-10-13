package com.sedmelluq.discord.lavaplayer.tools.io;

import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import lavalink.server.player.LiveStreamResume;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.getHeaderValue;
import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.isSuccessWithContent;

/*
 * FlaviBot fork: lavaplayer 2.2.7's PersistentHttpStream (Apache License 2.0,
 * https://github.com/lavalink-devs/lavaplayer), modified. Lavalink's own classes come
 * before its dependency jars (BOOT-INF/classes in the boot jar, the main output on the
 * test classpath) and plugin class loaders delegate to the application's, so this copy
 * replaces lavaplayer's for every stream: obsidian's HTTP tracks, LavaSrc's, ripsrc's,
 * lavaplayer's own sources. Every upstream member is kept as it was (LavaSrc and
 * lavaplayer subclass this class). Diff it against lavaplayer's on every upgrade.
 *
 * The change, a live stream resume (lavalink.server.liveStreamResume, LiveStreamResume):
 * a live radio is a body with no Content-Length that never ends. Upstream reads the end
 * of that body, when anything on the way closes it, as the end of the track: read()
 * returns -1 (TrackEnd "finished") or, for a chunked body cut mid-chunk, throws an
 * exception lavaplayer does not retry (TrackException). The node's HTTP proxy closes
 * every tunnel ~121 s after it opens, so every radio played through it stopped every
 * two minutes and the client restarted it after ~2 s of silence.
 *
 * Here, when the body of such a stream ends or breaks after it stayed open long enough
 * to be a live stream (not a file read to its end, not a server that hangs up at once),
 * the same URL is requested again through the same HTTP interface (same proxy, same
 * credentials), without a Range header (a live body has no byte positions). A live
 * server starts every new listener with a burst of the last seconds it sent, which
 * holds the bytes the old connection delivered last: the stream looks them up there and
 * resumes right after them, so the reader sees one unbroken byte stream, frames intact,
 * in any container (MP3, ADTS, OGG...), nothing replayed and nothing skipped. No match
 * (no burst, or a server that serves something else) ends the stream as upstream does,
 * as do a refused request, a body that now has a Content-Length, or the attempts and
 * the time budget running out. A read that timed out (a stall, not a cut) keeps
 * upstream's own reconnect. Only this exact class resumes: subclasses (LavaSrc's
 * decrypting and YouTube streams) keep upstream's behaviour.
 */

/**
 * Use an HTTP endpoint as a stream, where the connection resetting is handled gracefully by reopening the connection
 * and using a closed stream will just reopen the connection.
 */
public class PersistentHttpStream extends SeekableInputStream implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PersistentHttpStream.class);

    private static final long MAX_SKIP_DISTANCE = 512L * 1024L;

    protected final HttpInterface httpInterface;
    protected final URI contentUrl;
    private int lastStatusCode;
    private CloseableHttpResponse currentResponse;
    protected InputStream currentContent;
    protected long position;

    // Live stream resume (FlaviBot fork): the last bytes delivered, looked up in the
    // next connection; null for every stream that is not a live candidate.
    private byte[] liveTail;
    private int liveTailNext;
    private long liveTailFilled;
    private long connectedAtNanos;
    private long connectionBytes;

    /**
     * @param httpInterface The HTTP interface to use for requests
     * @param contentUrl    The URL of the resource
     * @param contentLength The length of the resource in bytes
     */
    public PersistentHttpStream(HttpInterface httpInterface, URI contentUrl, Long contentLength) {
        super(contentLength == null ? Units.CONTENT_LENGTH_UNKNOWN : contentLength, MAX_SKIP_DISTANCE);

        this.httpInterface = httpInterface;
        this.contentUrl = contentUrl;
        this.position = 0;
    }

    /**
     * Connect and return status code or return last status code if already connected. This causes the internal status
     * code checker to be disabled, so non-success status codes will be returned instead of being thrown as they would
     * be otherwise.
     *
     * @return The status code when connecting to the URL
     * @throws IOException On IO error
     */
    public int checkStatusCode() throws IOException {
        connect(true);

        return lastStatusCode;
    }

    /**
     * @return An HTTP response if one is currently open.
     */
    public HttpResponse getCurrentResponse() {
        return currentResponse;
    }

    protected URI getConnectUrl() {
        return contentUrl;
    }

    protected boolean useHeadersForRange() {
        return true;
    }

    private static boolean validateStatusCode(HttpResponse response, boolean returnOnServerError) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (returnOnServerError && statusCode >= HttpStatus.SC_INTERNAL_SERVER_ERROR) {
            return false;
        } else if (!isSuccessWithContent(statusCode)) {
            throw new RuntimeException("Not success status code: " + statusCode);
        }
        return true;
    }

    protected HttpGet getConnectRequest() {
        HttpGet request = new HttpGet(getConnectUrl());

        if (position > 0 && useHeadersForRange()) {
            request.setHeader(HttpHeaders.RANGE, "bytes=" + position + "-");
        }

        return request;
    }

    protected void connect(boolean skipStatusCheck) throws IOException {
        if (currentResponse == null) {
            for (int i = 1; i >= 0; i--) {
                if (attemptConnect(skipStatusCheck, i > 0)) {
                    break;
                }
            }
        }
    }

    /**
     * @return An InputStream implementation for the current http stream.
     */
    public InputStream createContentInputStream(HttpResponse response) throws IOException {
        return new BufferedInputStream(response.getEntity().getContent());
    }

    private boolean attemptConnect(boolean skipStatusCheck, boolean retryOnServerError) throws IOException {
        currentResponse = httpInterface.execute(getConnectRequest());
        lastStatusCode = currentResponse.getStatusLine().getStatusCode();

        if (!skipStatusCheck && !validateStatusCode(currentResponse, retryOnServerError)) {
            return false;
        }

        if (currentResponse.getEntity() == null) {
            currentContent = EmptyInputStream.INSTANCE;
            contentLength = 0;
            return true;
        }

        currentContent = createContentInputStream(currentResponse);

        if (contentLength == Units.CONTENT_LENGTH_UNKNOWN) {
            Header header = currentResponse.getFirstHeader("Content-Length");

            if (header != null) {
                contentLength = Long.parseLong(header.getValue());
            }
        }

        connectedAtNanos = System.nanoTime();
        connectionBytes = 0;

        if (liveTail == null && isLiveResumeCandidate()) {
            liveTail = new byte[LiveStreamResume.getNeedleBytes()];
        }

        return true;
    }

    private boolean isLiveResumeCandidate() {
        return LiveStreamResume.isEnabled()
            && getClass() == PersistentHttpStream.class
            && contentLength == Units.CONTENT_LENGTH_UNKNOWN
            && isSuccessWithContent(lastStatusCode);
    }

    private void handleNetworkException(IOException exception, boolean attemptReconnect) throws IOException {
        if (!attemptReconnect || !HttpClientTools.isRetriableNetworkException(exception)) {
            throw exception;
        }

        close();

        log.debug("Encountered retriable exception on url {}.", contentUrl, exception);
    }

    private int internalRead(boolean attemptReconnect) throws IOException {
        connect(false);

        int result;

        try {
            result = currentContent.read();
        } catch (IOException e) {
            if (resumeLiveStream(e)) {
                return internalRead(attemptReconnect);
            }

            handleNetworkException(e, attemptReconnect);
            return internalRead(false);
        }

        if (result >= 0) {
            position++;
            rememberLiveByte(result);
        } else if (resumeLiveStream(null)) {
            return internalRead(attemptReconnect);
        }

        return result;
    }

    @Override
    public int read() throws IOException {
        return internalRead(true);
    }

    protected int internalRead(byte[] b, int off, int len, boolean attemptReconnect) throws IOException {
        connect(false);

        int result;

        try {
            result = currentContent.read(b, off, len);
        } catch (IOException e) {
            if (resumeLiveStream(e)) {
                return internalRead(b, off, len, attemptReconnect);
            }

            handleNetworkException(e, attemptReconnect);
            return internalRead(b, off, len, false);
        }

        if (result >= 0) {
            position += result;
            rememberLiveBytes(b, off, result);
        } else if (resumeLiveStream(null)) {
            return internalRead(b, off, len, attemptReconnect);
        }

        return result;
    }

    private void rememberLiveByte(int value) {
        if (liveTail != null) {
            liveTail[liveTailNext] = (byte) value;
            liveTailNext = (liveTailNext + 1) % liveTail.length;
            liveTailFilled++;
            connectionBytes++;
        }
    }

    private void rememberLiveBytes(byte[] b, int off, int len) {
        if (liveTail == null || len <= 0) {
            return;
        }

        connectionBytes += len;
        liveTailFilled += len;

        int size = liveTail.length;

        if (len >= size) {
            System.arraycopy(b, off + len - size, liveTail, 0, size);
            liveTailNext = 0;
            return;
        }

        int first = Math.min(len, size - liveTailNext);
        System.arraycopy(b, off, liveTail, liveTailNext, first);
        System.arraycopy(b, off + first, liveTail, 0, len - first);
        liveTailNext = (liveTailNext + len) % size;
    }

    private void forgetLiveTail(long skipped) {
        if (liveTail != null && skipped > 0) {
            // The skipped bytes were never seen: the tail must be refilled with
            // contiguous bytes before it can be looked up again.
            connectionBytes += skipped;
            liveTailFilled = 0;
        }
    }

    private byte[] liveTailInOrder() {
        int size = liveTail.length;
        byte[] ordered = new byte[size];
        System.arraycopy(liveTail, liveTailNext, ordered, 0, size - liveTailNext);
        System.arraycopy(liveTail, 0, ordered, size - liveTailNext, liveTailNext);
        return ordered;
    }

    private enum LiveResumeOutcome { RESUMED, RETRY, GIVE_UP }

    /**
     * Called when the body of the current connection ended ([cause] null) or broke.
     *
     * @return true when the stream now reads from a new connection, right after the last
     * byte it delivered; false to let upstream's handling end the stream (or retry the
     * exception as it always did).
     */
    private boolean resumeLiveStream(IOException cause) {
        if (liveTail == null || currentResponse == null || !LiveStreamResume.isEnabled()) {
            return false;
        }

        long livedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectedAtNanos);
        String host = contentUrl.getHost();
        String how = cause == null ? "ended" : "broke (" + cause.getClass().getSimpleName() + ")";

        if (liveTailFilled < liveTail.length || connectionBytes == 0 || livedMs < LiveStreamResume.getMinConnectionMs()) {
            log.debug("Live candidate stream from {} {} after {} ms and {} bytes: too soon to be a cut, not resumed.",
                host, how, livedMs, connectionBytes);
            return false;
        } else if (isReadTimeout(cause)) {
            // A stall as long as the socket timeout (3 s on the node): the bytes heard last
            // are older than most servers' burst, so a byte-exact resume would almost never
            // find them and would only delay upstream's own reconnect for this exception.
            log.debug("Live stream from {} stalled after {} ms; left to the reconnect on retriable errors.", host, livedMs);
            return false;
        }

        byte[] needle = liveTailInOrder();
        long endedBytes = connectionBytes;
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(LiveStreamResume.getDeadlineMs());
        CloseableHttpResponse ended = currentResponse;

        closeQuietly(ended);
        currentResponse = null;
        currentContent = null;

        if (resumeFromNewConnection(needle, deadline, host)) {
            log.info("Live stream from {} {} after {} s and {} bytes; resumed in {} ms.",
                host, how, livedMs / 1000, endedBytes, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            LiveStreamResume.record("resumed");
            return true;
        }

        log.info("Live stream from {} {} after {} s and {} bytes; not resumed after {} ms, ending it.",
            host, how, livedMs / 1000, endedBytes, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        // Where upstream would be: at the end of that body. Reads keep returning -1 (no
        // new request, which a null response would make), and this connection, having
        // delivered nothing more, is never resumed twice.
        currentResponse = ended;
        currentContent = EmptyInputStream.INSTANCE;
        connectionBytes = 0;
        return false;
    }

    private boolean resumeFromNewConnection(byte[] needle, long deadline, String host) {
        for (int attempt = 1; attempt <= LiveStreamResume.getAttempts(); attempt++) {
            long wait = LiveStreamResume.backoffBefore(attempt);

            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait) >= deadline) {
                break;
            }

            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                // The track is being stopped: nothing to resume for.
                return false;
            }

            LiveResumeOutcome outcome = attemptLiveResume(needle, deadline, host);

            if (outcome == LiveResumeOutcome.RESUMED) {
                return true;
            } else if (outcome == LiveResumeOutcome.GIVE_UP) {
                return false;
            }
        }

        log.warn("Live stream from {} could not be reopened within {} attempts and {} ms.",
            host, LiveStreamResume.getAttempts(), LiveStreamResume.getDeadlineMs());
        LiveStreamResume.record("failed");
        return false;
    }

    private LiveResumeOutcome attemptLiveResume(byte[] needle, long deadline, String host) {
        CloseableHttpResponse response = null;

        try {
            // No Range header: a live body has no byte positions to resume from.
            response = httpInterface.execute(new HttpGet(getConnectUrl()));
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode >= HttpStatus.SC_INTERNAL_SERVER_ERROR || statusCode == 429) {
                log.debug("Live stream from {} answered {} to its reopen, retrying.", host, statusCode);
                closeQuietly(response);
                return LiveResumeOutcome.RETRY;
            } else if (!isSuccessWithContent(statusCode) || response.getEntity() == null) {
                log.info("Live stream from {} answered {} to its reopen, ending it.", host, statusCode);
                LiveStreamResume.record("failed");
                closeQuietly(response);
                return LiveResumeOutcome.GIVE_UP;
            } else if (response.getFirstHeader("Content-Length") != null) {
                log.info("Live stream from {} reopened with a Content-Length, no longer live: ending it.", host);
                LiveStreamResume.record("failed");
                closeQuietly(response);
                return LiveResumeOutcome.GIVE_UP;
            }

            InputStream content = createContentInputStream(response);
            long scanEnd = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LiveStreamResume.getScanMs()));
            int maxScan = Math.max(LiveStreamResume.getMaxScanBytes(), needle.length);
            byte[] scan = new byte[Math.min(64 * 1024, maxScan)];
            int filled = 0;

            while (true) {
                if (filled == scan.length) {
                    if (scan.length >= maxScan) {
                        break;
                    }

                    scan = Arrays.copyOf(scan, Math.min(scan.length * 2, maxScan));
                }

                int read = content.read(scan, filled, scan.length - filled);

                if (read < 0) {
                    log.debug("Live stream from {} closed its reopened body after {} bytes, retrying.", host, filled);
                    closeQuietly(response);
                    return LiveResumeOutcome.RETRY;
                }

                int from = Math.max(0, filled - needle.length + 1);
                filled += read;
                int found = LiveStreamResume.indexOf(scan, filled, needle, from);

                if (found >= 0) {
                    int resumeAt = found + needle.length;
                    log.debug("Live stream from {}: {} bytes of the reopened body were already heard.", host, resumeAt);

                    currentResponse = response;
                    lastStatusCode = statusCode;
                    currentContent = resumeAt < filled
                        ? new SequenceInputStream(new ByteArrayInputStream(scan, resumeAt, filled - resumeAt), content)
                        : content;
                    connectedAtNanos = System.nanoTime();
                    connectionBytes = 0;
                    return LiveResumeOutcome.RESUMED;
                }

                if (System.nanoTime() >= scanEnd) {
                    break;
                }
            }

            log.info("Live stream from {}: the bytes heard last are not in the first {} bytes of the reopened body, ending it.",
                host, filled);
            LiveStreamResume.record("no_overlap");
            closeQuietly(response);
            return LiveResumeOutcome.GIVE_UP;
        } catch (IOException | RuntimeException e) {
            log.debug("Live stream from {} failed to reopen, retrying.", host, e);
            closeQuietly(response);
            return LiveResumeOutcome.RETRY;
        }
    }

    private static boolean isReadTimeout(IOException cause) {
        return cause instanceof SocketTimeoutException
            || (cause instanceof javax.net.ssl.SSLException && "Read timed out".equals(cause.getMessage()));
    }

    private static void closeQuietly(CloseableHttpResponse response) {
        if (response != null) {
            try {
                response.close();
            } catch (IOException e) {
                log.debug("Failed to close response.", e);
            }
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return internalRead(b, off, len, true);
    }

    protected long internalSkip(long n, boolean attemptReconnect) throws IOException {
        connect(false);

        try {
            long result = currentContent.skip(n);
            if (result >= 0) {
                position += result;
                forgetLiveTail(result);
            }
            return result;
        } catch (IOException e) {
            handleNetworkException(e, attemptReconnect);
            return internalSkip(n, false);
        }
    }

    @Override
    public long skip(long n) throws IOException {
        return internalSkip(n, true);
    }

    private int internalAvailable(boolean attemptReconnect) throws IOException {
        connect(false);

        try {
            return currentContent.available();
        } catch (IOException e) {
            handleNetworkException(e, attemptReconnect);
            return internalAvailable(false);
        }
    }

    @Override
    public int available() throws IOException {
        return internalAvailable(true);
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void close() throws IOException {
        if (currentResponse != null) {
            try {
                currentResponse.close();
            } catch (IOException e) {
                log.debug("Failed to close response.", e);
            }

            currentResponse = null;
            currentContent = null;
        }
    }

    /**
     * Detach from the current connection, making sure not to close the connection when the stream is closed.
     */
    public void releaseConnection() {
        if (currentContent != null) {
            try {
                currentContent.close();
            } catch (IOException e) {
                log.debug("Failed to close response stream.", e);
            }
        }

        currentResponse = null;
        currentContent = null;
    }

    @Override
    public long getPosition() {
        return position;
    }

    @Override
    protected void seekHard(long position) throws IOException {
        close();

        this.position = position;
    }

    @Override
    public boolean canSeekHard() {
        return contentLength != Units.CONTENT_LENGTH_UNKNOWN;
    }

    @Override
    public List<AudioTrackInfoProvider> getTrackInfoProviders() {
        if (currentResponse != null) {
            return Collections.singletonList(createIceCastHeaderProvider());
        } else {
            return Collections.emptyList();
        }
    }

    private AudioTrackInfoProvider createIceCastHeaderProvider() {
        AudioTrackInfoBuilder builder = AudioTrackInfoBuilder.empty()
            .setTitle(getHeaderValue(currentResponse, "icy-description"))
            .setAuthor(getHeaderValue(currentResponse, "icy-name"));

        if (builder.getTitle() == null) {
            builder.setTitle(getHeaderValue(currentResponse, "icy-url"));
        }

        return builder;
    }
}

package com.sedmelluq.discord.lavaplayer.track;

import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

/*
 * FlaviBot fork: lavaplayer 2.2.7's DelegatedAudioTrack (Apache License 2.0,
 * https://github.com/lavalink-devs/lavaplayer), modified. Lavalink's own classes come
 * before its dependency jars (BOOT-INF/classes in the boot jar, the main output on the
 * test classpath) and plugin class loaders delegate to the application's, so this copy
 * replaces lavaplayer's for every delegated track: obsidian's HTTP tracks, LavaSrc's,
 * ripsrc's, lavaplayer's own sources. Diff it against lavaplayer's on every upgrade.
 *
 * The change: upstream, processDelegate is synchronized around delegate.process(), so
 * the playback thread holds this track's monitor for the whole playback, and
 * getPosition / getDuration / setPosition take that monitor whenever they read a null
 * delegate. A thread that read null while the track was starting and reached the
 * monitor right after the playback thread took it stayed parked until the track ended
 * or was stopped. The thread that starts a track reads its position microseconds after
 * handing it to the playback thread (LavalinkPlayer.play -> sendPlayerUpdate, then the
 * PATCH answer), and so does the player-update task onTrackStart schedules at once: a
 * play PATCH hung, track audible, until the next request for the player stopped it.
 */

/**
 * Audio track which delegates its processing to another track. The delegate does not have to be known when the
 * track is created, but is passed when processDelegate() is called.
 */
public abstract class DelegatedAudioTrack extends BaseAudioTrack {
    // Read without the monitor by any thread; written by the playback thread.
    private volatile InternalAudioTrack delegate;

    /**
     * @param trackInfo Track info
     */
    public DelegatedAudioTrack(AudioTrackInfo trackInfo) {
        super(trackInfo);
    }

    protected void processDelegate(InternalAudioTrack delegate, LocalAudioTrackExecutor localExecutor)
        throws Exception {

        // The monitor only makes the hand-over atomic for the accessors below. The
        // delegate gets the executor before it is published: a seek that sees the
        // delegate then lands on the executor, never on the delegate's primordial
        // executor, which assignExecutor(..., false) would not carry over.
        synchronized (this) {
            delegate.assignExecutor(localExecutor, false);
            this.delegate = delegate;
        }

        // The whole playback: never under the monitor.
        delegate.process(localExecutor);
    }

    @Override
    public void setPosition(long position) {
        InternalAudioTrack current = delegate;

        if (current != null) {
            current.setPosition(position);
        } else {
            synchronized (this) {
                if (delegate != null) {
                    delegate.setPosition(position);
                } else {
                    super.setPosition(position);
                }
            }
        }
    }

    @Override
    public long getDuration() {
        InternalAudioTrack current = delegate;

        if (current != null) {
            return current.getDuration();
        } else {
            synchronized (this) {
                if (delegate != null) {
                    return delegate.getDuration();
                } else {
                    return super.getDuration();
                }
            }
        }
    }

    @Override
    public long getPosition() {
        InternalAudioTrack current = delegate;

        if (current != null) {
            return current.getPosition();
        } else {
            synchronized (this) {
                if (delegate != null) {
                    return delegate.getPosition();
                } else {
                    return super.getPosition();
                }
            }
        }
    }
}

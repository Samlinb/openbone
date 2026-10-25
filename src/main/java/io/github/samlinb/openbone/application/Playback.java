package io.github.samlinb.openbone.application;

import io.github.samlinb.openbone.core.animation.*;
import static io.github.samlinb.openbone.i18n.M.m;

/**
 * Absolute monotonic-clock anchor; delayed UI ticks cannot accumulate frame
 * errors.
 */
public final class Playback {
    private double time, anchorTime, absoluteTime;
    private long anchorNanos;
    private boolean playing, loop;

    public double time() {
        return time;
    }

    public double absoluteTime() {
        return absoluteTime;
    }

    public boolean playing() {
        return playing;
    }

    public boolean loop() {
        return loop;
    }

    public void seek(double value) {
        time = Keyframe.seconds(value);
        absoluteTime = time;
        playing = false;
    }

    public void loop(boolean value) {
        loop = value;
    }

    public void play(long now, double duration) {
        Keyframe.seconds(duration);
        if (duration <= 0) {
            playing = false;
            time = 0;
            return;
        }
        if (time >= duration)
            time = 0;
        absoluteTime = time;
        anchorTime = time;
        anchorNanos = now;
        playing = true;
    }

    public void pause(long now, double duration) {
        tick(now, duration);
        playing = false;
    }

    public void tick(long now, double duration) {
        if (!playing)
            return;
        Keyframe.seconds(duration);
        double elapsed = Math.max(0, (now - anchorNanos) / 1_000_000_000.0), raw = anchorTime + elapsed;
        absoluteTime = loop ? raw : Math.min(raw, duration);
        time = AnimationSampler.sampleTime(raw, duration, loop);
        if (!loop && raw >= duration || duration == 0)
            playing = false;
    }

    public static double snap(double time, int fps) {
        Keyframe.seconds(time);
        if (fps < 1 || fps > 240)
            throw new IllegalArgumentException(m("playback.0001"));
        return Keyframe.seconds(Math.rint(time * fps) / fps);
    }
}

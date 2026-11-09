package com.brouken.player;

import androidx.media3.exoplayer.Renderer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Subtitle sync: wraps the text renderer so the position it sees is shifted by the subtitle
 * offset. Only wrapped once subtitle sync has been used (or a video has a saved offset), so
 * normal playback is untouched.
 */
final class SubtitleShift {

    /** True once the player must be built with the wrapped text renderer. */
    static volatile boolean enabled = false;

    private SubtitleShift() {
    }

    static Renderer wrap(final Renderer real) {
        return (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(),
                new Class<?>[]{Renderer.class}, (proxy, method, args) -> {
                    try {
                        final String name = method.getName();
                        if (name.equals("equals") && args != null && args.length == 1) {
                            return proxy == args[0];
                        }
                        if (name.equals("hashCode") && (args == null || args.length == 0)) {
                            return System.identityHashCode(proxy);
                        }
                        if (name.equals("render") && args != null && args.length == 2
                                && args[0] instanceof Long) {
                            final long offsetUs = AudioAdjustProcessor.subDelayMs * 1000L;
                            args = new Object[]{(Long) args[0] - offsetUs, args[1]};
                        }
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}

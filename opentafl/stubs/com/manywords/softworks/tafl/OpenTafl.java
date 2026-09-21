package com.manywords.softworks.tafl;

/**
 * Stub replacing OpenTafl's application class: the engine core only reads its dev-mode flag.
 * (The real class is the desktop entry point with its terminal UI.)
 */
public final class OpenTafl {
    public static final String CURRENT_VERSION = "v0.4.8.0b";
    public static boolean devMode = false;

    private OpenTafl() {}
}

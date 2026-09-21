package com.manywords.softworks.tafl.ui;

import com.manywords.softworks.tafl.engine.GameState;

/** Stub of OpenTafl's terminal renderer: only reached from debug dumps in the engine core. */
public final class RawTerminal {
    public static void disableColor() {}
    public static void enableColor() {}
    public static String getGameStateString(GameState state) { return state.getOTNPositionString(); }
    public static void renderGameState(GameState state) {}

    private RawTerminal() {}
}

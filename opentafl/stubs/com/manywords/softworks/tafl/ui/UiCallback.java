package com.manywords.softworks.tafl.ui;

/**
 * Stub of OpenTafl's UI callback with only the members the engine core calls
 * (AiWorkspace's progress text, GameClock's time events). The real interface also carries
 * the desktop UI's mode and move-input methods.
 */
public interface UiCallback {
    void statusText(String text);
    void timeUpdate(boolean currentSideAttackers);
    void timeExpired(boolean currentSideAttackers);
}

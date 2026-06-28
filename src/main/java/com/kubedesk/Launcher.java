package com.kubedesk;

/**
 * Plain (non-JavaFX) entry point.
 *
 * <p>Launching a class that does NOT extend {@link javafx.application.Application} as the JAR's
 * main class avoids the classic "JavaFX runtime components are missing" error when the app is
 * started from a fat jar. It simply delegates to {@link App}.
 */
public final class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}

package com.kubedesk.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A log line's severity, detected heuristically from common formats: JSON ({@code "level":"error"}),
 * logfmt ({@code level=warn}), bracketed ({@code [error]}, nginx style), glog ({@code E1121 …}) and a bare
 * upper-case word ({@code ERROR}, {@code WARN}, as in Spring, Python, zap, logback). Pure and stateless,
 * so it's easy to extend with more formats.
 */
public enum LogLevel {
    ERROR("Error"), WARN("Warn"), INFO("Info"), DEBUG("Debug"), TRACE("Trace"), NONE("No level");

    private final String label;

    LogLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    private static final int WINDOW = 200; // only look near the start of the line (timestamp + level)

    private static final Pattern JSON = Pattern.compile(
            "\"(?:level|severity|lvl|loglevel|log\\.level)\"\\s*:\\s*\"?([A-Za-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGFMT = Pattern.compile(
            "(?:^|\\s)(?:level|lvl|severity)=\"?([A-Za-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET = Pattern.compile(
            "\\[(error|err|warn|warning|info|notice|debug|trace|crit|alert|emerg|fatal)]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GLOG = Pattern.compile("^([IWEF])\\d{4}\\s");
    private static final Pattern WORD = Pattern.compile(
            "(?<![A-Za-z0-9_])(FATAL|CRITICAL|PANIC|SEVERE|ERROR|WARNING|WARN|NOTICE|INFO|DEBUG|TRACE)(?![A-Za-z0-9_])");

    /** The severity of {@code line}, or null when none can be recognised (the caller decides what that means). */
    public static LogLevel detect(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String s = line.length() > WINDOW ? line.substring(0, WINDOW) : line;
        LogLevel l = viaGroup(JSON, s);
        if (l == null) {
            l = viaGroup(LOGFMT, s);
        }
        if (l == null) {
            l = viaGroup(BRACKET, s);
        }
        if (l == null) {
            Matcher g = GLOG.matcher(s);
            if (g.find()) {
                l = switch (g.group(1)) {
                    case "E", "F" -> ERROR;
                    case "W" -> WARN;
                    default -> INFO;
                };
            }
        }
        if (l == null) {
            l = viaGroup(WORD, s);
        }
        return l;
    }

    private static LogLevel viaGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? fromName(m.group(m.groupCount())) : null;
    }

    private static LogLevel fromName(String raw) {
        return switch (raw.toUpperCase()) {
            case "FATAL", "CRITICAL", "CRIT", "ALERT", "EMERG", "PANIC", "SEVERE", "ERROR", "ERR" -> ERROR;
            case "WARN", "WARNING" -> WARN;
            case "INFO", "NOTICE" -> INFO;
            case "DEBUG" -> DEBUG;
            case "TRACE" -> TRACE;
            default -> null;
        };
    }
}

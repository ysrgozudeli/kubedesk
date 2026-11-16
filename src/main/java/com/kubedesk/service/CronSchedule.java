package com.kubedesk.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny standard-cron (5-field) evaluator for explaining Kubernetes {@code CronJob} schedules:
 * it parses an expression, lists the next run times, and renders a plain-English description.
 *
 * <p>Supports asterisks, step values (slash-n, e.g. every 5), ranges (a-b), stepped ranges (a-b/n),
 * lists (a,b,c), three-letter month/day names (JAN..DEC, SUN..SAT), and the Vixie macros ({@code @hourly},
 * {@code @daily}, {@code @weekly}, {@code @monthly}, {@code @yearly}). Day-of-month and day-of-week follow
 * cron's OR rule: when <em>both</em> are restricted, a time matches if <em>either</em> does.
 *
 * <p><b>Timezone:</b> Kubernetes interprets a CronJob schedule in the controller's timezone — UTC unless
 * {@code spec.timeZone} is set — so {@link #nextRuns} computes in UTC. Callers can convert for display.
 */
public final class CronSchedule {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));
    private static final Map<String, Integer> DOW = Map.ofEntries(
            Map.entry("SUN", 0), Map.entry("MON", 1), Map.entry("TUE", 2), Map.entry("WED", 3),
            Map.entry("THU", 4), Map.entry("FRI", 5), Map.entry("SAT", 6));
    private static final String[] DOW_NAMES =
            {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] MONTH_NAMES = {"", "January", "February", "March", "April", "May",
            "June", "July", "August", "September", "October", "November", "December"};

    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] daysOfMonth = new boolean[32]; // index 1..31
    private final boolean[] months = new boolean[13];      // index 1..12
    private final boolean[] daysOfWeek = new boolean[7];   // index 0..6 (Sunday..Saturday)

    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String[] fields; // the five raw fields, for the describer

    private CronSchedule(String[] fields) {
        this.fields = fields;
        fill(minutes, fields[0], 0, 59, null);
        fill(hours, fields[1], 0, 23, null);
        fillDom(fields[2]);
        fill(months, fields[3], 1, 12, MONTHS);
        fillDowInto(fields[4]);
        this.domRestricted = !fields[2].equals("*");
        this.dowRestricted = !fields[4].equals("*");
    }

    /** Parse a 5-field cron expression (or a supported macro). Throws on anything invalid. */
    public static CronSchedule parse(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Empty schedule.");
        }
        String e = expr.trim();
        String macro = switch (e.toLowerCase(Locale.ROOT)) {
            case "@yearly", "@annually" -> "0 0 1 1 *";
            case "@monthly" -> "0 0 1 * *";
            case "@weekly" -> "0 0 * * 0";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@hourly" -> "0 * * * *";
            default -> null;
        };
        if (macro != null) {
            e = macro;
        } else if (e.startsWith("@")) {
            throw new IllegalArgumentException("Unsupported macro: " + e
                    + " (try @hourly, @daily, @weekly, @monthly, @yearly).");
        }
        String[] parts = e.split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Expected 5 fields (minute hour day-of-month month "
                    + "day-of-week), got " + parts.length + ".");
        }
        return new CronSchedule(parts);
    }

    private void fillDom(String field) {
        // Day-of-month uses no name map; '?' (Quartz) is treated like '*'.
        fill(daysOfMonth, field.equals("?") ? "*" : field, 1, 31, null);
    }

    private void fillDowInto(String field) {
        String f = field.equals("?") ? "*" : field;
        // Normalise 7 → 0 (both mean Sunday) before parsing.
        boolean[] tmp = new boolean[8];
        fill(tmp, f, 0, 7, DOW);
        if (tmp[7]) {
            tmp[0] = true;
        }
        System.arraycopy(tmp, 0, daysOfWeek, 0, 7);
    }

    private static void fill(boolean[] arr, String field, int min, int max, Map<String, Integer> names) {
        for (String part : field.split(",")) {
            parsePart(arr, part.trim(), min, max, names);
        }
    }

    private static void parsePart(boolean[] arr, String part, int min, int max, Map<String, Integer> names) {
        if (part.isEmpty()) {
            throw new IllegalArgumentException("Empty list element.");
        }
        int step = 1;
        String range = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = parseInt(part.substring(slash + 1));
            if (step < 1) {
                throw new IllegalArgumentException("Step must be ≥ 1 in '" + part + "'.");
            }
        }
        int lo;
        int hi;
        if (range.equals("*")) {
            lo = min;
            hi = max;
        } else if (range.contains("-")) {
            int dash = range.indexOf('-');
            lo = value(range.substring(0, dash), names);
            hi = value(range.substring(dash + 1), names);
        } else {
            lo = value(range, names);
            // `n/step` (no range) means n, n+step, … up to max — matches Vixie behaviour.
            hi = slash >= 0 ? max : lo;
        }
        if (lo < min || hi > max || lo > hi) {
            throw new IllegalArgumentException("Value out of range in '" + part + "' (allowed " + min
                    + "–" + max + ").");
        }
        for (int v = lo; v <= hi; v += step) {
            arr[v] = true;
        }
    }

    private static int value(String token, Map<String, Integer> names) {
        String t = token.trim();
        if (names != null) {
            Integer named = names.get(t.toUpperCase(Locale.ROOT));
            if (named != null) {
                return named;
            }
        }
        return parseInt(t);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Not a number: '" + s + "'.");
        }
    }

    private boolean matches(ZonedDateTime t) {
        if (!minutes[t.getMinute()] || !hours[t.getHour()] || !months[t.getMonthValue()]) {
            return false;
        }
        boolean domMatch = daysOfMonth[t.getDayOfMonth()];
        int cronDow = t.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7 → Mon=1..Sun=0
        boolean dowMatch = daysOfWeek[cronDow];
        if (domRestricted && dowRestricted) {
            return domMatch || dowMatch;
        }
        if (domRestricted) {
            return domMatch;
        }
        if (dowRestricted) {
            return dowMatch;
        }
        return true;
    }

    /**
     * The next {@code count} fire times at or after {@code from}, computed in UTC (Kubernetes' default
     * CronJob timezone). Returns fewer than {@code count} only if none fall within a ~5-year horizon.
     */
    public List<ZonedDateTime> nextRuns(Instant from, int count) {
        List<ZonedDateTime> out = new ArrayList<>(count);
        // Start at the next whole minute (a schedule fires on minute boundaries).
        ZonedDateTime t = from.atZone(ZoneOffset.UTC).withSecond(0).withNano(0).plusMinutes(1);
        long maxMinutes = 5L * 366 * 24 * 60; // ~5 years; enough for any realistic schedule
        for (long i = 0; i < maxMinutes && out.size() < count; i++, t = t.plusMinutes(1)) {
            if (matches(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** A plain-English summary of the schedule. Best-effort for common shapes, with a clear fallback. */
    public String describe() {
        String min = fields[0];
        String hour = fields[1];
        String dom = fields[2];
        String mon = fields[3];
        String dow = fields[4];

        String time = timePhrase(min, hour);
        String when = dayPhrase(dom, mon, dow);
        if (time != null && when != null) {
            return cap(time + " " + when) + ".";
        }
        // Fallback: a field-by-field readout — always correct, if less pretty.
        return "Minute " + min + ", hour " + hour + ", day-of-month " + dom
                + ", month " + mon + ", day-of-week " + dow + ".";
    }

    /** The "how often / at what time" part, or null if it's not a shape we phrase nicely. */
    private String timePhrase(String min, String hour) {
        if (min.equals("*") && hour.equals("*")) {
            return "every minute";
        }
        if (hour.equals("*") && min.matches("\\*/\\d+")) {
            return "every " + min.substring(2) + " minutes";
        }
        if (hour.equals("*") && min.matches("\\d+")) {
            return "at " + Integer.parseInt(min) + " minute(s) past every hour";
        }
        if (min.matches("\\d+") && hour.matches("\\*/\\d+")) {
            return String.format("at %02d minutes past every %s hours", Integer.parseInt(min),
                    hour.substring(2));
        }
        if (min.matches("\\d+") && hour.matches("\\d+")) {
            return String.format("at %02d:%02d", Integer.parseInt(hour), Integer.parseInt(min));
        }
        return null;
    }

    /** The "on which days" part, or null when it's unrestricted/complex. */
    private String dayPhrase(String dom, String mon, String dow) {
        boolean domAny = dom.equals("*") || dom.equals("?");
        boolean dowAny = dow.equals("*") || dow.equals("?");
        boolean monAny = mon.equals("*");

        StringBuilder sb = new StringBuilder();
        if (domAny && dowAny) {
            sb.append("every day");
        } else if (!dowAny && dowSingleList(dow) != null) {
            sb.append("on ").append(dowSingleList(dow));
        } else if (!domAny && dom.matches("\\d+")) {
            sb.append("on day ").append(Integer.parseInt(dom)).append(" of the month");
        } else {
            return null; // too complex to phrase — let the fallback handle it
        }
        if (!monAny) {
            String m = monthList(mon);
            if (m == null) {
                return null;
            }
            sb.append(" in ").append(m);
        }
        return sb.toString();
    }

    /** Render a day-of-week field as "Monday" or "Monday, Wednesday and Friday", or null if complex. */
    private String dowSingleList(String dow) {
        List<String> names = new ArrayList<>();
        for (String p : dow.split(",")) {
            Integer v = dowValue(p.trim());
            if (v == null) {
                return null;
            }
            names.add(DOW_NAMES[v % 7]);
        }
        return joinAnd(names);
    }

    private static Integer dowValue(String t) {
        Integer named = DOW.get(t.toUpperCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String monthList(String mon) {
        List<String> names = new ArrayList<>();
        for (String p : mon.split(",")) {
            String t = p.trim();
            Integer v = MONTHS.get(t.toUpperCase(Locale.ROOT));
            if (v == null) {
                try {
                    v = Integer.parseInt(t);
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (v < 1 || v > 12) {
                return null;
            }
            names.add(MONTH_NAMES[v]);
        }
        return joinAnd(names);
    }

    private static String joinAnd(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1))
                + " and " + items.get(items.size() - 1);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

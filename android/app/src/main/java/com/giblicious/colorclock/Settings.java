package com.giblicious.colorclock;

import java.util.Calendar;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Display settings; the schedule logic mirrors public/schedule.js in the web version. */
final class Settings {
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    String dayStart = "07:00";
    String nightStart = "19:00";
    String dayColor = "#16a34a";
    String nightColor = "#dc2626";
    boolean showStatus = false;
    boolean showTime = false;
    boolean pet = true;
    int dayBrightness = 100;
    int nightBrightness = 100;
    String dayLabel = "";
    String nightLabel = "";

    static Settings fromJson(String json) {
        Settings settings = new Settings();
        if (json == null) return settings;
        try {
            JSONObject object = new JSONObject(json);
            settings.dayStart = time(object.optString("dayStart"), settings.dayStart);
            settings.nightStart = time(object.optString("nightStart"), settings.nightStart);
            settings.dayColor = color(object.optString("dayColor"), settings.dayColor);
            settings.nightColor = color(object.optString("nightColor"), settings.nightColor);
            settings.showStatus = object.optBoolean("showStatus", settings.showStatus);
            settings.showTime = object.optBoolean("showTime", settings.showTime);
            settings.pet = object.optBoolean("pet", settings.pet);
            settings.dayBrightness = brightness(object.optInt("dayBrightness", settings.dayBrightness));
            settings.nightBrightness = brightness(object.optInt("nightBrightness", settings.nightBrightness));
            settings.dayLabel = label(object.optString("dayLabel"));
            settings.nightLabel = label(object.optString("nightLabel"));
        } catch (JSONException ignored) {
            // Fall back to defaults for unreadable settings.
        }
        return settings;
    }

    String toJson() {
        try {
            return new JSONObject()
                    .put("dayStart", dayStart)
                    .put("nightStart", nightStart)
                    .put("dayColor", dayColor)
                    .put("nightColor", nightColor)
                    .put("showStatus", showStatus)
                    .put("showTime", showTime)
                    .put("pet", pet)
                    .put("dayBrightness", dayBrightness)
                    .put("nightBrightness", nightBrightness)
                    .put("dayLabel", dayLabel)
                    .put("nightLabel", nightLabel)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean validTime(String value) { return value != null && TIME.matcher(value).matches(); }
    static boolean validColor(String value) { return value != null && COLOR.matcher(value).matches(); }

    boolean isDay(Calendar now) {
        int minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int day = minutes(dayStart);
        int night = minutes(nightStart);
        if (day == night) return true;
        return day < night ? minutes >= day && minutes < night : minutes >= day || minutes < night;
    }

    Calendar nextChange(Calendar now, boolean day) {
        int target = minutes(day ? nightStart : dayStart);
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, target / 60);
        next.set(Calendar.MINUTE, target % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_MONTH, 1);
        return next;
    }

    static int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    private static String time(String value, String fallback) { return validTime(value) ? value : fallback; }
    private static String color(String value, String fallback) { return validColor(value) ? value.toLowerCase() : fallback; }

    static String label(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    Settings copy() { return fromJson(toJson()); }

    private static int brightness(int value) { return Math.max(5, Math.min(100, value)); }
}

package me.cinematic.media;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Container-global stream indexes, independent of audio-track ordinal. */
public record AudioTrack(int streamIndex, String language, String title, String codec, int channels, boolean defaultTrack) {
    private static final Map<String, String> LANGUAGE_NAMES = languageNames();

    private static Map<String, String> languageNames() {
        var names = new HashMap<String, String>();
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.of(code);
            names.put(code, locale.getDisplayLanguage(Locale.ENGLISH));
            names.put(locale.getISO3Language(), locale.getDisplayLanguage(Locale.ENGLISH));
        }
        names.put("ger", "German"); names.put("fre", "French"); names.put("dut", "Dutch");
        names.put("chi", "Chinese"); names.put("cze", "Czech"); names.put("rum", "Romanian");
        names.put("gre", "Greek"); names.put("wel", "Welsh"); names.put("slo", "Slovak");
        names.put("baq", "Basque"); names.put("per", "Persian"); names.put("ice", "Icelandic");
        names.put("alb", "Albanian"); names.put("arm", "Armenian"); names.put("bur", "Burmese");
        names.put("geo", "Georgian"); names.put("mac", "Macedonian"); names.put("may", "Malay");
        names.put("tib", "Tibetan");
        return Map.copyOf(names);
    }

    public AudioTrack {
        if (streamIndex < 0 || channels < 0 || channels > 1024) { throw new IllegalArgumentException("Invalid audio track"); }
        language = bounded(language, 32);
        title = bounded(title, 128);
        codec = bounded(codec, 64);
    }

    private static String bounded(String value, int max) {
        if (value == null) { return ""; }
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.substring(0, Math.min(max, clean.length()));
    }

    public static List<AudioTrack> boundedTracks(List<AudioTrack> tracks) {
        if (tracks.size() > 64 || tracks.stream().map(AudioTrack::streamIndex).distinct().count() != tracks.size()) {
            throw new IllegalArgumentException("Invalid audio track list (maximum 64)");
        }
        return List.copyOf(tracks);
    }

    public static int defaultStream(List<AudioTrack> tracks) {
        return tracks.stream().filter(AudioTrack::defaultTrack).findFirst()
                .or(() -> tracks.stream().findFirst()).map(AudioTrack::streamIndex).orElse(-1);
    }

    public String languageName() {
        if (language.isBlank() || language.equalsIgnoreCase("und")) { return ""; }
        String tag = language.toLowerCase(Locale.ROOT);
        String known = LANGUAGE_NAMES.get(tag);
        if (known != null) { return known; }
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.equals(tag) ? "" : name;
    }

    public String label(int ordinal, List<AudioTrack> tracks) {
        String name = languageName();
        if (name.isEmpty()) { name = "Track " + (ordinal + 1); }
        boolean duplicate = !languageName().isEmpty() && tracks.stream().filter(t -> t.languageName().equals(languageName())).count() > 1;
        String details = title.isEmpty() ? codec + (channels > 0 ? " · " + channels + " ch" : "") : title;
        return name + (duplicate && !details.isBlank() ? " · " + details : "")
                + (duplicate ? " · Track " + (ordinal + 1) : "");
    }
}

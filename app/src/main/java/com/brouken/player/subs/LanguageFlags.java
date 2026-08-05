package com.brouken.player.subs;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort language-code → flag-emoji lookup for the subtitle selector rows. A language isn't a
 * country, so this is inherently approximate (picks one representative flag per language); codes
 * with no mapping just render without a flag.
 */
final class LanguageFlags {

    private static final Map<String, String> FLAGS = new HashMap<>();

    static {
        // Region-specific overrides checked before falling back to the primary subtag.
        FLAGS.put("pt-br", "🇧🇷"); // 🇧🇷
        FLAGS.put("zh-cn", "🇨🇳"); // 🇨🇳
        FLAGS.put("zh-tw", "🇹🇼"); // 🇹🇼

        FLAGS.put("en", "🇬🇧"); // 🇬🇧
        FLAGS.put("es", "🇪🇸"); // 🇪🇸
        FLAGS.put("pt", "🇵🇹"); // 🇵🇹
        FLAGS.put("fr", "🇫🇷"); // 🇫🇷
        FLAGS.put("de", "🇩🇪"); // 🇩🇪
        FLAGS.put("it", "🇮🇹"); // 🇮🇹
        FLAGS.put("nl", "🇳🇱"); // 🇳🇱
        FLAGS.put("sv", "🇸🇪"); // 🇸🇪
        FLAGS.put("da", "🇩🇰"); // 🇩🇰
        FLAGS.put("no", "🇳🇴"); // 🇳🇴
        FLAGS.put("fi", "🇫🇮"); // 🇫🇮
        FLAGS.put("pl", "🇵🇱"); // 🇵🇱
        FLAGS.put("tr", "🇹🇷"); // 🇹🇷
        FLAGS.put("ru", "🇷🇺"); // 🇷🇺
        FLAGS.put("uk", "🇺🇦"); // 🇺🇦
        FLAGS.put("cs", "🇨🇿"); // 🇨🇿
        FLAGS.put("sk", "🇸🇰"); // 🇸🇰
        FLAGS.put("hu", "🇭🇺"); // 🇭🇺
        FLAGS.put("ro", "🇷🇴"); // 🇷🇴
        FLAGS.put("bg", "🇧🇬"); // 🇧🇬
        FLAGS.put("el", "🇬🇷"); // 🇬🇷
        FLAGS.put("he", "🇮🇱"); // 🇮🇱
        FLAGS.put("ar", "🇸🇦"); // 🇸🇦
        FLAGS.put("hi", "🇮🇳"); // 🇮🇳
        FLAGS.put("th", "🇹🇭"); // 🇹🇭
        FLAGS.put("vi", "🇻🇳"); // 🇻🇳
        FLAGS.put("id", "🇮🇩"); // 🇮🇩
        FLAGS.put("ms", "🇲🇾"); // 🇲🇾
        FLAGS.put("ja", "🇯🇵"); // 🇯🇵
        FLAGS.put("ko", "🇰🇷"); // 🇰🇷
        FLAGS.put("zh", "🇨🇳"); // 🇨🇳
        FLAGS.put("ca", "🇪🇸"); // 🇪🇸 (Catalan — no own country flag)
        FLAGS.put("gl", "🇪🇸"); // 🇪🇸 (Galician — idem)
        FLAGS.put("eu", "🇪🇸"); // 🇪🇸 (Basque — idem)
        FLAGS.put("hr", "🇭🇷"); // 🇭🇷
        FLAGS.put("sr", "🇷🇸"); // 🇷🇸
        FLAGS.put("sl", "🇸🇮"); // 🇸🇮
        FLAGS.put("et", "🇪🇪"); // 🇪🇪
        FLAGS.put("lv", "🇱🇻"); // 🇱🇻
        FLAGS.put("lt", "🇱🇹"); // 🇱🇹
        FLAGS.put("is", "🇮🇸"); // 🇮🇸
        FLAGS.put("ga", "🇮🇪"); // 🇮🇪
        FLAGS.put("mt", "🇲🇹"); // 🇲🇹
        FLAGS.put("sq", "🇦🇱"); // 🇦🇱
        FLAGS.put("mk", "🇲🇰"); // 🇲🇰
        FLAGS.put("bs", "🇧🇦"); // 🇧🇦
        FLAGS.put("fa", "🇮🇷"); // 🇮🇷
        FLAGS.put("ur", "🇵🇰"); // 🇵🇰
        FLAGS.put("bn", "🇧🇩"); // 🇧🇩
        FLAGS.put("ta", "🇮🇳"); // 🇮🇳
        FLAGS.put("te", "🇮🇳"); // 🇮🇳
        FLAGS.put("ml", "🇮🇳"); // 🇮🇳
        FLAGS.put("mr", "🇮🇳"); // 🇮🇳
        FLAGS.put("ne", "🇳🇵"); // 🇳🇵
        FLAGS.put("si", "🇱🇰"); // 🇱🇰
        FLAGS.put("my", "🇲🇲"); // 🇲🇲
        FLAGS.put("km", "🇰🇭"); // 🇰🇭
        FLAGS.put("lo", "🇱🇦"); // 🇱🇦
        FLAGS.put("mn", "🇲🇳"); // 🇲🇳
        FLAGS.put("ka", "🇬🇪"); // 🇬🇪
        FLAGS.put("hy", "🇦🇲"); // 🇦🇲
        FLAGS.put("az", "🇦🇿"); // 🇦🇿
        FLAGS.put("kk", "🇰🇿"); // 🇰🇿
        FLAGS.put("uz", "🇺🇿"); // 🇺🇿
        FLAGS.put("sw", "🇹🇿"); // 🇹🇿
        FLAGS.put("af", "🇿🇦"); // 🇿🇦
    }

    private LanguageFlags() {
    }

    /** @return the flag emoji for the given BCP-47/ISO language tag, or {@code null} if unmapped. */
    @Nullable
    static String flagFor(@Nullable String languageTag) {
        if (languageTag == null) return null;
        String tag = languageTag.trim().toLowerCase(Locale.ROOT);
        if (tag.isEmpty()) return null;
        String exact = FLAGS.get(tag);
        if (exact != null) return exact;
        int dash = tag.indexOf('-');
        return dash > 0 ? FLAGS.get(tag.substring(0, dash)) : null;
    }
}

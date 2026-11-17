package com.lab.hv;

import androidx.annotation.Nullable;

/** Pure text rules shared by timed and plain lyric parsing. */
final class LyricLineSanitizer {

    private LyricLineSanitizer() {
    }

    /**
     * Matches only an independent slash placeholder. Real lyric text containing slashes is kept.
     * Full-width slashes are accepted because some translated LRC exporters use them.
     */
    static boolean isStandaloneSlashPlaceholder(@Nullable String text) {
        if (text == null) {
            return false;
        }
        int slashCount = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)
                    || codePoint == 0x00A0
                    || codePoint == 0xFEFF) {
                continue;
            }
            if (codePoint == '/' || codePoint == 0xFF0F) {
                slashCount++;
                continue;
            }
            return false;
        }
        return slashCount >= 2;
    }
}

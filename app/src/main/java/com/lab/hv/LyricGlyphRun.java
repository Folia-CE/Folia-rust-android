package com.lab.hv;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One glyph run of a lyric row (a CJK character, a Latin word with its trailing punctuation and
 * space, or a whitespace run), drawn by the row itself instead of by a view of its own.
 *
 * It keeps the {@link SPText} API the row drives per glyph and draws exactly what an SPText laid
 * out at the same frame drew: the same paint flags, metrics, baseline, karaoke scan gradient,
 * blur mask and long-note glow, translated to the frame the row gives it, with the view
 * properties a glyph view had (alpha, scale about its centre, translation) applied the way a
 * RenderNode applies them. A row of runs is one display list instead of one RenderNode, measure,
 * layout and record per glyph.
 *
 * A word may rise letter by letter ({@link #setLetterPresentationTranslationYPx}): the run stays
 * one. The word is shaped once, whole, and each letter's own glyphs are drawn at exactly the
 * places that shaping gave them, only higher or lower: nothing moves sideways (a kerned pair
 * such as "ry" keeps its kern; re-shaping a letter on its own would lose it). A word whose
 * glyphs cannot be told apart letter by letter (a ligature across letters) rises whole. Only
 * while the letters are at different heights; otherwise the word is one draw as before. Only
 * for scripts drawn glyph for glyph (Latin, Greek, Cyrillic, Hangul, kana, Han), and from
 * Android 12 (which hands out shaped glyphs): joined scripts and older systems move whole.
 */
final class LyricGlyphRun {

    static final int VISIBLE = View.VISIBLE;
    static final int GONE = View.GONE;
    private static final android.graphics.Matrix REVEAL_SHADER_MATRIX = new android.graphics.Matrix();

    /** Paints shared by every run of a row: a run sets them up completely before each draw. */
    static final class Paints {
        final Paint text = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
        final Paint blur = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
        final Paint glow = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
        /** Measures at the base size, with the same flags as the text paint (advances depend on them). */
        final Paint measure = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
        final Paint.FontMetrics metrics = new Paint.FontMetrics();

        Paints() {
            text.setStyle(Paint.Style.FILL);
            blur.setStyle(Paint.Style.FILL);
            glow.setStyle(Paint.Style.FILL);
        }

        /** The lyrics' font, on every paint (measuring too, so advances match what is drawn). */
        void setTypeface(@androidx.annotation.Nullable android.graphics.Typeface typeface) {
            text.setTypeface(typeface);
            blur.setTypeface(typeface);
            glow.setTypeface(typeface);
            measure.setTypeface(typeface);
        }
    }

    @NonNull private final View owner;
    @NonNull private final Paints paints;

    @NonNull private String text = "";
    private float textSizePx = 1f;
    private int textColor = 0xFF1A1A1A;
    private boolean textColorWide = false;
    private long textColorLong = 0L;
    private float weightStrengthPx = 0f;
    private float blurRadiusPx = 0f;
    private float presentationBlurRadiusPx = 0f;
    private float presentationTextScale = 1f;
    private float presentationTranslationYPx = 0f;
    private float longNoteGlowStrength = 0f;
    private float longNoteGlowInnerRadiusPx = 0f;
    private float longNoteGlowOuterRadiusPx = 0f;
    private float edgePaddingPx = 0f;
    private float advanceWidthPx = 0f;
    private float textHeightPx = 0f;
    private boolean revealScanEnabled = false;
    private int revealScanColor = 0xFF1A1A1A;
    private boolean revealScanWide = false;
    private long revealScanColorLong = 0L;
    private float revealScanFrontPx = 0f;
    private float revealScanFeatherPx = 1f;
    /** The scan gradient, built at x 0 and moved to the front by its local matrix. */
    @Nullable private LinearGradient revealShader;
    private boolean revealShaderWide;
    private long revealShaderFrom;
    private long revealShaderTo;
    private float revealShaderFeatherPx;
    private float revealShaderFrontX = Float.NaN;
    /** Each letter's first char (and the text's length after the last); null: one piece. */
    @Nullable private int[] letterStarts;
    /** Each letter's first code point, counted in the run. */
    @Nullable private int[] letterCodePoints;
    private float[] letterLiftPx = new float[0];
    private boolean letterLiftActive;
    /** The whole word shaped at {@link #shapedSizePx} with {@link #shapedTypeface}; null: not yet. */
    @Nullable private Object shapedWord;
    private float shapedSizePx = -1f;
    @Nullable private android.graphics.Typeface shapedTypeface;
    /** Each letter's first glyph in {@link #shapedWord} (and the glyph count after the last); null: whole. */
    @Nullable private int[] letterGlyphStarts;
    private int[] glyphIdScratch = new int[0];
    private float[] glyphPositionScratch = new float[0];

    // What a glyph view held as view properties and frame.
    private int visibility = GONE;
    private float alpha = 1f;
    private float scaleX = 1f;
    private float scaleY = 1f;
    private float translationY = 0f;
    private int measuredWidth;
    private int measuredHeight;
    private int left;
    private int top;
    private int right;
    private int bottom;

    LyricGlyphRun(@NonNull View owner, @NonNull Paints paints) {
        this.owner = owner;
        this.paints = paints;
    }

    // ---- view-like state ----

    int getVisibility() {
        return visibility;
    }

    void setVisibility(int value) {
        if (visibility == value) return;
        visibility = value;
        owner.invalidate();
    }

    void setAlpha(float value) {
        if (alpha == value) return;
        alpha = value;
        owner.invalidate();
    }

    void setScaleX(float value) {
        if (scaleX == value) return;
        scaleX = value;
        owner.invalidate();
    }

    void setScaleY(float value) {
        if (scaleY == value) return;
        scaleY = value;
        owner.invalidate();
    }

    void setTranslationY(float value) {
        if (translationY == value) return;
        translationY = value;
        owner.invalidate();
    }

    int getLeft() {
        return left;
    }

    int getMeasuredWidth() {
        return measuredWidth;
    }

    int getMeasuredHeight() {
        return measuredHeight;
    }

    /** As SPText.onMeasure with unspecified specs. */
    void measure(int widthMeasureSpec, int heightMeasureSpec) {
        measuredWidth = Math.max(1, Math.round(advanceWidthPx + edgePaddingPx * 2f));
        float visualPad = edgePaddingPx + resolveVisualOverflowPaddingPx();
        measuredHeight = Math.max(1, Math.round(textHeightPx + visualPad * 2f));
    }

    void layout(int l, int t, int r, int b) {
        if (left == l && top == t && right == r && bottom == b) return;
        left = l;
        top = t;
        right = r;
        bottom = b;
        owner.invalidate();
    }

    // ---- SPText API ----

    void setTextContent(@Nullable String value) {
        String safe = TextUtils.isEmpty(value) ? "" : value;
        if (TextUtils.equals(text, safe)) {
            return;
        }
        text = safe;
        resolveLetters();
        shapedWord = null;
        letterGlyphStarts = null;
        recalcTextMetrics();
    }

    void setTextSizePx(float valuePx) {
        float safe = Math.max(1f, valuePx);
        if (Math.abs(textSizePx - safe) < 0.01f) {
            return;
        }
        textSizePx = safe;
        recalcTextMetrics();
    }

    void setTextColorInt(int color) {
        setTextColorInternal(color, true);
    }

    void setTextColorLong(long color) {
        setTextColorLongInternal(color, true);
    }

    void setTextRevealScanWindow(int pendingColor, int revealedColor, float scanFrontPx, float featherPx) {
        float safeFeather = Math.max(1f, featherPx);
        float safeScanFrontPx = Math.max(0f, Math.min(advanceWidthPx + safeFeather, scanFrontPx));
        boolean changed = !revealScanEnabled
                || revealScanWide
                || textColorWide
                || textColor != pendingColor
                || revealScanColor != revealedColor
                || Math.abs(revealScanFrontPx - safeScanFrontPx) > 0.01f
                || Math.abs(revealScanFeatherPx - safeFeather) > 0.01f;
        revealScanEnabled = true;
        revealScanWide = false;
        revealScanColor = revealedColor;
        revealScanFrontPx = safeScanFrontPx;
        revealScanFeatherPx = safeFeather;
        textColorWide = false;
        textColor = pendingColor;
        if (changed) {
            owner.invalidate();
        }
    }

    void setTextRevealScanWindowLong(long pendingColor, long revealedColor, float scanFrontPx, float featherPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setTextRevealScanWindow(Color.toArgb(pendingColor), Color.toArgb(revealedColor), scanFrontPx, featherPx);
            return;
        }
        float safeFeather = Math.max(1f, featherPx);
        float safeScanFrontPx = Math.max(0f, Math.min(advanceWidthPx + safeFeather, scanFrontPx));
        boolean changed = !revealScanEnabled
                || !revealScanWide
                || !textColorWide
                || textColorLong != pendingColor
                || revealScanColorLong != revealedColor
                || Math.abs(revealScanFrontPx - safeScanFrontPx) > 0.01f
                || Math.abs(revealScanFeatherPx - safeFeather) > 0.01f;
        revealScanEnabled = true;
        revealScanWide = true;
        revealScanColorLong = revealedColor;
        revealScanFrontPx = safeScanFrontPx;
        revealScanFeatherPx = safeFeather;
        textColorWide = true;
        textColorLong = pendingColor;
        textColor = Color.toArgb(pendingColor);
        if (changed) {
            owner.invalidate();
        }
    }

    void setWeightStrengthPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(weightStrengthPx - safe) < 0.01f) {
            return;
        }
        weightStrengthPx = safe;
        recalcTextMetrics();
    }

    void setBlurRadiusPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(blurRadiusPx - safe) < 0.01f) {
            return;
        }
        blurRadiusPx = safe;
        owner.invalidate();
    }

    /**
     * An SPText applied this as a RenderEffect blur over the whole glyph. Both blurs share one
     * radius-to-sigma mapping, and Gaussians compose by adding variances, so it is drawn as one
     * mask blur of the combined sigma.
     */
    void setPresentationBlurRadiusPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(presentationBlurRadiusPx - safe) < 0.01f) {
            return;
        }
        presentationBlurRadiusPx = safe;
        owner.invalidate();
    }

    void setPresentationTextScale(float value) {
        float safe = Math.max(1f, value);
        if (Math.abs(presentationTextScale - safe) < 0.001f) {
            return;
        }
        presentationTextScale = safe;
        owner.invalidate();
    }

    void setPresentationTranslationYPx(float valuePx) {
        // The whole run at one height: any letter-by-letter rise ends here.
        if (letterLiftActive) {
            letterLiftActive = false;
            owner.invalidate();
        }
        float safe = Float.isNaN(valuePx) || Float.isInfinite(valuePx) ? 0f : valuePx;
        if (Math.abs(presentationTranslationYPx - safe) < 0.0001f) {
            return;
        }
        presentationTranslationYPx = safe;
        owner.invalidate();
    }

    /** How many letters the run rises as (1: it rises whole). */
    int getLetterCount() {
        return letterStarts == null ? 1 : letterStarts.length - 1;
    }

    /** The code point (counted in the run) letter {@code index} starts at. */
    int getLetterCodePointIndex(int index) {
        int[] codePoints = letterCodePoints;
        return codePoints == null || index <= 0 ? 0 : codePoints[Math.min(index, codePoints.length - 1)];
    }

    /**
     * Each letter's own presentation translation, as {@link #setPresentationTranslationYPx} is
     * the whole run's (called after it, which resets this). All about the same: the run is drawn
     * whole at its own translation.
     */
    void setLetterPresentationTranslationYPx(@NonNull float[] valuesPx, int count) {
        if (letterStarts == null || count != getLetterCount() || count > valuesPx.length) return;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, valuesPx[i]);
            max = Math.max(max, valuesPx[i]);
        }
        if (!(max - min > 0.05f)) return;
        if (letterLiftPx.length < count) letterLiftPx = new float[count];
        boolean changed = !letterLiftActive;
        for (int i = 0; i < count; i++) {
            float value = Float.isFinite(valuesPx[i]) ? valuesPx[i] : 0f;
            if (Math.abs(letterLiftPx[i] - value) >= 0.0001f) changed = true;
            letterLiftPx[i] = value;
        }
        letterLiftActive = true;
        if (changed) owner.invalidate();
    }

    void setLongNoteGlow(float strength, float innerRadiusPx, float outerRadiusPx) {
        float safeStrength = clamp01(Float.isFinite(strength) ? strength : 0f);
        float safeInnerRadius = Math.max(0f, Float.isFinite(innerRadiusPx) ? innerRadiusPx : 0f);
        float safeOuterRadius = Math.max(safeInnerRadius, Float.isFinite(outerRadiusPx) ? outerRadiusPx : 0f);
        if (Math.abs(longNoteGlowStrength - safeStrength) < 0.001f
                && Math.abs(longNoteGlowInnerRadiusPx - safeInnerRadius) < 0.01f
                && Math.abs(longNoteGlowOuterRadiusPx - safeOuterRadius) < 0.01f) {
            return;
        }
        longNoteGlowStrength = safeStrength;
        longNoteGlowInnerRadiusPx = safeInnerRadius;
        longNoteGlowOuterRadiusPx = safeOuterRadius;
        owner.invalidate();
    }

    void setEdgePaddingPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(edgePaddingPx - safe) < 0.01f) {
            return;
        }
        edgePaddingPx = safe;
        recalcTextMetrics();
    }

    float getAdvanceWidthPx() {
        return advanceWidthPx;
    }

    float getEdgePaddingPx() {
        return edgePaddingPx;
    }

    // ---- drawing ----

    /** Draws the run where a glyph view at this frame, with these view properties, drew. */
    void draw(@NonNull Canvas canvas) {
        if (visibility != VISIBLE || TextUtils.isEmpty(text) || alpha <= 0f) {
            return;
        }
        if (scaleX == 0f || scaleY == 0f) {
            return;
        }
        int save = canvas.save();
        canvas.translate(left, top + translationY);
        if (scaleX != 1f || scaleY != 1f) {
            canvas.scale(scaleX, scaleY, (right - left) * 0.5f, (bottom - top) * 0.5f);
        }
        drawContent(canvas, Math.min(1f, alpha));
        canvas.restoreToCount(save);
    }

    private void drawContent(@NonNull Canvas canvas, float viewAlpha) {
        applyDrawPaintPresentationState();
        Paint textPaint = paints.text;
        Paint blurPaint = paints.blur;
        Paint.FontMetrics metrics = paints.metrics;
        textPaint.getFontMetrics(metrics);
        float visualPad = (edgePaddingPx + resolveVisualOverflowPaddingPx()) * presentationTextScale;
        float baseline = visualPad - metrics.ascent + presentationTranslationYPx;
        float textX = edgePaddingPx * presentationTextScale;
        float effectiveBlurRadiusPx = resolveEffectiveBlurRadiusPx();
        LinearGradient revealShader = revealScanEnabled && advanceWidthPx > 0.01f
                ? obtainRevealScanShader(textX)
                : null;
        // Rising letter by letter only where the word's own glyphs can be split by letter.
        boolean byLetter = letterLiftActive && shapeLetters(textPaint);

        if (longNoteGlowStrength > 0.001f && longNoteGlowOuterRadiusPx > 0.01f) {
            drawLongNoteGlow(canvas, textX, baseline, revealShader, viewAlpha, byLetter);
        }

        if (revealShader != null) {
            Paint paint = effectiveBlurRadiusPx > 0.01f ? blurPaint : textPaint;
            paint.setMaskFilter(effectiveBlurRadiusPx > 0.01f
                    ? SPText.obtainSharedBlurMask(effectiveBlurRadiusPx)
                    : null);
            // Feather trails behind the reveal front so upcoming glyph pixels stay purely pending.
            paint.setColor(0xFFFFFFFF);
            paint.setShader(revealShader);
            applyViewAlpha(paint, viewAlpha);
            drawRunText(canvas, textX, baseline, paint, byLetter);
            paint.setShader(null);
            paint.setMaskFilter(null);
            return;
        }

        if (effectiveBlurRadiusPx > 0.01f) {
            applyPaintColor(blurPaint);
            blurPaint.setShader(null);
            blurPaint.setMaskFilter(SPText.obtainSharedBlurMask(effectiveBlurRadiusPx));
            applyViewAlpha(blurPaint, viewAlpha);
            drawRunText(canvas, textX, baseline, blurPaint, byLetter);
            blurPaint.setMaskFilter(null);
            return;
        }

        textPaint.setMaskFilter(null);
        textPaint.setShader(null);
        applyPaintColor(textPaint);
        applyViewAlpha(textPaint, viewAlpha);
        drawRunText(canvas, textX, baseline, textPaint, byLetter);
    }

    /**
     * The whole word shaped at the size drawn now (kept while size and typeface stay), and where
     * each letter's glyphs begin in it. A letter's glyphs are counted by shaping it in the word's
     * context; when the counts do not add up to the word's (a ligature across letters), the word
     * is not split. False: draw the word whole.
     */
    private boolean shapeLetters(@NonNull Paint paint) {
        int[] starts = letterStarts;
        if (starts == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        float size = paint.getTextSize();
        android.graphics.Typeface typeface = paint.getTypeface();
        if (shapedWord != null && shapedSizePx == size && shapedTypeface == typeface) {
            return letterGlyphStarts != null;
        }
        shapedSizePx = size;
        shapedTypeface = typeface;
        int length = text.length();
        android.graphics.text.PositionedGlyphs word = android.graphics.text.TextRunShaper.shapeTextRun(
                text, 0, length, 0, length, 0f, 0f, false, paint);
        shapedWord = word;
        int count = starts.length - 1;
        int[] glyphStarts = new int[count + 1];
        int glyphs = 0;
        for (int i = 0; i < count; i++) {
            glyphStarts[i] = glyphs;
            glyphs += android.graphics.text.TextRunShaper.shapeTextRun(
                    text, starts[i], starts[i + 1] - starts[i], 0, length, 0f, 0f, false, paint).glyphCount();
        }
        glyphStarts[count] = glyphs;
        letterGlyphStarts = glyphs == word.glyphCount() ? glyphStarts : null;
        return letterGlyphStarts != null;
    }

    /** The run's text: whole, or (rising letter by letter) each letter's glyphs at its own height. */
    private void drawRunText(@NonNull Canvas canvas, float textX, float baseline, @NonNull Paint paint,
                             boolean byLetter) {
        int[] glyphStarts = letterGlyphStarts;
        if (!byLetter || glyphStarts == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            canvas.drawText(text, textX, baseline, paint);
            return;
        }
        drawLetterGlyphs(canvas, textX, baseline - presentationTranslationYPx, paint, glyphStarts);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private void drawLetterGlyphs(@NonNull Canvas canvas, float textX, float base, @NonNull Paint paint,
                                  @NonNull int[] glyphStarts) {
        android.graphics.text.PositionedGlyphs word = (android.graphics.text.PositionedGlyphs) shapedWord;
        if (word == null) return;
        int total = word.glyphCount();
        if (glyphIdScratch.length < total) {
            glyphIdScratch = new int[total];
            glyphPositionScratch = new float[total * 2];
        }
        boolean fakeBold = paint.isFakeBoldText();
        float skew = paint.getTextSkewX();
        try {
            for (int letter = 0; letter < glyphStarts.length - 1; letter++) {
                float lift = letterLiftPx[letter];
                int first = glyphStarts[letter];
                int end = glyphStarts[letter + 1];
                // One call per run of glyphs in the same font (a fallback font starts a new one).
                while (first < end) {
                    android.graphics.fonts.Font font = word.getFont(first);
                    int stop = first;
                    while (stop < end && word.getFont(stop) == font) {
                        glyphIdScratch[stop - first] = word.getGlyphId(stop);
                        glyphPositionScratch[(stop - first) * 2] = textX + word.getGlyphX(stop);
                        glyphPositionScratch[(stop - first) * 2 + 1] = base + lift + word.getGlyphY(stop);
                        stop++;
                    }
                    applySyntheticStyle(paint, word, first, font, fakeBold, skew);
                    canvas.drawGlyphs(glyphIdScratch, 0, glyphPositionScratch, 0, stop - first, font, paint);
                    first = stop;
                }
            }
        } finally {
            paint.setFakeBoldText(fakeBold);
            paint.setTextSkewX(skew);
        }
    }

    /**
     * Drawn as glyphs, a font's synthesized bold or italic (a typeface asking for a style its file
     * lacks) would be lost: it is put on the paint, as the text drawing would have applied it.
     * Android 15 says so per glyph; below it, the typeface's style against the font file's.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private static void applySyntheticStyle(@NonNull Paint paint,
                                            @NonNull android.graphics.text.PositionedGlyphs word,
                                            int glyph,
                                            @NonNull android.graphics.fonts.Font font,
                                            boolean fakeBold,
                                            float skew) {
        boolean bold;
        boolean italic;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bold = word.getFakeBold(glyph);
            italic = word.getFakeItalic(glyph);
        } else {
            android.graphics.Typeface typeface = paint.getTypeface();
            android.graphics.fonts.FontStyle style = font.getStyle();
            bold = typeface != null && typeface.isBold() && style.getWeight() < 600;
            italic = typeface != null && typeface.isItalic()
                    && style.getSlant() == android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT;
        }
        paint.setFakeBoldText(fakeBold || bold);
        paint.setTextSkewX(italic ? skew - 0.25f : skew);
    }

    /**
     * Splits the run into letters (a base with its marks, joiners and modifiers kept together),
     * when every letter is drawn glyph for glyph and there are two or more.
     */
    private void resolveLetters() {
        letterLiftActive = false;
        letterStarts = null;
        letterCodePoints = null;
        int length = text.length();
        if (length < 2) return;
        int[] starts = new int[length + 1];
        int[] codePoints = new int[length];
        int count = 0;
        int codePointIndex = 0;
        boolean joinNext = false;
        for (int i = 0; i < length; ) {
            int cp = text.codePointAt(i);
            if (!letterByLetterScript(cp)) return;
            boolean attaches = joinNext || attachesToPrevious(cp);
            if (!attaches || count == 0) {
                starts[count] = i;
                codePoints[count] = codePointIndex;
                count++;
            }
            joinNext = cp == 0x200D;
            i += Character.charCount(cp);
            codePointIndex++;
        }
        if (count < 2) return;
        starts[count] = length;
        letterStarts = java.util.Arrays.copyOf(starts, count + 1);
        letterCodePoints = java.util.Arrays.copyOf(codePoints, count);
    }

    private static boolean attachesToPrevious(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || cp == 0x200D
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }

    /** A script whose letters never join or reorder: drawing them one by one changes nothing. */
    private static boolean letterByLetterScript(int cp) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(cp);
        } catch (IllegalArgumentException e) {
            return false;
        }
        switch (script) {
            case LATIN:
            case GREEK:
            case CYRILLIC:
            case ARMENIAN:
            case GEORGIAN:
            case HANGUL:
            case HIRAGANA:
            case KATAKANA:
            case HAN:
            case COMMON:
            case INHERITED:
                return true;
            default:
                return false;
        }
    }

    private void drawLongNoteGlow(@NonNull Canvas canvas,
                                  float textX,
                                  float baseline,
                                  @Nullable Shader revealShader,
                                  float viewAlpha,
                                  boolean byLetter) {
        // A broad, faint halo removes the visible blur boundary; the tighter layer gives the
        // held glyph a luminous core without turning the letter into a solid white blob.
        drawLongNoteGlowLayer(canvas, textX, baseline,
                longNoteGlowOuterRadiusPx * presentationTextScale,
                0.40f * longNoteGlowStrength, revealShader, viewAlpha, byLetter);
        if (longNoteGlowInnerRadiusPx > 0.01f) {
            drawLongNoteGlowLayer(canvas, textX, baseline,
                    longNoteGlowInnerRadiusPx * presentationTextScale,
                    0.55f * longNoteGlowStrength, revealShader, viewAlpha, byLetter);
        }
    }

    private void drawLongNoteGlowLayer(@NonNull Canvas canvas,
                                       float textX,
                                       float baseline,
                                       float radiusPx,
                                       float opacity,
                                       @Nullable Shader revealShader,
                                       float viewAlpha,
                                       boolean byLetter) {
        if (radiusPx <= 0.01f || opacity <= 0.001f) {
            return;
        }
        Paint glowPaint = paints.glow;
        float quantizedRadiusPx = Math.max(0.5f, Math.round(radiusPx * 2f) * 0.5f);
        glowPaint.setMaskFilter(SPText.obtainSharedBlurMask(quantizedRadiusPx));
        if (revealShader != null) {
            glowPaint.setColor(Color.WHITE);
            glowPaint.setShader(revealShader);
            glowPaint.setAlpha(Math.max(0, Math.min(255, Math.round(255f * opacity))));
        } else {
            glowPaint.setShader(null);
            applyPaintColor(glowPaint);
            int sourceAlpha = textColorWide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? Math.round(Color.alpha(textColorLong) * 255f)
                    : Color.alpha(textColor);
            glowPaint.setAlpha(Math.max(0, Math.min(255, Math.round(sourceAlpha * opacity))));
        }
        applyViewAlpha(glowPaint, viewAlpha);
        drawRunText(canvas, textX, baseline, glowPaint, byLetter);
        glowPaint.setShader(null);
        glowPaint.setMaskFilter(null);
        glowPaint.setAlpha(255);
    }

    @NonNull
    private LinearGradient obtainRevealScanShader(float textX) {
        float scanFrontX = textX + (revealScanFrontPx * presentationTextScale);
        float scanFeatherPx = revealScanFeatherPx * presentationTextScale;
        LinearGradient shader = revealShader;
        if (revealScanWide && textColorWide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (shader == null || !revealShaderWide || revealShaderFrom != revealScanColorLong
                    || revealShaderTo != textColorLong || revealShaderFeatherPx != scanFeatherPx) {
                shader = new LinearGradient(-scanFeatherPx, 0f, 0f, 0f,
                        revealScanColorLong, textColorLong, Shader.TileMode.CLAMP);
                revealShaderWide = true;
                revealShaderFrom = revealScanColorLong;
                revealShaderTo = textColorLong;
                revealShaderFeatherPx = scanFeatherPx;
                revealShaderFrontX = Float.NaN;
            }
        } else if (shader == null || revealShaderWide || revealShaderFrom != revealScanColor
                || revealShaderTo != textColor || revealShaderFeatherPx != scanFeatherPx) {
            shader = new LinearGradient(-scanFeatherPx, 0f, 0f, 0f,
                    revealScanColor, textColor, Shader.TileMode.CLAMP);
            revealShaderWide = false;
            revealShaderFrom = revealScanColor;
            revealShaderTo = textColor;
            revealShaderFeatherPx = scanFeatherPx;
            revealShaderFrontX = Float.NaN;
        }
        revealShader = shader;
        if (revealShaderFrontX != scanFrontX) {
            // The shader copies the matrix; the rows are drawn on the main thread only.
            REVEAL_SHADER_MATRIX.setTranslate(scanFrontX, 0f);
            shader.setLocalMatrix(REVEAL_SHADER_MATRIX);
            revealShaderFrontX = scanFrontX;
        }
        return shader;
    }

    private void applyDrawPaintPresentationState() {
        float safeSize = Math.max(1f, textSizePx <= 0f ? 1f : textSizePx);
        float drawScale = Math.max(1f, presentationTextScale);
        float drawSize = safeSize * drawScale;
        float stroke = Math.max(0f, weightStrengthPx * drawScale);
        configure(paints.text, drawSize, stroke);
        configure(paints.blur, drawSize, stroke);
        configure(paints.glow, drawSize, stroke);
    }

    private static void configure(@NonNull Paint paint, float size, float stroke) {
        paint.setTextSize(size);
        if (stroke > 0.001f) {
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeMiter(10f);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0f);
        }
    }

    private void recalcTextMetrics() {
        Paint measure = paints.measure;
        measure.setTextSize(Math.max(1f, textSizePx <= 0f ? 1f : textSizePx));
        advanceWidthPx = measure.measureText(text);
        measure.getFontMetrics(paints.metrics);
        textHeightPx = Math.max(1f, paints.metrics.descent - paints.metrics.ascent);
        owner.requestLayout();
        owner.invalidate();
    }

    private float resolveEffectiveBlurRadiusPx() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Math.max(blurRadiusPx, presentationBlurRadiusPx);
        }
        if (presentationBlurRadiusPx <= 0.01f) {
            return blurRadiusPx;
        }
        if (blurRadiusPx <= 0.01f) {
            return presentationBlurRadiusPx;
        }
        float sigmaA = radiusToSigma(blurRadiusPx);
        float sigmaB = radiusToSigma(presentationBlurRadiusPx);
        float sigma = (float) Math.sqrt(sigmaA * sigmaA + sigmaB * sigmaB);
        return Math.max(0.01f, (sigma - 0.5f) / 0.57735f);
    }

    /** Skia's mapping for both BlurMaskFilter and RenderEffect blur radii. */
    private static float radiusToSigma(float radius) {
        return radius > 0f ? 0.57735f * radius + 0.5f : 0f;
    }

    private float resolveVisualOverflowPaddingPx() {
        return Math.max(0f, weightStrengthPx * 0.5f);
    }

    private void setTextColorInternal(int color, boolean clearRevealScan) {
        boolean changed = false;
        if (clearRevealScan && revealScanEnabled) {
            revealScanEnabled = false;
            revealScanWide = false;
            changed = true;
        }
        if (textColorWide || textColor != color) {
            textColorWide = false;
            textColor = color;
            changed = true;
        }
        if (changed) {
            owner.invalidate();
        }
    }

    private void setTextColorLongInternal(long color, boolean clearRevealScan) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setTextColorInternal(Color.toArgb(color), clearRevealScan);
            return;
        }
        boolean changed = false;
        if (clearRevealScan && revealScanEnabled) {
            revealScanEnabled = false;
            revealScanWide = false;
            changed = true;
        }
        if (!textColorWide || textColorLong != color) {
            textColorWide = true;
            textColorLong = color;
            textColor = Color.toArgb(color);
            changed = true;
        }
        if (changed) {
            owner.invalidate();
        }
    }

    private void applyPaintColor(@NonNull Paint paint) {
        if (textColorWide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.setColor(textColorLong);
        } else {
            paint.setColor(textColor);
        }
    }

    /** A glyph view's alpha (no overlapping rendering): multiplied into every draw. */
    private static void applyViewAlpha(@NonNull Paint paint, float viewAlpha) {
        if (viewAlpha >= 1f) return;
        paint.setAlpha(Math.round(paint.getAlpha() * viewAlpha));
    }

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}

package com.lab.hv;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

public class SPText extends View {
    private static final Object BLUR_MASK_CACHE_LOCK = new Object();
    private static final SparseArray<BlurMaskFilter> BLUR_MASK_CACHE = new SparseArray<>();
    /**
     * Presentation blurs keyed by their exact radius bits. Prep dots step through a fixed set of
     * quantized radii every frame; a RenderEffect is immutable, so equal radii share one native
     * filter instead of allocating a new one per glyph per frame.
     */
    private static final LruCache<Integer, RenderEffect> PRESENTATION_BLUR_EFFECT_CACHE =
            new LruCache<>(96);

    private final Paint textPaint = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
    private final Paint blurPaint = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
    private final Paint glowPaint = com.lab.hv.util.AppFont.track(new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
    private final Paint.FontMetrics drawFontMetrics = new Paint.FontMetrics();

    @NonNull
    private String text = "";
    private float textSizePx;
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
    private float leftOverhangPx, rightOverhangPx;
    private final android.graphics.Rect inkBounds = new android.graphics.Rect();
    private float textHeightPx = 0f;
    private boolean revealScanEnabled = false;
    private int revealScanColor = 0xFF1A1A1A;
    private boolean revealScanWide = false;
    private long revealScanColorLong = 0L;
    private float revealScanFrontPx = 0f;
    private float revealScanFeatherPx = 1f;

    @Nullable
    private BlurMaskFilter cachedBlurMask;
    private float cachedBlurRadiusPx = -1f;

    public SPText(@NonNull Context context) {
        this(context, null);
    }

    public SPText(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SPText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        textPaint.setStyle(Paint.Style.FILL);
        blurPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        syncPaintState();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setTextContent(@Nullable String value) {
        String safe = TextUtils.isEmpty(value) ? "" : value;
        if (TextUtils.equals(text, safe)) {
            return;
        }
        text = safe;
        recalcTextMetrics();
    }

    public void setTextSizePx(float valuePx) {
        float safe = Math.max(1f, valuePx);
        if (Math.abs(textSizePx - safe) < 0.01f) {
            return;
        }
        textSizePx = safe;
        syncPaintState();
        recalcTextMetrics();
    }

    public void setFontTypeface(@Nullable android.graphics.Typeface typeface) {
        if (textPaint.getTypeface() == typeface) {
            return;
        }
        textPaint.setTypeface(typeface);
        blurPaint.setTypeface(typeface);
        glowPaint.setTypeface(typeface);
        recalcTextMetrics();
    }

    public void setTextColorInt(int color) {
        setTextColorInternal(color, true);
    }

    public void setTextColorLong(long color) {
        setTextColorLongInternal(color, true);
    }

    public void setTextRevealScan(int pendingColor, int revealedColor, float progress, float featherPx) {
        float safeProgress = clamp01(progress);
        setTextRevealScanWindow(pendingColor, revealedColor, advanceWidthPx * safeProgress, featherPx);
    }

    public void setTextRevealScanWindow(int pendingColor, int revealedColor, float scanFrontPx, float featherPx) {
        float safeFeather = Math.max(1f, featherPx);
        float safeScanFrontPx = Math.max(0f, Math.min(advanceWidthPx + safeFeather, scanFrontPx));

        boolean changed = !revealScanEnabled
                || revealScanWide
                || textColor != pendingColor
                || revealScanColor != revealedColor
                || Math.abs(revealScanFrontPx - safeScanFrontPx) > 0.01f
                || Math.abs(revealScanFeatherPx - safeFeather) > 0.01f;
        revealScanEnabled = true;
        revealScanWide = false;
        revealScanColor = revealedColor;
        revealScanFrontPx = safeScanFrontPx;
        revealScanFeatherPx = safeFeather;
        if (textColorWide || textColor != pendingColor) {
            textColorWide = false;
            textColor = pendingColor;
            syncPaintState();
        }
        if (changed) {
            invalidate();
        }
    }

    public void setTextRevealScanWindowLong(long pendingColor,
                                            long revealedColor,
                                            float scanFrontPx,
                                            float featherPx) {
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
        if (!textColorWide || textColorLong != pendingColor) {
            textColorWide = true;
            textColorLong = pendingColor;
            textColor = Color.toArgb(pendingColor);
            syncPaintState();
        }
        if (changed) {
            invalidate();
        }
    }

    public void setWeightStrengthPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(weightStrengthPx - safe) < 0.01f) {
            return;
        }
        weightStrengthPx = safe;
        syncPaintState();
        recalcTextMetrics();
    }

    public void setBlurRadiusPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(blurRadiusPx - safe) < 0.01f) {
            return;
        }
        blurRadiusPx = safe;
        invalidate();
    }

    public void setPresentationBlurRadiusPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(presentationBlurRadiusPx - safe) < 0.01f) {
            return;
        }
        presentationBlurRadiusPx = safe;
        applyPresentationBlurEffect();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            invalidate();
        }
    }

    public void setPresentationTextScale(float value) {
        float safe = Math.max(1f, value);
        if (Math.abs(presentationTextScale - safe) < 0.001f) {
            return;
        }
        presentationTextScale = safe;
        invalidate();
    }

    public void setPresentationTranslationYPx(float valuePx) {
        float safe = Float.isNaN(valuePx) || Float.isInfinite(valuePx) ? 0f : valuePx;
        if (Math.abs(presentationTranslationYPx - safe) < 0.0001f) {
            return;
        }
        presentationTranslationYPx = safe;
        invalidate();
    }

    /**
     * Draws a soft, two-stage halo behind the glyph. The halo is rendered as part of this
     * view so it follows presentation translation and scaling without lagging behind the text.
     */
    public void setLongNoteGlow(float strength, float innerRadiusPx, float outerRadiusPx) {
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
        invalidate();
    }

    public void setEdgePaddingPx(float valuePx) {
        float safe = Math.max(0f, valuePx);
        if (Math.abs(edgePaddingPx - safe) < 0.01f) {
            return;
        }
        edgePaddingPx = safe;
        recalcTextMetrics();
    }

    public float getAdvanceWidthPx() {
        return advanceWidthPx;
    }

    /**
     * Resolves a fractional Unicode code-point position to the matching horizontal text advance.
     * Karaoke callers should use this instead of multiplying the total width by a character ratio:
     * glyphs, punctuation and whitespace do not have uniform advances.
     */
    public float getAdvanceAtCodePointProgress(float codePointProgress) {
        if (TextUtils.isEmpty(text) || advanceWidthPx <= 0f) {
            return 0f;
        }
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= 0) {
            return 0f;
        }
        float safeProgress = Math.max(0f, Math.min(codePointCount, codePointProgress));
        int completeCodePoints = Math.min(codePointCount, (int) Math.floor(safeProgress));
        int completeEnd = Character.offsetByCodePoints(text, 0, completeCodePoints);
        float advance = completeEnd <= 0 ? 0f : textPaint.measureText(text, 0, completeEnd);
        if (completeCodePoints >= codePointCount) {
            return advanceWidthPx;
        }
        float partial = safeProgress - completeCodePoints;
        if (partial <= 0f) {
            return Math.max(0f, Math.min(advanceWidthPx, advance));
        }
        int nextEnd = Character.offsetByCodePoints(text, completeEnd, 1);
        float nextAdvance = textPaint.measureText(text, 0, nextEnd);
        return Math.max(0f, Math.min(advanceWidthPx, advance + (nextAdvance - advance) * partial));
    }

    public float getEdgePaddingPx() {
        return edgePaddingPx + leftOverhangPx;
    }

    public static void prewarmBlurMaskRadiusPx(float valuePx) {
        float safe = Math.max(0.01f, valuePx);
        obtainSharedBlurMask(safe);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.max(1, (int) Math.ceil(advanceWidthPx + leftOverhangPx + rightOverhangPx + edgePaddingPx * 2f));
        float visualPad = edgePaddingPx + resolveVisualOverflowPaddingPx();
        int desiredHeight = Math.max(1, Math.round(textHeightPx + visualPad * 2f));
        int measuredWidth = resolveSize(desiredWidth, widthMeasureSpec);
        int measuredHeight = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
        
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (TextUtils.isEmpty(text)) {
            return;
        }
        applyDrawPaintPresentationState();
        Paint.FontMetrics metrics = drawFontMetrics;
        textPaint.getFontMetrics(metrics);
        float visualPad = (edgePaddingPx + resolveVisualOverflowPaddingPx()) * presentationTextScale;
        float baseline = visualPad - metrics.ascent + presentationTranslationYPx;
        float textX = (edgePaddingPx + leftOverhangPx) * presentationTextScale;
        float effectiveBlurRadiusPx = resolveEffectiveSoftwareBlurRadiusPx();
        LinearGradient revealShader = revealScanEnabled && advanceWidthPx > 0.01f
                ? createRevealScanShader(textX)
                : null;

        if (longNoteGlowStrength > 0.001f && longNoteGlowOuterRadiusPx > 0.01f) {
            drawLongNoteGlow(canvas, textX, baseline, revealShader);
        }

        if (revealShader != null) {
            drawRevealScanText(canvas, textX, baseline, effectiveBlurRadiusPx, revealShader);
            return;
        }

        if (effectiveBlurRadiusPx > 0.01f) {
            ensureBlurMask(effectiveBlurRadiusPx);
            applyPaintColor(blurPaint, textColor, textColorLong, textColorWide);
            blurPaint.setShader(null);
            blurPaint.setMaskFilter(cachedBlurMask);
            canvas.drawText(text, textX, baseline, blurPaint);
            return;
        }

        textPaint.setMaskFilter(null);
        textPaint.setShader(null);
        applyPaintColor(textPaint, textColor, textColorLong, textColorWide);
        canvas.drawText(text, textX, baseline, textPaint);
    }

    private void drawLongNoteGlow(@NonNull Canvas canvas,
                                  float textX,
                                  float baseline,
                                  @Nullable Shader revealShader) {
        // A broad, faint halo removes the visible blur boundary; the tighter layer gives the
        // held glyph a luminous core without turning the letter into a solid white blob.
        drawLongNoteGlowLayer(
                canvas,
                textX,
                baseline,
                longNoteGlowOuterRadiusPx * presentationTextScale,
                0.40f /* OUTER */ * longNoteGlowStrength,
                revealShader
        );
        if (longNoteGlowInnerRadiusPx > 0.01f) {
            drawLongNoteGlowLayer(
                    canvas,
                    textX,
                    baseline,
                    longNoteGlowInnerRadiusPx * presentationTextScale,
                    0.55f /* INNER */* longNoteGlowStrength,
                    revealShader
            );
        }
    }

    private void drawLongNoteGlowLayer(@NonNull Canvas canvas,
                                       float textX,
                                       float baseline,
                                       float radiusPx,
                                       float opacity,
                                       @Nullable Shader revealShader) {
        if (radiusPx <= 0.01f || opacity <= 0.001f) {
            return;
        }
        // Presentation scale can animate every frame. Half-pixel quantization keeps the shared
        // mask cache bounded without producing a visible step in such a soft halo.
        float quantizedRadiusPx = Math.max(0.5f, Math.round(radiusPx * 2f) * 0.5f);
        glowPaint.setMaskFilter(obtainSharedBlurMask(quantizedRadiusPx));
        if (revealShader != null) {
            glowPaint.setColor(Color.WHITE);
            glowPaint.setShader(revealShader);
            glowPaint.setAlpha(Math.max(0, Math.min(255, Math.round(255f * opacity))));
        } else {
            glowPaint.setShader(null);
            applyPaintColor(glowPaint, textColor, textColorLong, textColorWide);
            int sourceAlpha = textColorWide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? Math.round(Color.alpha(textColorLong) * 255f)
                    : Color.alpha(textColor);
            glowPaint.setAlpha(Math.max(0, Math.min(255, Math.round(sourceAlpha * opacity))));
        }
        canvas.drawText(text, textX, baseline, glowPaint);
        glowPaint.setShader(null);
        glowPaint.setMaskFilter(null);
        glowPaint.setAlpha(255);
    }

    private void drawRevealScanText(@NonNull Canvas canvas,
                                    float textX,
                                    float baseline,
                                    float effectiveBlurRadiusPx,
                                    @NonNull Shader revealShader) {
        Paint paint = effectiveBlurRadiusPx > 0.01f ? blurPaint : textPaint;
        if (paint == blurPaint) {
            ensureBlurMask(effectiveBlurRadiusPx);
            paint.setMaskFilter(cachedBlurMask);
        } else {
            paint.setMaskFilter(null);
        }

        // Feather trails behind the reveal front so upcoming glyph pixels stay purely pending.
        paint.setColor(0xFFFFFFFF);
        paint.setShader(revealShader);
        canvas.drawText(text, textX, baseline, paint);
        paint.setShader(null);
        paint.setMaskFilter(null);
    }

    @NonNull
    private LinearGradient createRevealScanShader(float textX) {
        float scanFrontX = textX + (revealScanFrontPx * presentationTextScale);
        float scanFeatherPx = revealScanFeatherPx * presentationTextScale;
        if (revealScanWide && textColorWide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new LinearGradient(
                    scanFrontX - scanFeatherPx,
                    0f,
                    scanFrontX,
                    0f,
                    revealScanColorLong,
                    textColorLong,
                    Shader.TileMode.CLAMP
            );
        }
        return new LinearGradient(
                scanFrontX - scanFeatherPx,
                0f,
                scanFrontX,
                0f,
                revealScanColor,
                textColor,
                Shader.TileMode.CLAMP
        );
    }

    private void applyDrawPaintPresentationState() {
        float safeSize = Math.max(1f, textSizePx <= 0f ? 1f : textSizePx);
        float drawScale = Math.max(1f, presentationTextScale);
        float drawSize = safeSize * drawScale;
        textPaint.setTextSize(drawSize);
        blurPaint.setTextSize(drawSize);
        glowPaint.setTextSize(drawSize);

        float stroke = Math.max(0f, weightStrengthPx * drawScale);
        if (stroke > 0.001f) {
            textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            blurPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            glowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            textPaint.setStrokeWidth(stroke);
            blurPaint.setStrokeWidth(stroke);
            glowPaint.setStrokeWidth(stroke);
            textPaint.setStrokeJoin(Paint.Join.ROUND);
            blurPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setStrokeMiter(10f);
            blurPaint.setStrokeMiter(10f);
            glowPaint.setStrokeMiter(10f);
        } else {
            textPaint.setStyle(Paint.Style.FILL);
            blurPaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.FILL);
            textPaint.setStrokeWidth(0f);
            blurPaint.setStrokeWidth(0f);
            glowPaint.setStrokeWidth(0f);
        }
    }

    private void syncPaintState() {
        float safeSize = Math.max(1f, textSizePx <= 0f ? 1f : textSizePx);
        textPaint.setTextSize(safeSize);
        blurPaint.setTextSize(safeSize);
        glowPaint.setTextSize(safeSize);

        float stroke = Math.max(0f, weightStrengthPx);
        if (stroke > 0.001f) {
            textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            blurPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            glowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            textPaint.setStrokeWidth(stroke);
            blurPaint.setStrokeWidth(stroke);
            glowPaint.setStrokeWidth(stroke);
            textPaint.setStrokeJoin(Paint.Join.ROUND);
            blurPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setStrokeMiter(10f);
            blurPaint.setStrokeMiter(10f);
            glowPaint.setStrokeMiter(10f);
        } else {
            textPaint.setStyle(Paint.Style.FILL);
            blurPaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.FILL);
            textPaint.setStrokeWidth(0f);
            blurPaint.setStrokeWidth(0f);
            glowPaint.setStrokeWidth(0f);
        }
    }

    /** The app's font changed (the paints already carry it): measure the text again. */


    private void recalcTextMetrics() {
        syncPaintState();
        advanceWidthPx = textPaint.measureText(text);
        textPaint.getTextBounds(text, 0, text.length(), inkBounds);
        leftOverhangPx = Math.max(0, -inkBounds.left);
        rightOverhangPx = Math.max(0f, inkBounds.right - advanceWidthPx);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        textHeightPx = Math.max(1f, metrics.descent - metrics.ascent);
        requestLayout();
        invalidate();
    }

    private void ensureBlurMask(float radiusPx) {
        float radius = Math.max(0.01f, radiusPx);
        if (cachedBlurMask != null && Math.abs(cachedBlurRadiusPx - radius) < 0.01f) {
            return;
        }
        cachedBlurRadiusPx = radius;
        cachedBlurMask = obtainSharedBlurMask(radius);
    }

    private float resolveEffectiveSoftwareBlurRadiusPx() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return blurRadiusPx;
        }
        return Math.max(blurRadiusPx, presentationBlurRadiusPx);
    }

    private void applyPresentationBlurEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (presentationBlurRadiusPx <= 0.01f) {
            setRenderEffect(null);
            return;
        }
        setRenderEffect(obtainPresentationBlurEffect(presentationBlurRadiusPx));
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.S)
    private static RenderEffect obtainPresentationBlurEffect(float radiusPx) {
        Integer key = Float.floatToIntBits(radiusPx);
        RenderEffect cached = PRESENTATION_BLUR_EFFECT_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        RenderEffect created = RenderEffect.createBlurEffect(
                radiusPx,
                radiusPx,
                Shader.TileMode.CLAMP
        );
        PRESENTATION_BLUR_EFFECT_CACHE.put(key, created);
        return created;
    }

    @NonNull
    static BlurMaskFilter obtainSharedBlurMask(float radiusPx) {
        int key = Math.round(Math.max(0.01f, radiusPx) * 100f);
        synchronized (BLUR_MASK_CACHE_LOCK) {
            BlurMaskFilter cached = BLUR_MASK_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            BlurMaskFilter created = new BlurMaskFilter(key / 100f, BlurMaskFilter.Blur.NORMAL);
            BLUR_MASK_CACHE.put(key, created);
            return created;
        }
    }

    private float resolveVisualOverflowPaddingPx() {
        // Layout size must stay stable during active line switches to avoid whole-list flicker.
        // Reserve with edgePadding only; blurRadius changes should repaint, not relayout.
        float blurPad = 0f;
        float strokePad = Math.max(0f, weightStrengthPx * 0.5f);
        return blurPad + strokePad;
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
            syncPaintState();
            changed = true;
        }
        if (changed) {
            invalidate();
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
            syncPaintState();
            changed = true;
        }
        if (changed) {
            invalidate();
        }
    }

    private void applyPaintColor(@NonNull Paint paint, int color, long colorLong, boolean wideColor) {
        if (wideColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.setColor(colorLong);
        } else {
            paint.setColor(color);
        }
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}

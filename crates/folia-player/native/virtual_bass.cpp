// Extracted unchanged from SalamPlayer hxcore_dsp_native.cpp, 2026-11-15.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
namespace {
constexpr double kStateSilenceEpsilon = 1.0e-30;
inline float sanitizeFloat(float v) { return std::isfinite(v) ? v : 0.0f; }
// ---- HXCore virtual bass: native twin of VirtualBassDsp.java (keep the two in step) ----

constexpr int kVbBandCount = 5;
constexpr int kVbSubBand = 0;
constexpr int kVbUpperBand = 4;  // quiet cutoff .. upper top: only the 85–110 Hz layer
constexpr int kVbMaxOrders = 5;
constexpr int kVbDesignHeader = 3 + kVbBandCount;
constexpr int kVbBandStride = 2 + kVbMaxOrders * 2;
constexpr int kVbDesignLength = kVbDesignHeader + kVbBandCount * kVbBandStride;
constexpr float kVbReferenceStrength = 20.0f;
constexpr float kVbLayerGain = 1.6f;
constexpr float kVbRealRemovalLowHz = 1.0f;
constexpr float kVbRealRemovalHighHz = 123.0f;
constexpr float kVbRealSourceLowHz = 30.0f;
constexpr float kVbRealSourceHighHz = 120.0f;
constexpr float kVbRealSynthesisPoleHz = 123.0f * 2.299f;
constexpr double kVbRealDcBlockRadiansAt48k = 0.005;
constexpr float kVbRealBassGainPerStrength = -0.8374f;
constexpr float kVbRealBoostHz = 104.0f;
constexpr double kVbRealBoostQ = 1.0;
constexpr float kVbRealBoostPerStrength = 0.36f / 20.3f;
constexpr float kVbSubsonicHz = 16.0f;
constexpr int kVbSubFirstOrder = 2;
constexpr float kVbSubTargetHz = 100.0f;
constexpr float kVbSubWidthOctaves = 0.3f;
constexpr float kVbSubWeightSmoothingMs = 20.0f;
constexpr float kVbSubLayerGain = 1.828f;  // 1.17, ×1.25 (three layers), ×1.25 (sub + kick)
constexpr float kVbSubGateLowHz = 16.0f;
constexpr float kVbSubGateFullLowHz = 20.0f;
constexpr float kVbSubGateFullHighHz = 40.0f;
constexpr float kVbSubGateHighHz = 50.0f;
// The second pitch-kept layer: 50–75 Hz notes → orders 2–4 on a bell around 142 Hz.
constexpr int kVbKickFirstOrder = 2;
constexpr int kVbKickOrders = 3;
constexpr float kVbKickTargetHz = 142.0f;
constexpr float kVbKickWidthOctaves = kVbSubWidthOctaves;
constexpr float kVbKickGateLowHz = 42.0f;
constexpr float kVbKickGateFullLowHz = 50.0f;
constexpr float kVbKickGateFullHighHz = 75.0f;
constexpr float kVbKickGateHighHz = 88.0f;
constexpr float kVbKickLayerGain = 1.667f;  // 0.6 +5 dB, ×1.25 (three layers), ×1.25 (sub + kick)
constexpr int kVbKickHighestOrder = kVbKickFirstOrder + kVbKickOrders - 1;
// VirtualBassDsp.UPPER_*: notes in 85–110 Hz give their second harmonic (170–220 Hz).
constexpr int kVbUpperOrder = 2;
constexpr float kVbUpperGateLowHz = 84.0f;
constexpr float kVbUpperGateFullLowHz = 86.0f;
constexpr float kVbUpperGateFullHighHz = 109.0f;
constexpr float kVbUpperGateHighHz = 111.0f;
constexpr float kVbUpperHighPassHz = 120.0f;
constexpr float kVbUpperLowPassHz = 300.0f;
constexpr float kVbUpperLayerGain = 3.0f;  // 2.4 (~5 dB over the note), ×1.25
constexpr double kVbAnalysisRateHz = 3000.0;
constexpr float kVbDryDelayMs = 12.0f;
constexpr float kVbMixTransitionMs = 24.0f;
constexpr float kVbParameterTransitionMs = 55.0f;
constexpr float kVbFrequencySmoothingMs = 10.0f;
constexpr float kVbPhasePullMs = 30.0f;
// Java declares these as float: the same float values keep the twins in step.
constexpr float kVbLoudnessReference = 0.1f;
constexpr float kVbLoudnessMin = 0.25f;
constexpr float kVbLoudnessMax = 3.0f;
constexpr double kVbLr2Q = 0.5;
constexpr double kVbButterworthQ = 0.70710678118654752;
constexpr double kVbButterworth4Q1 = 0.54119610;
constexpr double kVbButterworth4Q2 = 1.30656296;
constexpr double kVbTwoPi = 6.283185307179586476925;
const double kVbInverseLn2 = 1.0 / std::log(2.0);
constexpr std::array<double, 4> kVbHilbertA{
        0.47940086558884, 0.87621849353931, 0.97659758950819, 0.99749925593555};
constexpr std::array<double, 4> kVbHilbertB{
        0.16175849836770, 0.73302893234149, 0.94534970032911, 0.99059915668453};

inline double vbSmoothingAlpha(double rateHz, float milliseconds) {
    const double seconds = std::max(0.001, static_cast<double>(milliseconds) / 1000.0);
    return static_cast<double>(static_cast<float>(1.0 - std::exp(-1.0 / (rateHz * seconds))));
}

/** RBJ second-order section, double precision (same arithmetic as the Java Biquad). */
struct VbBiquad {
    double b0 = 0.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0, z1 = 0.0, z2 = 0.0;

    void set(double hz, double q, int rate, int kind) {
        const double w = 2.0 * M_PI * std::min(hz, rate * 0.45) / rate;
        const double alpha = std::sin(w) / (2.0 * q);
        const double cosine = std::cos(w);
        const double a0 = 1.0 + alpha;
        if (kind == 0) {
            b0 = (1.0 - cosine) * 0.5 / a0;
            b1 = (1.0 - cosine) / a0;
            b2 = b0;
        } else if (kind == 1) {
            b0 = (1.0 + cosine) * 0.5 / a0;
            b1 = -(1.0 + cosine) / a0;
            b2 = b0;
        } else {
            b0 = alpha / a0;
            b1 = 0.0;
            b2 = -alpha / a0;
        }
        a1 = -2.0 * cosine / a0;
        a2 = (1.0 - alpha) / a0;
    }

    void lowPass(double hz, double q, int rate) { set(hz, q, rate, 0); }

    void highPass(double hz, double q, int rate) { set(hz, q, rate, 1); }

    void bandPass(double hz, double q, int rate) { set(hz, q, rate, 2); }

    void allPassFirstOrder(double hz, int rate) {
        const double t = std::tan(M_PI * std::min(hz, rate * 0.45) / rate);
        const double c = (t - 1.0) / (t + 1.0);
        b0 = c;
        b1 = 1.0;
        b2 = 0.0;
        a1 = c;
        a2 = 0.0;
    }

    double process(double x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    void clear() {
        z1 = 0.0;
        z2 = 0.0;
    }
};

/** Niemitalo 90° pair; re + i·im turns forwards (branch B leads the delayed branch A). */
struct VbHilbert {
    std::array<double, 4> ax1{}, ax2{}, ay1{}, ay2{}, bx1{}, bx2{}, by1{}, by2{};
    double delayedA = 0.0;
    double re = 0.0;
    double im = 0.0;

    void process(double x) {
        double a = x;
        double b = x;
        for (int i = 0; i < 4; ++i) {
            const double y = kVbHilbertA[i] * (a + ay2[i]) - ax2[i];
            ax2[i] = ax1[i];
            ax1[i] = a;
            ay2[i] = ay1[i];
            ay1[i] = y;
            a = y;
        }
        for (int i = 0; i < 4; ++i) {
            const double y = kVbHilbertB[i] * (b + by2[i]) - bx2[i];
            bx2[i] = bx1[i];
            bx1[i] = b;
            by2[i] = by1[i];
            by1[i] = y;
            b = y;
        }
        re = delayedA;
        delayedA = a;
        im = -b;
    }

    void clear() { *this = VbHilbert{}; }
};

/** VirtualBassDsp.RealBass: the earlier Chebyshev stage's linear path, filter for filter. */
struct VbRealBassCoefficients {
    double sourceHigh = 0.0, sourceLow = 0.0, split = 0.0, removalHigh = 0.0, removalLow = 0.0;
    double synthesis = 0.0, dcBlock = 0.0;

    void configure(int rate) {
        sourceHigh = std::exp(-2.0 * M_PI * kVbRealSourceLowHz / rate);
        sourceLow = 1.0 - std::exp(-2.0 * M_PI * kVbRealSourceHighHz / rate);
        split = 1.0 - std::exp(-2.0 * M_PI * kVbRealSourceLowHz / rate);
        removalHigh = std::exp(-2.0 * M_PI * kVbRealRemovalLowHz / rate);
        removalLow = 1.0 - std::exp(-2.0 * M_PI * kVbRealRemovalHighHz / rate);
        synthesis = 1.0 - std::exp(-2.0 * M_PI * static_cast<double>(kVbRealSynthesisPoleHz) / rate);
        dcBlock = std::exp(-kVbRealDcBlockRadiansAt48k * 48000.0 / rate);
    }
};

struct VbRealBass {
    double sourceIn1 = 0.0, sourceOut1 = 0.0, sourceIn2 = 0.0, sourceOut2 = 0.0;
    double sourceLow1 = 0.0, sourceLow2 = 0.0, split1 = 0.0, split2 = 0.0;
    double removalIn1 = 0.0, removalOut1 = 0.0, removalIn2 = 0.0, removalOut2 = 0.0;
    double removalLow1 = 0.0, removalLow2 = 0.0;
    double dcIn = 0.0, dcOut = 0.0;
    double synthesis1 = 0.0, synthesis2 = 0.0, synthesis3 = 0.0, synthesis4 = 0.0;

    double process(double x, double lift, const VbRealBassCoefficients& c) {
        const double high1 = c.sourceHigh * (sourceOut1 + x - sourceIn1);
        sourceIn1 = x;
        sourceOut1 = high1;
        const double high2 = c.sourceHigh * (sourceOut2 + high1 - sourceIn2);
        sourceIn2 = high1;
        sourceOut2 = high2;
        sourceLow1 += c.sourceLow * (high2 - sourceLow1);
        sourceLow2 += c.sourceLow * (sourceLow1 - sourceLow2);
        split1 += c.split * (high2 - split1);
        split2 += c.split * (split1 - split2);
        const double source = sourceLow2 - split2;

        const double removal1 = c.removalHigh * (removalOut1 + x - removalIn1);
        removalIn1 = x;
        removalOut1 = removal1;
        const double removal2 = c.removalHigh * (removalOut2 + removal1 - removalIn2);
        removalIn2 = removal1;
        removalOut2 = removal2;
        removalLow1 += c.removalLow * (removal2 - removalLow1);
        removalLow2 += c.removalLow * (removalLow1 - removalLow2);

        const double lifted = source * lift;
        const double blocked = lifted - dcIn + c.dcBlock * dcOut;
        dcIn = lifted;
        dcOut = blocked;
        synthesis1 += c.synthesis * (blocked - synthesis1);
        synthesis2 += c.synthesis * (synthesis1 - synthesis2);
        synthesis3 += c.synthesis * (synthesis2 - synthesis3);
        synthesis4 += c.synthesis * (synthesis3 - synthesis4);
        return x - removalLow2 + synthesis4;
    }

    void clear() { *this = VbRealBass{}; }
};

/** The cutoff-derived plan, as VirtualBassDsp.Design.toArray() lays it out. */
struct VbDesign {
    std::array<float, kVbBandCount> crossoverHz{};
    float layerHighPassHz = 88.0f;
    float layerLowPassHz = 286.0f;
    std::array<float, kVbBandCount> centerHz{};
    std::array<int, kVbBandCount> orderCount{};
    std::array<std::array<int, kVbMaxOrders>, kVbBandCount> orders{};
    std::array<std::array<float, kVbMaxOrders>, kVbBandCount> weights{};
    std::array<float, kVbDesignLength> raw{};

    bool read(const float* values, int length) {
        if (values == nullptr || length < kVbDesignLength
                || static_cast<int>(values[0]) != kVbBandCount) {
            return false;
        }
        for (int i = 0; i < kVbDesignLength; ++i) {
            if (!std::isfinite(values[i])) return false;
            raw[i] = values[i];
        }
        for (int i = 0; i < kVbBandCount; ++i) crossoverHz[i] = values[1 + i];
        layerHighPassHz = values[1 + kVbBandCount];
        layerLowPassHz = values[2 + kVbBandCount];
        for (int band = 0; band < kVbBandCount; ++band) {
            const float* b = values + kVbDesignHeader + band * kVbBandStride;
            centerHz[band] = b[0];
            orderCount[band] = std::max(0, std::min(kVbMaxOrders, static_cast<int>(b[1])));
            for (int i = 0; i < kVbMaxOrders; ++i) {
                orders[band][i] = std::max(1, std::min(16, static_cast<int>(b[2 + i * 2])));
                weights[band][i] = b[3 + i * 2];
            }
        }
        return true;
    }
};

class VirtualBassState {
public:
    bool configure(int requestedSampleRateHz,
                   int requestedChannelCount,
                   const float* designValues,
                   int designLength,
                   float strength,
                   float requestedInputReferenceGain) {
        VbDesign next;
        if (!next.read(designValues, designLength)) return false;
        design = next;
        sampleRateHz = std::max(1, requestedSampleRateHz);
        channelCount = std::max(1, requestedChannelCount);
        decimation = std::max(1, static_cast<int>(std::lround(sampleRateHz / kVbAnalysisRateHz)));
        analysisRateHz = sampleRateHz / static_cast<double>(decimation);
        dryDelayFrames = std::max(
                1, static_cast<int>(std::lround(sampleRateHz * kVbDryDelayMs / 1000.0f)));
        dryDelay.assign(static_cast<std::size_t>(dryDelayFrames * channelCount), 0.0f);
        realBass.assign(static_cast<std::size_t>(channelCount), VbRealBass{});
        realBoost.assign(static_cast<std::size_t>(channelCount), VbBiquad{});
        realCoefficients.configure(sampleRateHz);
        inputReferenceGain = std::max(0.000001f, requestedInputReferenceGain);
        mixAlpha = static_cast<float>(vbSmoothingAlpha(sampleRateHz, kVbMixTransitionMs));
        parameterAlpha = static_cast<float>(
                vbSmoothingAlpha(sampleRateHz, kVbParameterTransitionMs));
        frequencyAlpha = vbSmoothingAlpha(analysisRateHz, kVbFrequencySmoothingMs);
        phasePull = vbSmoothingAlpha(analysisRateHz, kVbPhasePullMs);
        subWeightAlpha = vbSmoothingAlpha(analysisRateHz, kVbSubWeightSmoothingMs);
        applyDesign();
        targetStrength = strength;
        currentStrength = strength;
        reset();
        return true;
    }

    bool applyParameters(const float* designValues, int designLength, float strength) {
        VbDesign next;
        if (!next.read(designValues, designLength)) return false;
        if (next.raw != design.raw) {
            design = next;
            applyDesign();
        }
        targetStrength = strength;
        return true;
    }

    int process(const float* input, float* output, int frameCount, bool enabled) {
        if (input == nullptr || output == nullptr || frameCount <= 0 || channelCount <= 0) {
            return 0;
        }
        for (int frame = 0; frame < frameCount; ++frame) {
            const float* in = input + frame * channelCount;
            float* out = output + frame * channelCount;
            const float targetWet = enabled ? 1.0f : 0.0f;
            wetMix += (targetWet - wetMix) * mixAlpha;
            currentStrength += (targetStrength - currentStrength) * parameterAlpha;

            const int delayBase = dryDelayWrite * channelCount;
            if (++dryDelayWrite >= dryDelayFrames) dryDelayWrite = 0;
            if (!enabled && wetMix <= 0.00001f) {
                wetMix = 0.0f;
                if (!steadyBypass) {
                    resetProcessing();
                    steadyBypass = true;
                }
                for (int channel = 0; channel < channelCount; ++channel) {
                    const float dry = sanitizeFloat(in[channel]);
                    dryDelay[delayBase + channel] = dry;
                    out[channel] = dry;
                }
                continue;
            }
            steadyBypass = false;

            const double referenceGain = inputReferenceGain;
            double mono = 0.0;
            for (int channel = 0; channel < channelCount; ++channel) {
                mono += sanitizeFloat(in[channel]);
            }
            mono = mono / channelCount / referenceGain;

            const double source = subsonic.process(mono);
            double rest = source;
            for (int band = 0; band < kVbBandCount; ++band) {
                double value;
                if (band == kVbSubBand) {
                    value = subLow2.process(splitLow[band].process(rest));
                    rest = subHigh2.process(splitHigh[band].process(rest));
                } else {
                    value = splitLow[band].process(rest);
                    if (band < kVbBandCount - 1) rest = -splitHigh[band].process(rest);
                }
                for (int i = 0; i < kVbUpperBand - 1 - band; ++i) {
                    value = bandAllPass[band][i].process(value);
                }
                bandSamples[band] = value;
            }
            if (++decimationPhase >= decimation) {
                decimationPhase = 0;
                heldLayer = synthesize();
            }

            const double layer = layerLowPass2.process(
                    layerLowPass.process(layerHighPass.process(heldLayer)));
            const double upper = upperLowPass2.process(
                    upperLowPass.process(upperHighPass.process(heldUpper)));
            const float strengthScale = currentStrength / kVbReferenceStrength * kVbLayerGain;
            const double scale = strengthScale * referenceGain;
            const float added = static_cast<float>(
                    (layer + static_cast<double>(kVbUpperLayerGain) * upper) * scale);
            const double lift = kVbRealBassGainPerStrength * currentStrength;
            const double boost = kVbRealBoostPerStrength * currentStrength;
            for (int channel = 0; channel < channelCount; ++channel) {
                const float dry = sanitizeFloat(in[channel]);
                const float delayed = dryDelay[delayBase + channel];
                dryDelay[delayBase + channel] = dry;
                const double real = realBass[channel].process(delayed, lift, realCoefficients);
                const float kept = static_cast<float>(real + boost * realBoost[channel].process(real));
                const float processed = channelCount <= 2 || channel < 2 ? kept + added : kept;
                out[channel] = sanitizeFloat(dry + (processed - dry) * wetMix);
            }
        }
        return frameCount;
    }

    void reset() {
        resetProcessing();
        std::fill(dryDelay.begin(), dryDelay.end(), 0.0f);
        dryDelayWrite = 0;
        wetMix = 0.0f;
        steadyBypass = false;
    }

    int channels() const { return channelCount; }

private:
    void applyDesign() {
        subsonic.highPass(kVbSubsonicHz, kVbButterworthQ, sampleRateHz);
        for (auto& filter : realBoost) filter.bandPass(kVbRealBoostHz, kVbRealBoostQ, sampleRateHz);
        const auto& edge = design.crossoverHz;
        for (int k = 0; k < kVbBandCount; ++k) {
            const double q = k == kVbSubBand ? kVbButterworthQ : kVbLr2Q;
            splitLow[k].lowPass(edge[k], q, sampleRateHz);
            if (k < kVbBandCount - 1) splitHigh[k].highPass(edge[k], q, sampleRateHz);
        }
        subLow2.lowPass(edge[kVbSubBand], kVbButterworthQ, sampleRateHz);
        subHigh2.highPass(edge[kVbSubBand], kVbButterworthQ, sampleRateHz);
        for (int band = 0; band < kVbBandCount; ++band) {
            for (int i = 0; i < kVbUpperBand - 1 - band; ++i) {
                bandAllPass[band][i].allPassFirstOrder(edge[band + 1 + i], sampleRateHz);
            }
        }
        const double t = std::tan(M_PI * std::min(static_cast<double>(edge[kVbUpperBand]),
                                                  sampleRateHz * 0.45) / sampleRateHz);
        upperAllPassC = (t - 1.0) / (t + 1.0);
        upperHighPass.highPass(kVbUpperHighPassHz, kVbButterworthQ, sampleRateHz);
        upperLowPass.lowPass(kVbUpperLowPassHz, kVbButterworth4Q1, sampleRateHz);
        upperLowPass2.lowPass(kVbUpperLowPassHz, kVbButterworth4Q2, sampleRateHz);
        layerHighPass.highPass(design.layerHighPassHz, kVbButterworthQ, sampleRateHz);
        layerLowPass.lowPass(design.layerLowPassHz, kVbButterworth4Q1, sampleRateHz);
        layerLowPass2.lowPass(design.layerLowPassHz, kVbButterworth4Q2, sampleRateHz);
    }

    void resetProcessing() {
        for (auto& state : realBass) state.clear();
        for (auto& filter : realBoost) filter.clear();
        subsonic.clear();
        for (auto& filter : splitLow) filter.clear();
        for (auto& filter : splitHigh) filter.clear();
        subLow2.clear();
        subHigh2.clear();
        for (auto& passes : bandAllPass) {
            for (auto& filter : passes) filter.clear();
        }
        subWeights.fill(0.0);
        for (auto& weights : kickWeights) weights.fill(0.0);
        upperWeights.fill(0.0);
        for (int band = 0; band < kVbBandCount; ++band) {
            hilbert[band].clear();
            previousRe[band] = 0.0;
            previousIm[band] = 0.0;
            crossSmooth[band] = 0.0;
            dotSmooth[band] = 0.0;
            phase[band] = 0.0;
        }
        layerHighPass.clear();
        layerLowPass.clear();
        layerLowPass2.clear();
        upperHighPass.clear();
        upperLowPass.clear();
        upperLowPass2.clear();
        decimationPhase = 0;
        heldLayer = 0.0;
        heldUpper = 0.0;
    }

    double synthesize() {
        double layer = 0.0;
        double upper = 0.0;
        for (int band = 0; band < kVbBandCount; ++band) {
            VbHilbert& pair = hilbert[band];
            pair.process(bandSamples[band]);
            const double re = pair.re;
            const double im = pair.im;
            const double cross = previousRe[band] * im - previousIm[band] * re;
            const double dot = previousRe[band] * re + previousIm[band] * im;
            previousRe[band] = re;
            previousIm[band] = im;
            crossSmooth[band] += (cross - crossSmooth[band]) * frequencyAlpha;
            dotSmooth[band] += (dot - dotSmooth[band]) * frequencyAlpha;
            const double nominal = kVbTwoPi * design.centerHz[band] / analysisRateHz;
            double step = crossSmooth[band] == 0.0 && dotSmooth[band] == 0.0
                    ? nominal : std::atan2(crossSmooth[band], dotSmooth[band]);
            step = std::max(nominal * 0.4, std::min(nominal * 2.5, step));
            const double envelope = std::sqrt(re * re + im * im);
            double theta = phase[band] + step;
            if (envelope > 1e-9) {
                double error = std::atan2(im, re) - theta;
                error -= kVbTwoPi * std::rint(error / kVbTwoPi);
                theta += error * phasePull;
            }
            theta -= kVbTwoPi * std::floor(theta / kVbTwoPi);
            phase[band] = theta;
            if (envelope <= 1e-9) continue;

            double growth = std::sqrt(envelope / static_cast<double>(kVbLoudnessReference));
            growth = std::max(static_cast<double>(kVbLoudnessMin),
                              std::min(static_cast<double>(kVbLoudnessMax), growth));
            const int count = design.orderCount[band];
            int highest = design.orders[band][std::max(0, count - 1)];
            if (band != kVbSubBand) highest = std::max(highest, kVbKickHighestOrder);
            const double c = std::cos(theta);
            double previous = 1.0;
            double current = c;
            chebyshev[0] = 1.0;
            chebyshev[1] = c;
            for (int n = 2; n <= highest; ++n) {
                const double next = 2.0 * c * current - previous;
                previous = current;
                current = next;
                chebyshev[n] = next;
            }
            double partials = 0.0;
            if (band == kVbSubBand) {
                partials = kVbSubLayerGain * subPartials(step);
            } else {
                for (int i = 0; i < count; ++i) {
                    partials += design.weights[band][i] * chebyshev[design.orders[band][i]];
                }
                if (band != kVbUpperBand) {
                    partials += kVbKickLayerGain * kickPartials(step, kickWeights[band]);
                }
                upper += envelope * growth * upperPartial(step, theta, band);
            }
            layer += envelope * growth * partials;
        }
        heldUpper = upper;
        return layer;
    }

    /** VirtualBassDsp.upperPartial: the note's 2nd harmonic; the upper band turned back by its all-pass lag. */
    double upperPartial(double step, double theta, int band) {
        const double hz = step * analysisRateHz / (2.0 * M_PI);
        const double target = gate(hz, kVbUpperGateLowHz, kVbUpperGateFullLowHz,
                                   kVbUpperGateFullHighHz, kVbUpperGateHighHz);
        upperWeights[band] += (target - upperWeights[band]) * subWeightAlpha;
        const double weight = upperWeights[band];
        if (weight <= 1e-9) return 0.0;
        if (band != kVbUpperBand) return weight * chebyshev[kVbUpperOrder];
        const double w = step / decimation;
        const double cosW = std::cos(w);
        const double sinW = std::sin(w);
        const double c = upperAllPassC;
        const double lag = std::atan2(-sinW, c + cosW) - std::atan2(-c * sinW, 1.0 + c * cosW);
        return weight * std::cos(kVbUpperOrder * (theta - lag));
    }

    static double gate(double hz, double low, double fullLow, double fullHigh, double high) {
        if (hz <= low || hz >= high) return 0.0;
        if (hz < fullLow) {
            const double t = (hz - low) / (fullLow - low);
            return 0.5 - 0.5 * std::cos(M_PI * t);
        }
        if (hz > fullHigh) {
            const double t = (hz - fullHigh) / (high - fullHigh);
            return 0.5 + 0.5 * std::cos(M_PI * t);
        }
        return 1.0;
    }

    static double subGate(double hz) {
        return gate(hz, kVbSubGateLowHz, kVbSubGateFullLowHz, kVbSubGateFullHighHz, kVbSubGateHighHz);
    }

    /** VirtualBassDsp.kickPartials: orders 2–4 of the band's note on a bell at 142 Hz. */
    double kickPartials(double step, std::array<double, kVbKickOrders>& weights) {
        const double hz = step * analysisRateHz / (2.0 * M_PI);
        std::array<double, kVbKickOrders> wanted{};
        double sum = 0.0;
        for (int i = 0; i < kVbKickOrders; ++i) {
            const double octaves = std::log((kVbKickFirstOrder + i) * hz / kVbKickTargetHz)
                    * kVbInverseLn2;
            const double x = octaves / kVbKickWidthOctaves;
            wanted[i] = std::exp(-0.5 * x * x);
            sum += wanted[i];
        }
        const double g = gate(hz, kVbKickGateLowHz, kVbKickGateFullLowHz,
                              kVbKickGateFullHighHz, kVbKickGateHighHz);
        double partials = 0.0;
        for (int i = 0; i < kVbKickOrders; ++i) {
            const double target = sum > 1e-12 ? g * wanted[i] / sum : 0.0;
            weights[i] += (target - weights[i]) * subWeightAlpha;
            partials += weights[i] * chebyshev[kVbKickFirstOrder + i];
        }
        return partials;
    }

    /** VirtualBassDsp.subPartials: orders of the tracked note weighted by a bell at 100 Hz. */
    double subPartials(double step) {
        const double hz = step * analysisRateHz / (2.0 * M_PI);
        std::array<double, kVbMaxOrders> wanted{};
        double sum = 0.0;
        for (int i = 0; i < kVbMaxOrders; ++i) {
            const double octaves = std::log((kVbSubFirstOrder + i) * hz / kVbSubTargetHz)
                    * kVbInverseLn2;
            const double x = octaves / kVbSubWidthOctaves;
            wanted[i] = std::exp(-0.5 * x * x);
            sum += wanted[i];
        }
        const double gate = subGate(hz);
        double partials = 0.0;
        for (int i = 0; i < kVbMaxOrders; ++i) {
            const double target = sum > 1e-12 ? gate * wanted[i] / sum : 0.0;
            subWeights[i] += (target - subWeights[i]) * subWeightAlpha;
            partials += subWeights[i] * chebyshev[kVbSubFirstOrder + i];
        }
        return partials;
    }

    VbDesign design;
    int sampleRateHz = 44100;
    int channelCount = 2;
    int decimation = 1;
    double analysisRateHz = kVbAnalysisRateHz;
    float inputReferenceGain = 1.0f;
    float mixAlpha = 0.0f;
    float parameterAlpha = 0.0f;
    double frequencyAlpha = 0.0;
    double phasePull = 0.0;
    float targetStrength = 20.0f;
    float currentStrength = 20.0f;
    float wetMix = 0.0f;
    bool steadyBypass = false;

    std::vector<float> dryDelay;
    int dryDelayFrames = 1;
    int dryDelayWrite = 0;
    std::vector<VbRealBass> realBass;
    std::vector<VbBiquad> realBoost;
    VbRealBassCoefficients realCoefficients;
    VbBiquad subsonic, subLow2, subHigh2;
    std::array<VbBiquad, kVbBandCount> splitLow{};
    std::array<VbBiquad, kVbBandCount - 1> splitHigh{};
    std::array<std::array<VbBiquad, kVbBandCount - 1>, kVbBandCount> bandAllPass{};
    std::array<double, kVbMaxOrders> subWeights{};
    std::array<std::array<double, kVbKickOrders>, kVbBandCount> kickWeights{};
    std::array<double, kVbBandCount> upperWeights{};
    double subWeightAlpha = 0.0;
    VbBiquad layerHighPass, layerLowPass, layerLowPass2;
    VbBiquad upperHighPass, upperLowPass, upperLowPass2;
    double upperAllPassC = 0.0;
    std::array<VbHilbert, kVbBandCount> hilbert{};
    std::array<double, kVbBandCount> bandSamples{};
    std::array<double, kVbBandCount> previousRe{};
    std::array<double, kVbBandCount> previousIm{};
    std::array<double, kVbBandCount> crossSmooth{};
    std::array<double, kVbBandCount> dotSmooth{};
    std::array<double, kVbBandCount> phase{};
    std::array<double, 20> chebyshev{};
    int decimationPhase = 0;
    double heldLayer = 0.0;
    double heldUpper = 0.0;
};
}

extern "C" void* folia_bass_create(int rate,int channels,const float* design,float gain,float reference) {
    try {auto* p=new VirtualBassState();if(!p->configure(rate,channels,design,kVbDesignLength,gain,reference)){delete p;return nullptr;}return p;} catch(...){return nullptr;}
}
extern "C" void folia_bass_destroy(void* p){delete static_cast<VirtualBassState*>(p);}
extern "C" void folia_bass_reset(void* p){static_cast<VirtualBassState*>(p)->reset();}
extern "C" void folia_bass_parameters(void* p,const float* design,float gain){static_cast<VirtualBassState*>(p)->applyParameters(design,kVbDesignLength,gain);}
extern "C" void folia_bass_process(void* p,float* data,int frames,bool enabled){static_cast<VirtualBassState*>(p)->process(data,data,frames,enabled);}

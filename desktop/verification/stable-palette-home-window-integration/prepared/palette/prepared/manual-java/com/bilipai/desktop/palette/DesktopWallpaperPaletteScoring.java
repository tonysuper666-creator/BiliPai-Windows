/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Original Palette1.0.0 target scoring, not another quantizer. */
package com.bilipai.desktop.palette;
import com.bilipai.desktop.palette.DesktopPalette.Swatch;
import java.util.*;
public final class DesktopWallpaperPaletteScoring {
 private final List<Swatch> mSwatches;
 private final List<DesktopPaletteTarget> mTargets;
 private final Map<DesktopPaletteTarget,Swatch> mSelectedSwatches=new HashMap<>();
 private final Set<Integer> mUsedColors=new HashSet<>();
 private final Swatch mDominantSwatch;
 public DesktopWallpaperPaletteScoring(List<Swatch> swatches) {
  mSwatches=swatches;
  mTargets=Arrays.asList(DesktopPaletteTarget.LIGHT_VIBRANT,DesktopPaletteTarget.VIBRANT,DesktopPaletteTarget.DARK_VIBRANT,DesktopPaletteTarget.LIGHT_MUTED,DesktopPaletteTarget.MUTED,DesktopPaletteTarget.DARK_MUTED);
  mDominantSwatch=findDominantSwatch();generate();
 }
 public Swatch getVibrantSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.VIBRANT);}
 public Swatch getLightVibrantSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.LIGHT_VIBRANT);}
 public Swatch getDarkVibrantSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.DARK_VIBRANT);}
 public Swatch getMutedSwatch(){return mSelectedSwatches.get(DesktopPaletteTarget.MUTED);}
 public Swatch getDominantSwatch(){return mDominantSwatch;}
    void generate() {
        // We need to make sure that the scored targets are generated first. This is so that
        // inherited targets have something to inherit from
        for (int i = 0, count = mTargets.size(); i < count; i++) {
            final DesktopPaletteTarget target = mTargets.get(i);
            target.normalizeWeights();
            mSelectedSwatches.put(target, generateScoredTarget(target));
        }
        // We now clear out the used colors
        mUsedColors.clear();
    }

    private Swatch generateScoredTarget(final DesktopPaletteTarget target) {
        final Swatch maxScoreSwatch = getMaxScoredSwatchForTarget(target);
        if (maxScoreSwatch != null && target.isExclusive()) {
            // If we have a swatch, and the target is exclusive, add the color to the used list
            mUsedColors.add(maxScoreSwatch.getRgb());
        }
        return maxScoreSwatch;
    }

    private Swatch getMaxScoredSwatchForTarget(final DesktopPaletteTarget target) {
        float maxScore = 0;
        Swatch maxScoreSwatch = null;
        for (int i = 0, count = mSwatches.size(); i < count; i++) {
            final Swatch swatch = mSwatches.get(i);
            if (shouldBeScoredForTarget(swatch, target)) {
                final float score = generateScore(swatch, target);
                if (maxScoreSwatch == null || score > maxScore) {
                    maxScoreSwatch = swatch;
                    maxScore = score;
                }
            }
        }
        return maxScoreSwatch;
    }

    private boolean shouldBeScoredForTarget(final Swatch swatch, final DesktopPaletteTarget target) {
        // Check whether the HSL values are within the correct ranges, and this color hasn't
        // been used yet.
        final float hsl[] = swatch.getHsl();
        return hsl[1] >= target.getMinimumSaturation() && hsl[1] <= target.getMaximumSaturation()
                && hsl[2] >= target.getMinimumLightness() && hsl[2] <= target.getMaximumLightness()
                && !mUsedColors.contains(swatch.getRgb());
    }

    private float generateScore(Swatch swatch, DesktopPaletteTarget target) {
        final float[] hsl = swatch.getHsl();

        float saturationScore = 0;
        float luminanceScore = 0;
        float populationScore = 0;

        final int maxPopulation = mDominantSwatch != null ? mDominantSwatch.getPopulation() : 1;

        if (target.getSaturationWeight() > 0) {
            saturationScore = target.getSaturationWeight()
                    * (1f - Math.abs(hsl[1] - target.getTargetSaturation()));
        }
        if (target.getLightnessWeight() > 0) {
            luminanceScore = target.getLightnessWeight()
                    * (1f - Math.abs(hsl[2] - target.getTargetLightness()));
        }
        if (target.getPopulationWeight() > 0) {
            populationScore = target.getPopulationWeight()
                    * (swatch.getPopulation() / (float) maxPopulation);
        }

        return saturationScore + luminanceScore + populationScore;
    }

    private Swatch findDominantSwatch() {
        int maxPop = Integer.MIN_VALUE;
        Swatch maxSwatch = null;
        for (int i = 0, count = mSwatches.size(); i < count; i++) {
            Swatch swatch = mSwatches.get(i);
            if (swatch.getPopulation() > maxPop) {
                maxSwatch = swatch;
                maxPop = swatch.getPopulation();
            }
        }
        return maxSwatch;
    }
}

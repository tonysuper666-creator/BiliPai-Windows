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

package com.bilipai.desktop.palette;
import java.util.List;
public final class DesktopPalette {
public static final class Swatch {
private final int mRed,mGreen,mBlue,mRgb,mPopulation; private float[] mHsl;
public Swatch(int color, int population) {
            mRed = Color.red(color);
            mGreen = Color.green(color);
            mBlue = Color.blue(color);
            mRgb = color;
            mPopulation = population;
        }
public int getRgb() {
            return mRgb;
        }
public float[] getHsl() {
            if (mHsl == null) {
                mHsl = new float[3];
            }
            ColorUtils.RGBToHSL(mRed, mGreen, mBlue, mHsl);
            return mHsl;
        }
public int getPopulation() {
            return mPopulation;
        }
}
public interface Filter {
        /**
         * Hook to allow clients to be able filter colors from resulting palette.
         *
         * @param rgb the color in RGB888.
         * @param hsl HSL representation of the color.
         *
         * @return true if the color is allowed, false if not.
         *
         * @see Builder#addFilter(Filter)
         */
        boolean isAllowed(int rgb, float[] hsl);
    }
public static List<Swatch> quantize(int[] pixels,int maximum){return new ColorCutQuantizer(pixels,maximum,null).getQuantizedColors();}
}

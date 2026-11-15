package io.relimus.zflow.xposed.nativewin;
/* Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 * Adapted thresholds; new Android-independent input/output helpers. */

/** SunOS thresholds, separated from Android input so rotation/threshold cases can be checked. */
final class GestureMath {
    static String release(float visualScale, float defaultVisual, boolean appLandscape, boolean displayLandscape) {
        if (!Float.isFinite(visualScale) || defaultVisual<=0) throw new IllegalArgumentException("scale");
        float originalDefault=appLandscape?(displayLandscape?.68f:.9f):(displayLandscape?.9f:.75f);
        float normalized=visualScale/defaultVisual*originalDefault;
        boolean reversed=appLandscape!=displayLandscape;
        return normalized<=(reversed?.66f:.6f)?"mini":normalized>(reversed?1f:.86f)?"full":"normal";
    }
    static float clampScale(float scale,float normal,boolean appLandscape,boolean displayLandscape){
        if(!Float.isFinite(scale)||!Float.isFinite(normal)||normal<=0)throw new IllegalArgumentException("scale");
        float original=appLandscape?(displayLandscape?.68f:.9f):(displayLandscape?.9f:.75f);
        float max=(appLandscape!=displayLandscape?1.1f:.96f)*normal/original;
        float min=Math.min(.28f,normal);
        return Math.max(min,Math.min(Math.max(min,max),scale));
    }
    static String horizontalRelease(float delta,float threshold) {
        if(!Float.isFinite(delta)||!Float.isFinite(threshold)||threshold<=0)throw new IllegalArgumentException("distance");
        return delta>=threshold?"full":delta<=-threshold?"mini":"normal";
    }
    static float horizontalScale(float start,float delta,float threshold,float maximum){
        if(!Float.isFinite(start)||start<=0||!Float.isFinite(maximum)||maximum<=0)
            throw new IllegalArgumentException("scale");
        horizontalRelease(delta,threshold);
        float progress=Math.max(-1,Math.min(1,delta/threshold));
        return Math.min(Math.max(start,maximum),start*(1+.10f*progress));
    }
    static float dragScale(float start, float delta, int logicalAxis) {
        if (logicalAxis<=0) throw new IllegalArgumentException("axis");
        return Math.max(.28f,start+2f*delta/logicalAxis);
    }
}

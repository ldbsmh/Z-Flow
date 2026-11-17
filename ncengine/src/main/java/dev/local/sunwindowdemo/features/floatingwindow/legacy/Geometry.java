package dev.local.sunwindowdemo;

/** SPDX-License-Identifier: Apache-2.0
 * Geometry adapted from Nameless/SunOS WindowResizingAlgorithm.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Changes: pure Java, explicit safe rectangle, landscape left placement.
 */
final class Geometry {
    record Placement(int width, int height, float scale, float x, float y) {}

    static Placement miniTopRight(Placement p,float normal,int left,int top,int right,int bottom,float margin){
        return miniTopRight(p,normal,left,top,right,bottom,margin,false);
    }
    static Placement miniTopRight(Placement p,float normal,int left,int top,int right,int bottom,float margin,boolean large){
        return miniTopRight(p,normal,left,top,right,bottom,margin,large,WindowSettings.DEFAULT_MINI);
    }
    /**
     * @param miniPercent 迷你窗宽度占逻辑画布的百分比（Z-Flow 的 freeform_float_view_size）。
     *                    接入设置前这里是固定 .28f，默认 25 后略小一点，但可被用户调整。
     */
    static Placement miniTopRight(Placement p,float normal,int left,int top,int right,int bottom,float margin,boolean large,int miniPercent){
        if(p==null||p.width()<=0||p.height()<=0||!Float.isFinite(normal)||normal<=0
            ||!Float.isFinite(margin)||right<=left||bottom<=top||margin<0)throw new IllegalArgumentException("safe area");
        // Only the expanded mini changes: a landscape preview on a portrait display
        // spans the safe width, giving equal side margins like the reference preview.
        float small=Math.min(Math.max(.10f,miniPercent/100f),normal);
        boolean widePreview=large&&p.width()>p.height()&&bottom-top>right-left;
        float scale=widePreview?(right-left-2*margin)/p.width()
            :large?Math.max(small,Math.min(normal*.95f,p.width()>p.height()?.60f:.50f)):small;
        scale=Math.min(scale,Math.min((right-left-2*margin)/p.width(),(bottom-top-2*margin)/p.height()));
        if(scale<=0)throw new IllegalArgumentException("mini fit");
        return new Placement(p.width(),p.height(),scale,right-margin-p.width()*scale,top+margin);
    }
    static Placement resizeMini(Placement p,float normal,int left,int top,int right,int bottom,float margin,boolean large){
        return resizeMini(p,normal,left,top,right,bottom,margin,large,WindowSettings.DEFAULT_MINI);
    }
    static Placement resizeMini(Placement p,float normal,int left,int top,int right,int bottom,float margin,boolean large,int miniPercent){
        Placement target=miniTopRight(p,normal,left,top,right,bottom,margin,large,miniPercent);
        float available=bottom-top-2*margin-p.height()*p.scale();
        float ratio=available<=0?0:Math.max(0,Math.min(1,(p.y()-top-margin)/available));
        boolean dockLeft=p.x()+p.width()*p.scale()/2<(left+right)/2f;
        float x=dockLeft?left+margin:target.x();
        float y=top+margin+ratio*Math.max(0,bottom-top-2*margin-p.height()*target.scale());
        return new Placement(p.width(),p.height(),target.scale(),x,y);
    }
    /** SunOS pinned windows retain their edge and vertical fraction after a display change. */
    static Placement remapMini(Placement previous,Placement canvas,float normal,
            int oldLeft,int oldTop,int oldRight,int oldBottom,float oldMargin,
            int left,int top,int right,int bottom,float margin,boolean large){
        return remapMini(previous,canvas,normal,oldLeft,oldTop,oldRight,oldBottom,oldMargin,
            left,top,right,bottom,margin,large,WindowSettings.DEFAULT_MINI);
    }
    static Placement remapMini(Placement previous,Placement canvas,float normal,
            int oldLeft,int oldTop,int oldRight,int oldBottom,float oldMargin,
            int left,int top,int right,int bottom,float margin,boolean large,int miniPercent){
        Placement target=miniTopRight(canvas,normal,left,top,right,bottom,margin,large,miniPercent);
        float range=oldBottom-oldTop-2*oldMargin-previous.height()*previous.scale();
        float ratio=range<=0?.5f:clamp((previous.y()-oldTop-oldMargin)/range,0,1);
        boolean onLeft=previous.x()+previous.width()*previous.scale()/2<(oldLeft+oldRight)/2f;
        float y=top+margin+ratio*Math.max(0,bottom-top-2*margin-target.height()*target.scale());
        return new Placement(target.width(),target.height(),target.scale(),onLeft?left+margin:target.x(),y);
    }
    /** The keyboard changes only presentation Y, never the logical canvas or fixed home position. */
    static Placement avoidIme(Placement normal,int displayWidth,int displayHeight,int bottomInset){
        if(displayWidth>displayHeight||bottomInset<=0)return normal;
        float y=Math.max(0,Math.min(normal.y(),displayHeight-bottomInset-normal.height()*normal.scale()));
        return y==normal.y()?normal:new Placement(normal.width(),normal.height(),normal.scale(),normal.x(),y);
    }
    static int hideSide(Placement p,float proposedX,int displayWidth){
        if(!Float.isFinite(proposedX)||displayWidth<=0)throw new IllegalArgumentException("position");
        float width=p.width()*p.scale();
        return proposedX<=-width/3?-1:proposedX+width>=displayWidth+width/3?1:0;
    }
    static int hideSide(Placement p,float proposedX,int left,int right,float margin){
        if(!Float.isFinite(proposedX))throw new IllegalArgumentException("position");
        float center=proposedX+p.width()*p.scale()/2;
        return center<=left+margin?-1:center>=right-margin?1:0;
    }
    static Placement offscreen(Placement p,int side,int left,int right,float gap){
        if(side!=-1&&side!=1)throw new IllegalArgumentException("edge");
        return new Placement(p.width(),p.height(),p.scale(),side<0?left-p.width()*p.scale()-gap:right+gap,p.y());
    }
    private static float clamp(float v,float low,float high){return Math.max(low,Math.min(v,Math.max(low,high)));}
    static Placement place(int logicalShort, boolean appLandscape,
            int left, int top, int right, int bottom, int margin) {
        return place(logicalShort, appLandscape, left, top, right, bottom, margin,
            WindowSettings.DEFAULT_SIZE, WindowSettings.DEFAULT_SIZE_LAND);
    }
    /**
     * @param sizePercent 竖屏显示时窗口宽度占屏幕宽度的百分比（Z-Flow 的 freeform_size）。
     * @param landPercent 横屏显示时的高度比例（Z-Flow 的 freeform_size_land）。
     *
     * 默认 75 / 90 与接入设置前的硬编码（.75 / .9）完全一致：不配置就不改变现有表现。
     * 横屏显示时旧代码用的是 .75 / 1f，这里以 90% 为基准折算，设置 90 时仍是原值。
     */
    static Placement place(int logicalShort, boolean appLandscape,
            int left, int top, int right, int bottom, int margin, int sizePercent, int landPercent) {
        if (logicalShort <= 0 || right <= left || bottom <= top || margin < 0)
            throw new IllegalArgumentException("geometry");
        int longSide = Math.round(logicalShort * (16f / 9f));
        int w = appLandscape ? longSide : logicalShort;
        int h = appLandscape ? logicalShort : longSide;
        boolean displayPortrait = bottom - top >= right - left;
        float land = Math.max(10, Math.min(100, landPercent)) / 100f;
        float scale = displayPortrait
            ? (appLandscape ? land : Math.max(10, Math.min(100, sizePercent)) / 100f)
            : (appLandscape ? .75f : 1f) * (land / (WindowSettings.DEFAULT_SIZE_LAND / 100f));
        if (appLandscape == displayPortrait) scale *= (float) logicalShort / longSide;
        int availableW = right - left - 2 * margin;
        int availableH = bottom - top - 2 * margin;
        if (availableW <= 0 || availableH <= 0) throw new IllegalArgumentException("safe area");
        float fit=Math.min((float)availableW/w,(float)availableH/h);
        scale=displayPortrait?Math.min(scale,fit):fit;
        // Leave room for the rightward +10% handle feedback on a landscape canvas.
        if(!displayPortrait&&appLandscape)scale*=.90f;
        if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("scale");
        return new Placement(w, h, scale,
            displayPortrait ? left + (right - left - w * scale) / 2f : left + margin,
            top + (bottom - top - h * scale) / 2f);
    }
}

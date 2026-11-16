package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.LauncherApps;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import android.icu.text.Transliterator;
import java.util.List;

final class AppCatalog {
    static final class App {
        final String pkg, label, section, sortKey;
        final Drawable icon;
        App(String p, String l, Drawable i) {
            pkg=p; label=l; icon=i; sortKey=AppCatalog.sortKey(l); section=AppCatalog.sectionFromKey(sortKey);
        }
        @Override public String toString() { return label; }
    }
    static Drawable normalized(Context c,Drawable icon){
        // Draw adaptive layers once at local origin; never share their mutable drawing cache
        // between the full-screen fan Canvas and ImageViews.
        if(icon instanceof AdaptiveIconDrawable)return raster(c,icon);
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        int width=Math.max(1,icon.getIntrinsicWidth()),height=Math.max(1,icon.getIntrinsicHeight());
        float scale=192f/Math.max(width,height);int w=Math.round(width*scale),h=Math.round(height*scale);
        Rect before=new Rect(icon.getBounds());icon.setBounds((192-w)/2,(192-h)/2,(192+w)/2,(192+h)/2);
        icon.draw(new Canvas(bitmap));icon.setBounds(before);
        return raster(c,new AdaptiveIconDrawable(new ColorDrawable(Color.WHITE),new InsetDrawable(new BitmapDrawable(c.getResources(),bitmap),.18f)));
    }
    static Drawable icon(Context c,String pkg,Drawable original){
        if(!dev.local.sunwindowdemo.features.iconlayers.IconAssets.ready())return new ColorDrawable(Color.TRANSPARENT);
        Drawable adapted=dev.local.sunwindowdemo.features.iconlayers.IconAssets.colored(c,pkg);
        return normalized(c,adapted!=null?adapted:original);
    }
    /** Menu/open-wait use the same original or colour-adapted artwork, never Monet. */
    static Drawable windowIcon(Context c,String pkg,Drawable original){return icon(c,pkg,original);}
    private static Drawable raster(Context c,Drawable icon){
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        Rect saved=new Rect(icon.getBounds());int alpha=icon.getAlpha();
        try{icon.setBounds(0,0,192,192);icon.setAlpha(255);icon.draw(new Canvas(bitmap));}
        finally{icon.setBounds(saved);icon.setAlpha(alpha);}
        return new BitmapDrawable(c.getResources(),bitmap);
    }
    static List<App> load(Context c) {
        List<App> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        LauncherApps launcher=c.getSystemService(LauncherApps.class);
        for (var activity : launcher.getActivityList(null,android.os.Process.myUserHandle())) {
            String pkg = activity.getComponentName().getPackageName();
            if (pkg.equals(Wire.PKG) || !seen.add(pkg)) continue;
            try { result.add(new App(pkg, activity.getLabel().toString(), windowIcon(c,pkg,activity.getIcon(c.getResources().getDisplayMetrics().densityDpi)))); }
            catch (RuntimeException ignored) { }
        }
        result.sort(Comparator.comparing((App a)->a.section.equals("#")?"~"+a.sortKey:a.sortKey)
                .thenComparing(a->a.pkg));
        return result;
    }

    private static final ThreadLocal<Transliterator> PINYIN=ThreadLocal.withInitial(
            ()->Transliterator.getInstance("Han-Latin; Latin-ASCII; Upper"));
    static String sortKey(String label) {
        String value=label==null?"":label.trim();
        try { return PINYIN.get().transliterate(value).toUpperCase(Locale.ROOT); }
        catch (RuntimeException unavailable) { return value.toUpperCase(Locale.ROOT); }
    }
    private static String sectionFromKey(String key) {
        return !key.isEmpty()&&key.charAt(0)>='A'&&key.charAt(0)<='Z'?key.substring(0,1):"#";
    }
    static String section(String label) { return sectionFromKey(sortKey(label)); }

}

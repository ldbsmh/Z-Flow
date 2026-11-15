package io.relimus.zflow.xposed.nativewin;

/** Display-independent geometry; SunOS bar dimensions, adapted radius normalization. */
final class MotionStyle {
    record Bar(float length,float thickness,float margin) {}
    record Visibility(Geometry.Placement placement,float alpha) {}
    record Decoration(float handle,float dim) {}
    static Decoration entryDecoration(float progress,boolean fadeIn){
        if(!Float.isFinite(progress))throw new IllegalArgumentException("decoration progress");
        return new Decoration(smooth((progress-(fadeIn?.25f:.65f))/(fadeIn?.5f:.35f)),
            smooth(progress/(fadeIn?.625f:1)));
    }
    private static float smooth(float value){float p=Math.max(0,Math.min(1,value));return p*p*(3-2*p);}
    /** The same centered 94%→100% path is sampled forward for entry, backward for exit. */
    static Visibility visibility(Geometry.Placement target,float progress){
        if(target==null||!Float.isFinite(progress))throw new IllegalArgumentException("visibility");
        float alpha=Math.max(0,Math.min(1,progress)),factor=.94f+.06f*alpha;
        float scale=target.scale()*factor;
        return new Visibility(new Geometry.Placement(target.width(),target.height(),scale,
            target.x()+target.width()*(target.scale()-scale)/2,
            target.y()+target.height()*(target.scale()-scale)/2),alpha);
    }
    static Bar bar(float scale){
        if(!Float.isFinite(scale)||scale<=0)throw new IllegalArgumentException("scale");
        return new Bar(114*scale,4.8f*scale,16*scale);
    }
    static float radius(float scale,float normal){
        if(!Float.isFinite(scale)||!Float.isFinite(normal)||scale<=0||normal<=0)
            throw new IllegalArgumentException("scale");
        return 24*Math.min(1,scale/normal);
    }
    static Geometry.Placement interpolate(Geometry.Placement a,Geometry.Placement b,float t){
        if(!Float.isFinite(t))throw new IllegalArgumentException("progress");
        t=Math.max(0,Math.min(1,t));
        return new Geometry.Placement(b.width(),b.height(),a.scale()+(b.scale()-a.scale())*t,
            a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t);
    }
}

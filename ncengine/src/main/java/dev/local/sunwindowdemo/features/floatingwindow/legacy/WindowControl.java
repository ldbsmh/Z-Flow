package dev.local.sunwindowdemo;

/** Shared control decisions and value-state transforms; native ownership and execution stay in the engine. */
final class WindowControl {
    enum Route { IGNORE, MUTE, RESTORE_MINI, MINI_SIZE, EDGE, MINI_DRAG, GENERAL }
    record Facts(String form,boolean closing,boolean fullTransition,boolean transitioning,boolean entering,boolean edgeHidden) {}
    static boolean entryRequest(String op){
        return op!=null&&switch(op){case "RESIZE","END_RESIZE","RELEASE","NORMAL","MINI","FULL","CLOSE"->true;default->false;};
    }
    static boolean cancelsLaunch(String op){return "CLOSE".equals(op)||"FULL".equals(op);}
    /** Runs after launch cancellation: CLOSE/FULL still cancel a pending launch on an already closing owner. */
    static Route route(String op,Facts f){
        if(f.closing||f.fullTransition&&!"CLOSE".equals(op))return Route.IGNORE;
        boolean mini="mini".equals(f.form);
        if("MUTE".equals(op))return mini&&!f.transitioning&&!f.entering?Route.MUTE:Route.IGNORE;
        if("RESTORE_MINI".equals(op))return mini&&f.edgeHidden&&!f.transitioning?Route.RESTORE_MINI:Route.IGNORE;
        if("MINI_SIZE".equals(op))return mini&&!f.edgeHidden&&!f.transitioning&&!f.entering?Route.MINI_SIZE:Route.IGNORE;
        if(op!=null&&op.startsWith("EDGE_"))return mini&&f.edgeHidden&&!f.entering&&!f.transitioning?Route.EDGE:Route.IGNORE;
        if("MINI_DRAG".equals(op)||"MINI_DRAG_CANCEL".equals(op))return mini&&!f.edgeHidden&&!f.entering?Route.MINI_DRAG:Route.IGNORE;
        return Route.GENERAL;
    }
    static String form(String op){
        if(op==null)return null;
        return switch(op){case "CLOSE"->"closed";case "FULL"->"full";case "MINI"->"mini";case "NORMAL"->"normal";default->null;};
    }
    enum SlotChange { KEEP, EXPAND_PINNED, MINIMIZE_ACTIVE }
    static SlotChange slot(String target,boolean sourceActive,boolean sourcePinned){
        if("normal".equals(target)&&sourcePinned)return SlotChange.EXPAND_PINNED;
        return "mini".equals(target)&&sourceActive?SlotChange.MINIMIZE_ACTIVE:SlotChange.KEEP;
    }
    enum Previous { KEEP, RETAIN_MINI, MINIMIZE, FINISH }
    static Previous previous(boolean present,boolean different,boolean sameToken,String form,boolean autoMini,boolean validMini){
        if(!present||!different||sameToken)return Previous.KEEP;
        if("mini".equals(form))return Previous.RETAIN_MINI;
        return WindowPolicy.autoMini(autoMini,different,sameToken,form,validMini)?Previous.MINIMIZE:Previous.FINISH;
    }
    static Geometry.Placement resize(Geometry.Placement p,float scale,float normal,boolean landscape,boolean horizontal){
        if(!Float.isFinite(scale))throw new IllegalArgumentException("scale");
        return centered(p,GestureMath.clampScale(scale,normal,landscape,horizontal),centerX(p),centerY(p));
    }
    record Drag(Geometry.Placement placement,float opacity) {}
    static Drag drag(Geometry.Placement base,float opacity,float progress,float cx,float cy){
        if(!Float.isFinite(progress)||!Float.isFinite(cx)||!Float.isFinite(cy))return null;
        return new Drag(centered(base,base.scale()*MiniMagnet.scaleFactor(progress),cx,cy),opacity*MiniMagnet.alphaFactor(progress));
    }
    static Geometry.Placement endDrag(Geometry.Placement base,Geometry.Placement current,boolean cancel){
        return cancel?base:centered(base,base.scale(),centerX(current),centerY(current));
    }
    record Safe(int left,int top,int right,int bottom,float margin) {}
    record Dock(Geometry.Placement placement,int hiddenSide) {}
    static Dock move(Geometry.Placement p,float x,float y,Safe safe,boolean dock,int displayWidth){
        if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("position");
        if(dock){
            int side=Geometry.hideSide(p,x,displayWidth);if(side!=0)return new Dock(p,side);
            x=dockX(p,x,safe);
        }
        return new Dock(new Geometry.Placement(p.width(),p.height(),p.scale(),
            Math.max(safe.left,Math.min(x,safe.right-p.width()*p.scale())),
            Math.max(safe.top,Math.min(y,safe.bottom-p.height()*p.scale()))),0);
    }
    static Geometry.Placement hiddenMini(Geometry.Placement p,int side,float y,Safe safe){
        y=Math.max(safe.top+safe.margin,Math.min(y,safe.bottom-safe.margin-p.height()*p.scale()));
        return new Geometry.Placement(p.width(),p.height(),p.scale(),side<0?safe.left+safe.margin:safe.right-safe.margin-p.width()*p.scale(),y);
    }
    record Edge(Geometry.Placement placement,Geometry.Placement mini,int side,boolean hidden,boolean barDragging,float barX,float barY) {}
    static Edge cancelEdge(Edge current,Edge base){
        Edge kept=base==null?current:base;
        return new Edge(kept.placement,kept.mini,kept.side,current.hidden,false,current.barX,current.barY);
    }
    static Edge edgeBarDock(Geometry.Placement mini,float x,float y,Safe safe,int displayWidth){
        int side=x<displayWidth/2f?-1:1;
        Geometry.Placement visible=hiddenMini(mini,side,y-mini.height()*mini.scale()/2,safe);
        return new Edge(Geometry.offscreen(visible,side,0,displayWidth,safe.margin),visible,side,true,false,x,y);
    }
    static Dock edgeMove(Geometry.Placement p,float x,float y,Safe safe,boolean dock,int displayWidth){
        // Edge drags are validated by the caller before native safe-area sampling.
        y=Math.max(safe.top,Math.min(y,safe.bottom-p.height()*p.scale()));
        Geometry.Placement destination=new Geometry.Placement(p.width(),p.height(),p.scale(),x,y);
        if(dock){
            int side=Geometry.hideSide(destination,x,displayWidth);if(side!=0)return new Dock(destination,side);
            destination=new Geometry.Placement(p.width(),p.height(),p.scale(),dockX(p,x,safe),y);
        }
        return new Dock(destination,0);
    }
    static Geometry.Placement miniTarget(Geometry.Placement start,Geometry.Placement retained,float normal,Safe safe,boolean large){
        return retained==null?Geometry.miniTopRight(start,normal,safe.left,safe.top,safe.right,safe.bottom,safe.margin,large)
            :Geometry.resizeMini(retained,normal,safe.left,safe.top,safe.right,safe.bottom,safe.margin,large);
    }
    private static float dockX(Geometry.Placement p,float x,Safe safe){
        return x+p.width()*p.scale()/2<((safe.left+safe.right)>>1)?safe.left+safe.margin:safe.right-safe.margin-p.width()*p.scale();
    }
    private static float centerX(Geometry.Placement p){return p.x()+p.width()*p.scale()/2;}
    private static float centerY(Geometry.Placement p){return p.y()+p.height()*p.scale()/2;}
    private static Geometry.Placement centered(Geometry.Placement p,float scale,float cx,float cy){
        return new Geometry.Placement(p.width(),p.height(),scale,cx-p.width()*scale/2,cy-p.height()*scale/2);
    }
}

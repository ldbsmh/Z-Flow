package dev.local.sunwindowdemo;

/** Observes the native input monitor; ownership starts only after a diagonal swipe. */
final class CornerGesture {
    static final int PASS=-1,TRACK=0,BEGIN=1,CLAIM=2,MOVE=3,FINISH=4,END=5,CANCEL=6;
    record Area(int width,int height,int heat,float distance,int longPress) {}
    private Area area;
    private boolean tracking,claimed,passive,right;
    private long down;private int device;private float x,y;
    boolean right(){return right;}
    synchronized void abandon(){passive=true;claimed=false;}
    synchronized int event(int action,float px,float py,boolean finger,int pointers,int display,int id,long began,long time,Area current){
        if(action==0){
            if(display!=0||tracking&&id!=device)return PASS;
            tracking=false;claimed=false;passive=false;
            if(!finger||pointers!=1||current==null||px<0||py<current.height-current.heat||py>=current.height
                ||px>=current.width||px>=current.heat&&px<current.width-current.heat)return PASS;
            area=current;right=px>=current.width-current.heat;x=px;y=py;device=id;down=began;tracking=true;return BEGIN;
        }
        if(!tracking||display!=0||id!=device||began!=down)return PASS;
        boolean interrupted=current==null||!area.equals(current)||pointers!=1||action==5;
        if(interrupted||!claimed&&time-down>=area.longPress)passive=true;
        if(interrupted&&claimed&&action!=1&&action!=3){claimed=false;return CANCEL;}
        if(action==3){tracking=false;claimed=false;return CANCEL;}
        if(action==1){int result=claimed&&!passive?FINISH:END;tracking=false;claimed=false;return result;}
        if(passive)return TRACK;
        if(action==2){
            if(!claimed&&Rules.triggered(px-x,py-y,right,area.distance)){claimed=true;return CLAIM;}
            return claimed?MOVE:TRACK;
        }
        return TRACK;
    }
}

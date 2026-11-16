package dev.local.sunwindowdemo;

/** Waiting-request ownership, independent of Android views and scheduling. */
final class OpenWaitState {
    enum Change { KEEP, HIDE, SHOW }
    record Request(String token,String pkg) {}
    private Request current;
    private String cancelledToken;

    Change update(boolean hasWaiting,boolean waiting,boolean valid,boolean permitted,String token,String pkg){
        if(!hasWaiting)return Change.KEEP;
        if(!waiting||!valid){hide();return Change.HIDE;}
        if(!permitted)return Change.KEEP;
        if(token.equals(cancelledToken)||current!=null&&token.equals(current.token()))return Change.KEEP;
        current=new Request(token,pkg);return Change.SHOW;
    }
    String packageName(){return current==null?null:current.pkg();}
    void hide(){current=null;}
    Request cancel(){
        Request request=current;
        if(request!=null)cancelledToken=request.token();
        hide();return request;
    }
    boolean timeoutMatches(String token){return current!=null&&current.token().equals(token);}
}

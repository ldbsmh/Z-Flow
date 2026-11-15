package io.relimus.zflow.xposed.nativewin;

/** Shared, framework-independent geometry and orientation decisions. */
final class WindowPolicy {
    static boolean nativeForeground(Object before,Object target,String beforePackage,String targetPackage,
            int beforeUser,int targetUser,Object beforeDisplay,Object targetDisplay,int mode,boolean owned){
        return before!=null&&before==target&&beforePackage!=null&&beforePackage.equals(targetPackage)
            &&beforeUser==targetUser&&beforeDisplay!=null&&beforeDisplay==targetDisplay&&mode==1&&!owned;
    }
    /** The start targets the exact fullscreen task that a focused floating window was covering before the start. */
    static boolean coveredFullscreen(Object fullscreen,Object focused,Object target,Object beforeDisplay,Object targetDisplay){
        return fullscreen!=null&&fullscreen!=focused&&fullscreen==target
            &&beforeDisplay!=null&&beforeDisplay==targetDisplay;
    }
    /** Background touch gate behind a normal window: during its entry/transition (capped), then a short grace
     * while SystemUI re-shows the outside-dismiss overlays. Times share one monotonic clock. */
    static boolean startsTouchGuard(Object owner,Object previous,boolean guarded,boolean reported){
        return guarded&&(!reported||owner!=previous);
    }
    static boolean backgroundTouchGuard(boolean shown,boolean entering,boolean reported,long since,long graceUntil,long now,long max){
        if(!shown)return false;
        if(entering)return now-(reported?since:now)<max;
        return now<graceUntil;
    }
    static boolean autoMini(boolean enabled,boolean different,boolean sameToken,String previousForm,boolean validMini){
        return enabled&&different&&!sameToken&&"normal".equals(previousForm)&&!validMini;
    }
    static boolean entryBusy(boolean animating,int relativeRotation){return animating||relativeRotation!=0;}
    static boolean morphEntry(boolean delegated,boolean focused,boolean visible,int mode){return !delegated&&focused&&visible&&mode==1;}
    static String failureMessage(String report){
        String first=report==null?"":report.split("\n",2)[0];
        if(first.contains("等待当前任务转场")||first.contains("桌面转场尚未结束"))return "系统转场尚未结束，请稍后重试";
        if(first.contains("默认窗已被占用"))return "请先关闭当前默认小窗";
        if(first.contains("先解锁"))return "请先解锁屏幕";
        return "小窗操作未完成，请稍后重试";
    }
    static boolean mayRestoreNormalFocus(int type,boolean dimmed,int mode){
        // Standard full-screen app windows may change underneath a normal window.
        // System/IME overlays, modal dialogs, PiP and other freeform windows win.
        return type>=1&&type<=99&&!dimmed&&mode==1;
    }
    static boolean steadyMiniTransition(boolean liveMini,boolean busy,int changeMode,boolean sameGeometry,
            boolean sameRotation,boolean dependentChanges,boolean snapshot){
        return liveMini&&!busy&&changeMode==6&&sameGeometry&&sameRotation&&!dependentChanges&&!snapshot;
    }
    static boolean sameWindowCaller(int callingUid,int realCallingUid,int ownerUid,String callingPackage,String ownerPackage){
        return ownerUid>=10000&&callingUid==ownerUid&&(realCallingUid<0||realCallingUid==ownerUid)
            &&ownerPackage!=null&&!ownerPackage.isEmpty()&&ownerPackage.equals(callingPackage);
    }
    static boolean refreshRootLayout(boolean launcher,boolean attached,boolean relaunching,boolean finishing,boolean editing,String lifecycle){
        return launcher&&attached&&!relaunching&&!finishing&&!editing
            &&("RESUMED".equals(lifecycle)||"PAUSED".equals(lifecycle)||"STOPPED".equals(lifecycle));
    }
    static boolean initialLandscape(int entryDeclaration,int launcherDeclaration){
        return landscape(entryDeclaration,false)&&landscape(launcherDeclaration,false);
    }
    static boolean initialLandscape(int entryDeclaration,int launcherDeclaration,int requested,Boolean remembered){
        if(initialLandscape(entryDeclaration,launcherDeclaration))return true;
        return remembered==null?false:landscape(requested,remembered);
    }
    static boolean currentLandscape(int orientation,int request,boolean fallback){
        return orientation==2 || orientation!=1&&landscape(request,fallback);
    }
    static int clientMode(String pkg){return "com.tencent.mm".equals(pkg)?5:1;}
    static boolean landscape(int requested,boolean previous){
        return switch(requested){case 0,6,8,11 -> true;case 1,7,9,12 -> false;default -> previous;};
    }
    static float logicalRaw(float raw,float offset,float scale){
        if(!Float.isFinite(scale)||scale<=0||!Float.isFinite(offset))return raw;
        return (raw-offset)/scale;
    }
    static boolean sameLiveTask(Object task,Object owned,int id,int expectedId,int user,int expectedUser){
        return task!=null&&task==owned&&id==expectedId&&user==expectedUser;
    }
}

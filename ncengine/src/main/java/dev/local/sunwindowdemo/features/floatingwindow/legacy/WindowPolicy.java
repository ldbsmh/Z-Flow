package dev.local.sunwindowdemo;

/** Shared, framework-independent geometry and orientation decisions. */
final class WindowPolicy {
    static boolean nativeForeground(boolean sameTask,String beforePackage,String targetPackage,
            int beforeUser,int targetUser,boolean sameDisplay,int mode,boolean owned){
        return sameTask&&beforePackage!=null&&beforePackage.equals(targetPackage)
            &&beforeUser==targetUser&&sameDisplay&&mode==1&&!owned;
    }
    /** The start targets the exact fullscreen task that a focused floating window was covering before the start. */
    static boolean coveredFullscreen(boolean coveredTarget,boolean sameDisplay){
        return coveredTarget&&sameDisplay;
    }
    /** Background touch gate behind a normal window: during its entry/transition (capped), then a short grace
     * while SystemUI re-shows the outside-dismiss overlays. Times share one monotonic clock. */
    static boolean startsTouchGuard(boolean sameOwner,boolean guarded,boolean reported){
        return guarded&&(!reported||!sameOwner);
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
    static boolean ownedLaunch(int targetUid,int ownerUid,int targetUser,int ownerUser,boolean targetDisplayPresent,boolean compatibleDisplay){
        return ownerUid>=10000&&targetUid==ownerUid&&targetUser==ownerUser
            &&targetDisplayPresent&&compatibleDisplay;
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
    static boolean sameLiveTask(boolean sameTask,int id,int expectedId,int user,int expectedUser){
        return sameTask&&id==expectedId&&user==expectedUser;
    }
}

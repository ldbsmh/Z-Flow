package dev.local.sunwindowdemo;

/** Value-only acquisition rules. Native facts are read in rejection order by the Android adapter. */
final class WindowAdmission {
    enum Rejection {
        MISSING("目标任务不存在"), IDENTITY("应用身份不匹配"), DIAGNOSTIC_ID("诊断任务标识不匹配"),
        CROP("原始裁剪不可用"), OPAQUE("系统已强制此任务遮挡底层，不接管"),
        CAPABILITY("ROM 未启用原生 freeform；此版保持预检，不全局强开"), HIERARCHY("任务层级不受首版支持"),
        DISPLAY_USER("首版只测试主用户、内置屏幕"), ACTIVITY_TYPE("只接管普通应用任务"),
        WAIT("等待当前任务转场结束后再试"), RESIZE("当前任务不支持可调整的 freeform"),
        OWNERSHIP("只接管普通全屏或直接关联的 freeform 任务"), OVERRIDE("原请求配置不是标准全屏");
        final String message;
        Rejection(String message){this.message=message;}
    }
    static Rejection identity(String expectedPackage,String actualPackage){
        return expectedPackage!=null&&expectedPackage.equals(actualPackage)?null:Rejection.IDENTITY;
    }
    static Rejection diagnostic(boolean diagnostic,String expectedToken,String actualToken){
        return diagnostic&&!java.util.Objects.equals(expectedToken,actualToken)?Rejection.DIAGNOSTIC_ID:null;
    }
    static Rejection crop(int width,int height){return width<0||height<0?Rejection.CROP:null;}
    static Rejection opaque(boolean opaque){return opaque?Rejection.OPAQUE:null;}
    static Rejection capability(boolean multiWindow,boolean freeform){return multiWindow&&freeform?null:Rejection.CAPABILITY;}
    record Hierarchy(boolean root,boolean leaf,boolean attached,boolean displayAreaParent) {}
    static Rejection hierarchy(Hierarchy f){return f.root&&f.leaf&&f.attached&&f.displayAreaParent?null:Rejection.HIERARCHY;}
    static Rejection displayUser(boolean displayPresent,int displayId,int userId){return displayPresent&&displayId==0&&userId==0?null:Rejection.DISPLAY_USER;}
    static Rejection activityType(int type){return type==1?null:Rejection.ACTIVITY_TYPE;}
    static Rejection busy(boolean busy){return busy?Rejection.WAIT:null;}
    record Resize(boolean freeformInArea,boolean validMode,boolean boundsOverride,boolean modeOverride) {}
    static Rejection resize(Resize f){return f.freeformInArea&&f.validMode&&f.boundsOverride&&f.modeOverride?null:Rejection.RESIZE;}
    static boolean inherited(boolean linked,int mode,boolean alwaysOnTop){return linked&&mode==5&&!alwaysOnTop;}
    static Rejection ownership(boolean inherited,int mode,boolean alwaysOnTop,boolean focusable){
        return inherited||mode==1&&!alwaysOnTop&&focusable?null:Rejection.OWNERSHIP;
    }
    static Rejection override(int mode,boolean boundsEmpty,int topState){
        return (mode==0||mode==1)&&boundsEmpty&&(topState==0||topState==2)?null:Rejection.OVERRIDE;
    }
}

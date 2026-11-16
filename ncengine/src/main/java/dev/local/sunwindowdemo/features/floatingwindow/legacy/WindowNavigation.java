package dev.local.sunwindowdemo;

/** Shared navigation values. Native sender/task identities are sampled by AndroidWindowLaunch. */
final class WindowNavigation {
    static String wakeForm(String frame,boolean hidden,boolean step){
        return step&&hidden&&"mini".equals(frame)?"restore-mini":"normal";
    }
    static final class Launch {
        final long generation,sentAt;
        final String hint,expectedPackage,foregroundPackage;
        final int userId;
        volatile int taskId=-1;
        volatile String targetPackage;
        volatile boolean dispatched,nativeTarget;
        Launch(long generation,String hint,String expectedPackage,int userId,long sentAt,String foregroundPackage){
            this.generation=generation;this.hint=hint;this.expectedPackage=expectedPackage;
            this.userId=userId;this.sentAt=sentAt;this.foregroundPackage=foregroundPackage;
        }
        boolean currentRequest(long current,String requestHint,int requestUser){
            return generation==current&&hint.equals(requestHint)&&userId==requestUser;
        }
        boolean accepts(long current,boolean sameSender,boolean sameBoundTask){
            return generation==current&&(sameSender||sameBoundTask);
        }
        boolean targetAllowed(String pkg,int user,boolean allowedPackage){
            return user==userId&&allowedPackage&&(expectedPackage==null||expectedPackage.equals(pkg));
        }
        void dispatched(long current){if(generation==current)dispatched=true;}
        void bind(int id,String pkg){taskId=id;targetPackage=pkg;}
        boolean keepNativeForeground(){return nativeTarget;}
    }
}

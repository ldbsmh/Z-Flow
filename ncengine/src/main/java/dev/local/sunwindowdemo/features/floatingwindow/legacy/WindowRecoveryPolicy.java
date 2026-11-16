package dev.local.sunwindowdemo;

/** Recovery decisions only. Native task lookup, verification and durable writes stay with the adapter. */
final class WindowRecoveryPolicy {
    enum Eligibility { WAIT, COMPLETE, RELEASE_STALE, RESTORE, REJECT }
    static Eligibility eligibility(boolean live,boolean ready,boolean found,boolean identity,boolean sameObject,boolean baseId){
        if(!found)return ready?Eligibility.RELEASE_STALE:live?Eligibility.COMPLETE:Eligibility.WAIT;
        if(!identity||live&&!sameObject)return ready?Eligibility.RELEASE_STALE:Eligibility.REJECT;
        return !live&&!baseId&&!ready?Eligibility.WAIT:Eligibility.RESTORE;
    }
    static final class Retry {
        private int waits;
        private boolean readyRechecked;
        int waits(){return waits;}
        void resetWaits(){waits=0;}
        void begin(){resetWaits();readyRechecked=false;}
        boolean failed(){return ++waits<=100;}
        boolean needsReadinessCheck(){return !readyRechecked;}
        boolean becameReady(){
            if(readyRechecked)return false;
            readyRechecked=true;
            if(waits<=100)return false;
            resetWaits();return true;
        }
    }
}

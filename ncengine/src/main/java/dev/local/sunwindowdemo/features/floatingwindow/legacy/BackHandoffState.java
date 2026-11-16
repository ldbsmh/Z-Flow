package dev.local.sunwindowdemo;

/** One prepared long-Back ticket. Native controller/token identity never enters this state. */
final class BackHandoffState {
    static final int NATIVE_RETRY_MS=40, NATIVE_ATTEMPTS=40, IDLE_TIMEOUT_MS=650;
    enum Action { IGNORE, ABORT, CHECK_NATIVE, WAIT_NATIVE, WAIT_IDLE, COMMIT }
    private enum Phase { WAIT_NATIVE, CHECKING, WAIT_IDLE, READY, SENDING, FINISHED, CANCELLED }
    private final int revision;
    private Phase phase=Phase.WAIT_NATIVE;
    BackHandoffState(int revision){this.revision=revision;}
    private boolean valid(boolean live,int generation){return live&&revision==generation;}
    synchronized Action check(boolean live,int generation,int attempt){
        if(phase!=Phase.WAIT_NATIVE)return Action.IGNORE;
        if(!valid(live,generation)||attempt>=NATIVE_ATTEMPTS)return cancel()?Action.ABORT:Action.IGNORE;
        phase=Phase.CHECKING;return Action.CHECK_NATIVE;
    }
    synchronized Action nativeState(boolean busy,boolean hasController,boolean live,int generation){
        if(phase!=Phase.CHECKING)return Action.IGNORE;
        if(!valid(live,generation))return cancel()?Action.ABORT:Action.IGNORE;
        if(busy){phase=Phase.WAIT_NATIVE;return Action.WAIT_NATIVE;}
        phase=hasController?Phase.WAIT_IDLE:Phase.READY;
        return hasController?Action.WAIT_IDLE:Action.COMMIT;
    }
    synchronized Action idle(boolean live,int generation){
        if(phase!=Phase.WAIT_IDLE)return Action.IGNORE;
        if(!valid(live,generation))return cancel()?Action.ABORT:Action.IGNORE;
        phase=Phase.READY;return Action.COMMIT;
    }
    synchronized Action idleTimeout(){
        return phase==Phase.WAIT_IDLE&&cancel()?Action.ABORT:Action.IGNORE;
    }
    synchronized Action commit(boolean live,int generation){
        if(phase!=Phase.READY)return Action.IGNORE;
        if(!valid(live,generation))return cancel()?Action.ABORT:Action.IGNORE;
        phase=Phase.SENDING;return Action.COMMIT;
    }
    synchronized void completed(){if(phase==Phase.SENDING)phase=Phase.FINISHED;}
    synchronized boolean cancel(){
        if(phase==Phase.CANCELLED||phase==Phase.FINISHED)return false;
        phase=Phase.CANCELLED;return true;
    }
}

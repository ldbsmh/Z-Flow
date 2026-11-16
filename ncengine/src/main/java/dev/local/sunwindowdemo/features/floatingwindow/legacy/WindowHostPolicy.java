package dev.local.sunwindowdemo;

/** Admission decisions on sampled facts; each entry deliberately keeps its own policy. */
final class WindowHostPolicy {
    private WindowHostPolicy(){}
    static boolean cornerConfigured(boolean enabled,boolean backendReady,boolean cleaned,boolean screenshot,boolean systemFocus){
        return enabled&&backendReady&&!cleaned&&!screenshot&&!systemFocus;
    }
    static boolean defaultSlot(String state,boolean normalOccupied){
        return "idle".equals(state)||"recovery".equals(state)||"active".equals(state)&&!normalOccupied;
    }
    static boolean slotConfigured(boolean enabled,boolean backendReady,boolean slotAvailable){return enabled&&backendReady&&slotAvailable;}
    static boolean notificationConfigured(boolean enabled,boolean notificationEnabled,boolean backendReady,String state){
        return enabled&&notificationEnabled&&backendReady&&!"recovery".equals(state);
    }
    static boolean currentTarget(int display,boolean manager,boolean systemUi,boolean launcher,boolean allowedPackage){
        return display==0&&!manager&&!systemUi&&!launcher&&allowedPackage;
    }
}

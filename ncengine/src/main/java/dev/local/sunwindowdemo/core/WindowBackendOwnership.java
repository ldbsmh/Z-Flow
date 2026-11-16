package dev.local.sunwindowdemo.core;

import java.util.Properties;

/** Select only in system_server; Properties shares strings across module ClassLoaders. */
public final class WindowBackendOwnership {
    private static final String KEY="dev.nativecomplements.window.backend.owner.v1";
    private static final String ELIGIBILITY="dev.nativecomplements.window.backend.eligibility.v2.";
    private WindowBackendOwnership() {}
    public static String claim(String product) { return claim(System.getProperties(),product); }
    public static String claimEligible(String product,int eligibility){return claimEligible(System.getProperties(),product,eligibility);}
    static String claimEligible(Properties properties,String product,int eligibility){
        ModuleIdentity.forPackage(product);
        if(eligibility < -1 || eligibility > 1)throw new IllegalArgumentException("Eligibility");
        synchronized(properties){
            // ponytail: freeze only each loaded product's qualification; two eligible products retain first-claim arbitration.
            String key=ELIGIBILITY+product;
            String saved=properties.getProperty(key);
            if(saved==null){saved=Integer.toString(eligibility);properties.setProperty(key,saved);}
            if("0".equals(saved))return properties.getProperty(KEY,"");
            return claim(properties,product);
        }
    }
    public static String owner(){synchronized(System.getProperties()){return System.getProperties().getProperty(KEY,"");}}
    static String claim(Properties properties,String product) {
        ModuleIdentity.forPackage(product); // Reject unknown identities before mutating shared process state.
        synchronized(properties) {
            String owner=properties.getProperty(KEY);
            if(owner==null) { properties.setProperty(KEY,product); return product; }
            return owner;
        }
    }
}

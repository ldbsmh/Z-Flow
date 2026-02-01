package android.content.pm;

import android.os.IBinder;
import android.os.IInterface;

public interface IPackageManager extends IInterface {

    abstract class Stub extends android.os.Binder implements IPackageManager {
        public static IPackageManager asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}

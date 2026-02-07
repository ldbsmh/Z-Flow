package android.view;

import android.os.Binder;
import android.os.IInterface;

public interface IRotationWatcher extends IInterface {
    void onRotationChanged(int rotation);

    abstract class Stub extends Binder implements IRotationWatcher {
    }
}

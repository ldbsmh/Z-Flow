package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.drawable.Drawable;
import java.util.List;

/** The fixed catalog of actions that can be mixed into the application shortcut list. */
final class QuickActionCatalog {
    record Action(String id, String label, int icon) {}

    static final String PREFIX = "quick:";
    static final List<Action> ACTIONS = List.of(
        new Action("dnd", "免打扰", R.drawable.ic_game_do_not_disturb_on),
        new Action("wifi", "Wi-Fi", R.drawable.ic_game_wifi),
        new Action("rotation", "旋转锁定", R.drawable.ic_game_screen_rotation),
        new Action("mute", "静音", R.drawable.ic_game_volume_off),
        new Action("screenshot", "截屏", R.drawable.ic_game_screenshot),
        new Action("record", "录屏", R.drawable.ic_game_screen_record),
        new Action("flashlight", "手电筒", R.drawable.ic_qs_flashlight),
        new Action("airplane", "飞行模式", R.drawable.ic_qs_airplane),
        new Action("current", "应用小窗", R.drawable.ic_qs_current_window),
        new Action("autobrightness", "自动亮度", R.drawable.ic_game_brightness_6),
        new Action("batterysaver", "省电模式", R.drawable.ic_qs_battery_saver),
        new Action("darkmode", "深色模式", R.drawable.ic_qs_dark_mode)
    );

    private QuickActionCatalog() {}

    static Action find(String id) {
        for (Action action : ACTIONS) if (action.id().equals(id)) return action;
        return null;
    }

    static Action fromKey(String key) {
        if (key == null || !key.startsWith(PREFIX)) return null;
        return find(key.substring(PREFIX.length()));
    }

    static boolean validKey(String key) { return fromKey(key) != null; }

    static String key(String id) { return PREFIX + id; }

    static Drawable icon(Context c, Action action) {
        try {
            Context module = Wire.PKG.equals(c.getPackageName()) ? c : c.createPackageContext(Wire.PKG, 0);
            return module.getDrawable(action.icon());
        } catch (Exception unavailable) {
            return null;
        }
    }

    static AppList.QuickEntry entry(Context c, Action action) {
        return new AppList.QuickEntry(action.id(), action.label(), icon(c, action));
    }

    static List<AppList.QuickEntry> entries(Context c) {
        return ACTIONS.stream().map(action -> entry(c, action)).toList();
    }
}

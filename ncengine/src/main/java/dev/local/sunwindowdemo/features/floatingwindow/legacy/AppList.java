package dev.local.sunwindowdemo;

import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.core.view.NestedScrollingChild3;
import androidx.core.view.NestedScrollingChildHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** A View card inside the existing SystemUI overlay. Never starts an Activity or owns a task. */
public final class AppList {
    interface SaveAction { void save(List<ShortcutTarget> targets,Consumer<Boolean> done); }

    static View create(Context c,List<AppCatalog.App> all,List<ShortcutTarget> selected,
            Consumer<ShortcutTarget> launch,SaveAction save,Runnable close) {
        return new Panel(c,all,selected,launch,save);
    }
    static View create(Context c,List<AppCatalog.App> all,List<ShortcutTarget> selected,
            Consumer<ShortcutTarget> launch,SaveAction save,List<QuickEntry> quickEntries,Consumer<String> quickAction) {
        return new Tabs(c,new Panel(c,all,selected,launch,save,null,true),quickEntries,quickAction);
    }
    static boolean back(View content){
        if(content instanceof Tabs tabs&&tabs.quickShown){
            tabs.select(false);
            return true;
        }
        return false;
    }

    static View createPage(Context c,List<AppCatalog.App> all,List<ShortcutTarget> selected,
            Consumer<ShortcutTarget> launch,SaveAction save,int[] colors) {
        return new Panel(c,all,selected,launch,save,colors);
    }
    static View createPage(Context c,List<AppCatalog.App> all,List<ShortcutTarget> selected,
            Consumer<ShortcutTarget> launch,SaveAction save,int[] colors,BiConsumer<Boolean,Boolean> editState) {
        Panel page=new Panel(c,all,selected,launch,save,colors);page.editState=editState;
        editState.accept(page.editing,page.saving);return page;
    }
    static void edit(View page){((Panel)page).toggleEditing();}
    static void updateApps(View view,List<AppCatalog.App> apps){
        Panel panel=view instanceof Tabs tabs?tabs.apps:(Panel)view;
        int first=panel.list.getFirstVisiblePosition();View top=panel.list.getChildAt(0);int offset=top==null?0:top.getTop();
        panel.apps=List.copyOf(apps);
        panel.appRows.clear();for(AppCatalog.App app:apps)panel.appRows.add(ShortcutTarget.app(panel.c,app));
        panel.chosen.replaceAll(target->{ShortcutTarget updated=ShortcutTarget.fromKey(panel.c,target.key(),apps);return updated!=null?updated:target;});
        panel.renderSelected();panel.refreshRows();panel.list.setSelectionFromTop(first,offset);
    }
    /** Shared safe-area placement for the application and quick-function menu. */
    static Geometry.Placement placement(Context c,android.view.WindowMetrics metrics) {
        android.graphics.Rect display=metrics.getBounds();
        boolean landscape=display.width()>display.height();
        int types=android.view.WindowInsets.Type.displayCutout();
        if(!landscape)types|=android.view.WindowInsets.Type.systemBars();
        android.graphics.Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(types);
        int margin=Math.round((landscape?4:12)*c.getResources().getDisplayMetrics().density);
        return Geometry.place(Math.min(display.width(),display.height()),false,
            display.left+bars.left,display.top+bars.top,display.right-bars.right,display.bottom-bars.bottom,margin);
    }
    static void bottomInset(View view,int bottom) {
        Panel panel=(Panel)view;
        panel.list.setPadding(0,panel.dp(2),0,panel.dp(2)+Math.max(0,bottom));
    }
    static void blur(View view,boolean enabled) {
        Panel panel=view instanceof Tabs tabs?tabs.apps:(Panel)view;
        if(panel.page)return;
        dev.local.sunwindowdemo.core.ui.MenuBackground.bind(view,enabled,panel.container,panel.dp(28));
    }

    public record QuickEntry(String id,String label,Drawable icon) {}
    static View quickPage(Context c,List<QuickEntry> entries,Consumer<String> action){return new QuickPage(c,entries,action);}
    static void updateQuick(View page,List<QuickEntry> entries){((QuickPage)page).update(entries);}
    private static int pixels(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    private static int themeColor(Context c,int attr,int fallback){
        TypedValue value=new TypedValue();
        try{if(c.getTheme().resolveAttribute(attr,value,true))return value.resourceId!=0?c.getColor(value.resourceId):value.data;}
        catch(RuntimeException ignored){}
        return fallback;
    }

    /** The same icon, caption, padding and touch feedback for applications and quick actions. */
    private static final class IconTile extends LinearLayout {
        final FrameLayout artwork;final ImageView image;final TextView title;
        IconTile(Context c,boolean compact,String label,Drawable icon,int text,int accent){
            this(c,compact,false,false,label,icon,text,accent);
        }
        IconTile(Context c,boolean compact,boolean page,String label,Drawable icon,int text,int accent){
            this(c,compact,page,false,label,icon,text,accent);
        }
        IconTile(Context c,boolean compact,boolean page,boolean quick,String label,Drawable icon,int text,int accent){
            super(c);setOrientation(VERTICAL);setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(pixels(c,2),pixels(c,page?6:compact?2:4),pixels(c,2),pixels(c,2));
            GradientDrawable mask=new GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadius(pixels(c,22));
            setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x26000000),null,mask));
            artwork=new FrameLayout(c);addView(artwork,new LinearLayout.LayoutParams(-1,pixels(c,page?60:compact?36:40)));
            image=new ImageView(c);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if(icon!=null&&icon.getConstantState()!=null)icon=icon.getConstantState().newDrawable(c.getResources()).mutate();
            image.setImageDrawable(icon);
            if(quick){
                // Fixed white disc and black glyph: the on/off state never changes this appearance.
                image.setImageTintList(ColorStateList.valueOf(0xff1b1c1f));
                FrameLayout disc=new FrameLayout(c);
                GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(Color.WHITE);
                disc.setBackground(circle);
                int discSize=pixels(c,compact?32:36),glyph=pixels(c,compact?18:20);
                artwork.addView(disc,new FrameLayout.LayoutParams(discSize,discSize,Gravity.CENTER));
                disc.addView(image,new FrameLayout.LayoutParams(glyph,glyph,Gravity.CENTER));
            }else{
                int size=pixels(c,page?52:compact?32:36);
                artwork.addView(image,new FrameLayout.LayoutParams(size,size,Gravity.CENTER));
            }
            title=new TextView(c);title.setText(label);title.setTextSize(page?13:11);title.setTextColor(text);
            title.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);title.setSingleLine(true);title.setIncludeFontPadding(false);title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title,new LinearLayout.LayoutParams(-1,pixels(c,page?20:17)));setContentDescription(label);
        }
    }

    private static final class QuickPage extends ScrollView {
        final LinearLayout rows;final Consumer<String> action;final boolean compact;
        final int text,accent;final ArrayList<IconTile> cells=new ArrayList<>();List<String> keys=List.of();
        QuickPage(Context c,List<QuickEntry> entries,Consumer<String> action){
            super(c);this.action=action;compact=c.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
            text=themeColor(c,android.R.attr.textColorPrimary,0xff1d1b20);accent=themeColor(c,android.R.attr.colorAccent,0xff6750a4);
            setFillViewport(true);setVerticalScrollBarEnabled(false);setVerticalFadingEdgeEnabled(false);setClipToPadding(false);
            setPadding(pixels(c,12),0,pixels(c,12),0);
            rows=new LinearLayout(c);rows.setOrientation(LinearLayout.VERTICAL);addView(rows,new ScrollView.LayoutParams(-1,-2));update(entries);
        }
        void update(List<QuickEntry> entries){
            List<String> next=entries.stream().map(QuickEntry::id).toList();Context c=getContext();
            if(entries.isEmpty()){
                keys=List.of();cells.clear();rows.removeAllViews();
                TextView hint=new TextView(c);hint.setText("全部快捷功能都已添加");hint.setTextSize(12);hint.setTextColor(text);hint.setAlpha(.7f);
                hint.setGravity(android.view.Gravity.CENTER);rows.addView(hint,new LinearLayout.LayoutParams(-1,pixels(c,compact?66:72)));
                return;
            }
            if(!keys.equals(next)){
                keys=next;cells.clear();rows.removeAllViews();LinearLayout row=null;
                for(int i=0;i<entries.size();i++){
                    if(i%4==0){row=new LinearLayout(c);rows.addView(row,new LinearLayout.LayoutParams(-1,pixels(c,compact?66:72)));}
                    QuickEntry entry=entries.get(i);IconTile cell=new IconTile(c,compact,false,true,entry.label(),entry.icon(),text,accent);
                    cell.setOnClickListener(v->action.accept(entry.id()));cells.add(cell);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(pixels(c,2),0,pixels(c,2),0);row.addView(cell,p);
                }
                if(row!=null)while(row.getChildCount()<4){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(pixels(c,2),0,pixels(c,2),0);row.addView(new View(c),p);}
            }
            for(int i=0;i<entries.size();i++){
                QuickEntry entry=entries.get(i);IconTile cell=cells.get(i);
                cell.title.setText(entry.label());cell.image.setImageDrawable(entry.icon());
                cell.setContentDescription(entry.label());
            }
        }
    }

    private static final class Tabs extends LinearLayout {
        final Panel apps;final View quick;
        final TextView appTab,quickTab;boolean quickShown;
        Tabs(Context c,Panel apps,List<QuickEntry> quickEntries,Consumer<String> quickAction){
            super(c);this.apps=apps;setOrientation(VERTICAL);setClipToOutline(true);
            apps.setBackgroundColor(Color.TRANSPARENT);apps.setClipToOutline(false);
            apps.quickAction=quickAction;
            // The shared title/selected area stays above both tabs; the tab row
            // replaces the old divider and never jumps when switching pages.
            addView(apps.header,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout tabs=new LinearLayout(c);tabs.setPadding(apps.dp(12),apps.dp(6),apps.dp(12),apps.dp(4));
            appTab=apps.button("应用",()->select(false));quickTab=apps.button("快捷功能",()->select(true));
            tabs.addView(appTab,new LinearLayout.LayoutParams(0,apps.dp(40),1));tabs.addView(quickTab,new LinearLayout.LayoutParams(0,apps.dp(40),1));addView(tabs);
            apps.manage.setOnClickListener(v->apps.toggleEditing());
            quick=new QuickPage(c,availableQuick(quickEntries),id->{
                if(apps.editing)apps.toggleQuick(id);else quickAction.accept(id);
            });
            apps.quickRefresher=()->updateQuick((QuickPage)quick,availableQuick(quickEntries));
            quick.setOnScrollChangeListener((v,sx,sy,oldX,oldY)->{if(quickShown)apps.setSelectedCollapsed(sy>0);});
            FrameLayout pages=new FrameLayout(c);addView(pages,new LinearLayout.LayoutParams(-1,0,1));
            pages.addView(apps,new FrameLayout.LayoutParams(-1,-1));pages.addView(quick,new FrameLayout.LayoutParams(-1,-1));select(false);
        }
        private List<QuickEntry> availableQuick(List<QuickEntry> all){
            ArrayList<QuickEntry> visible=new ArrayList<>();
            for(QuickEntry entry:all)if(apps.selectedIndex(QuickActionCatalog.key(entry.id()))<0)visible.add(entry);
            return visible;
        }
        void select(boolean show){
            if(apps.saving)return;
            quickShown=show;apps.setVisibility(show?GONE:VISIBLE);quick.setVisibility(show?VISIBLE:GONE);apps.setSelectedCollapsed(false);
            for(TextView tab:new TextView[]{appTab,quickTab}){
                boolean active=(tab==quickTab)==show;tab.setSelected(active);tab.setTextColor(active?apps.accent:apps.secondary);
                tab.setBackground(active?apps.round(apps.tonal,20):null);
            }
        }
    }

    /** Bridge platform ListView's untyped scroll calls to AndroidView's type-aware parent. */
    private static final class MenuList extends ListView implements NestedScrollingChild3 {
        private final NestedScrollingChildHelper scrolling=new NestedScrollingChildHelper(this);
        MenuList(Context c){super(c);setNestedScrollingEnabled(true);}
        @Override public void setNestedScrollingEnabled(boolean enabled){
            super.setNestedScrollingEnabled(enabled);
            if(scrolling!=null)scrolling.setNestedScrollingEnabled(enabled);
        }
        @Override public boolean isNestedScrollingEnabled(){return scrolling!=null&&scrolling.isNestedScrollingEnabled();}
        @Override public boolean startNestedScroll(int axes){return startNestedScroll(axes,0);}
        @Override public boolean startNestedScroll(int axes,int type){return scrolling.startNestedScroll(axes,type);}
        @Override public void stopNestedScroll(){stopNestedScroll(0);}
        @Override public void stopNestedScroll(int type){scrolling.stopNestedScroll(type);}
        @Override public boolean hasNestedScrollingParent(){return hasNestedScrollingParent(0);}
        @Override public boolean hasNestedScrollingParent(int type){return scrolling.hasNestedScrollingParent(type);}
        @Override public boolean dispatchNestedPreScroll(int dx,int dy,int[] consumed,int[] offset){return dispatchNestedPreScroll(dx,dy,consumed,offset,0);}
        @Override public boolean dispatchNestedPreScroll(int dx,int dy,int[] consumed,int[] offset,int type){return scrolling.dispatchNestedPreScroll(dx,dy,consumed,offset,type);}
        @Override public boolean dispatchNestedScroll(int dx,int dy,int ux,int uy,int[] offset){return dispatchNestedScroll(dx,dy,ux,uy,offset,0);}
        @Override public boolean dispatchNestedScroll(int dx,int dy,int ux,int uy,int[] offset,int type){return scrolling.dispatchNestedScroll(dx,dy,ux,uy,offset,type);}
        @Override public void dispatchNestedScroll(int dx,int dy,int ux,int uy,int[] offset,int type,int[] consumed){scrolling.dispatchNestedScroll(dx,dy,ux,uy,offset,type,consumed);}
        @Override public boolean dispatchNestedPreFling(float x,float y){return scrolling.dispatchNestedPreFling(x,y);}
        @Override public boolean dispatchNestedFling(float x,float y,boolean consumed){return scrolling.dispatchNestedFling(x,y,consumed);}
    }

    private static final class Panel extends LinearLayout {
        static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
        final Context c;
        final boolean compact,page,sharedHeader;
        List<AppCatalog.App> apps;
        final ArrayList<ShortcutTarget> appRows=new ArrayList<>(),chosen=new ArrayList<>(),visible=new ArrayList<>();
        final ArrayList<AppRow> rows=new ArrayList<>();
        final int[] sectionPositions=new int[ALPHABET.length()];
        final Consumer<ShortcutTarget> launch;
        final SaveAction save;
        final Handler main=new Handler(Looper.getMainLooper());
        final int surface,container,text,secondary,accent,tonal;
        final TextView manage,help,empty,bubble;
        final LinearLayout header;
        final LinearLayout selectedScroll;
        final View divider;
        final LinearLayout selectedRows;
        final ListView list;
        final FrameLayout body;
        final IndexBar index;
        final BaseAdapter adapter;
        final Runnable dragScroll=this::scrollDrag,hideIndex=this::hideIndex;
        boolean scrolling,indexTouching;
        int dragFrom=-1;
        boolean editing,saving,disposed;
        BiConsumer<Boolean,Boolean> editState;
        Consumer<String> quickAction=id->{};
        Runnable quickRefresher;
        boolean selectedCollapsed;
        android.animation.ValueAnimator selectedAnimator;
        int hover=-1;
        float dragX,dragY;

        static final class AppRow {
            final String section;
            final List<ShortcutTarget> items;
            AppRow(String section,List<ShortcutTarget> items){this.section=section;this.items=items;}
        }

        Panel(Context context,List<AppCatalog.App> all,List<ShortcutTarget> initial,
                Consumer<ShortcutTarget> launch,SaveAction save) {
            this(context,all,initial,launch,save,null,false);
        }
        Panel(Context context,List<AppCatalog.App> all,List<ShortcutTarget> initial,
                Consumer<ShortcutTarget> launch,SaveAction save,int[] colors) {
            this(context,all,initial,launch,save,colors,false);
        }
        Panel(Context context,List<AppCatalog.App> all,List<ShortcutTarget> initial,
                Consumer<ShortcutTarget> launch,SaveAction save,int[] colors,boolean sharedHeader) {
            super(context);c=context;apps=List.copyOf(all);this.launch=launch;this.save=save;
            page=colors!=null;this.sharedHeader=sharedHeader;
            compact=context.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
            surface=colors==null?color(android.R.attr.colorBackground,0xfff8f7fc):colors[0];
            text=colors==null?color(android.R.attr.textColorPrimary,0xff1d1b20):colors[1];
            secondary=colors==null?color(android.R.attr.textColorSecondary,0xff625f68):colors[2];
            accent=colors==null?color(android.R.attr.colorAccent,0xff6750a4):colors[3];
            container=blend(surface,text,.045f);tonal=blend(surface,accent,.13f);
            setOrientation(VERTICAL);setPadding(page?0:dp(12),0,page?0:dp(12),0);
            if(!page)setBackground(round(container,28));
            setClipToOutline(!page);setClipToPadding(false);setOnClickListener(v->{});
            setNestedScrollingEnabled(colors!=null);
            for(AppCatalog.App app:apps)appRows.add(ShortcutTarget.app(c,app));
            for(ShortcutTarget item:initial)if(item!=null&&ShortcutTarget.validKey(item.key())&&selectedIndex(item.key())<0&&chosen.size()<Config.MAX_TARGETS)chosen.add(item);
            header=new LinearLayout(c);header.setOrientation(VERTICAL);
            manage=button("管理",this::toggleEditing);manage.setBackground(null);manage.setTextColor(text);manage.setIncludeFontPadding(false);manage.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
            if(!page){
                LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);
                TextView title=label("更多",14,text);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setIncludeFontPadding(false);
                row.addView(title,new LinearLayout.LayoutParams(0,dp(compact?36:42),1));
                row.addView(manage,new LinearLayout.LayoutParams(dp(72),dp(compact?36:42)));
                header.addView(row);
                if(!sharedHeader)addView(header);else header.setPadding(dp(12),0,dp(12),0);
            }

            selectedScroll=new LinearLayout(c);selectedScroll.setOrientation(VERTICAL);
            selectedRows=new LinearLayout(c);selectedRows.setOrientation(VERTICAL);selectedScroll.addView(selectedRows,new LinearLayout.LayoutParams(-1,-2));
            selectedScroll.setOnDragListener((view,event)->drag(event));
            help=label("",11,secondary);help.setPadding(dp(4),dp(2),dp(4),dp(4));help.setMaxLines(2);
            divider=new View(c);divider.setBackgroundColor(blend(container,text,.12f));
            LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.setMargins(0,dp(6),0,dp(6));
            if(!sharedHeader)selectedScroll.addView(divider,line);
            if(sharedHeader)header.addView(selectedScroll,new LinearLayout.LayoutParams(-1,-2));

            body=new FrameLayout(c);body.setClipChildren(false);body.setClipToPadding(false);addView(body,new LinearLayout.LayoutParams(-1,0,1));
            list=colors==null?new ListView(c):new MenuList(c);list.setDivider(null);list.setDividerHeight(0);list.setClipToPadding(false);list.setPadding(0,dp(2),0,dp(2));
            list.setVerticalFadingEdgeEnabled(false);list.setCacheColorHint(Color.TRANSPARENT);
            list.setNestedScrollingEnabled(true);
            list.setVerticalScrollBarEnabled(false);list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);body.addView(list,lp);
            adapter=new BaseAdapter(){
                public int getCount(){return rows.size();}
                public Object getItem(int position){return rows.get(position);}
                public long getItemId(int position){return position;}
                public int getViewTypeCount(){return 2;}
                public int getItemViewType(int position){return rows.get(position).section!=null?0:1;}
                public boolean areAllItemsEnabled(){return false;}
                public boolean isEnabled(int position){return rows.get(position).section==null;}
                public View getView(int position,View recycled,ViewGroup parent){
                    AppRow entry=rows.get(position);
                    if(entry.section!=null){
                        TextView heading=recycled instanceof TextView?(TextView)recycled:label("",14,accent);
                        heading.setText(entry.section);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setPadding(dp(6),0,0,0);
                        heading.setLayoutParams(new AbsListView.LayoutParams(-1,dp(sectionHeight())));return heading;
                    }
                    LinearLayout row=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(c);row.removeAllViews();
                    row.setLayoutParams(new AbsListView.LayoutParams(-1,dp(appTileHeight())));
                    for(int column=0;column<4;column++){
                        View child;
                        if(column<entry.items.size()){
                            ShortcutTarget target=entry.items.get(column);child=tile(target);
                            child.setOnClickListener(v->{if(!saving){if(editing)toggle(target);else launch.accept(target);}});
                        }else child=new View(c);
                        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),0);row.addView(child,p);
                    }
                    return row;
                }
            };
            if(!sharedHeader)list.addHeaderView(selectedScroll,null,false);list.setAdapter(adapter);
            empty=label("",14,secondary);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),0,dp(16),0);empty.setLayoutParams(new AbsListView.LayoutParams(-1,dp(72)));list.addFooterView(empty,null,false);
            bubble=label("",22,accent);bubble.setGravity(Gravity.CENTER);bubble.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            bubble.setBackground(round(tonal,20));bubble.setVisibility(GONE);bubble.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.TOP|Gravity.RIGHT);bp.rightMargin=dp(32);body.addView(bubble,bp);
            index=new IndexBar(c);index.setVisibility(INVISIBLE);index.setBackground(round((container&0x00ffffff)|0xe6000000,13));body.addView(index,new FrameLayout.LayoutParams(dp(26),-1,Gravity.RIGHT));
            list.setOnScrollListener(new AbsListView.OnScrollListener(){
                public void onScrollStateChanged(AbsListView v,int state){scrolling=state!=SCROLL_STATE_IDLE;updateIndex();}
                public void onScroll(AbsListView v,int first,int count,int total){updateIndex();if(getVisibility()==VISIBLE)setSelectedCollapsed(first>0);}
            });
            renderSelected();refreshRows();
        }
        int dp(float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
        int appTileHeight(){return page?96:compact?66:72;}
        int sectionHeight(){return compact?22:26;}
        void updateIndex(){
            if(index==null)return;
            boolean scrolled=list.getFirstVisiblePosition()>0
                || (!sharedHeader&&selectedScroll.getTop()+divider.getBottom()<=list.getPaddingTop());
            main.removeCallbacks(hideIndex);
            if(rows.isEmpty()||!scrolled){index.animate().cancel();index.setVisibility(INVISIBLE);bubble.setVisibility(GONE);}
            else if(scrolling||indexTouching){index.animate().cancel();index.setAlpha(1);index.setVisibility(VISIBLE);}
            else if(index.getVisibility()==VISIBLE)main.postDelayed(hideIndex,650);
        }
        void hideIndex(){
            if(!scrolling&&!indexTouching)index.animate().alpha(0).setDuration(140).withEndAction(()->index.setVisibility(INVISIBLE)).start();
        }
        int color(int attr,int fallback){
            return themeColor(c,attr,fallback);
        }
        static int blend(int a,int b,float amount){return Color.rgb(
                Math.round(Color.red(a)*(1-amount)+Color.red(b)*amount),
                Math.round(Color.green(a)*(1-amount)+Color.green(b)*amount),
                Math.round(Color.blue(a)*(1-amount)+Color.blue(b)*amount));}
        GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
        Drawable ripple(int background){return new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x26000000),round(background,22),round(Color.WHITE,22));}
        TextView label(String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
        TextView button(String value,Runnable action){TextView t=label(value,14,accent);t.setGravity(Gravity.CENTER);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setBackground(ripple(Color.TRANSPARENT));t.setOnClickListener(v->action.run());return t;}
        int selectedIndex(String key){for(int i=0;i<chosen.size();i++)if(chosen.get(i).key().equals(key))return i;return -1;}
        /** Roll the selected strip away while the catalogue scrolls, so browsing keeps the full height. */
        void setSelectedCollapsed(boolean value){
            if(page||!sharedHeader||selectedCollapsed==value||selectedScroll==null)return;
            selectedCollapsed=value;
            int current=selectedScroll.getHeight();
            if(selectedAnimator!=null)selectedAnimator.cancel();
            int width=selectedScroll.getWidth()>0?selectedScroll.getWidth():header.getWidth();
            selectedScroll.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,width),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            int expanded=selectedScroll.getMeasuredHeight();
            selectedAnimator=android.animation.ValueAnimator.ofInt(current,value?0:expanded);
            selectedAnimator.setDuration(160);
            selectedAnimator.addUpdateListener(animation->{
                int height=(int)animation.getAnimatedValue();ViewGroup.LayoutParams params=selectedScroll.getLayoutParams();
                if(params!=null){params.height=height;selectedScroll.setLayoutParams(params);}
                selectedScroll.setAlpha(expanded<=0?1f:Math.min(1f,height/(float)expanded));
            });
            selectedAnimator.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){
                    selectedAnimator=null;resetSelectedHeight();
                }
            });
            selectedAnimator.start();
        }
        void resetSelectedHeight(){
            if(selectedAnimator!=null)selectedAnimator.cancel();
            ViewGroup.LayoutParams params=selectedScroll.getLayoutParams();
            if(params!=null){params.height=selectedCollapsed?0:ViewGroup.LayoutParams.WRAP_CONTENT;selectedScroll.setLayoutParams(params);}
            selectedScroll.setAlpha(selectedCollapsed?0f:1f);
        }
        void toggleEditing(){
            if(saving)return;
            if(!editing){editing=true;renderSelected();adapter.notifyDataSetChanged();setSelectedCollapsed(false);return;}
            saving=true;renderSelected();adapter.notifyDataSetChanged();
            try{save.save(List.copyOf(chosen),success->main.post(()->finishSaving(Boolean.TRUE.equals(success))));}
            catch(RuntimeException failure){finishSaving(false);}
        }
        void finishSaving(boolean success){
            if(disposed||!saving)return;saving=false;
            if(success){editing=false;}else Toast.makeText(c,"保存失败，请重试",Toast.LENGTH_SHORT).show();
            renderSelected();adapter.notifyDataSetChanged();
        }
        void toggle(ShortcutTarget target){
            if(saving)return;
            int position=selectedIndex(target.key());
            if(position>=0)chosen.remove(position);
            else if(chosen.size()<Config.MAX_TARGETS)chosen.add(target);
            else{Toast.makeText(c,"最多添加 12 个快捷入口",Toast.LENGTH_SHORT).show();return;}
            renderSelected();refreshRows();
        }
        void toggleQuick(String id){
            QuickActionCatalog.Action action=QuickActionCatalog.find(id);
            ShortcutTarget target=action==null?null:ShortcutTarget.fromKey(c,QuickActionCatalog.key(id),apps);
            if(target!=null)toggle(target);
        }
        void renderSelected(){
            main.removeCallbacks(dragScroll);hover=-1;
            resetSelectedHeight();
            manage.setText(saving?"保存中…":editing?"完成":"管理");manage.setEnabled(!saving);
            if(editState!=null)editState.accept(editing,saving);
            help.setVisibility(editing&&!compact?VISIBLE:GONE);
            selectedRows.removeAllViews();
            if(chosen.isEmpty()){
                TextView hint=label(editing?"点击下方应用或快捷功能添加":"点击管理添加快捷入口",12,secondary);hint.setGravity(Gravity.CENTER);
                selectedRows.addView(hint,new LinearLayout.LayoutParams(-1,dp(appTileHeight())));
            }
            for(int start=0;start<chosen.size();start+=4){
                LinearLayout row=new LinearLayout(c);selectedRows.addView(row,new LinearLayout.LayoutParams(-1,dp(appTileHeight())));
                for(int column=0;column<4;column++){
                    int position=start+column;View child;
                    if(position<chosen.size()){
                        ShortcutTarget target=chosen.get(position);child=tile(target);child.setTag(position);
                        child.setOnClickListener(v->{if(!saving){if(editing)toggle(target);else if(target.quick)quickAction.accept(target.action);else launch.accept(target);}});
                        child.setOnLongClickListener(v->editing&&!saving&&v.startDragAndDrop(ClipData.newPlainText("shortcut-target",target.key()),new View.DragShadowBuilder(v),target.key(),0));
                        child.setContentDescription(target.label+(editing?"，点击移除，长按拖动调整顺序":target.quick?"，点击执行":"，点击打开"));
                    }else child=new View(c);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),0);row.addView(child,p);
                }
            }
            if(quickRefresher!=null)quickRefresher.run();
            selectedScroll.post(this::updateIndex);requestLayout();
        }
        int dropIndex(float px,float py){
            float x=px+selectedScroll.getScrollX()-selectedRows.getLeft(),y=py+selectedScroll.getScrollY()-selectedRows.getTop();
            float best=Float.MAX_VALUE;int position=-1;
            for(int r=0;r<selectedRows.getChildCount();r++){
                View candidate=selectedRows.getChildAt(r);if(!(candidate instanceof LinearLayout))continue;
                LinearLayout row=(LinearLayout)candidate;
                for(int column=0;column<row.getChildCount();column++){
                    View child=row.getChildAt(column);if(!(child.getTag() instanceof Integer))continue;
                    float dx=x-row.getLeft()-child.getLeft()-child.getWidth()/2f,dy=y-row.getTop()-child.getTop()-child.getHeight()/2f;
                    float distance=dx*dx+dy*dy;
                    if(distance<best){best=distance;position=(Integer)child.getTag();}
                }
            }
            return position;
        }
        View selectedTile(int position){
            if(position<0||position>=chosen.size()||position/4>=selectedRows.getChildCount())return null;
            return ((LinearLayout)selectedRows.getChildAt(position/4)).getChildAt(position%4);
        }
        void highlightDrop(){
            int next=dropIndex(dragX,dragY);if(next==hover)return;hover=next;
            for(int i=0;i<chosen.size();i++){
                View child=selectedTile(i);if(child==null)continue;
                int destination=i;
                if(hover>=0&&dragFrom>=0){
                    if(dragFrom<hover&&i>dragFrom&&i<=hover)destination=i-1;
                    else if(hover<dragFrom&&i>=hover&&i<dragFrom)destination=i+1;
                }
                View slot=selectedTile(destination);
                if(slot==null)continue;
                View row=(View)child.getParent(),targetRow=(View)slot.getParent();
                child.animate().translationX(targetRow.getLeft()+slot.getLeft()-row.getLeft()-child.getLeft())
                    .translationY(targetRow.getTop()+slot.getTop()-row.getTop()-child.getTop())
                    .alpha(i==dragFrom?0:1).setDuration(140).start();
            }
        }
        void clearDrag(){
            main.removeCallbacks(dragScroll);hover=-1;
            for(int r=0;r<selectedRows.getChildCount();r++)if(selectedRows.getChildAt(r) instanceof LinearLayout row)
                for(int column=0;column<row.getChildCount();column++)row.getChildAt(column).animate()
                    .translationX(0).translationY(0).alpha(1).setDuration(140).start();
        }
        void scrollDrag(){
            if(!editing||saving||hover<0||disposed)return;
            int[] headerLocation=new int[2],listLocation=new int[2];selectedScroll.getLocationOnScreen(headerLocation);list.getLocationOnScreen(listLocation);
            int top=listLocation[1]-headerLocation[1],edge=Math.min(dp(24),list.getHeight()/3);
            int direction=dragY<top+edge?-1:dragY>top+list.getHeight()-edge?1:0;
            if(direction==0)return;
            list.smoothScrollBy(direction*dp(10),16);
            highlightDrop();main.postDelayed(dragScroll,16);
        }
        boolean drag(DragEvent event){
            if(!editing||saving||!(event.getLocalState() instanceof String)||selectedIndex((String)event.getLocalState())<0)return false;
            int action=event.getAction();
            dragFrom=selectedIndex((String)event.getLocalState());
            if(action==DragEvent.ACTION_DRAG_STARTED){View source=selectedTile(dragFrom);if(source!=null)source.setAlpha(0);}
            if(action==DragEvent.ACTION_DRAG_LOCATION||action==DragEvent.ACTION_DROP){
                dragX=event.getX();dragY=event.getY();highlightDrop();main.removeCallbacks(dragScroll);
                if(action==DragEvent.ACTION_DRAG_LOCATION)main.post(dragScroll);
            }
            if(action==DragEvent.ACTION_DROP){
                int from=selectedIndex((String)event.getLocalState());
                if(hover>=0){ShortcutTarget item=chosen.remove(from);chosen.add(hover,item);renderSelected();}
                clearDrag();return true;
            }
            if(action==DragEvent.ACTION_DRAG_ENDED||action==DragEvent.ACTION_DRAG_EXITED){clearDrag();if(action==DragEvent.ACTION_DRAG_ENDED)dragFrom=-1;}
            return true;
        }
        View tile(ShortcutTarget target){
            IconTile cell=new IconTile(c,compact,page,target.quick,target.label,target.icon,text,accent);
            if(editing) {
                TextView badge=label(selectedIndex(target.key())>=0?"−":"+",16,accent);badge.setGravity(Gravity.CENTER);badge.setBackground(round(tonal,12));badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                FrameLayout.LayoutParams b=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP|Gravity.RIGHT);cell.artwork.addView(badge,b);
            }
            cell.setContentDescription(target.label+(editing?(selectedIndex(target.key())>=0?"，已选，点击移除":"，点击添加"):""));
            return cell;
        }
        int sectionIndex(String section){int position=section==null||section.length()!=1?-1:ALPHABET.indexOf(section);return position>=0?position:26;}
        void refreshRows(){
            visible.clear();for(ShortcutTarget target:appRows)if(selectedIndex(target.key())<0)visible.add(target);rows.clear();Arrays.fill(sectionPositions,-1);
            for(int letter=0;letter<ALPHABET.length();letter++){
                ArrayList<ShortcutTarget> group=new ArrayList<>();for(ShortcutTarget target:visible)if(sectionIndex(target.section)==letter)group.add(target);
                if(group.isEmpty())continue;
                sectionPositions[letter]=rows.size()+list.getHeaderViewsCount();rows.add(new AppRow(ALPHABET.substring(letter,letter+1),List.of()));
                for(int start=0;start<group.size();start+=4)rows.add(new AppRow(null,List.copyOf(group.subList(start,Math.min(start+4,group.size())))));
            }
            adapter.notifyDataSetChanged();boolean noRows=rows.isEmpty();
            empty.setText("暂无可启动的应用");
            empty.setVisibility(noRows?VISIBLE:GONE);list.setVisibility(VISIBLE);updateIndex();bubble.setVisibility(GONE);
        }
        @Override protected void onDetachedFromWindow(){disposed=true;main.removeCallbacksAndMessages(null);resetSelectedHeight();super.onDetachedFromWindow();}

        final class IndexBar extends View {
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            int active=-1;
            IndexBar(Context context){super(context);setContentDescription("按字母滑动定位，中文应用按拼音分组");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
            @Override protected void onDraw(Canvas canvas){
                super.onDraw(canvas);float row=getHeight()/(float)ALPHABET.length();
                paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(Math.min(dp(11),Math.max(dp(6),row*.8f)));
                int step=Math.max(1,(int)Math.ceil(dp(10)/Math.max(1,row)));
                for(int i=0;i<ALPHABET.length();i++)if(i%step==0||i==ALPHABET.length()-1||i==active){
                    paint.setColor(i==active?accent:secondary);Paint.FontMetrics fm=paint.getFontMetrics();
                    canvas.drawText(ALPHABET.substring(i,i+1),getWidth()/2f,row*(i+.5f)-(fm.ascent+fm.descent)/2,paint);
                }
            }
            @Override public boolean onTouchEvent(MotionEvent event){
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE){
                    indexTouching=true;main.removeCallbacks(hideIndex);getParent().requestDisallowInterceptTouchEvent(true);active=Math.max(0,Math.min(26,(int)(event.getY()/Math.max(1,getHeight())*27)));
                    int position=-1;for(int letter=active;letter<sectionPositions.length;letter++)if(sectionPositions[letter]>=0){position=sectionPositions[letter];break;}
                    if(position<0)for(int letter=active-1;letter>=0;letter--)if(sectionPositions[letter]>=0){position=sectionPositions[letter];break;}
                    if(position>=0)list.setSelectionFromTop(position,0);
                    bubble.setText(ALPHABET.substring(active,active+1));FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)bubble.getLayoutParams();p.topMargin=Math.max(0,Math.min(body.getHeight()-dp(40),(int)event.getY()-dp(20)));bubble.setLayoutParams(p);bubble.setVisibility(VISIBLE);invalidate();return true;
                }
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);indexTouching=false;active=-1;bubble.setVisibility(GONE);invalidate();updateIndex();return true;}
                return true;
            }
        }
    }
}

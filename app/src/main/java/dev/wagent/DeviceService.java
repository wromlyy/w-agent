package dev.wagent;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

public class DeviceService extends AccessibilityService {
    public static volatile DeviceService live;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Integer,AccessibilityNodeInfo> nodes=new HashMap<>();
    private WindowManager wm;
    private View overlay;
    private CompletableFuture<Boolean> pendingApproval;
    private int counter;
    @Override public void onServiceConnected(){live=this;wm=getSystemService(WindowManager.class);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){live=null;((WApp)getApplication()).agent.stop();removeOverlay();super.onDestroy();}
    public <T> T ui(Callable<T> action) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return action.call();
        FutureTask<T> task=new FutureTask<>(action); main.post(task);return task.get(8,TimeUnit.SECONDS);
    }
    private void clearNodes(){for(AccessibilityNodeInfo n:nodes.values())n.recycle();nodes.clear();counter=0;}
    public String screen() throws Exception {return ui(()->{
        clearNodes();JSONObject s=new JSONObject();JSONArray items=new JSONArray();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null){s.put("package",String.valueOf(root.getPackageName()));walk(root,items,0);root.recycle();}
        else s.put("notice","Экран недоступен. Откройте приложение или разблокируйте телефон вручную.");
        android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
        return s.put("width",dm.widthPixels).put("height",dm.heightPixels).put("elements",items).toString();
    });}
    private void walk(AccessibilityNodeInfo n,JSONArray out,int depth) throws Exception {
        if(depth>35||counter>=220)return;
        if(n.isVisibleToUser()) {
            Rect r=new Rect();n.getBoundsInScreen(r);
            String text=n.isPassword()?"[password]":String.valueOf(n.getText()==null?"":n.getText());
            String desc=n.isPassword()?"":String.valueOf(n.getContentDescription()==null?"":n.getContentDescription());
            if(!text.trim().isEmpty()||!desc.trim().isEmpty()||n.isClickable()||n.isScrollable()||n.isEditable()) {
                int id=++counter;nodes.put(id,AccessibilityNodeInfo.obtain(n));
                out.put(new JSONObject().put("id",id).put("text",clip(text,600)).put("description",clip(desc,300)).put("viewId",n.getViewIdResourceName())
                    .put("clickable",n.isClickable()).put("editable",n.isEditable()).put("scrollable",n.isScrollable()).put("password",n.isPassword())
                    .put("bounds",new JSONArray(new int[]{r.left,r.top,r.right,r.bottom})));
            }
        }
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){walk(c,out,depth+1);c.recycle();}}
    }
    private static String clip(String s,int n){return s.length()>n?s.substring(0,n):s;}
    public String execute(JSONObject a) throws Exception {
        String op=a.getString("action");
        if("read_screen".equals(op))return screen();
        if("wait".equals(op)){Thread.sleep(Math.min(5000,Math.max(100,a.optInt("ms",1000))));return "Ожидание завершено";}
        if("tap".equals(op)||"input_text".equals(op)||"press".equals(op)||"scroll".equals(op)){
            String label=ui(()->{AccessibilityNodeInfo n=nodes.get(a.optInt("id",-1));return n==null?"":String.valueOf(n.getText())+" "+String.valueOf(n.getContentDescription());});
            if(label.toLowerCase(Locale.ROOT).matches(".*(send|отправ|оплат|купить|purchase|delete|удал|publish|опубликов|подписаться|subscribe|like|нравится|follow).*"))
                if(!approve("Подтвердите действие",label))return "Пользователь отклонил действие. Не повторяй его.";
        }
        return ui(()->{
            AccessibilityNodeInfo n=nodes.get(a.optInt("id",-1));
            switch(op){
                case "open_app": {
                    Intent i=getPackageManager().getLaunchIntentForPackage(a.getString("package"));
                    if(i==null)throw new IllegalArgumentException("Приложение не установлено или не доступно для запуска");
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return "Приложение открыто";
                }
                case "open_url": {
                    android.net.Uri u=android.net.Uri.parse(a.getString("url"));if(!"https".equals(u.getScheme()))throw new IllegalArgumentException("Поддерживаются HTTPS-ссылки");
                    Intent i=new Intent(Intent.ACTION_VIEW,u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(!a.optString("package").trim().isEmpty())i.setPackage(a.getString("package"));
                    startActivity(i);return "Ссылка открыта";
                }
                case "tap": {
                    if(n!=null){if(n.isPassword())throw new IllegalArgumentException("Поле пароля заполняется вручную");AccessibilityNodeInfo p=AccessibilityNodeInfo.obtain(n);
                        try{for(int i=0;i<6&&p!=null;i++){if(p.isClickable())return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)?"Нажатие выполнено":"Не удалось нажать";AccessibilityNodeInfo next=p.getParent();p.recycle();p=next;}}finally{if(p!=null)p.recycle();}
                        Rect b=new Rect();n.getBoundsInScreen(b);return gesture(b.centerX(),b.centerY(),b.centerX(),b.centerY(),80);
                    }
                    return gesture(a.getInt("x"),a.getInt("y"),a.getInt("x"),a.getInt("y"),80);
                }
                case "swipe":return gesture(a.getInt("x"),a.getInt("y"),a.getInt("x2"),a.getInt("y2"),Math.min(1500,Math.max(100,a.optInt("ms",400))));
                case "input_text": {
                    AccessibilityNodeInfo target=n; boolean recycle=false;
                    if(target==null){AccessibilityNodeInfo root=getRootInActiveWindow();if(root!=null){target=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);root.recycle();recycle=true;}}
                    if(target==null)return "Нет поля ввода. Прочитай экран и выбери editable элемент.";
                    try{if(target.isPassword())throw new IllegalArgumentException("Пароль вводится пользователем вручную");
                        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.getString("text"));
                        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)?"Текст введён":"Поле не приняло текст";
                    }finally{if(recycle)target.recycle();}
                }
                case "scroll":return n!=null&&n.performAction("backward".equals(a.optString("direction"))?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)?"Прокрутка выполнена":"Выбери scrollable элемент или используй swipe";
                case "press":{
                    String key=a.optString("key");
                    if("enter".equals(key)){if(Build.VERSION.SDK_INT<30)return "Enter требует Android 11";AccessibilityNodeInfo root=getRootInActiveWindow();AccessibilityNodeInfo f=root==null?null:root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);if(root!=null)root.recycle();if(f==null)return "Нет поля ввода";try{return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())?"Enter":"Не удалось нажать Enter";}finally{f.recycle();}}
                    int code=switch(key){case "home"->GLOBAL_ACTION_HOME;case "back"->GLOBAL_ACTION_BACK;case "recents"->GLOBAL_ACTION_RECENTS;case "notifications"->GLOBAL_ACTION_NOTIFICATIONS;default->throw new IllegalArgumentException("Неизвестная кнопка");};
                    return performGlobalAction(code)?"Готово":"Действие не выполнено";
                }
                default:throw new IllegalArgumentException("Неизвестное действие: "+op);
            }
        });
    }
    private String gesture(int x,int y,int x2,int y2,int duration){
        android.util.DisplayMetrics d=getResources().getDisplayMetrics();
        if(x<0||y<0||x2<0||y2<0||x>=d.widthPixels||x2>=d.widthPixels||y>=d.heightPixels||y2>=d.heightPixels)throw new IllegalArgumentException("Координаты вне экрана");
        Path p=new Path();p.moveTo(x,y);if(x!=x2||y!=y2)p.lineTo(x2,y2);
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,duration)).build(),null,null)?"Жест отправлен":"Жест недоступен";
    }
    public String screenshot() throws Exception {
        if(Build.VERSION.SDK_INT<30)return null;
        CompletableFuture<String> result=new CompletableFuture<>();
        ui(()->{takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult shot){
                try(HardwareBuffer buffer=shot.getHardwareBuffer()){
                    Bitmap src=Bitmap.wrapHardwareBuffer(buffer,shot.getColorSpace());if(src==null){result.complete(null);return;}
                    Bitmap copy=src.copy(Bitmap.Config.ARGB_8888,true);src.recycle();
                    // Remove pixels of password fields before sending the screenshot to a provider.
                    Canvas canvas=new Canvas(copy);Paint mask=new Paint();mask.setColor(Color.BLACK);
                    AccessibilityNodeInfo root=getRootInActiveWindow();if(root!=null){maskPasswords(root,canvas,mask);root.recycle();}
                    float scale=Math.min(1f,1280f/Math.max(copy.getWidth(),copy.getHeight()));
                    Bitmap small=Bitmap.createScaledBitmap(copy,Math.max(1,(int)(copy.getWidth()*scale)),Math.max(1,(int)(copy.getHeight()*scale)),true);
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,75,bytes);
                    if(small!=copy)small.recycle();copy.recycle();result.complete(android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));
                }catch(Exception e){result.complete(null);}
            }
            @Override public void onFailure(int error){result.complete(null);}
        });return null;});return result.get(5,TimeUnit.SECONDS);
    }
    private void maskPasswords(AccessibilityNodeInfo n,Canvas c,Paint p){if(n.isPassword()){Rect r=new Rect();n.getBoundsInScreen(r);c.drawRect(r,p);}for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null){maskPasswords(child,c,p);child.recycle();}}}
    public boolean approve(String title,String detail) throws Exception {
        CompletableFuture<Boolean> answer=new CompletableFuture<>();pendingApproval=answer;
        ui(()->{showOverlay(true,title,clip(detail,220),answer);return null;});
        try {return answer.get(120,TimeUnit.SECONDS);}catch(TimeoutException e){return false;}
        finally{pendingApproval=null;main.post(()->{removeOverlay();if(((WApp)getApplication()).agent.running())showStop();});}
    }
    public void cancelApproval(){CompletableFuture<Boolean> p=pendingApproval;if(p!=null)p.complete(false);}
    public void showStop(){main.post(()->showOverlay(false,"W  ·  Остановить",null,null));}
    public void hideStop(){main.post(this::removeOverlay);}
    private void showOverlay(boolean approval,String title,String detail,CompletableFuture<Boolean> answer){
        removeOverlay();LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(28,20,28,20);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(Color.rgb(38,61,48));bg.setCornerRadius(28);box.setBackground(bg);box.setElevation(12);
        TextView label=new TextView(this);label.setText(title);label.setTextColor(Color.WHITE);label.setTextSize(14);box.addView(label);
        if(approval){TextView info=new TextView(this);info.setText(detail);info.setTextColor(Color.rgb(220,225,219));box.addView(info);LinearLayout buttons=new LinearLayout(this);
            Button no=new Button(this);no.setText("Отклонить");no.setOnClickListener(v->answer.complete(false));buttons.addView(no);
            Button yes=new Button(this);yes.setText("Разрешить");yes.setOnClickListener(v->answer.complete(true));buttons.addView(yes);box.addView(buttons);
        }else box.setOnClickListener(v->((WApp)getApplication()).agent.stop());
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(approval?Math.min(getResources().getDisplayMetrics().widthPixels-40,900):WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=16;lp.y=approval?80:getResources().getDisplayMetrics().heightPixels/2;
        if(!approval)box.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY;int startX,startY;boolean moved;
            @Override public boolean onTouch(View v,MotionEvent e){
                if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();startX=lp.x;startY=lp.y;moved=false;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-downX,dy=e.getRawY()-downY;if(Math.abs(dx)+Math.abs(dy)>12)moved=true;if(moved){lp.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-v.getWidth(),startX+(int)dx));lp.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-v.getHeight(),startY+(int)dy));try{wm.updateViewLayout(v,lp);}catch(Exception ignored){}}return true;}
                if(e.getAction()==MotionEvent.ACTION_UP){if(!moved)v.performClick();return true;}return true;
            }
        });
        wm.addView(box,lp);overlay=box;
    }
    private void removeOverlay(){if(overlay!=null&&wm!=null){try{wm.removeView(overlay);}catch(Exception ignored){}overlay=null;}}
}

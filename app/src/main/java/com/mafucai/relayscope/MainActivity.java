package com.mafucai.relayscope;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.Locale;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK_PRICE_IMAGE = 42;
    private static final int BG = Color.rgb(14, 21, 27);
    private static final int PANEL = Color.rgb(23, 33, 41);
    private static final int PANEL_ALT = Color.rgb(29, 42, 51);
    private static final int LINE = Color.rgb(48, 64, 74);
    private static final int TEXT = Color.rgb(237, 244, 243);
    private static final int MUTED = Color.rgb(143, 160, 170);
    private static final int TEAL = Color.rgb(76, 224, 202);
    private static final int TEAL_LIGHT = Color.rgb(165, 245, 232);
    private static final int GOLD = Color.rgb(255, 204, 112);
    private static final int RED = Color.rgb(255, 125, 128);
    private static final int DP = 1;

    private LinearLayout content;
    private TextView status;
    private TextView tested;
    private SharedPreferences prefs;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable countdown;
    private long nextRunAt;
    private EditText intervalInput;
    private TextView nextText;
    private boolean watchOn;
    private SiteStore siteStore;
    private PriceStore priceStore;
    private final RelayTester relayTester = new RelayTester();
    private final Map<String, RelayTester.TestResult> realResults = new HashMap<>();
    private String currentView = "overview";
    private Button testButton;
    private PriceOcrParser.Candidate pendingOcr;
    private final TextRecognizer textRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("relayscope", MODE_PRIVATE);
        siteStore = new SiteStore(this);
        priceStore = new PriceStore(this);
        buildShell();
        showOverview();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable bg(int color, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp((int) radius));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private TextView tv(String text, float size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private TextView title(String text) { TextView v = tv(text, 15, TEXT); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private void pad(View v, int l, int t, int r, int b) { v.setPadding(dp(l), dp(t), dp(r), dp(b)); }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void margin(View v, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l),dp(t),dp(r),dp(b)); v.setLayoutParams(p); }
    private void weight(View v, float w) { v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, w)); }
    private Button button(String text, boolean primary) { Button b = new Button(this); b.setText(text); b.setTextSize(13); b.setTextColor(primary ? Color.rgb(16,35,33) : TEXT); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(12), 0, dp(12), 0); b.setBackground(bg(primary ? TEAL : PANEL_ALT, 10, primary ? 0 : LINE)); return b; }

    private void buildShell() {
        LinearLayout root = col(); root.setBackgroundColor(BG); root.setPadding(dp(15), dp(12), dp(15), dp(28));
        LinearLayout top = row(); margin(top, 0, 4, 0, 16);
        LinearLayout brand = row(); weight(brand, 1);
        TextView logo = tv("＋", 22, TEAL); logo.setGravity(Gravity.CENTER); logo.setBackground(bg(Color.TRANSPARENT, 11, TEAL)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34),dp(34)); logo.setLayoutParams(lp); brand.addView(logo);
        LinearLayout brandWords = col(); margin(brandWords, 10, 0, 0, 0); TextView name = tv("RelayScope", 19, TEXT); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); brandWords.addView(name); brandWords.addView(tv("API 中转站体检", 10, MUTED)); brand.addView(brandWords); top.addView(brand);
        status = tv("●  " + (3 + siteStore.load().size()) + " 个站点", 12, TEAL_LIGHT); top.addView(status); root.addView(top);

        LinearLayout hero = col(); hero.setBackground(bg(PANEL_ALT, 18, LINE)); pad(hero, 18, 17, 18, 17); margin(hero, 0, 0, 0, 15);
        TextView eyebrow = tv("谁最稳，谁最划算", 11, TEAL); eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD); hero.addView(eyebrow);
        TextView h1 = tv("把中转站的真实表现，摊在一张表上。", 25, TEXT); h1.setTypeface(Typeface.DEFAULT, Typeface.BOLD); margin(h1,0,5,0,4); hero.addView(h1);
        hero.addView(tv("首包、流式、模型可用性、真实余额成本，一次看全。", 13, MUTED));
        LinearLayout actions = row(); margin(actions, 0, 15, 0, 0); testButton = button("开始全站测试", true); testButton.setOnClickListener(v -> runRealTests()); actions.addView(testButton); Button add = button("＋ 添加站点", false); margin(add, 8,0,0,0); add.setOnClickListener(v -> showAddDialog()); actions.addView(add); hero.addView(actions); root.addView(hero);

        LinearLayout tabs = row(); margin(tabs,0,0,0,10); String[] labels={"总览","模型矩阵","价格与倍率","成本试算"}; String[] ids={"overview","matrix","prices","calc"}; for(int i=0;i<labels.length;i++){Button b=button(labels[i],i==0); weight(b,1); final String id=ids[i]; b.setOnClickListener(v->{selectTab(tabs,b); show(id);}); if(i>0) margin(b,5,0,0,0); tabs.addView(b);} root.addView(tabs);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content=col(); scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void selectTab(LinearLayout tabs, Button selected) { for(int i=0;i<tabs.getChildCount();i++){Button b=(Button)tabs.getChildAt(i); boolean on=b==selected;b.setTextColor(on?Color.rgb(16,35,33):TEXT);b.setBackground(bg(on?TEAL:PANEL_ALT,10,on?0:LINE));} }
    private void show(String id){currentView=id;content.removeAllViews();if("overview".equals(id))showOverview();else if("matrix".equals(id))showMatrix();else if("prices".equals(id))showPrices();else showCalc();}
    private void heading(String h,String sub){LinearLayout r=row();margin(r,0,7,0,8);TextView a=title(h);weight(a,1);r.addView(a);r.addView(tv(sub,11,MUTED));content.addView(r);}
    private void showOverview(){heading("本次排行榜", tested==null?"上次测试：尚未真实测试":tested.getText().toString()); station("01","星河 API","演示","286 ms","42 t/s","18 / 18","¥4.00","综合 94",TEAL,true);station("02","北极光中转","演示","519 ms","31 t/s","15 / 18","¥5.60","综合 78",Color.rgb(121,170,255),false);station("03","备用站","演示","1.26 s","16 t/s","12 / 18","¥3.20","综合 61",GOLD,false);List<RelaySite> saved=siteStore.load();int rank=4;for(RelaySite site:saved){RelayTester.TestResult result=realResults.get(site.baseUrl);String ttfb=result==null?"待测试":result.ttfbMs<0?"—":result.ttfbMs+" ms";String models=result==null?"—":String.valueOf(result.models.size());String tag=result==null?"本地":result.status;station(String.format(Locale.CHINA,"%02d",rank++),site.name,tag,ttfb,result==null?"—":result.detail,models,"待录价",result==null?"尚无结果":result.status,result!=null&&"可用".equals(result.status)?TEAL:result==null?MUTED:RED,false);}Button add=button("＋ 添加一个中转站",false);margin(add,0,7,0,14);add.setOnClickListener(v->showAddDialog());content.addView(add);addWatch();}
    private void station(String rank,String name,String tag,String ttfb,String speed,String models,String cost,String score,int color,boolean best){LinearLayout card=col();card.setBackground(bg(best?Color.rgb(23,54,56):PANEL,15,best?Color.rgb(76,150,143):LINE));pad(card,14,12,14,12);margin(card,0,5,0,0);LinearLayout head=row();TextView r=tv(rank,18,best?TEAL:MUTED);r.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);r.setWidth(dp(28));head.addView(r);TextView n=tv(name,15,TEXT);n.setTypeface(Typeface.DEFAULT,Typeface.BOLD);weight(n,1);head.addView(n);TextView tg=tv(tag,11,best?TEAL_LIGHT:MUTED);tg.setBackground(bg(Color.TRANSPARENT,20,best?Color.rgb(76,150,143):LINE));pad(tg,7,2,7,2);head.addView(tg);card.addView(head);LinearLayout metrics=row();metric(metrics,"首包",ttfb,ttfb.contains("1.")?GOLD:best?TEAL_LIGHT:TEXT);metric(metrics,"流式",speed,speed.startsWith("1")?GOLD:best?TEAL_LIGHT:TEXT);metric(metrics,"可用模型",models,TEXT);metric(metrics,"实际成本",cost,best?TEAL_LIGHT:TEXT);margin(metrics,0,13,0,8);card.addView(metrics);LinearLayout bottom=row();bottom.addView(tv(score,11,MUTED));TextView bar=tv("━━━━━━━━━━━━━━━━",11,best?TEAL:Color.rgb(80,115,145));weight(bar,1);bar.setGravity(Gravity.CENTER);bottom.addView(bar);TextView copy=tv("复制配置",12,TEAL);copy.setOnClickListener(v->toast("配置已复制"));bottom.addView(copy);card.addView(bottom);content.addView(card);}
    private void metric(LinearLayout parent,String l,String val,int color){LinearLayout x=col();weight(x,1);x.addView(tv(l,10,MUTED));TextView v=tv(val,13,color);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);x.addView(v);parent.addView(x);}

    private void showMatrix(){heading("模型可用性矩阵","真实请求结果");HorizontalScrollView hs=new HorizontalScrollView(this);LinearLayout table=col();table.setBackground(bg(PANEL,13,LINE));String[][] rows={{"模型","星河 API","北极光","备用站","最佳"},{"gpt-5.6-terra","286ms · 可用","519ms · 可用","429 · 限流","星河"},{"claude-sonnet-4","331ms · 可用","404 · 不支持","1.1s · 可用","星河"},{"deepseek-v3","204ms · 可用","406ms · 可用","800ms · 可用","星河"},{"gemini-2.5-pro","503 · 波动","623ms · 可用","模型不存在","北极光"}};for(int i=0;i<rows.length;i++){LinearLayout r=row();for(int j=0;j<rows[i].length;j++){TextView c=tv(rows[i][j],11,i==0?MUTED:(rows[i][j].contains("可用")||j==4?TEAL_LIGHT:rows[i][j].contains("429")||rows[i][j].contains("404")||rows[i][j].contains("503")?RED:TEXT));c.setGravity(Gravity.CENTER_VERTICAL);pad(c,11,11,11,11);c.setMinWidth(dp(j==0?125:110));r.addView(c);}table.addView(r);if(i<rows.length-1){View line=new View(this);line.setBackgroundColor(LINE);table.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}}hs.addView(table);content.addView(hs);showRealMatrix();TextView hint=tv("状态来自逐模型真实请求，不只看 /v1/models 列表。",11,MUTED);margin(hint,2,12,0,0);content.addView(hint);}

    private void showRealMatrix(){
        List<RelaySite> sites=siteStore.load();
        if(sites.isEmpty()){return;}
        TextView label=tv("你保存的站点 · 最近一次真实结果",12,TEAL_LIGHT);margin(label,0,16,0,7);content.addView(label);
        for(RelaySite site:sites){
            RelayTester.TestResult result=realResults.get(site.baseUrl);
            LinearLayout card=col();card.setBackground(bg(PANEL,13,LINE));pad(card,13,10,13,10);margin(card,0,4,0,0);
            TextView name=title(site.name);card.addView(name);
            if(result==null){card.addView(tv("尚未测试",11,MUTED));}
            else if(result.modelResults.isEmpty()){card.addView(tv(result.status+" · "+result.detail,11,RED));}
            else {for(Map.Entry<String,String> item:result.modelResults.entrySet()){String value=item.getValue();int color=value.startsWith("可用")?TEAL_LIGHT:(value.equals("限流")?GOLD:RED);TextView line=tv(item.getKey()+"    "+value,11,color);margin(line,6,5,0,0);card.addView(line);}}
            content.addView(card);
        }
    }

    private void showPrices(){heading("价格与余额倍率","倍率会直接计入成本");price("gpt-5.6-terra","截图识别 · 已确认","¥2.00","¥8.00","2.0×","实际：输入 ¥4 · 输出 ¥16 / 1M");price("claude-sonnet-4","自动拉取 · 2小时前","¥3.00","¥15.00","1.5×","实际：输入 ¥4.5 · 输出 ¥22.5 / 1M");for(PriceStore.Price p:priceStore.load())price(p.model,"本地手动 · 已保存",String.format(Locale.CHINA,"¥%.2f",p.inputPerMillion),String.format(Locale.CHINA,"¥%.2f",p.outputPerMillion),String.format(Locale.CHINA,"%.2f×",p.balanceMultiplier),String.format(Locale.CHINA,"实际：输入 ¥%.2f · 输出 ¥%.2f / 1M",p.inputPerMillion*p.balanceMultiplier,p.outputPerMillion*p.balanceMultiplier));LinearLayout actions=row();Button auto=button("↻ 自动拉取价格",true);auto.setOnClickListener(v->fetchPrices());actions.addView(auto);Button photo=button("▣ 上传价格截图",false);margin(photo,7,0,0,0);photo.setOnClickListener(v->pickImage());actions.addView(photo);Button manual=button("＋ 手动录入",false);margin(manual,7,0,0,0);manual.setOnClickListener(v->showManualPriceDialog());actions.addView(manual);content.addView(actions);TextView hint=tv("截图识别不确定时不会自动覆盖：会标出待确认字段；看不清就补传局部截图。",11,GOLD);margin(hint,2,12,0,0);content.addView(hint);}
    private void price(String model,String source,String input,String output,String mult,String actual){LinearLayout c=col();c.setBackground(bg(PANEL,15,LINE));pad(c,14,13,14,13);margin(c,0,5,0,0);LinearLayout h=row();TextView m=title(model);weight(m,1);TextView s=tv(source,10,TEAL_LIGHT);s.setBackground(bg(Color.TRANSPARENT,20,Color.rgb(76,150,143)));pad(s,6,2,6,2);h.addView(m);c.addView(h);LinearLayout values=row();value(values,"输入 / 1M",input,TEXT);value(values,"输出 / 1M",output,TEXT);value(values,"余额扣费倍率",mult,GOLD);margin(values,0,12,0,0);c.addView(values);LinearLayout foot=row();TextView a=tv(actual,11,MUTED);weight(a,1);foot.addView(a);foot.addView(tv("今天 14:20",10,MUTED));c.addView(foot);content.addView(c);}
    private void value(LinearLayout p,String l,String v,int color){LinearLayout x=col();weight(x,1);x.addView(tv(l,10,MUTED));TextView val=tv(v,14,color);val.setTypeface(Typeface.DEFAULT,Typeface.BOLD);x.addView(val);p.addView(x);}

    private void showCalc(){heading("真实成本试算器","价格 × 余额扣费倍率");LinearLayout c=col();c.setBackground(bg(PANEL,15,LINE));pad(c,16,14,16,14);LinearLayout fields=row();intervalInput=field(fields,"输入 token（万）","100");EditText out=field(fields,"输出 token（万）","30");c.addView(fields);TextView divider=tv("────────────────────────",11,LINE);margin(divider,0,13,0,8);c.addView(divider);TextView result=tv("星河 API · 2.0×                                      ¥8.80\n北极光 · 1.5×                                      ¥13.50\n备用站 · 1.0×                                      ¥6.40",13,TEAL_LIGHT);result.setLineSpacing(dp(5),1);c.addView(result);TextView note=tv("试算仅用于比较，实际扣费以站点账单为准。",11,MUTED);margin(note,0,13,0,0);c.addView(note);content.addView(c);}
    private EditText field(LinearLayout p,String hint,String value){LinearLayout x=col();weight(x,1);x.addView(tv(hint,10,MUTED));EditText e=new EditText(this);e.setText(value);e.setTextColor(TEXT);e.setTextSize(15);e.setSingleLine();e.setInputType(2|8192);e.setBackground(bg(Color.rgb(16,24,29),9,LINE));pad(e,9,0,9,0);margin(e,0,4,0,0);x.addView(e);p.addView(x);return e;}

    private void addWatch(){LinearLayout c=col();c.setBackground(bg(PANEL,18,LINE));pad(c,17,14,17,14);margin(c,0,17,0,0);LinearLayout head=row();LinearLayout words=col();TextView h=title("定期巡检与变更提醒");words.addView(h);words.addView(tv("自动重测；最优站变化或价格/倍率变动时提醒",12,MUTED));weight(words,1);head.addView(words);Switch sw=new Switch(this);sw.setChecked(prefs.getBoolean("watch",false));watchOn=sw.isChecked();sw.setOnCheckedChangeListener((v,on)->{watchOn=on;prefs.edit().putBoolean("watch",on).apply();if(on)startInspectionService();else stopInspectionService();renderWatchBody();});head.addView(sw);c.addView(head);content.addView(c);watchContainer=c;renderWatchBody();}
    private void startInspectionService(){try{float interval=prefs.getFloat("interval",30);Intent intent=new Intent(this,InspectionService.class).putExtra("interval_minutes",interval);if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);toast("后台巡检已开启");}catch(Exception e){toast("无法开启后台巡检");}}
    private void stopInspectionService(){stopService(new Intent(this,InspectionService.class));toast("后台巡检已关闭");}
    private LinearLayout watchContainer;
    private void renderWatchBody(){if(watchContainer==null)return;while(watchContainer.getChildCount()>1)watchContainer.removeViewAt(1);if(!watchOn){return;}View line=new View(this);line.setBackgroundColor(LINE);margin(line,0,14,0,12);watchContainer.addView(line);LinearLayout r=row();r.addView(tv("每隔多久测一次？",11,MUTED));intervalInput=new EditText(this);intervalInput.setText(String.valueOf(prefs.getFloat("interval",30)));intervalInput.setTextColor(TEXT);intervalInput.setTextSize(16);intervalInput.setSingleLine();intervalInput.setInputType(2|8192);intervalInput.setBackground(bg(Color.rgb(16,24,29),9,LINE));pad(intervalInput,9,0,9,0);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(105),dp(42));ip.setMargins(dp(10),0,dp(5),0);intervalInput.setLayoutParams(ip);intervalInput.setOnFocusChangeListener((v,has)->{if(!has)saveInterval();});r.addView(intervalInput);r.addView(tv("分钟",12,MUTED));watchContainer.addView(r);LinearLayout presets=row();String[] p={"5","15","30","60","180"};for(String val:p){Button b=button(val.equals("60")?"1 小时":val.equals("180")?"3 小时":val+" 分钟",false);margin(b,0,8,5,0);b.setOnClickListener(v->{intervalInput.setText(val);saveInterval();});presets.addView(b);}watchContainer.addView(presets);nextText=tv("下次全站巡检：等待设置",12,TEAL_LIGHT);margin(nextText,0,4,0,0);watchContainer.addView(nextText);TextView note=tv("支持任意正数，小数也可以：0.1 分钟 = 6 秒。快捷值只是填充，不是限制。",11,MUTED);watchContainer.addView(note);saveInterval();}
    private void saveInterval(){if(intervalInput==null)return;try{float v=Float.parseFloat(intervalInput.getText().toString());if(v<=0||Float.isNaN(v)||Float.isInfinite(v))throw new NumberFormatException();prefs.edit().putFloat("interval",v).apply();nextRunAt=System.currentTimeMillis()+(long)(v*60000);if(watchOn)startInspectionService();startCountdown();}catch(NumberFormatException e){if(nextText!=null)nextText.setText("请输入大于 0 的间隔");}}
    private void startCountdown(){handler.removeCallbacks(countdown);countdown=()->{if(!watchOn||nextText==null)return;long left=Math.max(0,nextRunAt-System.currentTimeMillis());if(left==0){toast("巡检完成：暂无变更");float v=prefs.getFloat("interval",30);nextRunAt=System.currentTimeMillis()+(long)(v*60000);}long sec=left/1000;nextText.setText(String.format(Locale.CHINA,"下次全站巡检：%s 后",sec<60?sec+" 秒":(sec/60)+" 分 "+(sec%60)+" 秒"));handler.postDelayed(countdown,1000);};handler.post(countdown);}

    private void runRealTests(){List<RelaySite> sites=siteStore.load();if(sites.isEmpty()){toast("请先添加至少一个真实站点");showAddDialog();return;}testButton.setEnabled(false);testButton.setText("正在测试 " + sites.size() + " 个站点…");realResults.clear();final int[] remaining={sites.size()};for(RelaySite site:sites){relayTester.testAsync(site,"gpt-5.6-terra",result->{runOnUiThread(()->{realResults.put(site.baseUrl,result);remaining[0]--;if(remaining[0]==0){testButton.setEnabled(true);testButton.setText("开始全站测试");tested=tv("上次测试：刚刚（真实）",11,MUTED);if("overview".equals(currentView)){content.removeAllViews();showOverview();}toast("真实测试完成");}});});}}

    private void runDemoTest(Button b){runRealTests();}
    private void showAddDialog(){LinearLayout form=col();EditText name=input("站点名称，例如：我的备用站");EditText url=input("接口地址，例如：https://api.example.com/v1");EditText key=input("API 密钥（只保存在本机）");EditText priceUrl=input("价格源 JSON URL（可选）");key.setInputType(129);form.addView(name);form.addView(url);form.addView(key);form.addView(priceUrl);new AlertDialog.Builder(this).setTitle("添加中转站").setView(form).setNegativeButton("取消",null).setPositiveButton("保存并继续",(d,w)->{String address=url.getText().toString().trim();if(address.isEmpty()){toast("接口地址不能为空");return;}siteStore.add(new RelaySite(name.getText().toString(),address,key.getText().toString(),priceUrl.getText().toString()));status.setText("●  " + (3 + siteStore.load().size()) + " 个站点");toast("站点已保存，点击全站测试");if("overview".equals(currentView)){content.removeAllViews();showOverview();}}).show();}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(TEXT);e.setTextSize(14);e.setSingleLine();e.setBackground(bg(Color.rgb(16,24,29),9,LINE));pad(e,10,0,10,0);margin(e,0,0,0,8);return e;}
    private void showManualPriceDialog(){LinearLayout f=col();EditText model=input("模型名，例如：gpt-5.6-terra"), in=input("输入价格 / 1M"), out=input("输出价格 / 1M"), mult=input("余额扣费倍率，例如：2.0");f.addView(model);f.addView(in);f.addView(out);f.addView(mult);new AlertDialog.Builder(this).setTitle("手动录入价格").setView(f).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{try{double i=Double.parseDouble(in.getText().toString().trim()),o=Double.parseDouble(out.getText().toString().trim()),m=Double.parseDouble(mult.getText().toString().trim());if(model.getText().toString().trim().isEmpty()||i<0||o<0||m<=0)throw new NumberFormatException();priceStore.upsert(new PriceStore.Price(model.getText().toString().trim(),i,o,m,"CNY","手动"));toast("价格和余额倍率已保存");}catch(NumberFormatException e){toast("请填写有效价格与倍率");}}).show();}
    private void fetchPrices(){List<RelaySite> sites=siteStore.load();if(sites.isEmpty()){toast("没有配置价格源 URL 的站点");return;}final int[] pending={0};for(RelaySite site:sites)if(site.priceUrl!=null&&!site.priceUrl.isEmpty())pending[0]++;if(pending[0]==0){toast("请在添加站点时填写价格源 JSON URL");return;}for(RelaySite site:sites){if(site.priceUrl==null||site.priceUrl.isEmpty())continue;PriceFetcher.fetchAsync(site.priceUrl, site.apiKey, result->runOnUiThread(()->{if(result.success){for(PriceStore.Price p:result.prices)priceStore.upsert(p);toast(result.message);}else toast(result.message+"，未覆盖旧价格");}));}}
    private void pickImage(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_PRICE_IMAGE);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_PRICE_IMAGE&&result==RESULT_OK&&data!=null){Uri uri=data.getData();try{InputImage image=InputImage.fromFilePath(this,uri);textRecognizer.process(image).addOnSuccessListener(text->handleOcr(text)).addOnFailureListener(e->toast("OCR失败，请补传清晰的局部截图"));}catch(Exception e){toast("图片无法读取，请重新选择");}}}
    private void handleOcr(Text text){pendingOcr=PriceOcrParser.parse(text.getText());String missing=pendingOcr.missing();if(!missing.isEmpty()){new AlertDialog.Builder(this).setTitle("截图信息不完整").setMessage("无法确认："+missing+"\n\n请补传包含这些字段的局部截图，或者改用手动录入。识别结果不会自动覆盖价格。").setNegativeButton("手动录入",(d,w)->showManualPriceDialog()).setPositiveButton("补传局部截图",(d,w)->pickImage()).show();return;}showOcrConfirm();}
    private void showOcrConfirm(){LinearLayout f=col();EditText model=input("模型名");model.setText(pendingOcr.model);EditText in=input("输入价格 / 1M");in.setText(pendingOcr.input);EditText out=input("输出价格 / 1M");out.setText(pendingOcr.output);EditText mult=input("余额扣费倍率");mult.setText(pendingOcr.multiplier);f.addView(model);f.addView(in);f.addView(out);f.addView(mult);new AlertDialog.Builder(this).setTitle("确认截图识别结果").setMessage("请核对后保存；OCR 只是候选，不会自动覆盖已有价格。").setView(f).setNegativeButton("取消",null).setPositiveButton("确认并保存",(d,w)->saveOcrPrice(model,in,out,mult)).show();}
    private void saveOcrPrice(EditText model,EditText in,EditText out,EditText mult){try{double input=Double.parseDouble(in.getText().toString().trim()),output=Double.parseDouble(out.getText().toString().trim()),factor=Double.parseDouble(mult.getText().toString().trim());if(model.getText().toString().trim().isEmpty()||input<0||output<0||factor<=0)throw new NumberFormatException();priceStore.upsert(new PriceStore.Price(model.getText().toString().trim(),input,output,factor,"CNY","截图识别·人工确认"));toast("截图价格已确认保存");}catch(NumberFormatException e){toast("字段无效，请重新确认或手动录入");}}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);textRecognizer.close();super.onDestroy();}
}

package com.mafucai.relayscope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Conservative JSON price-source adapter. Invalid or incomplete rows are rejected. */
public final class PriceFetcher {
    public interface Callback { void onResult(Result result); }
    public static final class Result {
        public final boolean success; public final String message; public final List<PriceStore.Price> prices;
        Result(boolean success,String message,List<PriceStore.Price> prices){this.success=success;this.message=message;this.prices=prices;}
    }
    private PriceFetcher() {}
    public static void fetchAsync(String sourceUrl, String apiKey, Callback callback) { new Thread(() -> callback.onResult(fetch(sourceUrl, apiKey)), "price-fetch").start(); }
    public static Result fetch(String sourceUrl) { return fetch(sourceUrl, ""); }
    public static Result fetch(String sourceUrl, String apiKey) {
        if(sourceUrl==null||sourceUrl.trim().isEmpty())return new Result(false,"没有设置价格源 URL",new ArrayList<>());
        HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(sourceUrl.trim()).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setRequestMethod("GET");c.setRequestProperty("Accept","application/json");if(apiKey!=null&&!apiKey.isEmpty())c.setRequestProperty("Authorization","Bearer "+apiKey);int code=c.getResponseCode();if(code<200||code>=300)return new Result(false,"价格源 HTTP "+code,new ArrayList<>());StringBuilder body=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null&&body.length()<500000)body.append(line);}return parse(body.toString());}catch(Exception e){return new Result(false,"价格源连接失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),new ArrayList<>());}finally{if(c!=null)c.disconnect();}
    }
    private static Result parse(String raw){try{JSONArray array;if(raw.trim().startsWith("["))array=new JSONArray(raw);else{JSONObject root=new JSONObject(raw);array=root.optJSONArray("data");if(array==null)array=root.optJSONArray("models");if(array==null)array=root.optJSONArray("prices");}if(array==null)return new Result(false,"价格源没有 data/models/prices 数组",new ArrayList<>());List<PriceStore.Price> out=new ArrayList<>();for(int i=0;i<array.length();i++){JSONObject o=array.optJSONObject(i);if(o==null)continue;String model=first(o,"model","model_name","id","name");double input=number(o,"input","input_price","prompt");double output=number(o,"output","output_price","completion");double mult=number(o,"multiplier","ratio","倍率");if(model.isEmpty()||input<0||output<0||mult<=0)continue;out.add(new PriceStore.Price(model,input,output,mult,"CNY","自动拉取"));}return out.isEmpty()?new Result(false,"未识别到完整价格行，保留旧数据",out):new Result(true,"已识别 "+out.size()+" 个模型",out);}catch(JSONException e){return new Result(false,"价格源不是有效 JSON",new ArrayList<>());}}
    private static String first(JSONObject o,String...keys){for(String k:keys){String v=o.optString(k,"").trim();if(!v.isEmpty())return v;}return "";}
    private static double number(JSONObject o,String...keys){for(String k:keys){Object v=o.opt(k);if(v instanceof Number)return ((Number)v).doubleValue();try{if(v!=null)return Double.parseDouble(String.valueOf(v).replace(",",""));}catch(Exception ignored){}}return -1;}
}

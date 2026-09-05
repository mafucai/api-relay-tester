package com.mafucai.relayscope;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class PriceStore {
    public static final class Price {
        public final String model, currency, source;
        public final double inputPerMillion, outputPerMillion, balanceMultiplier;
        public Price(String model, double input, double output, double multiplier, String currency, String source) {
            this.model=model; inputPerMillion=input; outputPerMillion=output; balanceMultiplier=multiplier; this.currency=currency; this.source=source;
        }
        JSONObject json() throws JSONException { JSONObject o=new JSONObject();o.put("model",model);o.put("input",inputPerMillion);o.put("output",outputPerMillion);o.put("multiplier",balanceMultiplier);o.put("currency",currency);o.put("source",source);return o; }
        static Price from(JSONObject o){return new Price(o.optString("model"),o.optDouble("input"),o.optDouble("output"),o.optDouble("multiplier",1),o.optString("currency","CNY"),o.optString("source","手动"));}
    }
    private final Context context;
    public PriceStore(Context context){this.context=context.getApplicationContext();}
    public List<Price> load(){List<Price> out=new ArrayList<>();String raw=context.getSharedPreferences("relayscope_prices",Context.MODE_PRIVATE).getString("prices","[]");try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++)out.add(Price.from(a.getJSONObject(i)));}catch(JSONException ignored){}return out;}
    public void upsert(Price price){List<Price> all=load();for(int i=0;i<all.size();i++)if(all.get(i).model.equalsIgnoreCase(price.model)){all.set(i,price);save(all);return;}all.add(price);save(all);}
    public Price find(String model){for(Price p:load())if(p.model.equalsIgnoreCase(model))return p;return null;}
    public JSONArray exportJson(){JSONArray a=new JSONArray();for(Price p:load()){try{a.put(p.json());}catch(JSONException e){throw new IllegalStateException(e);}}return a;}
    private void save(List<Price> prices){JSONArray a=new JSONArray();try{for(Price p:prices)a.put(p.json());}catch(JSONException e){throw new IllegalStateException(e);}context.getSharedPreferences("relayscope_prices",Context.MODE_PRIVATE).edit().putString("prices",a.toString()).apply();}
}

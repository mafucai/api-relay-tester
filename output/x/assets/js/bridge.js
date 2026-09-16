// ===== 原生桥接层：所有 window.onNative* 回调 + 桥接调用 =====
function copyText(t){if(window.AndroidRelay)AndroidRelay.copyText(t);else if(navigator.clipboard)navigator.clipboard.writeText(t).then(()=>toast('已复制到剪贴板'),()=>toast('复制失败'));else toast('浏览器原型不支持')}
// Java 侧 pushState 用 JSONObject.put("models", modelsJson(...))，把数组序列化成了 JSON 字符串。
// 前端多处按数组使用（.forEach/.filter/.map），必须在入口统一还原，否则 openModeModal 直接抛错。
function asModelArray(value){
  if(Array.isArray(value))return value;
  if(typeof value==='string'){
    const t=value.trim();
    if(!t)return [];
    if(t[0]==='['){try{const a=JSON.parse(t);return Array.isArray(a)?a:[]}catch(e){return []}}
    return [t];
  }
  return [];
}
window.onNativeState=(sites,prices,results,balances,fails,rates)=>{nativeSites=Array.isArray(sites)?sites:[];nativePrices=Array.isArray(prices)?prices:[];nativeResults={};(Array.isArray(results)?results:[]).forEach(r=>{if(r&&r.site){r.models=asModelArray(r.models);nativeResults[r.site]=r}});nativeBalances={};(Array.isArray(balances)?balances:[]).forEach(b=>{nativeBalances[b.site]=b.balance});nativeFails={};(Array.isArray(fails)?fails:[]).forEach(f=>{nativeFails[f.site]=f.streak});nativeRates={};(Array.isArray(rates)?rates:[]).forEach(r=>{nativeRates[r.site]=r.rate});renderAll()};

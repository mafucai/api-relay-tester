// ===== Android 桥接模拟器 =====
// 浏览器打开时（无 AndroidRelay）注入假桥接，让所有交互可在浏览器复现调试。
// APK 内真实 AndroidRelay 存在时此段自动跳过。
(function(){
  if (window.AndroidRelay) { console.log('[RelayScope] 真实 Android 桥接'); return; }
  const fakeSites=[];let running=false;
  const delay=fn=>setTimeout(fn,300);
  window.AndroidRelay={
    syncState(){ evaluate("window.onNativeState && window.onNativeState([],[],[],[],[],[])") },
    addSite(){}, removeSite(){}, updateSite(){},
    testSite(n){ fakeSites.push(n); delay(()=>window.onNativeTestStart&&onNativeTestStart(1)); },
    testAll(){ if(running)return;running=true;const n=fakeSites.length||1;const models=['gpt-4o','claude-sonnet-4','deepseek-v3','gpt-5.6-terra','qwen-max','gemini-pro','slow-model-x','kimi-k2'];let i=0;const site=fakeSites[0]||'演示站点';delay(()=>window.onNativeTestStart&&window.onNativeTestStart(n));const step=()=>{if(!running)return;const m=models[i];const st=(m==='slow-model-x')?'超时':(i%3===1?'限流':'可用 · '+(300+i*40)+' ms');i++;window.onNativeModelResult&&window.onNativeModelResult(site,m,st,i,models.length);if(i<models.length)setTimeout(step,700);else{running=false;setTimeout(()=>window.onNativeTestDone&&window.onNativeTestDone(false),400)}};setTimeout(step,600) },
    testGroup(){},
    stopTest(){ running=false; delay(()=>window.onNativeTestDone&&window.onNativeTestDone(true)) },
    fetchBalance(){}, fetchPrices(){}, pickPriceImage(){}, saveManualPrice(){},
    startInspection(){}, stopInspection(){}, copyText(t){console.log('[模拟复制]',t)},
    fetchModelList(scope){ const all=['gpt-4o','claude-sonnet-4','deepseek-v3','gpt-5.6-terra']; const list=scope?all.slice(0,2):all; delay(()=>window.onNativeModelList&&window.onNativeModelList(list)); },
    setTestMode(m,model){ console.log('[模拟] 测试模式:',m,model) },
    setTestScope(names){ console.log('[模拟] 测试范围:', names||'全部站点') },
  };
  function evaluate(s){ try{ new Function(s.replace(/window\./g,'window.'))(); }catch(e){ console.warn('[模拟器]',e.message) } }
  console.log('[RelayScope] 浏览器模拟桥接已注入');
})();

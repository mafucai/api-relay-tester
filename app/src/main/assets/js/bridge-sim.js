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
    testAll(){ if(running)return;running=true;delay(()=>window.onNativeTestStart&&window.onNativeTestStart(fakeSites.length||1)) },
    testGroup(){},
    stopTest(){ running=false; delay(()=>window.onNativeTestDone&&window.onNativeTestDone(true)) },
    fetchBalance(){}, fetchPrices(){}, pickPriceImage(){}, saveManualPrice(){},
    startInspection(){}, stopInspection(){}, copyText(t){console.log('[模拟复制]',t)},
    fetchModelList(){ delay(()=>window.onNativeModelList&&window.onNativeModelList(['gpt-4o','claude-sonnet-4','deepseek-v3'])) },
    setTestMode(m,model){ console.log('[模拟] 测试模式:',m,model) },
    setTestScope(names){ console.log('[模拟] 测试范围:', names||'全部站点') },
  };
  function evaluate(s){ try{ new Function(s.replace(/window\./g,'window.'))(); }catch(e){ console.warn('[模拟器]',e.message) } }
  console.log('[RelayScope] 浏览器模拟桥接已注入');
})();

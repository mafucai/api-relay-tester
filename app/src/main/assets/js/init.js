// ===== 初始化：模块加载自检 + 启动渲染 =====
(function(){
  ['inTok','outTok'].forEach(id=>{const el=document.querySelector('#'+id);if(el)el.oninput=()=>{}});
  renderOverview();
  // 模块就绪自检：任何缺失会在控制台明确指出，而不是莫名白屏
  const checks={state:typeof nativeSites!=='undefined',esc:typeof esc==='function',render:typeof renderOverview==='function',bridge:typeof window.onNativeState==='function',mode:typeof openModeModal==='function'};
  const bad=Object.entries(checks).filter(([k,v])=>!v).map(([k])=>k);
  console.log('[RelayScope] 模块自检:', bad.length?('缺失 '+bad.join(',')):'全部就绪');
})();

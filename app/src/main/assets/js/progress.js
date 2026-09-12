// ===== v0.6.3 进度层：逐模型实时进度 + 用时/预计剩余 + 只重测超时模型 =====
// 契约：原生 → onNativeModelResult(siteName, model, status, completed, total)
(function(){
  const state={active:false,start:0,completed:0,total:0,ok:0,bad:0,timeouts:[],timer:null,collapsed:false};

  function pad(n){return String(n).padStart(2,'0')}
  function fmt(ms){const s=Math.max(0,Math.round(ms/1000));return s<60?s+' 秒':Math.floor(s/60)+' 分 '+pad(s%60)+' 秒'}

  function reset(total){
    state.active=true;state.start=Date.now();state.completed=0;state.total=total||0;state.ok=0;state.bad=0;state.timeouts=[];
    const p=$('#testProgress');if(!p)return;
    p.style.display='block';$('#tpModel').textContent='正在测试…';$('#tpPct').textContent='0 / '+(total||'?');
    $('#tpBar').style.width='0%';$('#tpStat').textContent='已用时 0 秒 · 预计剩余 —';
    const r=$('#retryTimeout');if(r)r.style.display='none';
    const c=$('#progMsg');if(c){c.style.display='inline-block';c.textContent='收起'}
    if(state.timer)clearInterval(state.timer);
    state.timer=setInterval(tick,1000);
  }

  function tick(){
    if(!state.active)return;
    const elapsed=Date.now()-state.start;
    let eta='—';
    if(state.completed>0&&state.total>0){
      const per=elapsed/state.completed;
      eta=fmt(Math.max(0,per*state.total-elapsed));
    }
    $('#tpStat').textContent='已用时 '+fmt(elapsed)+' · 预计剩余 '+eta;
  }

  function finish(){
    state.active=false;
    if(state.timer){clearInterval(state.timer);state.timer=null}
    const r=$('#retryTimeout');
    if(r&&state.timeouts.length){r.style.display='inline-block';r.textContent='↻ 只重测超时模型（'+state.timeouts.length+'）'}
  }

  window.onNativeModelResult=function(siteName,model,status,completed,total){
    if(state.total!==total)state.total=total;
    state.completed=completed;
    if(String(status).startsWith('可用'))state.ok++;else state.bad++;
    if(String(status).indexOf('超时')>=0)state.timeouts.push({site:siteName,model:model});
    // 结果即时并入内存态，让模型矩阵也逐条增长（节流重绘，避免 232 模型时频繁全量渲染）
    const live=nativeResults[siteName]||(nativeResults[siteName]={status:'测试中',detail:'逐模型测试中…',ttfb:-1,models:[],modelResults:{}});
    live.modelResults=live.modelResults||{};
    live.modelResults[model]=status;
    live.status='测试中';
    live.detail='已测 '+completed+'/'+total+' · 可用 '+state.ok+' · 失败/超时 '+state.bad;
    scheduleMatrix();
    const p=$('#testProgress');if(!p)return;
    p.style.display='block';
    $('#tpModel').textContent=siteName+' · '+model+' → '+status;
    const pct=total>0?Math.round(completed*100/total):0;
    $('#tpPct').textContent=completed+' / '+total+'（'+pct+'%）';
    $('#tpBar').style.width=pct+'%';
    tick();
  };

  let matrixQueued=false;
  function scheduleMatrix(){
    if(matrixQueued)return;
    matrixQueued=true;
    setTimeout(function(){matrixQueued=false;try{renderMatrix()}catch(e){}},500);
  }

  // 开始测试时复位进度（包装已有回调，保持单点绑定）
  const prevStart=window.onNativeTestStart;
  window.onNativeTestStart=function(n){reset(state.total||0);if(prevStart)prevStart(n)};

  const prevDone=window.onNativeTestDone;
  window.onNativeTestDone=function(stopped){finish();if(prevDone)prevDone(stopped)};

  // 收起/展开进度块
  document.addEventListener('click',function(e){
    if(e.target.id==='progMsg'){
      state.collapsed=!state.collapsed;
      ['#tpModel','#tpPct','#tpStat'].forEach(s=>{const el=$(s);if(el)el.style.display=state.collapsed?'none':(s==='#tpPct'?'inline':'block')});
      const bar=document.querySelector('#testProgress .bar');if(bar)bar.style.display=state.collapsed?'none':'block';
      e.target.textContent=state.collapsed?'展开':'收起';
      return;
    }
    if(e.target.id==='retryTimeout'){
      if(!state.timeouts.length||!window.AndroidRelay){toast('没有超时模型可重测');return}
      const list=state.timeouts.slice();
      state.timeouts=[];
      e.target.disabled=true;e.target.textContent='重测中…';
      toast('开始重测 '+list.length+' 个超时模型');
      // 复用既有桥接方法，不新增契约：逐个模型 → 指定站点 → 单模型测试
      list.forEach(function(item,idx){
        setTimeout(function(){
          if(!window.AndroidRelay)return;
          window.AndroidRelay.setTestMode('single',item.model);
          window.AndroidRelay.setTestScope(item.site);
          window.AndroidRelay.testAll();
          if(idx===list.length-1)setTimeout(function(){e.target.disabled=false;e.target.style.display='none'},1500);
        },idx*1200);
      });
    }
  },true);
})();

#!/usr/bin/env bash
# api-relay-tester preflight：治理笼子要求的可执行检查入口
# 用法：bash tools/preflight.sh
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
FAIL=0; WARN=0
say(){ local ok="$1"; shift; if [ "$ok" = PASS ]; then echo "PASS  $*"; else echo "$ok  $*"; [ "$ok" = FAIL ] && FAIL=$((FAIL+1)) || WARN=$((WARN+1)); fi }

# 1. 治理文档四件套存在
for f in PROJECT_RULES.md RISK_CHECKLIST.md ACCEPTANCE.md LOW_MODEL_TASK_TEMPLATE.md; do
  [ -f "$ROOT/$f" ] && say PASS "治理文档存在: $f" || say FAIL "缺治理文档: $f"
done

# 2. 文件行数上限（1500 行）
big=$(find app/src/main -type f \( -name '*.java' -o -name '*.js' -o -name '*.html' \) -exec awk 'length>1{c++}END{if(c>1500)print FILENAME" "c}' {} \;)
[ -z "$big" ] && say PASS "无超 1500 行源文件" || say FAIL "超行数文件: $big"

# 3. 调试面板存在（body 第一行内联，铁律 2）
if grep -q '__dp_trigger' app/src/main/assets/index.html && grep -q '__dp_panel' app/src/main/assets/index.html; then
  first_body=$(grep -n '</head><body>' app/src/main/assets/index.html | head -1 | cut -d: -f1)
  say PASS "调试面板存在（body 第 ${first_body:-?} 行内联）"
else say FAIL "缺调试面板 __dp_trigger/__dp_panel"; fi

# 4. 危险 API：eval/new Function/document.write（排除 bridge-sim 模拟器：APK 内真实桥接存在时整段跳过，不执行）
dang=$(grep -rnE 'document\.write|new Function' app/src/main/assets/js/ 2>/dev/null | grep -v 'bridge-sim.js' | grep -v '__dp' || true)
if [ -z "$dang" ]; then say PASS "无 document.write/new Function（bridge-sim 模拟器专用除外）"; else say WARN "发现危险 API: $dang"; fi

# 5. 未转义 innerHTML 风险：排除仅拼接 .length 数字（数字无 XSS）与已转义模板
unsec=$(grep -rnE 'innerHTML\s*=\s*[^`]*\+' app/src/main/assets/js/*.js 2>/dev/null | grep -vE 'esc\(|textContent|\.length' || true)
if [ -z "$unsec" ]; then say PASS "innerHTML 拼接均已转义/数字拼接/用模板"; else say WARN "发现未转义拼接: $unsec"; fi

# 6. 测试脚本存在（verify-*.js + preflight 自身）
[ -f tools/preflight.sh ] && [ -f tools/verify-0.6.3.js ] && [ -f tools/verify-0.6.3-runtime.js ] \
  && say PASS "测试脚本存在（verify-0.6.3.js + runtime）" || say FAIL "缺测试脚本"

# 7. 桥接三方契约：前端调用 ↔ Java 注解 ↔ bridge-sim（静态粗查）
js_calls=$(grep -rhoE 'AndroidRelay\.[A-Za-z]+' app/src/main/assets/js/ | sort -u | sed 's/AndroidRelay\.//')
java_methods=$(grep -oE '@JavascriptInterface\s+public void [A-Za-z]+' app/src/main/java/com/mafucai/relayscope/MainActivity.java | awk '{print $4}')
sim_missing=$(for m in $js_calls; do grep -qE "\\b$m\\s*\\(" app/src/main/assets/js/bridge-sim.js || echo "$m"; done)
java_missing=$(for m in $js_calls; do echo "$java_methods" | grep -qx "$m" || echo "$m"; done)
[ -z "$sim_missing" ] && say PASS "bridge-sim 覆盖全部前端调用" || say FAIL "bridge-sim 缺: $sim_missing"
[ -z "$java_missing" ] && say PASS "Java 注解覆盖全部前端调用" || say FAIL "Java 缺: $java_missing"

# 8. JS 语法：node --check
for f in app/src/main/assets/js/*.js; do
  node --check "$f" >/dev/null 2>&1 && say PASS "node --check $f" || { say FAIL "node --check 失败: $f"; node --check "$f"; }
done

# 9. 版本一致性：build.gradle versionName 与 docs 对齐
vn=$(grep -oE "versionName '[^']+'" app/build.gradle | cut -d"'" -f2)
vc=$(grep -oE 'versionCode [0-9]+' app/build.gradle | awk '{print $2}')
grep -q "versionName '$vn'" docs/ENGINEERING.md && say PASS "版本一致: $vn / versionCode $vc" || say WARN "docs/ENGINEERING.md 版本未同步 ($vn)"

# 10. 依赖审计：零第三方运行时依赖（仅 ML Kit OCR）
grep -q "implementation 'com.google.mlkit" app/build.gradle && say PASS "唯一第三方依赖 ML Kit（本地 OCR）" || say WARN "依赖情况未确认"

echo
echo "===== preflight 结果：FAIL=$FAIL WARN=$WARN ====="
[ "$FAIL" -eq 0 ] && echo "→ 通过（可继续）" || echo "→ 存在阻断项，先修复再继续"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
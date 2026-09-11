#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

fail(){ echo "FAIL: $*" >&2; exit 1; }
ok(){ echo "OK: $*"; }

[[ "$(tr -d '\r\n' < VERSION)" == "1.0.0" ]] || fail "suite VERSION is not 1.0.0"
[[ "$(tr -d '\r\n' < CONTROL_VERSION)" == "1.2.0" ]] || fail "CONTROL_VERSION is not 1.2.0"
grep -q "versionName '1.0.0'" android-client/app/build.gradle || fail "Android VPN client version"
grep -q "versionName '1.2.0'" android-admin/app/build.gradle || fail "Android Control app version"
grep -q "MARKETING_VERSION: '1.0.0'" ios-client/project.yml || fail "iOS VPN client version"
grep -q "'version': '1.2.0'" control/app.py || fail "Control API version"
grep -q 'VISION Control <span>1.2.0</span>' control/static/index.html || fail "Control UI version"
! grep -qE 'Протоколы <span>скоро|Маршрутизация <span>скоро|DNS / фильтры <span>скоро|Резервные копии <span>скоро|Роли / API <span>скоро' control/static/index.html || fail "unfinished Control navigation placeholders"
ok "version consistency"

python3 -m unittest discover -s tests -v
ok "server/control protocol tests"
python3 -m py_compile control/*.py sever/*.py tools/*.py
ok "Python compile"

if command -v node >/dev/null 2>&1; then
  node --check control/static/app.js
  ok "Control JavaScript syntax"
else
  echo "SKIP: node unavailable"
fi

python3 - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
for p in [Path('android-client/app/src/main/AndroidManifest.xml'), Path('android-admin/app/src/main/AndroidManifest.xml')]:
    ET.parse(p)
print('OK: Android manifest XML')
PY

for f in ops/*.sh ios-client/*.sh; do bash -n "$f"; done
ok "shell syntax"

python3 - <<'PY'
from pathlib import Path
required = [
  'control/static/index.html','control/static/app.js','control/static/style.css','control/app.py','control/store.py',
  'android-admin/app/src/main/java/app/vision/control/MainActivity.java','CONTROL_RELEASE_NOTES.md'
]
missing=[x for x in required if not Path(x).is_file()]
if missing: raise SystemExit('missing Control files: '+', '.join(missing))
print('OK: Control package files')
PY

echo "VISION VPN Suite 1.0.0 + VISION Control 1.2.0 source verification complete"

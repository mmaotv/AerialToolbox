#!/usr/bin/env bash
# 用 curl 覆盖 GitHub Release 资产（版本号不变）。需要 GITHUB_TOKEN 环境变量。
set -u
TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ]; then echo "ERROR: 需要 GITHUB_TOKEN"; exit 2; fi
OWNER=mmaotv
REPO=AerialToolbox
API="https://api.github.com"
UPLOAD="https://uploads.github.com"

# tag|本地apk|上传名|本地sha|上传sha名
PLAN=(
  "cyt-v3.0.0|dist/CleanYourTV-3.0.0.apk|CleanYourTV-3.0.0.apk|dist/CleanYourTV-3.0.0.apk.sha256|CleanYourTV-3.0.0.apk.sha256"
  "v1.3.4|dist/AerialToolbox-1.3.4.apk|AerialToolbox-1.3.4.apk|dist/AerialToolbox-1.3.4.apk.sha256|AerialToolbox-1.3.4.apk.sha256"
  "phone-v1.0.2|dist/AerialToolbox-Phone-1.0.2.apk|AerialToolbox-Phone-1.0.2.apk|dist/AerialToolbox-Phone-1.0.2.apk.sha256|AerialToolbox-Phone-1.0.2.apk.sha256"
)

auth_hdr=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "User-Agent: release-replace")

for entry in "${PLAN[@]}"; do
  IFS='|' read -r tag apk apkname shafile shaname <<< "$entry"
  echo "=== Release $tag ==="
  json=$(curl -s --max-time 30 "${auth_hdr[@]}" "$API/repos/$OWNER/$REPO/releases/tags/$tag")
  rid=$(printf '%s' "$json" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('id',''))" 2>/dev/null)
  if [ -z "$rid" ]; then echo "  ! 取不到 release id，跳过"; continue; fi
  echo "  release id=$rid"
  # 删除同名旧资产
  printf '%s' "$json" | python3 -c "
import sys,json
d=json.load(sys.stdin)
names=set(['$apkname','$shaname'])
for a in d.get('assets',[]):
    if a['name'] in names:
        print(a['id'], a['name'])
" > /tmp/del_assets.txt
  while read -r aid aname; do
    [ -z "$aid" ] && continue
    code=$(curl -s --max-time 30 -o /dev/null -w "%{http_code}" -X DELETE "${auth_hdr[@]}" "$API/repos/$OWNER/$REPO/releases/assets/$aid")
    echo "  - 删除旧资产 $aname -> $code"
  done < /tmp/del_assets.txt
  # 上传新资产
  for pair in "$apk|$apkname|application/vnd.android.package-archive" "$shafile|$shaname|text/plain"; do
    IFS='|' read -r lp up ct <<< "$pair"
    [ -f "$lp" ] || { echo "  ! 本地文件不存在: $lp"; continue; }
    # 上传前 sleep 一下，避免删除后立刻上传同名被 GitHub 短暂占用
    sleep 1
    url="$UPLOAD/repos/$OWNER/$REPO/releases/$rid/assets?name=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$up")"
    out=$(curl -s --max-time 120 -w "\nHTTP:%{http_code}" -H "Authorization: Bearer $TOKEN" -H "Content-Type: $ct" --data-binary "@$lp" "$url")
    status=$(printf '%s' "$out" | tail -1)
    echo "  + 上传 $up $status"
  done
done
echo "完成。"

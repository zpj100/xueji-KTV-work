#!/usr/bin/env bash
#
# 同步上游曲库到本仓库（一条命令）
#
#   bash tools/sync_catalog.sh            # 拉上游最新 db → 更新本地 assets → 上传 Gitee Release catalog-latest
#   bash tools/sync_catalog.sh --no-local # 只更新 Release，不改动本地 app/src/main/assets/igeba_catalog.db
#   bash tools/sync_catalog.sh --dry-run  # 只检查上游版本，不下载不上传
#   bash tools/sync_catalog.sh --force    # 即使本地已一致也重新下载并上传（修复损坏/强制刷新）
#
# 通道说明：
#   上游 raw 对 >20MB 文件要求登录（403），故改用 Gitee git blobs API（token 可读，返回 base64）。
#   本仓库 Release 附件（catalog-latest）匿名可下载，供 App 内「曲库更新」使用。
#
# 依赖：curl、python（PATH 中可用）；GITEE_TOKEN（自动从 xueji-env.sh 读取）
#
set -euo pipefail

# ---------- 配置 ----------
UP_OWNER=yangyachao-X
UP_REPO=maidong-ktv
UP_BRANCH=feature/igeba-media
DB_REPO_PATH=app/src/main/assets/igeba_catalog.db

MY_REPO=zpj100/xueji-KTV-work
MY_TAG=catalog-latest
ASSET_NAME=igeba_catalog.db

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.catalog_sync"
LOCAL_DB="$ROOT/app/src/main/assets/igeba_catalog.db"

# ---------- 参数 ----------
UPDATE_LOCAL=1
DRY_RUN=0
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --no-local) UPDATE_LOCAL=0 ;;
    --dry-run)  DRY_RUN=1 ;;
    --force)    FORCE=1 ;;
    -h|--help)  sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（可用 --no-local / --dry-run / -h）"; exit 2 ;;
  esac
done

# ---------- Token ----------
if [ -z "${GITEE_TOKEN:-}" ]; then
  for f in "$HOME/.workbuddy/credentials/xueji-env.sh" \
           "/e/Users/Administrator/.workbuddy/credentials/xueji-env.sh" \
           "E:/Users/Administrator/.workbuddy/credentials/xueji-env.sh"; do
    if [ -f "$f" ]; then . "$f" >/dev/null 2>&1 && break; fi
  done
fi
if [ -z "${GITEE_TOKEN:-}" ]; then
  echo "❌ 缺少 GITEE_TOKEN：请设置环境变量，或提供 xueji-env.sh"
  exit 1
fi

API="https://gitee.com/api/v5/repos"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

json_get() { # json_get <file> <key>
  python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8')).get(sys.argv[2],''))" "$1" "$2"
}

git_blob_sha() { # git_blob_sha <file>
  python - "$1" <<'PY'
import hashlib, os, sys
p = sys.argv[1]
h = hashlib.sha1()
h.update(b"blob %d\0" % os.path.getsize(p))
with open(p, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 16), b""):
        h.update(chunk)
print(h.hexdigest())
PY
}

# ---------- 1. 查上游版本 ----------
echo "==> 查询上游曲库版本（$UP_OWNER/$UP_REPO@$UP_BRANCH）"
curl -sS -m 40 -o "$WORK/meta.json" \
  "$API/$UP_OWNER/$UP_REPO/contents/$DB_REPO_PATH?ref=$UP_BRANCH&access_token=$GITEE_TOKEN"
UP_SHA=$(json_get "$WORK/meta.json" sha)
UP_SIZE=$(json_get "$WORK/meta.json" size)
if [ -z "$UP_SHA" ]; then
  echo "❌ 无法获取上游 sha，返回："; head -c 400 "$WORK/meta.json"; echo; exit 1
fi
echo "    上游 sha=$UP_SHA  size=$UP_SIZE"

# ---------- 2. 比对本地 ----------
LOCAL_SHA=""
if [ -f "$LOCAL_DB" ]; then LOCAL_SHA=$(git_blob_sha "$LOCAL_DB"); fi
echo "    本地 sha=${LOCAL_SHA:-（无本地库）}"

NEED_DOWNLOAD=1
if [ "$LOCAL_SHA" = "$UP_SHA" ]; then
  NEED_DOWNLOAD=0
  echo "    ✅ 本地已与上游一致"
fi
if [ "$FORCE" = "1" ]; then
  NEED_DOWNLOAD=1
  echo "    ⚠️  --force：强制重新下载"
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "==> dry-run：仅检查，未下载/未上传"
  [ "$NEED_DOWNLOAD" = "1" ] && echo "    需要更新（本地与上游不一致）"
  exit 0
fi

# ---------- 3. 下载（git blobs API） ----------
NEW_DB="$WORK/new.db"
if [ "$NEED_DOWNLOAD" = "1" ]; then
  echo "==> 下载上游曲库（git blobs API）"
  curl -sS -m 300 -o "$WORK/blob.json" \
    "$API/$UP_OWNER/$UP_REPO/git/blobs/$UP_SHA?access_token=$GITEE_TOKEN"
  python - "$WORK/blob.json" "$NEW_DB" "$UP_SHA" <<'PY'
import base64, hashlib, json, sys
blob, out, expect = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(blob, encoding="utf-8"))
raw = base64.b64decode(data["content"])
got = hashlib.sha1(b"blob %d\0" % len(raw) + raw).hexdigest()
if got != expect:
    print(f"❌ sha 校验失败: {got} != {expect}"); sys.exit(1)
open(out, "wb").write(raw)
print(f"    下载 {len(raw):,} 字节，校验通过")
PY

  if [ "$UPDATE_LOCAL" = "1" ]; then
    cp "$NEW_DB" "$LOCAL_DB"
    echo "==> 已更新本地 $LOCAL_DB（记得提交）"
  else
    echo "==> 已跳过本地更新（--no-local）"
  fi
else
  echo "==> 无需下载"
fi

# ---------- 4. 定位 Release ----------
echo "==> 定位 Release $MY_TAG"
curl -sS -m 40 -o "$WORK/release.json" \
  "$API/$MY_REPO/releases/tags/$MY_TAG?access_token=$GITEE_TOKEN"
RID=$(json_get "$WORK/release.json" id)
if [ -z "$RID" ]; then
  echo "❌ 未找到 Release $MY_TAG：先创建一次（tag_name=$MY_TAG）"; head -c 400 "$WORK/release.json"; echo; exit 1
fi
echo "    release id=$RID"

# ---------- 5. 删除旧附件（同名） ----------
# 注意：release 详情里的 assets 只有 name/url，没有 id；附件 id 需从 attach_files 接口取。
curl -sS -m 40 -o "$WORK/attach.json" \
  "$API/$MY_REPO/releases/$RID/attach_files?access_token=$GITEE_TOKEN"
OLD_IDS=$(python - "$WORK/attach.json" "$ASSET_NAME" <<'PYATS'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    d = []
if isinstance(d, dict):
    d = d.get("assets") or []
print(" ".join(str(a["id"]) for a in d if a.get("name") == sys.argv[2] and a.get("id")))
PYATS
)
# 无变化且远端大小一致时跳过上传，避免每次重复传 21MB
REMOTE_SIZE=$(python - "$WORK/attach.json" "$ASSET_NAME" <<'PYSZ'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    d = []
if isinstance(d, dict):
    d = d.get("assets") or []
match = [a for a in d if a.get("name") == sys.argv[2]]
print(match[0].get("size", 0) if match else 0)
PYSZ
)
if [ "$NEED_DOWNLOAD" = "0" ] && [ "$REMOTE_SIZE" = "$UP_SIZE" ]; then
  echo "    ✅ 远端 catalog-latest 已是上游版本（$REMOTE_SIZE 字节），无需上传"
  echo
  echo "🎉 已是最新，无需操作（sha=$UP_SHA）"
  exit 0
fi

if [ -n "${OLD_IDS:-}" ]; then
  for AID in $OLD_IDS; do
    code=$(curl -sS -m 60 -o /dev/null -w "%{http_code}" -X DELETE \
      -H "Authorization: token $GITEE_TOKEN" \
      "$API/$MY_REPO/releases/$RID/attach_files/$AID" || true)
    echo "    删除旧附件 id=$AID (HTTP $code)"
  done
else
  echo "    无同名旧附件"
fi

# ---------- 6. 上传新附件 ----------
SRC_DB="$LOCAL_DB"; [ -f "$NEW_DB" ] && SRC_DB="$NEW_DB"
# 复制成目标文件名再上传：curl 会以文件名作为附件名，避免使用 ;filename= 参数（Windows curl 不兼容）。
UPLOAD_STAGE="$WORK/$ASSET_NAME"
cp -f "$SRC_DB" "$UPLOAD_STAGE"
echo "==> 上传 $ASSET_NAME（$(python -c "import os,sys;print(f'{os.path.getsize(sys.argv[1]):,}')" "$UPLOAD_STAGE") 字节）"
UPLOAD_OK=0
for attempt in 1 2 3; do
  curl -sS -m 900 -X POST -H "Authorization: token $GITEE_TOKEN" \
    -F "file=@$UPLOAD_STAGE" \
    "$API/$MY_REPO/releases/$RID/attach_files" -o "$WORK/upload.json" || true
  URL=$(json_get "$WORK/upload.json" browser_download_url || true)
  if [ -n "$URL" ]; then echo "    ✅ $URL"; UPLOAD_OK=1; break; fi
  echo "    上传失败（第 $attempt 次），重试…"; sleep 3
done
if [ "$UPLOAD_OK" = "0" ]; then
  echo "❌ 上传失败，Release 内可能已无曲库附件，请重跑本脚本。返回："
  head -c 400 "$WORK/upload.json" || true; echo; exit 1
fi

# ---------- 7. 验证远端 ----------
echo "==> 验证远端附件"
VERIFY_URL="https://gitee.com/$MY_REPO/releases/download/$MY_TAG/$ASSET_NAME"
curl -sSL -m 300 -o "$WORK/verify.db" "$VERIFY_URL"
GOT_SHA=$(git_blob_sha "$WORK/verify.db")
echo "    远端 sha=$GOT_SHA"
if [ "$GOT_SHA" = "$UP_SHA" ]; then
  echo "    ✅ 与上游一致"
else
  echo "    ❌ 与上游不一致（期望 $UP_SHA）"; exit 1
fi

echo
echo "🎉 完成：catalog-latest 已同步上游最新曲库（sha=$UP_SHA）"
if [ "$UPDATE_LOCAL" = "1" ] && [ "$NEED_DOWNLOAD" = "1" ]; then
  echo "   提示：本地 assets 已更新，请 git add/commit 后重新打包 APK"
fi
exit 0

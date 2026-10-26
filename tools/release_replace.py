#!/usr/bin/env python3
"""替换 GitHub Release 上的 APK + sha256 资产（版本号不变，覆盖上传）。

用法：python3 tools/release_replace.py
依赖环境变量 GITHUB_TOKEN，或直接改下面的 TOKEN。
"""
import os
import sys
import json
import urllib.request
import urllib.error

TOKEN = os.environ.get("GITHUB_TOKEN")
if not TOKEN:
    sys.stderr.write("ERROR: 需要环境变量 GITHUB_TOKEN（不要硬编码进仓库，会被密钥扫描拦截）\n")
    sys.exit(2)
OWNER = "mmaotv"
REPO = "AerialToolbox"
API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"

# (release_tag, [(local_path, upload_name, content_type), ...])
PLAN = [
    ("cyt-v3.0.0", [
        ("dist/CleanYourTV-3.0.0.apk", "CleanYourTV-3.0.0.apk",
         "application/vnd.android.package-archive"),
        ("dist/CleanYourTV-3.0.0.apk.sha256", "CleanYourTV-3.0.0.apk.sha256",
         "text/plain"),
    ]),
    ("v1.3.4", [
        ("dist/AerialToolbox-1.3.4.apk", "AerialToolbox-1.3.4.apk",
         "application/vnd.android.package-archive"),
        ("dist/AerialToolbox-1.3.4.apk.sha256", "AerialToolbox-1.3.4.apk.sha256",
         "text/plain"),
    ]),
    ("phone-v1.0.2", [
        ("dist/AerialToolbox-Phone-1.0.2.apk", "AerialToolbox-Phone-1.0.2.apk",
         "application/vnd.android.package-archive"),
        ("dist/AerialToolbox-Phone-1.0.2.apk.sha256", "AerialToolbox-Phone-1.0.2.apk.sha256",
         "text/plain"),
    ]),
]


def api(method, url, data=None, headers=None):
    h = {"Authorization": "Bearer " + TOKEN,
         "Accept": "application/vnd.github+json",
         "User-Agent": "release-replace"}
    if headers:
        h.update(headers)
    if data is not None and not isinstance(data, bytes):
        data = data.encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read()
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def get_release_id(tag):
    status, body = api("GET", f"{API}/repos/{OWNER}/{REPO}/releases/tags/{tag}")
    if status != 200:
        raise RuntimeError(f"获取 release {tag} 失败: {status} {body[:200]}")
    return json.loads(body)["id"]


def delete_asset(asset_id, name):
    status, body = api("DELETE", f"{API}/repos/{OWNER}/{REPO}/releases/assets/{asset_id}")
    if status not in (200, 204):
        print(f"  ! 删除旧资产 {name} 失败: {status} {body[:120]}")
    else:
        print(f"  - 删除旧资产 {name}")


def upload_asset(release_id, local_path, upload_name, content_type):
    with open(local_path, "rb") as f:
        data = f.read()
    url = (f"{UPLOAD}/repos/{OWNER}/{REPO}/releases/{release_id}/assets"
           f"?name={urllib.parse.quote(upload_name)}")
    status, body = api("POST", url, data=data,
                       headers={"Content-Type": content_type})
    if status not in (200, 201):
        raise RuntimeError(f"上传 {upload_name} 失败: {status} {body[:200]}")
    info = json.loads(body)
    print(f"  + 上传 {upload_name} ({info.get('size')} bytes) -> {info.get('browser_download_url')}")


def main():
    import urllib.parse
    for tag, files in PLAN:
        print(f"\n=== Release {tag} ===")
        rid = get_release_id(tag)
        # 先删除同名旧资产
        status, body = api("GET", f"{API}/repos/{OWNER}/{REPO}/releases/{rid}")
        if status == 200:
            for a in json.loads(body).get("assets", []):
                if any(a["name"] == fn for _, fn, _ in files):
                    delete_asset(a["id"], a["name"])
        # 再上传新资产
        for local_path, upload_name, ct in files:
            if not os.path.isfile(local_path):
                print(f"  ! 本地文件不存在: {local_path}")
                continue
            upload_asset(rid, local_path, upload_name, ct)
    print("\n全部完成。")


if __name__ == "__main__":
    main()

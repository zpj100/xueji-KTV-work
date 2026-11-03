# 上游项目记录

> 本文档记录本项目的**上游项目（源项目 / 更新项目）**，用于跟踪功能演进、曲库数据与问题修复。

## 上游项目（源项目 / 更新项目）

| 项 | 内容 |
|---|---|
| 项目名 | maidong-ktv（麦动 KTV） |
| 地址 | https://gitee.com/yangyachao-X/maidong-ktv |
| 备注 | **上游项目 / 源项目 / 更新项目** |
| 默认分支 | `feature/igeba-media` |
| 说明 | 麦动 KTV Android 项目源码与离线曲库数据库分片 |

本项目（学记KTV）由该上游派生，仅做品牌重命名与本地化定制（见根目录 `README.md` 的「本仓库定制说明」）。

## 跟踪方式

- **代码更新**：以上游 `feature/igeba-media` 分支的提交为准。
- **版本发布**：以上游 Release 标签为准（如 `2.1.7`）。
- **曲库更新**：上游内置 `app/src/main/assets/igeba_catalog.db`（离线曲库分片）。

## 上游快照（截至 2026-11-03 核查）

| 项 | 状态 |
|---|---|
| 最新 Release | **2.1.7**（2026-11-01，`fix: synchronize dual-track seeking`） |
| 最近提交 | `ed145c12` 2026-11-01 双音轨 seek 同步（2.1.7） |
| 上一版 | `ef342e67` 2026-10-29 i歌霸账号登录（2.1.6） |
| 内置曲库 | 81,801 首歌曲 / 4,807 位歌星 / 63 分类歌单 / 7 排行榜（与本仓库一致） |
| 曲库仓库 | 无独立曲库仓库，曲库随源码以 `igeba_catalog.db` 内置分发 |

## i歌霸在线曲库接口状态（2026-11-03 实测）

| 接口 | 地址 | 状态 |
|---|---|---|
| 曲库目录 `GET_SONGS` | `http://app.ige8.net/cloud.php` | ❌ 已停止服务：返回 `{"Result":-1,"Description":"请使用鸿蒙客户端."}`，VER 6~12 全部拒绝 |
| 歌曲地址 `GET_SONGADDR` | `http://app.ige8.net/telnet.php` | ✅ 正常（返回 `Rights.Code=1` 与播放地址） |
| 账号注册 | `http://gc.amanktv.cn/usr.php` | ✅ 可达 |

**结论**：i歌霸仅保留了「取歌曲播放地址」接口，关闭了「在线曲库目录」接口。上游 2.1.7 仍使用 `VER=6`，因此上游同样无法在线更新曲库，需依赖 APK 内置曲库。

## 本项目的曲库更新策略（v2.3.6 起）

自 v2.3.6 起，「曲库更新」不再调用 i歌霸接口，改为**跟随上游 Gitee 仓库**：

1. **版本比对**：调用 Gitee contents API 读取上游 `app/src/main/assets/igeba_catalog.db` 的 Git blob SHA-1：
   `https://gitee.com/api/v5/repos/yangyachao-X/maidong-ktv/contents/app/src/main/assets/igeba_catalog.db?ref=feature/igeba-media`
2. **本地版本**：优先读取应用目录下的 `igeba_catalog.sha`；无记录时现场计算本地 db 的 Git blob SHA-1（算法与 Gitee 一致）。
3. **按需下载**：SHA 不同才下载，通道依次为
   - 上游 raw：`https://gitee.com/yangyachao-X/maidong-ktv/raw/feature/igeba-media/app/src/main/assets/igeba_catalog.db`（Gitee 对 >20MB 文件要求登录，通常 403）
   - 本仓库 Release 附件：`https://gitee.com/zpj100/xueji-KTV-work/releases/download/catalog-latest/igeba_catalog.db`（匿名可下载，随上游同步）
4. **完整性校验**：下载完成后校验 SHA 与上游一致，才替换本地曲库；失败保留原库。

## 一条命令同步上游曲库

```bash
bash tools/sync_catalog.sh            # 拉上游最新 db → 更新本地 assets → 上传 catalog-latest
bash tools/sync_catalog.sh --no-local # 只更新 Release，不改本地 assets
bash tools/sync_catalog.sh --dry-run  # 只查上游版本，不下载不上传
bash tools/sync_catalog.sh --force    # 本地已一致也强制重新下载并上传
```

脚本做的事：查上游 sha → 与本地比对 → 用 **git blobs API**（token 可读，绕开 raw 的大文件登录限制）取回 db → 校验 sha → 更新本地 `app/src/main/assets/igeba_catalog.db` → 删除 `catalog-latest` 旧附件 → 上传新附件（正确文件名）→ 远端下载回验 sha。

- 依赖：`curl`、`python`；`GITEE_TOKEN` 自动从 `xueji_env.sh` 读取。
- 无变化时会自动跳过上传（比对附件大小），日常执行秒级完成。
- 遇到 `catalog-latest` 不存在时，先手动创建一个该 tag 的 Release。

> 维护提示：上游更新曲库后，需把新的 `igeba_catalog.db` 重新上传到本仓库的 `catalog-latest` Release 附件，App 才能下载到新版。

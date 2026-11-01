# 工账

给妈妈用的记账 App：每天在手机上记 **出勤（人员、车辆、日工资）、加油、吨数、维修费、其他费用、加班费**，随时选一段日期看一共开出了多少钱、每个项目多少、每个人该发多少工资，并能导出 Excel。

- **安卓 App**（Kotlin + Compose）：`android/`，主页就是当天的记账页，第一屏一个按钮"全部出勤（工资不变）"。
- **后端**（FastAPI + SQLite，一个容器）：`server/`，部署在 NAS 上，数据只有一个文件，每晚自动备份。
- **一键更新**：App 启动时问服务器有没有新版，有就弹"一键更新"，下载后直接安装。第一次安装靠服务器首页的下载页。

## 记账规则（来自妈妈的工资明细表）

- 月薪的日工资 = 月薪 ÷ 30；日薪直接用。每天的出勤记录里会存下当天的日工资，**以后改工资不会改动已经记过的天**。
- 出勤：全天 / 半天 / 缺勤。"全部出勤"一键把所有在岗的人和车记上；"和上一次记的一样"照抄上一次（包括缺勤）。
- 货车可以设"超过几车算加班、每多一车加多少元"（表里是 2 车、70 元），记吨数时按车次自动算加班费。
- 汇总任意日期范围：人工、加班费、油费（升数和金额）、维修、其他、每辆车、每个人应发、每天开支，并可导出 Excel。

## 部署到 NAS

```bash
cp .env.example .env      # 编辑：GZ_SECRET 填长随机串，GZ_USERS 填账号密码
docker compose up -d --build
curl http://localhost:8100/health
```

`./data` 里：`gongzhang.db` 数据库、`backups/` 每晚备份（留 14 份）、`updates/` 放 App 安装包。

**走公网**：路由器把一个端口转发到 NAS 的 8100（App 默认地址在 `android/local.properties`）。强烈建议在前面加 HTTPS（群晖/极空间的反向代理或 Caddy），否则密码是明文传输；用了 HTTPS 就把 App 里的服务器地址改成 `https://…`。App 登录页点"服务器地址"可改；也可以在家时自动走局域网地址（`gongzhang.lanServer`）。

## 把旧的工资表导进来

```bash
python tools/import_excel.py 工资明细表.xlsx http://NAS地址:8100 妈妈
```

读最新一张"人工费用"表里的人员、车辆和工资，已有的名字会跳过。也可以直接在 App 的"人员车辆"页手动加。

## 发布 App / 让妈妈装上

1. 改 `android/app/build.gradle.kts` 里的 `versionCode`（每次 +1）和 `versionName`。
2. 构建并发布：`python tools/release.py "改了什么;还改了什么" --out <NAS 上挂载成 /data/updates 的文件夹>`
3. **第一次**：让妈妈在手机**浏览器**（不是微信里）打开 `http://你的服务器地址:8100/`，点"下载安装"，按提示允许安装。
4. 以后你再运行第 2 步，妈妈下次打开 App 就会弹出"一键更新"。

安装包用本机的 debug 签名，**永远在同一台电脑上构建发布**（或把 `~/.android/debug.keystore` 一起备份），换了签名手机会拒绝更新。

## 开发

```bash
cd server && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt pytest httpx
GZ_SECRET=dev GZ_USERS=mom:mama1234 .venv/Scripts/python -m uvicorn app.main:app --port 8100
.venv/Scripts/python -m pytest -q tests
cd ../android && ./gradlew assembleDebug      # 模拟器里服务器地址填 http://10.0.2.2:8100/
```

## 还没做

- 断网时先存在手机里、联网后补传（现在没网记不了账）。
- 记录"工资已发/未发"、预支。
- 历史数据（8 月的几张表）没有导入，只导了人员和工资。

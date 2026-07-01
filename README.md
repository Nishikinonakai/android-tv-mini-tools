# Android TV Mini Tools

在国产 4K Android TV 上折腾出来的两个自制小工具。都是 Android 11、KGZN 系 ROM（国产电视厂常见前缀）上写的，同类机型直接可用，别的机型改几行就能跑。

## `mini-home/` — Mini Home

极简 Launcher，替代厂商预装的那种「首屏全是自家频道推广位 + 广告」的桌面。UI 参考 macOS：全部应用宫格 + 底部三键（信号源 / 设置 / 清理）。附带一个应用管理页，可以逐个 disable 系统 bloatware、控制开机自启。

- 包名 `com.zy.tvhome`
- 需要装到 `/system/priv-app/` 才能拿到 `FORCE_STOP_PACKAGES / CHANGE_COMPONENT_ENABLED_STATE / REAL_GET_TASKS` 三个 signature|privileged 权限（用来 kill 应用 / 禁组件 / 读最近任务）
- **前提是目标 TV 已 root**；`deploy.sh` 里有一键部署脚本
- `AppOps.java` 里带一份 KGZN 系 ROM 的 vendor bloatware 隐藏名单（`com.kgzn.*`）；别的 ROM 改这个列表即可

## `settings-probe/` — TV Probe

30+ 按钮，每个触发一个 `Settings.ACTION_*` 或组件直拉，用来快速探测某个 ROM 里哪些原生入口被替换 / 禁用了。开发一个新 Launcher 之前用它摸清 ROM 家底非常方便。

- 包名 `com.zy.tvprobe`
- **不需要 root**，任何 Android TV 都能装

## 构建

两个都是独立 Gradle 项目：

```bash
cd mini-home && ./gradlew :app:assembleDebug
cd settings-probe && ./gradlew :app:assembleDebug
```

## LICENSE

MIT。

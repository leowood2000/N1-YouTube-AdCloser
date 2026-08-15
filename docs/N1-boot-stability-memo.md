# N1 启动稳定性备忘

记录日期：2026-08-15

## 症状

冷启动后偶发卡在 Slim Box 界面，遥控器无响应；拔电源重新启动后通常恢复。关联现象还包括 VPN、YouTube 或蓝牙音箱没有按预期自动恢复。

## 已确认的原因

系统日志中反复出现预装 `tv.autostart` 的 `BootReceiver` 在
`LOCKED_BOOT_COMPLETED` 阶段读取普通 SharedPreferences 的异常：

```text
SharedPreferences in credential encrypted storage are not available
until after user is unlocked
```

该接收器标记为 Direct Boot aware，却在用户解锁前访问凭据加密存储，造成启动阶段崩溃并放大了自启动时序竞争。当前证据没有显示内核 panic、System Server watchdog、OOM 或持续性 ANR；`slim.box.tv.customizer` 的空进程退出也不是已确认的根因。

## 修复方案

1. 只禁用有问题的组件，不卸载整个系统应用：

   ```text
   adb shell pm disable --user 0 tv.autostart/tv.autostart.BootReceiver
   ```

2. 为 N1 Helper 增加 Direct Boot 安全的启动接收器：
   - 监听 `BOOT_COMPLETED` 和 `USER_UNLOCKED`；
   - 启动前检查 `UserManager.isUserUnlocked`；
   - 用户未解锁时直接返回，不读取凭据加密存储；
   - 解锁后再启动 Helper 的初始化流程。

3. Helper 等待 ifClash 的时间设为有界等待，并保留兜底：
   - 最多等待 45 秒；
   - 超时仍继续启动 YouTube，避免启动流程永久卡住；
   - VPN 状态以 `tun0` 和 Connectivity 状态为准，不只看单次端口探测。

4. 保留 YouTube AdCloser 的无障碍规则、截图兜底和音量键处理修复，不回退音量功能。

## 重启验收记录

真实重启后已确认：

- 新的解锁后接收器自动启动 N1 Helper；
- 旧 `tv.autostart` 的 SharedPreferences 崩溃不再出现；
- YouTube 自动启动并位于前台；
- VPN 的 `tun0` 已连接；
- B5 蓝牙音箱已连接并为活动音频设备；
- YouTube AdCloser 服务已启用并运行；
- 未发现新的 Fatal Exception、ANR 或 YouTube 进程死亡。

注意：启动日志出现 ifClash 45 秒兜底并不等于 VPN 失败；本次验收中 VPN 随后已由系统 Connectivity 和 `tun0` 状态确认连接。后续排障应同时检查最终网络状态。

## 后续排查命令

```text
adb shell logcat -b all -d -v time
adb shell dumpsys package tv.autostart
adb shell dumpsys connectivity
adb shell ip addr show tun0
adb shell dumpsys bluetooth_manager
adb shell dumpsys accessibility
```

重点搜索：`FATAL EXCEPTION`、`ANR in`、`Fatal signal`、`watchdog`、`tv.autostart`、`N1BootHelper`。

## 维护原则

- 不要在 `LOCKED_BOOT_COMPLETED` 回调里访问普通 SharedPreferences、数据库或其他凭据加密存储。
- 不要为了修复单个启动接收器而禁用整个 `tv.autostart` 包。
- 自启动链路必须有超时和兜底，避免 VPN 或外部服务异常时阻塞桌面与遥控器。
- 每次改动后必须进行一次真实重启，并同时验收 YouTube、VPN、蓝牙和 AdCloser。

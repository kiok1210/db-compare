# Docker Desktop 国内镜像安装说明（desktop.docker.com 不可达时）

本机若 `winget install Docker.DockerDesktop` 卡在下载（Temp 下 exe 为 0 字节），说明访问不了官方 CDN。

## 方案 A：手动装 Docker Desktop 后用本仓库 compose

1. 从可用镜像站下载安装包（任选其一，以当时可用为准）：
   - https://mirrors.aliyun.com/docker-toolbox/ （或搜索「Docker Desktop 镜像」）
   - 公司内网软件库中的 Docker Desktop Installer
2. 安装并启动 Docker Desktop，托盘图标就绪
3. （可选）Docker Engine 镜像加速：设置 → Docker Engine → 增加 `registry-mirrors`（如阿里云个人加速地址）
4. 执行：

```powershell
.\scripts\loadtest\up-mysql.ps1
```

将拉取 `mysql:8.0` 并启动：

| 容器 | 端口 | 库 |
|------|------|-----|
| dbcompare-mysql-source | 3307 | loadtest |
| dbcompare-mysql-target | 3308 | loadtest |

账号：`sync` / `sync123`（root / root）

## 方案 B：无 Docker，本机单实例双库（见 up-mysql-native.ps1）

GoldenDB **无公开 Docker 镜像**，本地无法「Docker 安装 GoldenDB」；协议压测请用 MySQL。内网真实 GoldenDB 仍走应用里配置的数据源。

# 声页音乐服务器

使用 Node.js 20 或以上版本。服务器从你选定的音乐目录读取歌曲，支持 FLAC、MP3、WAV、M4A、AAC、OGG 和 Opus。用 `艺术家/专辑/曲名.flac` 整理文件可生成相应的艺术家和专辑。专辑封面可放 `cover.jpg` 或 `cover.png`，同步歌词可用与歌曲同名的 UTF-8 LRC。FLAC 内嵌的 `LYRICS` 标签也可读取。

## 在电脑上运行

在 PowerShell 里运行下面的命令。把示例目录改成你的音乐目录。访问令牌只需要生成一次，重启服务器时复用同一个令牌。

```powershell
$env:SHENYE_USER = 'music'
$env:SHENYE_TOKEN = node -e "console.log(require('node:crypto').randomBytes(24).toString('hex'))"
$env:SHENYE_HOST = '0.0.0.0'
$env:SHENYE_TOKEN
node .\shenye-server.mjs 'D:\Music'
```

手机和服务器连同一个局域网。在声页中进入「自定义音乐服务器」，填写 `http://电脑的局域网IP:8787`、用户名 `music` 和上面生成的令牌。电脑防火墙如有提示，允许在家庭或工作等专用网络上访问。公网访问请通过有有效证书的 HTTPS 反向代理，保留 `/shenye/v1/` 路径，并避免记录请求查询字符串。

默认只监听 `127.0.0.1:8787`。可通过 `SHENYE_HOST` 和 `SHENYE_PORT` 设置监听地址和端口，通过 `SHENYE_STATE` 设置收藏、歌单文件的存放位置。默认状态文件是启动目录下的 `.shenye-state.json`。服务器以原始音质提供文件和播放进度跳转，音频转码需要另行配置。

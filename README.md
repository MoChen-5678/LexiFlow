# LexiFlow

LexiFlow 是 Android 背词和备考应用。首页的「四六级 · 雅思备考练习」提供四级、六级和雅思的阅读、听力、填空、词义和听写。提交后可查看答案与解析，完成一组后可查看成绩。断网时，App 使用上次下载的同类题组或内置练习。

## 运行题库服务

在包含 `compose.yaml` 的目录运行以下命令，首次部署会构建镜像。

```sh
docker compose up -d --build
curl http://127.0.0.1:5000/health
```

已有离线镜像包时，可以直接加载并运行。

```sh
docker load -i lexiflow-api-1.0.tar.gz
docker run -d --name lexiflow-api --restart unless-stopped -p 5000:5000 -v lexiflow-questions:/data lexiflow-api:1.0
```

服务监听 TCP 5000，仅返回 JSON 或音频。根路径返回 JSON 404。SQLite 题库保存在数据卷中，更新镜像时使用原来的数据卷。App 默认连接 `47.109.146.2:5000`，学习界面不显示地址。公网端口是否可达取决于主机防火墙和云安全组设置。

| 接口 | 内容 |
| --- | --- |
| `GET /health` | 服务状态、远程题源同步结果及最近成功时间 |
| `GET /api/questions?exam=cet4&kind=listening&limit=10` | 随机抽取一组题，可选 cet4、cet6、ielts，每组 1–30 题 |
| `GET /api/audio?text=Thursday&accent=UK&speed=1` | 单声道 WAV，支持 US、UK 及 0.5–2 倍语速 |

## 题目来源与更新

服务启动时导入内置模拟题，默认每 24 小时检查 GitHub 上的 `app/src/main/assets/exam_questions.json`。主分支尚未包含题源时，服务从本次功能分支读取。相同 ID 更新原题，不同 ID 新增。整批校验通过才写库。下载或校验失败时继续提供已有题目，最迟 15 分钟后重试。

每天按 UTC 日期为每个考试从现有词库选取最多 8 道新听写、8 道新词义，并生成有关活动地点、时间和改期信息的听力、阅读与填空。相同单词和题型不会重复收录；词库用完后不再生成这一类新题。内容为专项练习，不含官方历年真题。

启动 Compose 前可设置 `QUESTION_FEED_URL` 指向自己的 HTTPS JSON 题源，设置 `UPDATE_HOURS` 调整轮询间隔。格式见内置题库。每题须有稳定的 `id`、`exam`、`kind`、`prompt`、`answer` 和 `explanation`，听力与听写还须有 `speech`。选择题的答案须与一个选项一致。

## Android

使用 JDK 17 或 21 和 Android SDK 36.1。在项目根目录运行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`）。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式升级包须使用原应用的签名密钥。

模拟器连接本机 WSL 服务时可使用 `-PlexiflowHost=10.0.2.2` 编译。USB 真机本地测试可使用 `-PlexiflowHost=127.0.0.1` 编译，再运行 `adb reverse tcp:5000 tcp:5000`。

朗读优先播放随 APK 安装的英式或美式音频，覆盖现有词库和内置听力材料。其他文本调用 Android 英语语音引擎，缺少语音、合成失败或启动超时则请求服务端合成。手动朗读可独立于自动发音设置工作。

听写和拼写练习参考 [Qwerty Learner](https://github.com/RealKai42/qwerty-learner)。离线发音和服务端语音使用 [eSpeak NG](https://github.com/espeak-ng/espeak-ng)。在安装 eSpeak NG 和 libmp3lame 的 Linux 环境下，可用 `scripts/build-pronunciations.py` 重新生成音频包，两个参数分别为 App 的 assets 目录和 audio 输出目录。

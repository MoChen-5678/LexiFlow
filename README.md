# LexiFlow

LexiFlow 是 Android 背词和备考应用。首页的「四六级 · 雅思备考练习」提供四级、六级和雅思的阅读、听力、填空、词义和听写。提交后可查看答案与解析，完成一组后可查看成绩。断网时，App 使用上次下载的同类题组或内置练习。

## 运行题库服务

在包含 `compose.yaml` 的目录运行以下命令，首次部署会构建镜像。

```sh
docker compose up -d --build
curl http://127.0.0.1:5000/health
```

离线部署包内含镜像、Compose 配置和启动脚本。解压后运行 `sh deploy.sh` 即可加载镜像、启动服务并等待健康检查通过。也可以手动加载镜像并运行独立容器。

```sh
docker load -i lexiflow-api-1.2.tar.gz
docker run -d --name lexiflow-api --restart unless-stopped -p 5000:5000 -v lexiflow-questions:/data lexiflow-api:1.2
```

服务监听 TCP 5000，仅返回 JSON 或音频。根路径返回 JSON 404。SQLite 题库保存在数据卷中，更新镜像时使用原来的数据卷。App 默认连接 `47.109.146.2:5000`，学习界面不显示地址。公网端口是否可达取决于主机防火墙和云安全组设置。

| 接口 | 内容 |
| --- | --- |
| `GET /health` | 服务状态、远程题源同步结果及最近成功时间 |
| `GET /api/questions?exam=cet4&kind=listening&limit=10` | 随机抽取一组题，可选 cet4、cet6、ielts，每组 1–30 题 |
| `GET /api/audio?text=Thursday&accent=UK&speed=1` | 单声道 WAV，支持 US、UK 及 0.5–2 倍语速 |

## 题目来源与更新

服务启动时导入内置模拟题，默认每 24 小时检查 GitHub 上的 `app/src/main/assets/exam_questions.json`。主分支尚未包含题源时，服务从本次功能分支读取。相同 ID 更新原题，不同 ID 新增。整批校验通过才写库。下载或校验失败时继续提供已有题目，并在下一次每日检查时重试。

每天按 UTC 日期为每个考试从现有词库选取最多 8 道新听写、8 道新词义，并生成有关活动地点、时间和改期信息的听力、阅读与填空。相同单词和题型不会重复收录；词库用完后不再生成这一类新题。这部分内容在 App 中标为「模拟练习」。

默认通过国内镜像读取题源。也可以设置 `QUESTION_FEED_URL` 指向自己维护的、在国内可达的 HTTPS JSON 文件。格式见内置题库。每题须有稳定的 `id`、`exam`、`kind`、`prompt`、`answer` 和 `explanation`，听力与听写还须有 `speech`。选择题的答案须与一个选项一致。

## 历年真题与考生回忆

在「备考练习」选择四级或六级，再点「历年真题」。四六级题源提供 2024 年 6 月至 2025 年 12 月共 24 套试卷，覆盖听力、选词填空、段落匹配和仔细阅读。选定卷次后按题号继续做，选择全部试卷则随机抽题。听力使用上游原卷的整段录音，可暂停、拖动进度。每题显示考期、卷次、题号和来源。[四六级题源与使用说明](https://github.com/couldyyy9/cet-website)

雅思的「考生回忆」收录具有考期、地区和来源链接的口语题。仅采用上游标记为考生实考回忆的来源，排除预测和无地区记录。口语采用自练模式，完成后记录本组练习题数。[雅思数据与来源说明](https://github.com/eugenewang5425/ielts-corpus-lab)

真题在部署后同步到本机数据卷。按上游使用说明，真题功能供个人学习使用。首次打开真题或回忆题时，需要输入本机生成的个人学习码，可在部署主机运行以下命令查看。

```sh
docker compose -p lexiflow exec api cat /data/study-key
```

App 将学习码保存在设备的私有、不参与备份的目录。每次请求用它签名，默认 HTTP 部署适用于受信任网络。镜像与安装包均不附带第三方试卷、回忆题或原卷音频。

所有远程题源首次启动时检查一次，此后每 24 小时检查一次。服务先在数据卷记录下一次检查时间，再访问网络。重启、网页查询、App 刷新或下载失败都不会提前触发额外检查。停机超过一天后，下一次启动会补查一次。访问 `/health` 可查看各题源上次检查、收录数量与 `next_checks_utc`（下次检查时间，UTC）。

## 国内镜像与离线部署

离线部署包可直接加载本地镜像，启动无需访问 Docker Hub。源码构建通过 DaoCloud 下载按摘要锁定的 Python 基础镜像，Debian 软件包使用华为云镜像。

Git 版本查询优先通过 GH-Proxy，失败时切换到 ghfast。试卷和词库 JSON 使用 JSDMirror 国内域名，失败时切换 GH-Proxy 和 ghfast。所有文件按固定提交读取。App 播放原卷录音时也按这三条镜像线路依次尝试。查看原始来源按钮保留原发布者链接。

## Android

使用 JDK 17 或 21 和 Android SDK 36.1。在项目根目录运行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`）。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式升级包须使用原应用的签名密钥。

模拟器连接本机 WSL 服务时可使用 `-PlexiflowHost=10.0.2.2` 编译。USB 真机本地测试可使用 `-PlexiflowHost=127.0.0.1` 编译，再运行 `adb reverse tcp:5000 tcp:5000`。

朗读可选择英式、美式口音及男声、女声。词库单词和整组雅思材料随 APK 提供四套离线音频。其他英文文本由服务端用所选英语声音合成，长文章会分段朗读。中文界面说明不参与英文朗读。设置中的「试听」播放当前声音。

听写和拼写练习参考 [Qwerty Learner](https://github.com/RealKai42/qwerty-learner)。离线发音和服务端语音使用 [eSpeak NG](https://github.com/espeak-ng/espeak-ng)。在安装 eSpeak NG 和 libmp3lame 的 Linux 环境下，可用 `scripts/build-pronunciations.py` 重新生成音频包，两个参数分别为 App 的 assets 目录和 audio 输出目录。


## 1.2 练习内容

背词入口使用例句填空，依次显示英文挖空句、中文释义与答案输入框，提交后显示完整句、答案与下一词。已收录 4,088 个单词的实际使用例句，例句练习队列采用已配有效语境的词汇。例句来自 Tatoeba/ManyThings 和 Princeton WordNet，句子作者信息保存在 word_examples.json，许可文本随应用提供。

雅思原创专项练习含两篇学术阅读、两段完整听力，共 40 题。每组共享文章或录音，支持 TRUE/FALSE/NOT GIVEN、选择题和限词填空。整组交卷后给出逐题答案、原文依据与中文解析，听力文字稿在交卷后显示。材料可离线作答及朗读。

请同时升级 App 和服务端至 1.2，服务端新增 /api/ielts/sets，并为 /api/audio 增加 gender=male 或 gender=female 参数。

# 第三方数据与语音

- 词库来自 [Qwerty Learner](https://github.com/RealKai42/qwerty-learner)。各词表路径、固定提交保存在 `app/src/main/assets/vocabulary_sources.json`，上游 GPL-3.0 许可全文随词库保存在 `vocabulary_license.txt`。LexiFlow 对词表做去重、字段转换和课程合并，四级包含高中基础词表，六级进一步包含四级词汇。
- 双语例句来自 [ManyThings/Tatoeba](https://www.manythings.org/anki/)，按 CC BY 2.0 FR 署名使用，逐句原作者与句子编号保存在 `word_examples.json` 的 `source`。其他例句来自 Princeton WordNet 3.0。许可见 `example_licenses.txt`。
- 自然语音采用 [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)，模型权重采用 Apache-2.0；[kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx) 使用 MIT 许可。模型下载路径和 SHA-256 固定在 `server/download_models.py`，四种音色为 `af_heart`、`am_michael`、`bf_emma`、`bm_george`。Python 依赖许可保存在安装包的 dist-info 中。
- APK 原有离线回退发音由 eSpeak NG 生成，相关来源与生成脚本见 README。
- 默认腾讯云语音由用户自己的腾讯云账户提供，密钥通过 Docker secret 在运行时只读挂载，镜像和 APK 均不包含密钥。

- 高频词书依据服务端已收录的四六级阅读真题和雅思口语回忆题统计；题源为 [cet-website](https://github.com/couldyyy9/cet-website) 和 [ielts-corpus-lab](https://github.com/eugenewang5425/ielts-corpus-lab)。内置数据仅保留重复词组的聚合频次与来源提交版本，统计范围及去重规则见 README。

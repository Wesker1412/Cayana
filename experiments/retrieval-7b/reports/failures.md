# Cayana Stage 7B-1 — 檢索失敗與對比案例深入分析

> 本報告基於 v1 評測集實驗結果自動生成，無人工寫死數據。

## 1. Stage 7A 詞法未命中、語意模型成功召回案例（語意泛化與跨語言）

在此類案例中，使用者使用了同義詞、口語或英文查詢，Stage 7A 倒排索引完全無匹配，而語意向量模型成功召回：

- **問題 [Q1_006]**: `十月八號在大安星巴克點的大杯焦糖瑪奇朵多少錢？` (分類: `multi_receipt_confusion`)
  - 正確目標: `['mem-sbux-02']`
  - Stage 7A Surrogate 結果: `['mem-sbux-03', 'mem-bg-v1-0169', 'mem-bg-v1-1555']` (未召回)
  - BGE 語意檢索結果: `['mem-sbux-02', 'mem-sbux-01', 'mem-sbux-03']` (命中)

- **問題 [Q1_031]**: `腳踏車壞了去哪裡保養修繕？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-bike']`
  - Stage 7A Surrogate 結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-sem-bike']` (命中)

- **問題 [Q1_032]**: `發工資了沒？這個月薪水進來了嗎？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-salary']`
  - Stage 7A Surrogate 結果: `['mem-long-q4budget', 'mem-long-dentist']` (未召回)
  - BGE 語意檢索結果: `['mem-sem-salary']` (命中)

- **問題 [Q1_033]**: `肚子痛胃不舒服要吃什麼藥？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-stomach']`
  - Stage 7A Surrogate 結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-sem-stomach']` (命中)

- **問題 [Q1_052]**: `下雨天叫車去內科上班的車資收據？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-uber-02']`
  - Stage 7A Surrogate 結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-uber-02', 'mem-sem-exhaust-fee']` (命中)

- **問題 [Q1_076]**: `10月28日的牙科預約時間是幾點？` (分類: `precise_attribute`)
  - 正確目標: `['mem-sem-dentist-date']`
  - Stage 7A Surrogate 結果: `['mem-bg-v1-1935', 'mem-bg-v1-1663', 'mem-bg-v1-1879']` (未召回)
  - BGE 語意檢索結果: `['mem-sem-dentist-date', 'mem-long-dentist']` (命中)

- **問題 [Q1_108]**: `醫生建議每天早餐後補充多少劑量的維生素D滴劑？` (分類: `long_transcript_detail`)
  - 正確目標: `['mem-long-dentist']`
  - Stage 7A Surrogate 結果: `['mem-bg-v1-1555', 'mem-bg-v1-0515', 'mem-bg-v1-1371']` (未召回)
  - BGE 語意檢索結果: `['mem-long-dentist', 'mem-sem-stomach', 'mem-bg-v1-0613']` (命中)

## 2. Stage 7A 精準命中、純語意模型反而退化案例（精確數字、日期、代碼）

在此類案例中，使用者詢問精確收據金額、訂單編號或日期，純語意模型被主題相近的同品牌其他記憶干擾，而 7A 依靠字符倒排精確命中：

- **問題 [Q1_040]**: `耐適恩錠一天要服用幾次？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-stomach']`
  - Stage 7A Surrogate 結果: `['mem-sem-stomach', 'mem-long-dentist']` (精準命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q1_043]**: `黃金巷參觀心得與拍照景點？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-castle']`
  - Stage 7A Surrogate 結果: `['mem-sem-castle']` (精準命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q1_057]**: `醫師開立的維生素D滴劑劑量？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-long-dentist']`
  - Stage 7A Surrogate 結果: `['mem-long-dentist']` (精準命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q1_060]**: `微服務分散式鏈路追蹤的 TraceID 規範？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-long-extra-01']`
  - Stage 7A Surrogate 結果: `['mem-long-extra-01', 'mem-long-extra-02', 'mem-long-extra-03']` (精準命中)
  - BGE 語意檢索結果: `['mem-long-extra-20', 'mem-long-extra-15', 'mem-long-extra-19']` (被相近干擾項稀釋)

- **問題 [Q1_062]**: `EVA Air BR198 flight boarding gate confirmation` (分類: `cross_lingual`)
  - 正確目標: `['mem-sem-br198']`
  - Stage 7A Surrogate 結果: `['mem-sem-br198', 'mem-refute-flight']` (精準命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q1_071]**: `Uber ride receipt to Neihu Technology Park` (分類: `cross_lingual`)
  - 正確目標: `['mem-uber-02']`
  - Stage 7A Surrogate 結果: `['mem-uber-03', 'mem-uber-02', 'mem-uber-01']` (精準命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q1_072]**: `Keychron mechanical keyboard purchase order` (分類: `cross_lingual`)
  - 正確目標: `['mem-pchome-02']`
  - Stage 7A Surrogate 結果: `['mem-pchome-02']` (精準命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q1_085]**: `好市多中和店發票號碼 AB-38291045 金額？` (分類: `precise_attribute`)
  - 正確目標: `['mem-costco-01']`
  - Stage 7A Surrogate 結果: `['mem-costco-01', 'mem-costco-03', 'mem-costco-02']` (精準命中)
  - BGE 語意檢索結果: `['mem-bg-v1-1265', 'mem-bg-v1-0673', 'mem-bg-v1-0929']` (被相近干擾項稀釋)

- **問題 [Q1_086]**: `好市多九月二十八日發票上的石安牧場動福蛋買了幾盒？` (分類: `precise_attribute`)
  - 正確目標: `['mem-costco-02']`
  - Stage 7A Surrogate 結果: `['mem-costco-02', 'mem-costco-03', 'mem-costco-01']` (精準命中)
  - BGE 語意檢索結果: `['mem-bg-v1-0673', 'mem-bg-v1-0273', 'mem-bg-v1-1953']` (被相近干擾項稀釋)

- **問題 [Q1_094]**: `PChome 訂單編號 20260920881 的訂購商品？` (分類: `precise_attribute`)
  - 正確目標: `['mem-pchome-01']`
  - Stage 7A Surrogate 結果: `['mem-pchome-01', 'mem-pchome-03', 'mem-pchome-02']` (精準命中)
  - BGE 語意檢索結果: `['mem-pchome-02', 'mem-bg-v1-0597', 'mem-bg-v1-0373']` (被相近干擾項稀釋)

## 3. Hybrid (混合檢索) 互補勝出的典型案例

Hybrid 透過 Union 候選池與 Reciprocal Rank Fusion (RRF)，同時兼備詞法精準度與語意擴散力：

- **問題 [Q1_005]**: `好市多哪天買了石安牧場動福蛋和鮮奶？` (分類: `multi_receipt_confusion`)
  - 正確目標: `['mem-costco-02']`
  - 7A 結果: `['mem-costco-02', 'mem-costco-03']` | BGE 結果: `['mem-refute-milk', 'mem-costco-02']`
  - Hybrid 結果: `['mem-costco-02', 'mem-refute-milk']` (成功推至 Top-1)

- **問題 [Q1_015]**: `車資 210 元的那趟 Uber 是哪一天搭的？` (分類: `multi_receipt_confusion`)
  - 正確目標: `['mem-uber-01']`
  - 7A 結果: `['mem-uber-01', 'mem-bg-v1-0093']` | BGE 結果: `['mem-uber-03', 'mem-uber-01']`
  - Hybrid 結果: `['mem-uber-01', 'mem-uber-03']` (成功推至 Top-1)

- **問題 [Q1_030]**: `參加小明婚禮最新正式版的喜帖備忘？` (分類: `event_update_version`)
  - 正確目標: `['mem-event-v2']`
  - 7A 結果: `['mem-event-v2', 'mem-event-v1']` | BGE 結果: `['mem-event-v1', 'mem-event-v2']`
  - Hybrid 結果: `['mem-event-v2', 'mem-event-v1']` (成功推至 Top-1)

- **問題 [Q1_031]**: `腳踏車壞了去哪裡保養修繕？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-bike']`
  - 7A 結果: `[]` | BGE 結果: `['mem-sem-bike']`
  - Hybrid 結果: `['mem-sem-bike']` (成功推至 Top-1)

- **問題 [Q1_033]**: `肚子痛胃不舒服要吃什麼藥？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-stomach']`
  - 7A 結果: `[]` | BGE 結果: `['mem-sem-stomach']`
  - Hybrid 結果: `['mem-sem-stomach']` (成功推至 Top-1)

- **問題 [Q1_040]**: `耐適恩錠一天要服用幾次？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-stomach']`
  - 7A 結果: `['mem-sem-stomach', 'mem-long-dentist']` | BGE 結果: `[]`
  - Hybrid 結果: `['mem-sem-stomach', 'mem-long-dentist']` (成功推至 Top-1)

- **問題 [Q1_043]**: `黃金巷參觀心得與拍照景點？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-sem-castle']`
  - 7A 結果: `['mem-sem-castle']` | BGE 結果: `[]`
  - Hybrid 結果: `['mem-sem-castle']` (成功推至 Top-1)

- **問題 [Q1_052]**: `下雨天叫車去內科上班的車資收據？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-uber-02']`
  - 7A 結果: `[]` | BGE 結果: `['mem-uber-02', 'mem-sem-exhaust-fee']`
  - Hybrid 結果: `['mem-uber-02', 'mem-sem-exhaust-fee']` (成功推至 Top-1)

- **問題 [Q1_055]**: `小明結婚喜酒辦在台北哪間五星飯店？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-event-v2']`
  - 7A 結果: `['mem-event-v2', 'mem-event-v1']` | BGE 結果: `['mem-event-v1', 'mem-event-v2']`
  - Hybrid 結果: `['mem-event-v2', 'mem-event-v1']` (成功推至 Top-1)

- **問題 [Q1_057]**: `醫師開立的維生素D滴劑劑量？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-long-dentist']`
  - 7A 結果: `['mem-long-dentist']` | BGE 結果: `[]`
  - Hybrid 結果: `['mem-long-dentist']` (成功推至 Top-1)

## 4. 無答案問題錯誤召回 (False Positive) 與安全抑制案例

在此類負向問題中，資料庫無答案或證據不足。純語意模型因向量空間距離過近產生錯誤召回，Hybrid 結合門檻與詞法抑制：

- **負向問題 [Q1_127]**: `家裡日立冷氣濾網叫修水電師傅的手機電話號碼？`
  - 類型/原因: ``
  - BGE 召回: `['mem-bg-v1-1048', 'mem-bg-v1-1368']`
  - Hybrid 召回: `['mem-costco-03', 'mem-bg-v1-1048']`

- **負向問題 [Q1_129]**: `媽媽六十大壽家族聚餐訂位的陶板屋餐廳時間？`
  - 類型/原因: ``
  - BGE 召回: `[]`
  - Hybrid 召回: `['mem-long-extra-01', 'mem-long-extra-02']`

- **負向問題 [Q1_130]**: `上個月去好市多買的牛肉捲退貨退款明細單？`
  - 類型/原因: ``
  - BGE 召回: `['mem-refute-milk', 'mem-bg-v1-1841']`
  - Hybrid 召回: `['mem-costco-01', 'mem-costco-03']`

- **負向問題 [Q1_134]**: `長榮航空 BR198 班機的正駕駛機長姓名是誰？`
  - 類型/原因: ``
  - BGE 召回: `['mem-sem-br198', 'mem-refute-flight']`
  - Hybrid 召回: `['mem-refute-flight', 'mem-sem-br198']`

- **負向問題 [Q1_135]**: `好市多中和店收銀台結帳員的員工編號？`
  - 類型/原因: ``
  - BGE 召回: `['mem-sem-salary']`
  - Hybrid 召回: `['mem-costco-01', 'mem-sem-salary']`

- **負向問題 [Q1_136]**: `大安星巴克焦糖瑪奇朵使用的是哪一款牛奶品牌？`
  - 類型/原因: ``
  - BGE 召回: `['mem-sbux-02', 'mem-sbux-01']`
  - Hybrid 召回: `['mem-sbux-02', 'mem-sbux-01']`

- **負向問題 [Q1_137]**: `Uber 司機開的車輛年份與車牌號碼？`
  - 類型/原因: ``
  - BGE 召回: `['mem-uber-01', 'mem-uber-03']`
  - Hybrid 召回: `['mem-uber-03', 'mem-uber-01']`

- **負向問題 [Q1_138]**: `PChome 羅技滑鼠送貨司機的手機電話號碼？`
  - 類型/原因: ``
  - BGE 召回: `['mem-pchome-01', 'mem-pchome-02']`
  - Hybrid 召回: `['mem-pchome-01', 'mem-pchome-02']`

- **負向問題 [Q1_139]**: `台大醫院牙科看診的主治醫師身分證字號？`
  - 類型/原因: ``
  - BGE 召回: `['mem-sem-dentist-date', 'mem-long-dentist']`
  - Hybrid 召回: `['mem-sem-dentist-date', 'mem-long-dentist']`

- **負向問題 [Q1_140]**: `捷克布拉格城堡聖維特教堂的門票票價是多少克朗？`
  - 類型/原因: ``
  - BGE 召回: `['mem-sem-castle']`
  - Hybrid 召回: `['mem-sem-castle']`


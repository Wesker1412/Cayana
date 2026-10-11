# Cayana Stage 7B-1 — 檢索失敗與對比案例深入分析

## 1. Stage 7A (FTS) 找不到、語意模型找得到的案例（語意改寫與跨語言優勢）

此類案例主要集中在 `synonym_paraphrase`（同義改寫）與 `cross_lingual`（跨語言）。7A 因字面沒有出現關鍵字而完全無召回，而語意向量模型能精準匹配概念：

- **問題 [Q026]**: `腳踏車壞了去哪裡保養修繕？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-01']`
  - Stage 7A 檢索結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-syn-01', 'mem-long-04']` (命中 Top-1)

- **問題 [Q027]**: `發工資了沒？這個月薪水進來了嗎？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-02']`
  - Stage 7A 檢索結果: `['mem-long-06', 'mem-lex-19', 'mem-pre-17']` (未召回)
  - BGE 語意檢索結果: `['mem-syn-02']` (命中 Top-1)

- **問題 [Q028]**: `肚子痛胃不舒服要吃什麼藥？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-03']`
  - Stage 7A 檢索結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-syn-03']` (命中 Top-1)

- **問題 [Q030]**: `去日本玩換日幣便宜的匯率是多少？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-05']`
  - Stage 7A 檢索結果: `['mem-pre-23']` (未召回)
  - BGE 語意檢索結果: `['mem-syn-05', 'mem-bg-0202', 'mem-bg-1442']` (命中 Top-1)

- **問題 [Q031]**: `家裡寵物每天要喝多少水才夠？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-06']`
  - Stage 7A 檢索結果: `['mem-lex-21', 'mem-pre-17', 'mem-long-04']` (未召回)
  - BGE 語意檢索結果: `['mem-syn-06', 'mem-pre-17']` (命中 Top-1)

- **問題 [Q032]**: `北美館最近有什麼好看的藝術展？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-07']`
  - Stage 7A 檢索結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-syn-07']` (命中 Top-1)

- **問題 [Q040]**: `剛開始爬山新手適合走哪種健行路線？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-15']`
  - Stage 7A 檢索結果: `[]` (未召回)
  - BGE 語意檢索結果: `['mem-syn-15', 'mem-long-19']` (命中 Top-1)

- **問題 [Q041]**: `影音串流平台降級方案每個月扣多少？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-16']`
  - Stage 7A 檢索結果: `['mem-bg-0804', 'mem-bg-1844', 'mem-bg-1604']` (未召回)
  - BGE 語意檢索結果: `['mem-syn-16']` (命中 Top-1)

## 2. Stage 7A 找得到、語意模型反而找錯的案例（精確數字、代碼與屬性退步）

此類案例主要集中在 `precise_attribute`（精確數字、日期、帳號、航班號）。語意模型易因『高鐵車票』或『機票收據』概念相近，將錯誤但主題相似的記憶排在前面，而 7A 依靠文字與詞法匹配精確命中：

- **問題 [Q014]**: `年度健檢醫師衛教醫囑摘要` (分類: `lexical_exact`)
  - 正確目標: `['mem-lex-14']`
  - Stage 7A 檢索結果: `['mem-lex-14', 'mem-long-01', 'mem-long-02']` (命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

- **問題 [Q074]**: `如何安裝設定普羅米修斯與 Grafana 監控` (分類: `cross_lingual`)
  - 正確目標: `['mem-cl-14']`
  - Stage 7A 檢索結果: `['mem-cl-14', 'mem-pre-18', 'mem-long-09']` (命中)
  - BGE 語意檢索結果: `[]` (被相近干擾項稀釋)

## 3. Hybrid (混合檢索) 互補勝出的典型案例

Hybrid 透過 Union 候選池與 RRF 融合，同時具備詞法精準度與語意泛化力：

- **問題 [Q014]**: `年度健檢醫師衛教醫囑摘要` (分類: `lexical_exact`)
  - 正確目標: `['mem-lex-14']`
  - 7A 結果: `['mem-lex-14', 'mem-long-01']` | BGE 結果: `[]`
  - Hybrid 結果: `['mem-lex-14', 'mem-long-01']` (成功推至 Top-1)

- **問題 [Q026]**: `腳踏車壞了去哪裡保養修繕？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-01']`
  - 7A 結果: `[]` | BGE 結果: `['mem-syn-01', 'mem-long-04']`
  - Hybrid 結果: `['mem-syn-01', 'mem-long-04']` (成功推至 Top-1)

- **問題 [Q028]**: `肚子痛胃不舒服要吃什麼藥？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-03']`
  - 7A 結果: `[]` | BGE 結果: `['mem-syn-03']`
  - Hybrid 結果: `['mem-syn-03']` (成功推至 Top-1)

- **問題 [Q032]**: `北美館最近有什麼好看的藝術展？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-07']`
  - 7A 結果: `[]` | BGE 結果: `['mem-syn-07']`
  - Hybrid 結果: `['mem-syn-07']` (成功推至 Top-1)

- **問題 [Q033]**: `健身課重訓還剩下幾堂課沒上？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-08']`
  - 7A 結果: `['mem-bg-0778', 'mem-bg-1498']` | BGE 結果: `['mem-syn-08']`
  - Hybrid 結果: `['mem-syn-08', 'mem-bg-0778']` (成功推至 Top-1)

- **問題 [Q040]**: `剛開始爬山新手適合走哪種健行路線？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-15']`
  - 7A 結果: `[]` | BGE 結果: `['mem-syn-15', 'mem-long-19']`
  - Hybrid 結果: `['mem-syn-15', 'mem-long-19']` (成功推至 Top-1)

- **問題 [Q042]**: `多肉小盆栽夏天怎麼照顧才不會死掉爛掉？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-17']`
  - 7A 結果: `['mem-long-16', 'mem-syn-17']` | BGE 結果: `['mem-syn-17', 'mem-syn-35']`
  - Hybrid 結果: `['mem-syn-17', 'mem-long-16']` (成功推至 Top-1)

- **問題 [Q044]**: `做手工酸種麵包起種要發酵幾天？` (分類: `synonym_paraphrase`)
  - 正確目標: `['mem-syn-19']`
  - 7A 結果: `[]` | BGE 結果: `['mem-syn-19', 'mem-cl-10']`
  - Hybrid 結果: `['mem-syn-19', 'mem-cl-10']` (成功推至 Top-1)

## 4. 無答案問題之錯誤召回（False Positive）案例分析

當使用者詢問記憶庫完全未記載的事件時，純向量模型可能因餘弦相似度依然有一定基礎分數而硬挑出『看起來最像』的干擾項；Hybrid 則依賴門檻值與交叉確認抑制錯誤召回：

- **負向問題 [Q126]**: `我昨天買的無糖豆漿花了多少錢？`
  - 預期理由: 記憶庫中只有『買人體工學滑鼠』與『超市買機票』，無豆漿花費收據。
  - BGE 檢索: `['mem-bg-0243', 'mem-bg-0203']`
  - Hybrid 檢索: `['mem-bg-0243', 'mem-bg-0203']`

- **負向問題 [Q127]**: `長榮航空飛往舊金山的航班起飛時間是幾點？`
  - 預期理由: 記憶庫只有 BR198 飛東京成田，沒有舊金山航班。
  - BGE 檢索: `['mem-cl-01', 'mem-lex-02']`
  - Hybrid 檢索: `['mem-cl-01', 'mem-lex-02']`

- **負向問題 [Q128]**: `阿基師川菜館的招牌酸菜魚多少錢？`
  - 預期理由: 記憶庫菜單有宮保雞丁、麻婆豆腐、水煮牛肉，沒有酸菜魚。
  - BGE 檢索: `['mem-lex-03', 'mem-bg-0436']`
  - Hybrid 檢索: `['mem-lex-03', 'mem-bg-0436']`

- **負向問題 [Q129]**: `MacBook Pro 14吋的訂單編號是多少？`
  - 預期理由: 記憶庫購買的是 MacBook Pro 16吋，沒有 14吋訂單。
  - BGE 檢索: `['mem-lex-25']`
  - Hybrid 檢索: `['mem-lex-25', 'mem-bg-0364']`

- **負向問題 [Q130]**: `高鐵訂票代碼 99887766 的座位在哪裡？`
  - 預期理由: 記憶庫訂票代碼為 08552147，無此代碼。
  - BGE 檢索: `['mem-lex-11', 'mem-cl-11']`
  - Hybrid 檢索: `['mem-lex-11', 'mem-cl-11']`

- **負向問題 [Q131]**: `玉山銀行十月份的信用卡帳單應繳多少？`
  - 預期理由: 記憶庫只有九月份帳單，無十月份帳單。
  - BGE 檢索: `['mem-lex-07', 'mem-pre-05']`
  - Hybrid 檢索: `['mem-lex-07', 'mem-pre-05']`


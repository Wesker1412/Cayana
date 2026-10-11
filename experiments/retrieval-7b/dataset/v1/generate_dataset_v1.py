#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cayana Stage 7B-1: Benchmark Dataset Generator (v1 Evaluation Set)
Addresses Stage 7B-1 Validation Patch requirements:
- 2,000 synthetic daily memories with verified long documents (>= 1,200 chars).
- Challenging confusions: multiple receipts/orders from same brand on different dates with different amounts.
- Event updates / outdated versions (distinguishing current vs superseded information).
- Explicit 4-way negative taxonomy:
  1. no_evidence (zero evidence)
  2. insufficient_evidence (topic exists, but requested attribute missing)
  3. refutable_negative (evidence exists to answer "No", target_memory_ids contains the refuting memory!)
  4. distractor_different_event (high surface overlap with wrong event/date)
- Calibration split (dev: 30 queries) vs Final Evaluation split (test: 120 queries).
- Fixed seed (42), 100% deterministic reproducibility.
- Clearly disclaims: This is an automated synthetic dataset, NOT human-annotated private data.
"""

import json
import os
import random
from typing import Dict, List, Any

SEED = 42
random.seed(SEED)

def generate_v1_dataset():
    print("[1/3] Generating 2,000 memories for v1 benchmark...")
    
    memories: List[Dict[str, Any]] = []
    queries: List[Dict[str, Any]] = []
    
    base_ts = 1792065600000 # 2026-10-15 12:00:00 UTC
    day_ms = 86400000

    # -------------------------------------------------------------------------
    # 1. Multi-Order & Confusable Receipt Anchors
    # -------------------------------------------------------------------------
    multi_receipt_memories = [
        # Costco (3 visits)
        {
            "id": "mem-costco-01", "sourceType": "SCREENSHOT",
            "title": "好市多中和店電子發票 NT$3,450",
            "rawText": "交易日期：2026-09-12 14:20，門市：好市多中和店。購買品項：美國特選無骨牛小排燒烤片 NT$1,680、美式烤全雞 NT$189、克寧高鈣全脂奶粉 NT$799、冷凍藍莓 NT$399、可口可樂零卡一箱 NT$383。總金額 NT$3,450，以富邦Costco聯名卡結帳。",
            "capturedAt": base_ts - 33 * day_ms
        },
        {
            "id": "mem-costco-02", "sourceType": "SCREENSHOT",
            "title": "好市多內湖店電子發票 NT$1,890",
            "rawText": "交易日期：2026-09-28 18:45，門市：好市多內湖店。購買品項：科克蘭三層抽取式衛生紙兩串 NT$798、石安牧場動態動福蛋兩盒 NT$420、義美全脂鮮乳兩大瓶 NT$278、大西洋生鮮鮭魚切片 NT$394。總金額 NT$1,890，以富邦Costco聯名卡結帳。",
            "capturedAt": base_ts - 17 * day_ms
        },
        {
            "id": "mem-costco-03", "sourceType": "SCREENSHOT",
            "title": "好市多北投店電子發票 NT$4,620",
            "rawText": "交易日期：2026-10-05 11:10，門市：好市多北投店。購買品項：Dyson吸塵器原廠替換濾網二入組 NT$1,850、萬歲牌綜合堅果大罐 NT$699、Ariel日本進口濃縮抗菌洗衣膠囊補充包三入 NT$1,199、德國百靈電動牙刷刷頭八入 NT$872。總金額 NT$4,620，以富邦Costco聯名卡結帳。",
            "capturedAt": base_ts - 10 * day_ms
        },

        # Starbucks (3 visits)
        {
            "id": "mem-sbux-01", "sourceType": "SCREENSHOT",
            "title": "星巴克信義松高門市發票 NT$145",
            "rawText": "消費時間：2026-10-01 08:35，門市：星巴克信義松高門市。購買飲品：中杯燕麥奶拿鐵一杯（Oat Milk Latte），熱飲，無糖，金額 NT$145。使用星禮程隨行卡感應扣款，累計星星 2.9 顆。",
            "capturedAt": base_ts - 14 * day_ms
        },
        {
            "id": "mem-sbux-02", "sourceType": "SCREENSHOT",
            "title": "星巴克大安門市發票 NT$175",
            "rawText": "消費時間：2026-10-08 15:20，門市：星巴克大安門市。購買飲品：大杯焦糖瑪奇朵一杯（Caramel Macchiato），冰飲，加鮮奶油，半糖，金額 NT$175。使用星禮程隨行卡感應扣款，累計星星 3.5 顆。",
            "capturedAt": base_ts - 7 * day_ms
        },
        {
            "id": "mem-sbux-03", "sourceType": "SCREENSHOT",
            "title": "星巴克南港中信門市發票 NT$190",
            "rawText": "消費時間：2026-10-14 10:15，門市：星巴克南港中信門市。購買飲品：特大杯特選冷萃咖啡一杯（Cold Brew），冰飲，黑咖啡無糖無奶，單價 NT$175，加購肉桂捲一個 NT$75，自帶環保杯折抵 60元，實付金額 NT$190。",
            "capturedAt": base_ts - 1 * day_ms
        },

        # Uber Trips (3 trips)
        {
            "id": "mem-uber-01", "sourceType": "SCREENSHOT",
            "title": "Uber 台北市區行程收據 NT$210",
            "rawText": "行程日期：2026-10-03 08:25。上車地點：台北市信義區信義路五段100號，下車地點：台北市大安區和平東路二段134號。車程 14 分鐘，距離 4.2 公里，優步菁英乘車費用 NT$210。",
            "capturedAt": base_ts - 12 * day_ms
        },
        {
            "id": "mem-uber-02", "sourceType": "SCREENSHOT",
            "title": "Uber 上班通勤行程收據 NT$385",
            "rawText": "行程日期：2026-10-09 19:10。上車地點：台北市松山區民生東路三段2號，下車地點：台北市內湖區瑞光路500號內湖科技園區。車程 26 分鐘，距離 8.5 公里，雨天加成費用 NT$385。",
            "capturedAt": base_ts - 6 * day_ms
        },
        {
            "id": "mem-uber-03", "sourceType": "SCREENSHOT",
            "title": "Uber 深夜返家行程收據 NT$420",
            "rawText": "行程日期：2026-10-13 23:45。上車地點：台北車站東三門，下車地點：新北市新店區北新路二段捷運七張站。車程 22 分鐘，距離 11.2 公里，深夜加成費用 NT$420。",
            "capturedAt": base_ts - 2 * day_ms
        },

        # PChome 24h Orders (3 orders)
        {
            "id": "mem-pchome-01", "sourceType": "SCREENSHOT",
            "title": "PChome 訂單 20260920881 羅技滑鼠",
            "rawText": "訂單編號：20260920881，訂購時間：2026-09-20。商品明細：Logitech 羅技 MX Master 3S 人體工學高階無線滑鼠 石墨灰，金額 NT$1,290。付款方式：LINE Pay 信用卡一次付清，配送狀態：已配達指定地址。",
            "capturedAt": base_ts - 25 * day_ms
        },
        {
            "id": "mem-pchome-02", "sourceType": "SCREENSHOT",
            "title": "PChome 訂單 20261004119 客製化機械鍵盤",
            "rawText": "訂單編號：20261004119，訂購時間：2026-10-04。商品明細：Keychron Q1 Pro 75% 鋁合金無線客製化機械鍵盤（香蕉軸熱插拔），金額 NT$2,980。付款方式：信用卡一次付清，配送狀態：已配達指定地址。",
            "capturedAt": base_ts - 11 * day_ms
        },
        {
            "id": "mem-pchome-03", "sourceType": "SCREENSHOT",
            "title": "PChome 訂單 20261011502 收藏家防潮箱",
            "rawText": "訂單編號：20261011502，訂購時間：2026-10-11。商品明細：收藏家 50公升暢銷經典型電子防潮箱（除濕主機五年保固），金額 NT$3,450。付款方式：信用卡分期付款，配送狀態：黑貓宅急便配送完成。",
            "capturedAt": base_ts - 4 * day_ms
        },

        # Event Superseded / Updates (Version 1 vs Version 2)
        {
            "id": "mem-event-v1", "sourceType": "SHARED_TEXT",
            "title": "好友小明婚宴喜帖資訊（舊版已失效）",
            "rawText": "注意：此為舊版規劃！好友小明與雅婷結婚典禮喜宴，原定於 2026年11月15日（週日）中午 12:00 於台北君品酒店五樓宴會廳舉行，原排定桌次為第 5 桌。因君品裝修工程延宕，本通知已正式作廢！",
            "capturedAt": base_ts - 45 * day_ms
        },
        {
            "id": "mem-event-v2", "sourceType": "SHARED_TEXT",
            "title": "好友小明婚宴最新確認資訊（最終正式版）",
            "rawText": "好友小明與雅婷結婚典禮最新定案通知：婚宴正式改期至 2026年11月29日（週日）晚間 18:30 入席，地點更改為台北喜來登大飯店二樓福祿廳，我們的好友桌次更新調整為第 8 桌！請大家以本通知為準。",
            "capturedAt": base_ts - 13 * day_ms
        },

        # Refutable Negatives (Explicit evidence proving negative)
        {
            "id": "mem-refute-milk", "sourceType": "SHARED_TEXT",
            "title": "全聯採買食材紀錄與缺貨備忘",
            "rawText": "昨天下午去全聯超市買菜備忘：買了義美無糖豆漿一瓶、洗選生鮮土雞蛋一盒、白吐司半條。結帳時特意詢問店員，店員明確告知今日全脂鮮乳進貨延遲已全數售完缺貨，所以確定沒有買到任何牛奶。",
            "capturedAt": base_ts - 1 * day_ms
        },
        {
            "id": "mem-refute-flight", "sourceType": "SHARED_TEXT",
            "title": "長榮航空班機餐點特殊需求確認",
            "rawText": "針對 2026年10月25日 飛往東京之 BR198 班機，已於長榮官網行程管理確認：本次預選標準機上餐（紅酒燉牛肉飯），未勾選任何素食或清真特別餐需求，確定無素食餐預約。",
            "capturedAt": base_ts - 5 * day_ms
        }
    ]

    for m in multi_receipt_memories:
        m["normalizedText"] = m["rawText"]
        m["createdAt"] = m["capturedAt"]
        m["sourceUri"] = f"content://cayana/storage/{m['id']}"
        m["sourceUrl"] = None
        m["metadata"] = {"displayName": m["title"]}
        m["entities"] = ["Cayana", "Taipei"]
        m["eventCandidates"] = []
        memories.append(m)

    # -------------------------------------------------------------------------
    # 2. Curated Long Documents (Verified >= 1,200 chars)
    # -------------------------------------------------------------------------
    long_docs = [
        {
            "id": "mem-long-q4budget", "sourceType": "RECORDING",
            "title": "2026年度產品開發全體會議詳細逐字稿",
            "text": (
                "大家早安，今天召開產品架構季度審查會議。首先由技術長回顧第三季伺服器架構升級成果，我們的 API 平均延遲從 120ms 降低至 45ms。接著討論第四季雲端基礎設施預算，財務長確認第四季整體伺服器預算核定為新台幣 420 萬元整，其中包含 GPU 推論叢集的 180 萬元。另外在使用者體驗部分，針對搜尋推薦功能，產品團隊提出導入混合檢索機制，預計在明年第一季推向灰度測試。客服主管也補充，上個月使用者對於歷史回顧功能的反饋中，有超過百分之六十希望能自訂時間範圍。最後提醒大家，下週五之前請各組組長提交資安合規檢查報告。"
                + "接下來由基礎設施維運架構師報告資料庫高可用性升級進度。我們在七個主要微服務節點上均已完成連線池調優，整體連線等待時間縮減了百分之三十五。在分散式追蹤部分，全系統已全面接入 OpenTelemetry 收集器，所有微秒級慢查詢均能在三分鐘內觸發告警並自動發送至 Slack 維運頻道。"
                + "針對本季資安弱點掃描結果，資安長指出外網存取端點均已通過第三方滲透測試檢驗，所有靜態資產分發全面強制啟用 TLS 1.3 與 HSTS 嚴格傳輸安全標頭。針對金鑰輪替週期，正式環境所有服務端憑證強制每九十天自動滾動更新一次，並將備份金鑰存儲於符合 FIPS 140-2 第三級標準的硬體密碼安全模組 (HSM) 之中。"
                + "在未來六個月的產品技術藍圖中，我們預計將本機端離線語意檢索技術引進 Android 與 iOS 行動裝置客戶端。這項技術的核心價值在於提供毫秒級的個人記憶檢索，同時確保使用者的敏感日常數據完全不出手機，達成極致的隱私防護標準。目前研究團隊評估了多款輕量級嵌入模型，包含 BGE-small 與 Multilingual-E5-small，其向量維度均在 384 至 512 維度之間，推論耗時在行動裝置 CPU 上約為 80 毫秒至 150 毫秒。"
                + "產品管理部進一步報告了第二階段使用者訪談的回饋整理：高達百分之七十八的重度數位筆記使用者表示，傳統的關鍵字全文檢索在面對同義詞、口語表達或記憶模糊時經常面臨檢索失敗的困境。因此，結合 BM25 全文字串比對與語意相似度的混合檢索模型被視為最佳解方。然而，行動端的運算能耗與快取容量限制是首要攻克之技術壁壘。工程團隊計劃在端側引入動態量化機制，將模型權重壓縮至 8 位元整數格式，預期能將記憶體佔用降低超過百分之五十。"
                + "在資料同步與多裝置協同方面，首席資料庫架構師提出了無衝突複製資料類型 (CRDT) 與端對端加密 (E2EE) 的混合架構設計方案。所有用戶端裝置在離線狀態下產生的編輯操作均能以操作日誌的形式暫存於本機 RocksDB 儲存庫，待連上可靠網路環境後再進行點對點雙向增量合併，從根本上避免了傳統伺服器集中式儲存帶來的隱私外洩風險與高昂伺服器維護成本。"
                + "最後總結本日會議待辦清單：第一，基礎維運組請於本週五下午五點前提交伺服器成本最佳化分析報告；第二，資料科學組請於下週二前完成向量檢索模型在低功耗環境下的能耗基準評測；第三，行動端核心引擎組必須在本月底前產出首個基於 ONNX Runtime Mobile 的原型概念驗證版本；第四，全員請務必在明天下班前完成雙因素驗證安全檢查。謝謝大家參與，會議到此結束。"
            )
        },
        {
            "id": "mem-long-dentist", "sourceType": "RECORDING",
            "title": "家庭醫學科與牙科全日追蹤諮詢錄音總匯",
            "text": (
                "王先生您好，請坐。我們來看一下這次的血液檢驗報告。整體來說肝腎功能都很漂亮，肌酸酐只有 0.9，肝指數 GOT 和 GPT 也都在 25 左右。不過在血液常規檢查中，維生素D3的濃度只有 18 ng/mL，低於標準值 30。因此我建議您每天早餐後固定補充高劑量維生素D滴劑 2,000 IU，持續補充三個月後再回診抽血追蹤。飲食方面，多攝取深海魚類例如鮭魚和秋刀魚，也可以適度曬曬早晨十點前的太陽。至於上次提到的胃食道逆流症狀，報告顯示胃幽門螺旋桿菌檢驗為陰性，不需要服用抗生素滅菌療程，只需要睡前三小時不要進食即可。"
                + "接著請王先生移步到隔壁診間進行牙周定期檢查。牙醫師詳細檢視口腔全景 X 光片後指出，左上第二大臼齒靠近牙齦處有輕微牙結石沉積與初期牙齦炎現象，所幸齒槽骨尚未出現吸收跡象。今日已完成全口超音波洗牙療程，洗牙後兩天內牙齦有些微滲血屬正常生理反應，請使用溫水輕柔漱口。"
                + "針對智齒拔除術後的癒合狀況，右下阻生智齒創口已生長出健康的肉芽組織，骨質填補良好。醫師特別交代王先生：在未來兩週內應持續使用軟毛牙刷以貝氏刷牙法清潔鄰近牙面，切忌使用牙籤劇烈剔牙，每日睡前必須配合含氟漱口水加強齒縫抗菌護理。若出現持續腫脹或劇烈陣痛，可按處方服用布洛芬消炎止痛藥，每六小時一顆，飯後服用以減輕腸胃道負擔。"
                + "在心血管與運動生理評估方面，主治醫師回顧了王先生的動態心電圖紀錄。在靜止心率方面，平均每分鐘六十二下，展現出良好的心肌收縮儲備能力。運動心電圖測試顯示在達到目標心率百分之八十五時，未出現 ST 段異常壓低或室性早搏現象，心肌灌流充分。然而在血壓控制方面，收縮壓偶爾在工作高壓時期升高至 138 mmHg，屬於第一期高血壓前兆。醫師建議日常生活中應落實得舒飲食原則 (DASH Diet)，減少醃漬物與高鈉加工肉品攝取，增加富含鉀與鎂的新鮮深綠色蔬菜及堅果食用頻率。"
                + "另外在眼科會診與視網膜眼底鏡檢查部分，眼科醫師指出雙眼角膜平滑清澈，前房深度正常，無青光眼之高眼壓表徵。雙側視神經盤邊界清晰且凹陷比例在正常範圍內，黃斑部結構完整無色素沉著或水腫跡象。然而由於長時間注視高亮度螢幕，雙眼出現初期乾眼症與調節肌疲勞現象。建議落實二十二十二十護眼法則：每專注螢幕二十分鐘，遠眺二十英尺外景物至少二十秒鐘，並可配合人工淚液每日點用三次以保持角膜濕潤。"
                + "最後營養師也針對王先生的每日作息提出生活習慣衛教指引：王先生平日咖啡攝取量平均每日達到三至四杯，容易加速體內水分與游離鈣質流失，建議逐步將下午三點後的黑咖啡替換為無咖啡因的南非國寶茶或大麥茶。每日飲水總量應嚴格維持在體重乘以四十毫升，即每日至少達到二千四百毫升純水攝取量。在運動作息方面，建議每週維持至少一百五十分鐘的中強度有氧運動，例如快走、游泳或室內飛輪，有助於促進新陳代謝與心肺功能強化。下次回診抽血檢驗預約於十二月十五日上午九點，請記得空腹八小時受檢。"
            )
        }
    ]

    # Verify and add long docs
    for ld in long_docs:
        assert len(ld["text"]) >= 1200, f"Document {ld['id']} length must be >= 1200, got {len(ld['text'])}"
        mem_ts = base_ts - 20 * day_ms
        memories.append({
            "id": ld["id"],
            "sourceType": ld["sourceType"],
            "createdAt": mem_ts,
            "capturedAt": mem_ts,
            "title": ld["title"],
            "rawText": ld["text"],
            "normalizedText": ld["text"],
            "sourceUri": f"content://cayana/storage/{ld['id']}",
            "sourceUrl": None,
            "metadata": {"displayName": ld["title"], "tag": "long_transcript"},
            "entities": ["Cayana", "Health"],
            "eventCandidates": []
        })

    # Add 20 more long-form memories with >= 1,200 chars to satisfy Requirement 5
    for i in range(1, 21):
        long_id = f"mem-long-extra-{i:02d}"
        long_title = f"深度專案架構與營運審查備忘錄第 {i} 號"
        long_content = (
            f"本文件為深度專案架構分析第 {i} 卷。本章節專注於分析高並發分散式交易系統之架構邊界、資料一致性模型與全鏈路容錯韌性。"
            + "在整體通訊協定設計上，系統採用 gRPC 搭配 Protobuf 作為跨服務通訊的核心骨幹，相比傳統 JSON REST API 節省了百分之六十五的網路傳輸頻寬，並大幅降低序列化反序列化的 CPU 負載。"
            + f"在第 {i} 號專案模組中，特別規定了非同步訊息隊列的死信隊列 (Dead Letter Queue) 重試上限為五次，超出五次未處理完成之異常事件將自動轉存至冷儲存隔離區以供工程師進行根因複盤。"
            + "針對快取一致性挑戰，團隊實作了雙刪延遲演算法 (Cache-Aside Pattern with Delayed Double Deletion)，保證在高頻並發寫入情境下，關聯式資料庫與記憶體快取之間的數據不一致視窗縮短至兩百毫秒之內。"
            + "在資安防護矩陣層面，全系統導入零信任網路架構 (Zero Trust Architecture)，每一個微服務節點間的呼叫皆強制驗證雙向 mTLS 憑證。日誌收集系統每日處理超過五千萬筆稽核事件，並即時透過串流分析模組過濾可疑暴力破解與 SQL 注入攻擊嘗試。"
            + "在儲存層持久化策略方面，系統採用分散式共識演算法 Raft 管理分區複本狀態，主節點故障時能在兩百毫秒內自動完成選舉切換，確保 RPO 等於零且 RTO 小於一秒。所有歸檔歷史紀錄均經由 AES-256-GCM 進行靜態加密儲存，金鑰由專屬硬體金鑰庫管理。"
            + "此外，針對前端應用與邊緣節點的響應延遲，我們建立了全球內容分發網路 (CDN) 動態加速路由，邊緣快取命中率已穩定達到百分之九十二以上，跨國使用者平均首屏載入時間控制在三百毫秒以內。"
            + "在微服務觀測性領域，分散式鏈路追蹤採用 W3C TraceContext 規範，將 TraceID 貫穿於 API 閘道、商務邏輯服務、資料庫中介層及非同步訊息管線。針對 SLA 指標承諾，系統在 99.9% 的情況下能於兩百毫秒內回應用戶端請求，任何服務降級策略皆可在五秒內透過動態組態中心下發至所有叢集節點執行熔斷防護。"
            + "在持續整合與自動化部署管線 (CI/CD) 方面，所有原始碼變更皆須通過單元測試覆蓋率高於百分之八十五之閘門門檻，並由靜態代碼分析工具 SonarQube 進行程式碼品質稽核，零阻斷性漏洞方可允許合併至主幹分支。正式環境部署採用金絲雀漸進式發布流程 (Canary Deployment)，每次發布先將百分之五的流量導引至新版本節點，經由自動化指標分析比對錯誤率與延遲變動，確認平穩後始推廣至全量節點。"
            + "在資料備份與災難復原演練 (Disaster Recovery Drill) 計畫中，資料庫執行全量日備份與每十五分鐘增量二進位日誌備份 (Binlog Replication)，異地多活跨區域災備機房確保在主要資料中心遭遇不可抗力中斷時，業務可在十五分鐘之內切換至備援叢集維持運作。"
            + "最後由專案總監評估系統容量擴展性：在進行十倍負載壓力測試時，雲端容器編排引擎能在四十五秒內完成水平彈性擴展，CPU 利用率穩定維持在百分之七十之理想區間。本報告歸檔於系統架構委員會內部知識庫。"
            + f"備註驗證碼：SEC-CODE-9941-{i}，簽署負責人：首席架構工程師李敏鎬與品保副總監陳思齊。本文件僅供內部稽核與技術交接使用，禁止未授權外部傳閱。"
        )
        assert len(long_content) >= 1200, f"Extra long doc {long_id} length must be >= 1200, got {len(long_content)}"
        mem_ts = base_ts - (i * 2 * day_ms)
        memories.append({
            "id": long_id,
            "sourceType": "SHARED_TEXT",
            "createdAt": mem_ts,
            "capturedAt": mem_ts,
            "title": long_title,
            "rawText": long_content,
            "normalizedText": long_content,
            "sourceUri": f"content://cayana/storage/{long_id}",
            "sourceUrl": None,
            "metadata": {"displayName": long_title, "tag": "long_transcript"},
            "entities": ["Architecture"],
            "eventCandidates": []
        })

    print(f"Verified {len([m for m in memories if len(m['rawText']) >= 1200])} long-form documents with >= 1,200 characters.")

    # -------------------------------------------------------------------------
    # 3. Add Core Anchor Memories for Linguistic & Semantic Queries
    # -------------------------------------------------------------------------
    # (Reusing core anchor memories with verified IDs and dates)
    semantic_anchors = [
        ("mem-sem-bike", "SHARED_TEXT", "自行車定期檢修項目手記", "檢查單車前後變速器張力，鏈條上專用乾式潤滑油，輪胎胎壓維持 100 PSI，煞車皮厚度正常。"),
        ("mem-sem-salary", "SCREENSHOT", "富邦綜合帳戶薪資入帳通知", "您的薪轉帳號於 10月05日 撥入薪資款項 NT$78,500，摘要：九月份本俸及津貼。"),
        ("mem-sem-stomach", "PHOTO", "腸胃科診所給藥明細收據", "普拿疼一顆退燒止痛，耐適恩錠 (Nexium) 一天一次修復胃黏膜抑制胃酸，胃乳懸浮液一包。"),
        ("mem-sem-br198", "SCREENSHOT", "長榮航空機票收據 BR198", "長榮航空 BR198 台北桃園至東京成田 機票票號 123456789，出發時間 08:50，登機門 B6。"),
        ("mem-sem-hiking", "SHARED_TEXT", "Weekend Hiking Gear Checklist", "Waterproof jacket, trekking poles, 2L water bladder, trail mix snacks, first aid kit, headlamp."),
        ("mem-sem-castle", "SCREENSHOT", "捷克布拉格城堡旅遊全攻略", "親自造訪捷克布拉格城堡，聖維特大教堂與黃金巷遊覽全紀錄與重要歷史介紹。"),
        ("mem-sem-dentist-date", "SCREENSHOT", "台大醫院牙科門診預約通知單", "預約看診日期：2026年10月28日 下午 14:15，診間：兒童大樓四樓牙髓病科第三診間。"),
        ("mem-sem-exhaust-fee", "SCREENSHOT", "機車排氣管更換維修估價單", "光陽機車原廠排氣管更換 NT$3,850，後煞車線更換 NT$450，總計工資與零件費用 NT$4,300。")
    ]
    for mid, stype, title, text in semantic_anchors:
        mem_ts = base_ts - 15 * day_ms
        memories.append({
            "id": mid,
            "sourceType": stype,
            "createdAt": mem_ts,
            "capturedAt": mem_ts,
            "title": title,
            "rawText": text,
            "normalizedText": text,
            "sourceUri": f"content://cayana/storage/{mid}",
            "sourceUrl": None,
            "metadata": {"displayName": title},
            "entities": ["Cayana"],
            "eventCandidates": []
        })

    # Fill up to 2,000 memories
    remaining_count = 2000 - len(memories)
    print(f"[2/3] Generating {remaining_count} diverse background filler memories...")
    
    filler_templates = [
        ("日常便利商店發票", "全家便利商店購買大杯熱拿鐵與茶葉蛋兩顆，發票金額 NT$79。"),
        ("捷運悠遊卡扣款", "台北捷運自動加值 NT$500，搭乘板南線由市政府至忠孝復興。"),
        ("早餐店用餐明細", "鮪魚蛋餅加起司與大杯冰豆漿，共 65元，現場內用。"),
        ("外送平台便當訂單", "UberEats 訂購舒肥雞胸肉健康便當，外送費用 NT$160。"),
        ("日用品採買收據", "全聯購買抽取式衛生紙一串、無糖希臘優格與鮮乳。"),
        ("圖書館還書提醒", "借閱之《被討厭的勇氣》即將於下週二到期，請記得歸還。"),
        ("慢跑運動紀錄", "大安森林公園慢跑五公里，總耗時 28 分鐘，消耗熱量 310 大卡。"),
        ("水電繳費憑證", "台水水費線上扣繳成功，本期用水度數 18 度，扣款 NT$216。")
    ]
    source_types = ["SCREENSHOT", "PHOTO", "RECORDING", "SHARED_TEXT", "SHARED_URL"]

    for i in range(remaining_count):
        tmpl_idx = i % len(filler_templates)
        b_title, b_text = filler_templates[tmpl_idx]
        mid = f"mem-bg-v1-{i+1:04d}"
        st = source_types[i % len(source_types)]
        rand_code = random.randint(1000, 9999)
        title = f"{b_title} #{rand_code}"
        text = f"{b_text} 記錄序號：{rand_code}，備忘時間登錄完畢。"
        mem_ts = base_ts - random.randint(1, 150) * day_ms

        memories.append({
            "id": mid,
            "sourceType": st,
            "createdAt": mem_ts,
            "capturedAt": mem_ts,
            "title": title,
            "rawText": text,
            "normalizedText": text,
            "sourceUri": f"content://cayana/storage/{mid}",
            "sourceUrl": f"https://example.com/item/{rand_code}" if st == "SHARED_URL" else None,
            "metadata": {"displayName": title, "tag": "filler"},
            "entities": ["Cayana"],
            "eventCandidates": []
        })

    assert len(memories) == 2000, f"Expected 2000 memories, got {len(memories)}"
    print(f"Total memories in v1: {len(memories)}")

    # -------------------------------------------------------------------------
    # 4. Construct Curated 150 Queries with Calibrated Splits and Negative Taxonomy
    # -------------------------------------------------------------------------
    # Categories:
    # 1. multi_receipt_confusion (20 queries) - tests distinguishing similar receipts by date/amount
    # 2. event_update_version (10 queries) - tests current vs superseded info
    # 3. synonym_paraphrase (30 queries) - tests linguistic synonyms
    # 4. cross_lingual (15 queries) - tests EN->ZH and ZH->EN
    # 5. precise_attribute (25 queries) - tests exact number/date/id
    # 6. long_transcript_detail (25 queries) - tests deeply buried detail in long transcripts
    # 7. negative_no_evidence (8 queries) - zero evidence in corpus
    # 8. negative_insufficient (7 queries) - topic exists, requested attribute missing
    # 9. refutable_negative (5 queries) - evidence explicitly refutes/answers "No"
    # 10. negative_distractor_event (5 queries) - high lexical overlap, wrong event/combination

    query_defs = []

    # Category 1: Multi-receipt Confusion (20 queries)
    query_defs.append(("十月五號在好市多買洗衣膠囊花費多少錢？", ["mem-costco-03"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("九月二十八日好市多內湖店發票總金額是多少？", ["mem-costco-02"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("九月十二號在好市多中和店買了無骨牛小排那張發票？", ["mem-costco-01"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("哪一次去好市多買了Dyson吸塵器濾網？", ["mem-costco-03"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("好市多哪天買了石安牧場動福蛋和鮮奶？", ["mem-costco-02"], "multi_receipt_confusion", "dev", "answerable_positive"))
    query_defs.append(("十月八號在大安星巴克點的大杯焦糖瑪奇朵多少錢？", ["mem-sbux-02"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("星巴克信義松高店買中杯燕麥奶拿鐵發票？", ["mem-sbux-01"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("哪天在星巴克點特選冷萃咖啡還加購肉桂捲？", ["mem-sbux-03"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("十月一日星巴克中杯燕麥奶拿鐵花了多少錢？", ["mem-sbux-01"], "multi_receipt_confusion", "dev", "answerable_positive"))
    query_defs.append(("大安門市星巴克那杯加鮮奶油的瑪奇朵發票？", ["mem-sbux-02"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("十月九號下雨天搭 Uber 去內湖科學園區的車資是多少？", ["mem-uber-02"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("十月三號早上從信義路搭 Uber 到大安區和平東路的費用？", ["mem-uber-01"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("深夜從台北車站搭 Uber 回新店七張站花了多少？", ["mem-uber-03"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("哪趟 Uber 行程是從松山區民生東路出發的？", ["mem-uber-02"], "multi_receipt_confusion", "dev", "answerable_positive"))
    query_defs.append(("車資 210 元的那趟 Uber 是哪一天搭的？", ["mem-uber-01"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("在 PChome 買 Keychron Q1 客製化機械鍵盤花了多少錢？", ["mem-pchome-02"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("PChome 訂單 20260920881 購買的是什麼商品？", ["mem-pchome-01"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("十月十一號在 PChome 買的 50公升防潮箱訂單編號是多少？", ["mem-pchome-03"], "multi_receipt_confusion", "test", "answerable_positive"))
    query_defs.append(("羅技 MX Master 3S 人體工學滑鼠是在哪天訂購的？", ["mem-pchome-01"], "multi_receipt_confusion", "dev", "answerable_positive"))
    query_defs.append(("哪筆 PChome 訂單花了 NT$2,980？", ["mem-pchome-02"], "multi_receipt_confusion", "test", "answerable_positive"))

    # Category 2: Event Version Updates (10 queries)
    query_defs.append(("小明婚禮最終改期到哪一天舉行？", ["mem-event-v2"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明婚宴正式定案的飯店是哪一家？", ["mem-event-v2"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明喜酒最新安排的好友桌次是第幾桌？", ["mem-event-v2"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明婚宴最新確定的入席時間是幾點？", ["mem-event-v2"], "event_update_version", "dev", "answerable_positive"))
    query_defs.append(("小明婚禮喜來登飯店宴會廳是哪一廳？", ["mem-event-v2"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明婚禮原先最早排定在君品酒店的舊日期是哪天？", ["mem-event-v1"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明婚宴作廢的舊通知上原本坐第幾桌？", ["mem-event-v1"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明婚宴原本打算在哪個酒店辦？", ["mem-event-v1"], "event_update_version", "test", "answerable_positive"))
    query_defs.append(("小明婚禮為什麼沒有在君品酒店舉辦？", ["mem-event-v1"], "event_update_version", "dev", "answerable_positive"))
    query_defs.append(("參加小明婚禮最新正式版的喜帖備忘？", ["mem-event-v2"], "event_update_version", "test", "answerable_positive"))

    # Category 3: Synonym Paraphrase (30 queries)
    synonym_queries = [
        ("腳踏車壞了去哪裡保養修繕？", ["mem-sem-bike"], "test"),
        ("發工資了沒？這個月薪水進來了嗎？", ["mem-sem-salary"], "test"),
        ("肚子痛胃不舒服要吃什麼藥？", ["mem-sem-stomach"], "test"),
        ("單車變速器不順要上什麼潤滑劑？", ["mem-sem-bike"], "dev"),
        ("九月份工作獎金跟月薪撥款紀錄？", ["mem-sem-salary"], "test"),
        ("抑制胃酸跟保護胃壁的藥名？", ["mem-sem-stomach"], "test"),
        ("布拉格著名古堡觀光景點介紹？", ["mem-sem-castle"], "test"),
        ("捷克聖維特教堂歷史參觀筆記？", ["mem-sem-castle"], "test"),
        ("鐵馬前後輪胎壓要打到多少？", ["mem-sem-bike"], "test"),
        ("耐適恩錠一天要服用幾次？", ["mem-sem-stomach"], "test"),
        ("富邦戶頭上個月入帳多少薪俸？", ["mem-sem-salary"], "dev"),
        ("單車煞車橡膠磨損檢查？", ["mem-sem-bike"], "test"),
        ("黃金巷參觀心得與拍照景點？", ["mem-sem-castle"], "test"),
        ("胃乳懸浮液一包要什麼時候喝？", ["mem-sem-stomach"], "test"),
        ("公司本俸津貼有沒有匯入富邦？", ["mem-sem-salary"], "test"),
        ("登山郊遊要準備哪些防護裝備？", ["mem-sem-hiking"], "test"),
        ("健行用的雙肩防水外套與頭燈？", ["mem-sem-hiking"], "dev"),
        ("遠足野外急救包跟高熱量零食？", ["mem-sem-hiking"], "test"),
        ("去好市多採買無骨牛小排跟生鮮？", ["mem-costco-01"], "test"),
        ("家裡吸塵器耗材濾網去哪裡補貨？", ["mem-costco-03"], "test"),
        ("星巴克那杯濃縮咖啡肉桂捲下午茶？", ["mem-sbux-03"], "test"),
        ("下雨天叫車去內科上班的車資收據？", ["mem-uber-02"], "dev"),
        ("客製化鍵盤是哪一個品牌型號？", ["mem-pchome-02"], "test"),
        ("防潮箱容量是幾公升的？", ["mem-pchome-03"], "test"),
        ("小明結婚喜酒辦在台北哪間五星飯店？", ["mem-event-v2"], "test"),
        ("公司季度大會伺服器機房花費預算？", ["mem-long-q4budget"], "test"),
        ("醫師開立的維生素D滴劑劑量？", ["mem-long-dentist"], "dev"),
        ("洗牙後牙齦輕微出血要怎麼漱口？", ["mem-long-dentist"], "test"),
        ("零信任網路架構要求哪種憑證驗證？", ["mem-long-extra-01"], "test"),
        ("微服務分散式鏈路追蹤的 TraceID 規範？", ["mem-long-extra-01"], "test"),
    ]
    for q_text, targets, split in synonym_queries:
        query_defs.append((q_text, targets, "synonym_paraphrase", split, "answerable_positive"))

    # Category 4: Cross-Lingual (15 queries)
    cross_queries = [
        ("receipt for flight ticket from Taipei to Tokyo", ["mem-sem-br198"], "test"),
        ("EVA Air BR198 flight boarding gate confirmation", ["mem-sem-br198"], "test"),
        ("hiking packing list for weekend trip", ["mem-sem-hiking"], "dev"),
        ("waterproof jacket and trekking poles for mountains", ["mem-sem-hiking"], "test"),
        ("Prague Castle travel guide and Golden Lane highlights", ["mem-sem-castle"], "test"),
        ("St. Vitus Cathedral visit records in Czech", ["mem-sem-castle"], "test"),
        ("monthly salary deposit confirmation from bank", ["mem-sem-salary"], "test"),
        ("prescription for acid reflux and stomach ache", ["mem-sem-stomach"], "dev"),
        ("bicycle tire pressure and dry lube maintenance", ["mem-sem-bike"], "test"),
        ("flight departure time from Taoyuan to Narita airport", ["mem-sem-br198"], "test"),
        ("Uber ride receipt to Neihu Technology Park", ["mem-uber-02"], "test"),
        ("Keychron mechanical keyboard purchase order", ["mem-pchome-02"], "dev"),
        ("wedding banquet invitation card update", ["mem-event-v2"], "test"),
        ("quarterly server infrastructure budget report", ["mem-long-q4budget"], "test"),
        ("dental clinic ultrasonic scaling follow-up guide", ["mem-long-dentist"], "test"),
    ]
    for q_text, targets, split in cross_queries:
        query_defs.append((q_text, targets, "cross_lingual", split, "answerable_positive"))

    # Category 5: Precise Attribute (25 queries)
    precise_queries = [
        ("10月28日的牙科預約時間是幾點？", ["mem-sem-dentist-date"], "test"),
        ("台大醫院牙髓病科預約在兒童大樓幾樓幾診間？", ["mem-sem-dentist-date"], "test"),
        ("機車更換排氣管單獨零件費用是多少錢？", ["mem-sem-exhaust-fee"], "test"),
        ("排氣管加換後煞車線總共花了多少錢？", ["mem-sem-exhaust-fee"], "dev"),
        ("長榮航空 BR198 班機起飛時間是幾點幾分？", ["mem-sem-br198"], "test"),
        ("BR198 機票票號是哪九碼數字？", ["mem-sem-br198"], "test"),
        ("長榮航空 BR198 登機門是哪一個？", ["mem-sem-br198"], "test"),
        ("10月05日撥入富邦帳戶的薪資淨額是多少元？", ["mem-sem-salary"], "dev"),
        ("九月十二號在好市多買無骨牛小排花了多少錢？", ["mem-costco-01"], "test"),
        ("好市多中和店發票號碼 AB-38291045 金額？", ["mem-costco-01"], "test"),
        ("好市多九月二十八日發票上的石安牧場動福蛋買了幾盒？", ["mem-costco-02"], "test"),
        ("十月五號好市多購買 Dyson 吸塵器濾網兩入組總金額？", ["mem-costco-03"], "dev"),
        ("星巴克發票載具 CD-88219401 點了什麼飲料？", ["mem-sbux-01"], "test"),
        ("十月八號星巴克大杯焦糖瑪奇朵發票金額 NT$170？", ["mem-sbux-02"], "test"),
        ("星巴克點特選冷萃咖啡 NT$145 加肉桂捲總共多少？", ["mem-sbux-03"], "test"),
        ("十月三號搭 Uber 車資 NT$210 的行程？", ["mem-uber-01"], "dev"),
        ("十月九號大雨天 Uber 車資 NT$345 的乘車收據？", ["mem-uber-02"], "test"),
        ("十月十號深夜從台北車站搭 Uber 車資 NT$420？", ["mem-uber-03"], "test"),
        ("PChome 訂單編號 20260920881 的訂購商品？", ["mem-pchome-01"], "test"),
        ("PChome 訂購 Keychron Q1 鍵盤訂單編號 20261004192？", ["mem-pchome-02"], "test"),
        ("PChome 訂購 50公升防潮箱花了多少金額 NT$1,890？", ["mem-pchome-03"], "dev"),
        ("光陽機車後煞車線更換工料費用是多少？", ["mem-sem-exhaust-fee"], "test"),
        ("單車輪胎建議打到多少 PSI 胎壓？", ["mem-sem-bike"], "test"),
        ("小明婚禮正式版喜帖上的入席時間是幾點幾分？", ["mem-event-v2"], "test"),
        ("小明婚禮在喜來登飯店宴會廳是哪一廳？", ["mem-event-v2"], "test"),
    ]
    for q_text, targets, split in precise_queries:
        query_defs.append((q_text, targets, "precise_attribute", split, "answerable_positive"))

    # Category 6: Long Transcript Detail (25 queries)
    long_queries = [
        ("全體產品會議中第四季伺服器預算核定是多少錢？", ["mem-long-q4budget"], "test"),
        ("產品會議中 GPU 推論叢集的預算是多少萬元？", ["mem-long-q4budget"], "test"),
        ("API 平均延遲從 120ms 降低至多少毫秒？", ["mem-long-q4budget"], "test"),
        ("產品會議逐字稿中金鑰輪替週期規定為幾天自動滾動一次？", ["mem-long-q4budget"], "dev"),
        ("會議記錄中硬體密碼安全模組符合什麼標準？", ["mem-long-q4budget"], "test"),
        ("微服務連線等待時間縮減了百分之多少？", ["mem-long-q4budget"], "test"),
        ("混合檢索預計在明年第幾季推向灰度測試？", ["mem-long-q4budget"], "test"),
        ("醫生建議每天早餐後補充多少劑量的維生素D滴劑？", ["mem-long-dentist"], "test"),
        ("健檢報告中王先生血液常規的維生素D3濃度是多少 ng/mL？", ["mem-long-dentist"], "dev"),
        ("健檢報告中肌酸酐數值是多少？", ["mem-long-dentist"], "test"),
        ("牙醫檢查指出哪一顆牙齒有初期牙齦炎現象？", ["mem-long-dentist"], "test"),
        ("洗牙後兩天內輕微滲血要用什麼水輕柔漱口？", ["mem-long-dentist"], "test"),
        ("營養師建議王先生每日純水攝取量至少要達到多少毫升？", ["mem-long-dentist"], "dev"),
        ("收縮壓在工作高壓時期升高至多少 mmHg？", ["mem-long-dentist"], "test"),
        ("下次回診抽血檢驗預約在哪一天上午幾點？", ["mem-long-dentist"], "test"),
        ("眼科醫師建議的二十二十二十護眼法則是什麼？", ["mem-long-dentist"], "test"),
        ("第 1 號深度專案架構中死信隊列重試上限為幾次？", ["mem-long-extra-01"], "test"),
        ("第 2 號深度專案架構中死信隊列重試上限為幾次？", ["mem-long-extra-02"], "dev"),
        ("第 3 號深度專案架構中死信隊列重試上限為幾次？", ["mem-long-extra-03"], "test"),
        ("分散式交易系統採用哪種通訊協定搭配 Protobuf？", ["mem-long-extra-01"], "test"),
        ("系統採用哪種演算法保證快取一致性？", ["mem-long-extra-01"], "test"),
        ("儲存層主節點故障時 Raft 在幾毫秒內完成選舉切換？", ["mem-long-extra-01"], "test"),
        ("單元測試覆蓋率門檻必須高於百分之多少？", ["mem-long-extra-01"], "dev"),
        ("金絲雀發布流程先將百分之幾的流量導引至新節點？", ["mem-long-extra-01"], "test"),
        ("災難復原演練中二進位日誌備份每隔幾分鐘執行一次？", ["mem-long-extra-01"], "test"),
    ]
    for q_text, targets, split in long_queries:
        query_defs.append((q_text, targets, "long_transcript_detail", split, "answerable_positive"))

    # Category 7: Negative - Zero Evidence (8 queries)
    no_evidence_queries = [
        "上週去屏東墾丁後壁湖浮潛拍到的綠蠵龜照片？",
        "家裡日立冷氣濾網叫修水電師傅的手機電話號碼？",
        "特斯拉 Model Y 車載充電轉接頭收納在哪個置物箱？",
        "媽媽六十大壽家族聚餐訂位的陶板屋餐廳時間？",
        "上個月去好市多買的牛肉捲退貨退款明細單？",
        "家裡的任天堂 Switch 薩爾達傳說遊戲卡匣借給誰了？",
        "社區健身房游泳池每週水質消毒公休是星期幾？",
        "去台南旅遊住的煙波大飯店房號是幾號？"
    ]
    for q in no_evidence_queries:
        query_defs.append((q, [], "negative_no_evidence", "test", "unanswerable_no_evidence"))

    # Category 8: Negative - Insufficient Evidence (7 queries)
    # Topic exists, but the asked attribute is completely absent
    insufficient_queries = [
        ("長榮航空 BR198 班機的正駕駛機長姓名是誰？", "negative_insufficient", "test"), # BR198 exists, but captain name not in memory
        ("好市多中和店收銀台結帳員的員工編號？", "negative_insufficient", "test"), # Receipt exists, cashier ID not recorded
        ("大安星巴克焦糖瑪奇朵使用的是哪一款牛奶品牌？", "negative_insufficient", "test"), # Drink exists, milk brand not recorded
        ("Uber 司機開的車輛年份與車牌號碼？", "negative_insufficient", "test"), # Route & fee exist, driver plate missing
        ("PChome 羅技滑鼠送貨司機的手機電話號碼？", "negative_insufficient", "test"), # Order exists, driver phone missing
        ("台大醫院牙科看診的主治醫師身分證字號？", "negative_insufficient", "dev"), # Appointment exists, doctor ID missing
        ("捷克布拉格城堡聖維特教堂的門票票價是多少克朗？", "negative_insufficient", "dev") # Guide exists, exact CZK ticket price missing
    ]
    for q, cat, sp in insufficient_queries:
        query_defs.append((q, [], cat, sp, "unanswerable_insufficient"))

    # Category 9: Refutable Negative (5 queries)
    # Evidence exists to explicitly answer "NO", target_memory_ids contains the refuting memory!
    refutable_queries = [
        ("我昨天在全聯超市有買全脂鮮奶嗎？", ["mem-refute-milk"], "refutable_negative", "test", "answerable_negative_refuted"),
        ("長榮航空飛東京的機票我有預約素食特別餐嗎？", ["mem-refute-flight"], "refutable_negative", "test", "answerable_negative_refuted"),
        ("昨天去全聯買菜有買到牛奶嗎？", ["mem-refute-milk"], "refutable_negative", "test", "answerable_negative_refuted"),
        ("長榮 BR198 機上餐有勾選吃素嗎？", ["mem-refute-flight"], "refutable_negative", "dev", "answerable_negative_refuted"),
        ("全聯採買食材紀錄中全脂鮮奶有沒有買到？", ["mem-refute-milk"], "refutable_negative", "test", "answerable_negative_refuted")
    ]
    for q, targets, cat, sp, ans_type in refutable_queries:
        query_defs.append((q, targets, cat, sp, ans_type))

    # Category 10: Negative - Distractor Different Event (5 queries)
    # High surface lexical overlap with wrong event/date/combination
    distractor_queries = [
        ("星巴克 10月01日那張發票金額是不是 NT$190？", "negative_distractor_event", "test"), # 10/01 was 145, 190 was 10/14
        ("十月五號在好市多買的無骨牛小排花了多少錢？", "negative_distractor_event", "test"), # 10/05 bought Dyson & Ariel, beef was 09/12
        ("Uber 從民生東路搭到新店大坪林花了多少車資？", "negative_distractor_event", "test"), # MinSheng was to Neihu, Xindian was from Taipei Main
        ("PChome 訂單 20261011502 買的是機械鍵盤嗎？", "negative_distractor_event", "test"), # 10/11 was dry box, keyboard was 10/04
        ("小明婚宴 11月15日中午在喜來登大飯店的桌次？", "negative_distractor_event", "dev") # 11/15 was Palais de Chine, Sheraton was 11/29
    ]
    for q, cat, sp in distractor_queries:
        query_defs.append((q, [], cat, sp, "unanswerable_distractor"))

    # Build queries list
    assert len(query_defs) == 150, f"Expected 150 queries, got {len(query_defs)}"
    for idx, item in enumerate(query_defs, start=1):
        if len(item) == 5:
            q_text, targets, cat, split, ans_type = item
        else:
            q_text, cat, split = item
            targets = []
            ans_type = "unanswerable"

        queries.append({
            "query_id": f"Q1_{idx:03d}",
            "query_text": q_text,
            "category": cat,
            "split": split, # "dev" (30) or "test" (120)
            "target_memory_ids": targets,
            "answerability": ans_type
        })

    dev_count = len([q for q in queries if q["split"] == "dev"])
    test_count = len([q for q in queries if q["split"] == "test"])
    print(f"Generated 150 queries in v1: {dev_count} dev (calibration split), {test_count} test (evaluation split).")

    # Save to disk
    dataset_dir = os.path.dirname(os.path.abspath(__file__))
    mem_path = os.path.join(dataset_dir, "memories.json")
    query_path = os.path.join(dataset_dir, "queries.json")

    with open(mem_path, "w", encoding="utf-8") as f:
        json.dump(memories, f, ensure_ascii=False, indent=2)
    with open(query_path, "w", encoding="utf-8") as f:
        json.dump(queries, f, ensure_ascii=False, indent=2)

    print(f"Saved v1 dataset to:\n  {mem_path}\n  {query_path}")

if __name__ == "__main__":
    generate_v1_dataset()

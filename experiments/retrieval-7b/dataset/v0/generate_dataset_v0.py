#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cayana Stage 7B-1: Benchmark Dataset Generator
Generates 2,000 synthetic, realistic daily memories and 150 independently annotated queries.
Guarantees 100% deterministic reproducibility using random.seed(42).
No real private data is used.
"""

import json
import os
import random
import time
from typing import Dict, List, Any

SEED = 42
random.seed(SEED)

def generate_benchmark_dataset():
    print("[1/3] Generating 2,000 realistic synthetic memories...")
    
    memories: List[Dict[str, Any]] = []
    queries: List[Dict[str, Any]] = []
    
    # Base timestamp: 2026-10-15 12:00:00 UTC
    base_ts = 1792065600000 
    day_ms = 86400000

    # -------------------------------------------------------------------------
    # Curated Seed Data for the 150 Evaluation Queries
    # -------------------------------------------------------------------------
    # We will generate specific anchor memories corresponding to our test queries across categories.
    
    # 1. Lexical Exact Matches (25 targets)
    lexical_seeds = [
        ("mem-lex-01", "SCREENSHOT", "捷克布拉格城堡旅遊全攻略", "親自造訪捷克布拉格城堡，聖維特大教堂與黃金巷遊覽全紀錄與重要歷史介紹。", "布拉格城堡的旅遊攻略"),
        ("mem-lex-02", "SCREENSHOT", "長榮航空機票收據 BR198", "長榮航空 BR198 台北桃園至東京成田 機票票號 123456789，出發時間 08:50。", "BR198機票收據"),
        ("mem-lex-03", "PHOTO", "阿基師川菜館菜單價目表", "宮保雞丁 280元，麻婆豆腐 220元，水煮牛肉 380元，白飯每碗 20元。", "阿基師川菜館菜單價目表"),
        ("mem-lex-04", "RECORDING", "每週前端技術架構會議記錄", "本次會議討論 React 19 Server Components 升級方案與效能瓶頸評估。", "前端技術架構會議記錄"),
        ("mem-lex-05", "SHARED_TEXT", "台北榮民總醫院神經內科掛號須知", "門診時間每週二上午九點，初診病患請於上午八點半前至批價櫃檯報到。", "榮總神經內科掛號須知"),
        ("mem-lex-06", "SHARED_URL", "GitHub 專案 Cayana 官方文件", "https://github.com/cayana-org/cayana - 本機優先的記憶檢索與個人資訊助手。", "GitHub 專案 Cayana 官方文件"),
        ("mem-lex-07", "SCREENSHOT", "玉山銀行信用卡 9月份消費帳單", "本期應繳總金額 NT$18,450，繳款截止日 2026年10月05日，主要消費為超市與機票。", "玉山銀行信用卡 9月份消費帳單"),
        ("mem-lex-08", "PHOTO", "地下室停車位編號 B2-158", "地下二樓柱子標號 B2-158，靠近第3號客梯出口。", "停車位編號 B2-158"),
        ("mem-lex-09", "RECORDING", "日文檢定 N2 文法整理隨身錄音", "今日は N2 文法『～に際して』と『～に基づいて』の例文と使い方を復習します。", "日文檢定 N2 文法整理隨身錄音"),
        ("mem-lex-10", "SHARED_TEXT", "家常紅燒牛肉麵獨家食譜配方", "牛腱肉 600g，洋蔥一顆，老薑片，八角兩粒，辣豆瓣醬兩大匙，純釀醬油半碗，慢火燉煮兩小時。", "家常紅燒牛肉麵食譜配方"),
        ("mem-lex-11", "SCREENSHOT", "台灣高鐵訂票代碼 08552147", "訂票代碼 08552147，台北至左營，車次 135，發車時間 14:30，第 6 車廂 8A 號座位。", "高鐵訂票代碼 08552147"),
        ("mem-lex-12", "SHARED_URL", "Python asyncio 官方非同步指南", "https://docs.python.org/3/library/asyncio.html - 核心概念包含 Event Loop、Tasks、Coroutines 詳細實作。", "Python asyncio 官方非同步指南"),
        ("mem-lex-13", "PHOTO", "租屋合約書水電費拆算備忘", "電費每度夏季 6.5元、非夏季 5.5元；水費每月固定收取每人 150元。", "租屋合約書水電費拆算備忘"),
        ("mem-lex-14", "RECORDING", "年度健檢醫師衛教醫囑摘要", "空腹血糖 95 mg/dL 正常，低密度膽固醇 LDL 稍高 138 mg/dL，建議少油少鹽規律有氧運動。", "年度健檢醫師衛教醫囑摘要"),
        ("mem-lex-15", "SHARED_TEXT", "露營必備裝備清單完整版", "雙人充氣睡墊、四人帳篷、營槌、LED露營燈、瓦斯爐、擋風板、保溫箱、防蚊液。", "露營必備裝備清單完整版"),
        ("mem-lex-16", "SCREENSHOT", "Uber 收據行程明細 NT$385", "行程自台北市信義路五段至大安區和平東路二段，車程 18 分鐘，費用 NT$385。", "Uber 收據行程明細 NT$385"),
        ("mem-lex-17", "PHOTO", "辦公室 Wi-Fi 訪客密碼牌", "SSID: Cayana-Guest, Password: WelcomeSpring2026, 每日午夜自動重新整理租期。", "辦公室 Wi-Fi 訪客密碼牌"),
        ("mem-lex-18", "RECORDING", "小提琴音階練習錄音紀錄", "G大調三八度音階練習，第三把位換把音準稍嫌偏高，弓速需保持均勻流暢。", "小提琴音階練習錄音紀錄"),
        ("mem-lex-19", "SHARED_URL", "內政部役政署出境申請須知", "https://dca.moi.gov.tw - 役男出境線上核准系統申請步驟與效期一個月規範說明。", "內政部役政署出境申請須知"),
        ("mem-lex-20", "SCREENSHOT", "蝦皮購物訂單編號 261010-SP4891", "商品：人體工學垂直滑鼠，出貨狀態：已抵達 7-11 信義門市，取件代碼 4891。", "蝦皮購物訂單編號 261010-SP4891"),
        ("mem-lex-21", "SHARED_TEXT", "家裡大門電子鎖密碼修改備忘", "原管理員密碼 888888，新設定使用者開門密碼為 952701#，備用指紋已重新錄入。", "電子鎖密碼修改備忘"),
        ("mem-lex-22", "PHOTO", "汽車定保維修工單明細表", "更換 5W-30 全合成機油 4罐、機油芯、煞車油抽換、前輪對調動態平衡，總計 NT$4,600。", "汽車定保維修工單明細表"),
        ("mem-lex-23", "RECORDING", "吉他彈唱自創曲 Demo", "木吉他標準調音，前奏使用 C - G/B - Am7 - Fadd9 和弦進行，速度 72 BPM。", "吉他彈唱自創曲 Demo"),
        ("mem-lex-24", "SHARED_URL", "Docker 官方生產環境優化手冊", "https://docs.docker.com/production/ - 多階段構建 (multi-stage builds) 與 rootless 容器安全指引。", "Docker 官方生產環境優化手冊"),
        ("mem-lex-25", "SCREENSHOT", "Apple 官網訂單 MacBook Pro 16", "訂單編號 W98124501，MacBook Pro 16吋 M4 Max 晶片 36GB 記憶體，預計 10月24日送達。", "Apple 官網訂單 MacBook Pro 16")
    ]

    # 2. Synonym & Paraphrase Matches (35 targets)
    # The query uses words that do NOT literally match the text, testing semantic understanding!
    synonym_seeds = [
        ("mem-syn-01", "SHARED_TEXT", "自行車定期檢修項目手記", "檢查單車前後變速器張力，鏈條上專用乾式潤滑油，輪胎胎壓維持 100 PSI，煞車皮厚度正常。", "腳踏車壞了去哪裡保養修繕？"),
        ("mem-syn-02", "SCREENSHOT", "富邦綜合帳戶薪資入帳通知", "您的薪轉帳號於 10月05日 撥入薪資款項 NT$78,500，摘要：九月份本俸及津貼。", "發工資了沒？這個月薪水進來了嗎？"),
        ("mem-syn-03", "PHOTO", "腸胃科診所給藥明細收據", "普拿疼一顆退燒止痛，耐適恩錠 (Nexium) 一天一次修復胃黏膜抑制胃酸，胃乳懸浮液一包。", "肚子痛胃不舒服要吃什麼藥？"),
        ("mem-syn-04", "RECORDING", "客廳裝潢選色與建材洽談音檔", "設計師建議電視牆採用淺灰清水模塗料，地板鋪設進口耐磨木紋地板，採光更柔和溫暖。", "新家客廳地板想鋪什麼材質？"),
        ("mem-syn-05", "SCREENSHOT", "日本旅遊換匯匯率確認單", "台灣銀行線上換匯，購入日圓現鈔 150,000 円，成交匯率 0.2145，扣繳新台幣 32,175 元。", "去日本玩換日幣便宜的匯率是多少？"),
        ("mem-syn-06", "SHARED_TEXT", "貓咪日常照護與飲食指引", "成貓每日飲水量至少需達 200ml，主食罐頭搭配無穀乾糧，每週清理一次貓砂盆並更換。", "家裡寵物每天要喝多少水才夠？"),
        ("mem-syn-07", "SHARED_URL", "台北市立美術館當期展覽介紹", "https://tfam.museum/exhibit/2026 - 展出二十世紀現代藝術巨作與當代裝置互動藝術，展期至十一月底。", "北美館最近有什麼好看的藝術展？"),
        ("mem-syn-08", "SCREENSHOT", "健身房私教課堂數剩餘紀錄", "體能教練指導深蹲與硬舉姿勢矯正，剩餘合約課程堂數 8 堂，有效期限至今年年底。", "健身課重訓還剩下幾堂課沒上？"),
        ("mem-syn-09", "PHOTO", "家庭常備急救箱藥品有效期限", "優碘藥水 2027年過期，滅菌紗布五包，止血繃帶兩捲，小護士曼秀雷敦軟膏全新未拆。", "急救包裡面的消毒藥水放過期了嗎？"),
        ("mem-syn-10", "RECORDING", "咖啡拉花與手沖水溫筆記", "淺焙耶加雪菲使用 91度水溫慢速注水，粉水比 1:15，萃取時間 2分15秒，柑橘果香明顯。", "沖泡淺焙咖啡豆水溫要幾度最適當？"),
        ("mem-syn-11", "SHARED_TEXT", "筆記型電腦螢幕擦拭清潔秘訣", "請勿使用酒精或揮發性溶劑擦拭鍍膜螢幕，使用超細纖維布微沾蒸餾水輕柔擦拭即可。", "電腦液晶螢幕髒了可以用酒精消毒擦乾淨嗎？"),
        ("mem-syn-12", "SCREENSHOT", "台灣電力公司電子帳單繳費憑證", "用電地址：台北市松山區民生東路，本期應繳電費 NT$2,140，扣款帳戶已順利扣繳成功。", "這個月家裡的電費繳了多少錢？"),
        ("mem-syn-13", "PHOTO", "社區大樓包裹領取條碼單", "包裹存放在管理室冷藏冰箱，寄件人為老天祿滷味，取件驗證碼 8520，請於三日內領取。", "大樓管理員那邊有沒有我的冷藏快遞？"),
        ("mem-syn-14", "RECORDING", "房東退租屋況檢查叮嚀語音", "退房當天需將全室清潔乾淨，冷氣濾網清洗乾淨晾乾，鑰匙兩串與門禁感應扣需全數歸還。", "退租房子搬走的時候需要還幾副鑰匙？"),
        ("mem-syn-15", "SHARED_URL", "台灣登山步道難度分級指南", "https://hiking.taiwan.gov.tw - 步道分級為第一級至第五級，初學者建議選擇第一級郊山休閒路線。", "剛開始爬山新手適合走哪種健行路線？"),
        ("mem-syn-16", "SCREENSHOT", "Netflix 方案異動確認信件", "您已成功將家庭高級方案切換為標準方案（雙螢幕 1080p），次月月費調整為 NT$330。", "影音串流平台降級方案每個月扣多少？"),
        ("mem-syn-17", "SHARED_TEXT", "植物多肉盆栽澆水照顧原則", "春秋兩季土乾透再澆透，夏季休眠期需嚴格控水避免爛根，置於通風半日照陽台最佳。", "多肉小盆栽夏天怎麼照顧才不會死掉爛掉？"),
        ("mem-syn-18", "PHOTO", "機車排氣定期檢驗合格標籤", "檢驗年份 115年度，檢驗合格代碼 A，CO 排放量 0.8% 符合環保法規第四期標準。", "摩托車今年的排氣檢查過了嗎？"),
        ("mem-syn-19", "RECORDING", "自製麵包天然酵母起種心得", "全麥麵粉與溫水比例 1:1，放置室溫發酵 24小時，第四天冒出均勻細緻氣泡且帶有微酸香氣。", "做手工酸種麵包起種要發酵幾天？"),
        ("mem-syn-20", "SCREENSHOT", "國泰世華定期定額存股明細", "每月 6日扣款 NT$10,000 買入 0050 台灣卓越50 ETF，累計持股單位 1,250 股。", "每個月定期買台灣五十ETF扣款哪一天？"),
        ("mem-syn-21", "SHARED_TEXT", "出國隨身行李液體管制限制", "單瓶液體容積不得超過 100ml，所有容器需裝入 1公升透明夾鏈塑膠袋內，每人限帶一袋。", "搭飛機隨身包包帶乳液保養品容量規定？"),
        ("mem-syn-22", "PHOTO", "洗衣機槽清洗劑使用步驟", "將整瓶清潔劑倒入洗衣槽，注滿高水位運轉 5分鐘後靜置浸泡 3小時，再啟動一般洗衣行程排空。", "洗洗衣機內膽要浸泡多久時間？"),
        ("mem-syn-23", "RECORDING", "牙醫師叮嚀智齒拔除術後保養", "咬緊紗布一小時勿吐口水，二十四小時內禁止使用吸管，不可漱口或劇烈漱口，兩天內冰敷止腫。", "拔完智齒之後可以拿吸管喝飲料嗎？"),
        ("mem-syn-24", "SHARED_URL", "行政院環境保護署大型廢棄物回收", "https://recycle.moenv.gov.tw - 舊床墊與大沙發需提前一日撥打清潔隊電話預約登記清運。", "不要的大型舊家具廢棄物要怎麼丟？"),
        ("mem-syn-25", "SCREENSHOT", "好市多線上購物會員續約扣款", "Costco 金星主卡會員年費 NT$1,350，自動續約扣款成功，有效期限延長至 2027年10月。", "美式大賣場會員卡續約一年要交多少錢？"),
        ("mem-syn-26", "SHARED_TEXT", "煎牛排五分熟火候掌控祕訣", "厚切肋眼牛排回溫三十分鐘，鑄鐵鍋大火熱鍋至冒煙，每面各煎兩分鐘後起鍋靜置五分鐘鎖肉汁。", "在家煎牛排要煎幾分鐘才不會太熟太硬？"),
        ("mem-syn-27", "PHOTO", "輪胎胎紋深度磨耗警示標誌", "胎面溝槽深度磨損至 1.6mm 安全指示線時必須立即更換新胎，避免雨天高速行駛打滑水漂。", "汽車輪胎磨到什麼程度一定要換新？"),
        ("mem-syn-28", "RECORDING", "睡眠品質改善醫師建議", "睡前一小時請關閉手機等藍光螢幕，臥室溫度維持 24度，固定作息不要熬夜補充褪黑激素。", "失眠睡不著醫生建議睡前不要做什麼？"),
        ("mem-syn-29", "SCREENSHOT", "星巴克隨行卡儲值紅利點數", "目前金星級點數累積 85 顆星星，每 35 顆星星可兌換中杯飲品一杯，年底前需兌換完畢。", "星巴克集點卡裡面的星星可以換什麼？"),
        ("mem-syn-30", "SHARED_TEXT", "被蜜蜂或蚊蟲叮咬緊急處置", "被蜂螫請用信用卡邊緣輕輕刮除螫針，切勿用手指擠壓毒囊，以清水肥皂洗淨後冰敷患部。", "被毒蜂叮到要怎麼把刺拔出來？"),
        ("mem-syn-31", "PHOTO", "羽絨外套手洗保養注意事項", "請使用中性溫和洗衣精，冷水輕壓手洗勿浸泡，脫水後平鋪陰乾，用手輕拍拍鬆結塊羽絨。", "冬天羽絨衣可以丟洗衣機用力洗嗎？"),
        ("mem-syn-32", "RECORDING", "日式柴魚高湯熬煮黃金比例", "昆布 10g 泡冷水一小時，小火煮至快沸騰撈起，熄火加入柴魚片 20g 靜置兩分鐘過濾取湯汁。", "煮日式高湯昆布要在什麼時候拿起來？"),
        ("mem-syn-33", "SCREENSHOT", "Google One 雲端儲存空間使用率", "已使用 85GB / 100GB (85%)，主要空間佔用為 Google 相簿原始畫質備份與大型雲端硬碟檔案。", "我的 Google 雲端硬碟容量快滿了嗎？"),
        ("mem-syn-34", "SHARED_TEXT", "保溫瓶異味去除清潔妙招", "瓶內注入溫水加入一大匙食用小蘇打粉或過碳酸鈉，靜置半小時後以軟海綿刷洗即可消除茶垢。", "保溫杯裝咖啡有臭味要用什麼洗乾淨？"),
        ("mem-syn-35", "PHOTO", "室內盆栽黃葉枯萎原因排查", "底盤積水導致根系缺氧腐爛，應倒掉底盤多餘水分，移至通風處等待土壤表面乾燥後再澆水。", "客廳養的綠色植物葉子發黃是為什麼？")
    ]

    # 3. Cross-lingual Matches (20 targets)
    crosslingual_seeds = [
        ("mem-cl-01", "SCREENSHOT", "長榮航空台北飛東京電子機票收據", "搭乘長榮航空 BR198 班機，由台北松山飛往東京羽田，預計飛行時間 2小時50分。", "receipt for flight ticket from Taipei to Tokyo"),
        ("mem-cl-02", "SHARED_TEXT", "Weekend Hiking Gear Checklist", "Waterproof jacket, trekking poles, 2L water bladder, trail mix snacks, first aid kit, headlamp.", "週末登山健行必備物品清單"),
        ("mem-cl-03", "PHOTO", "日本東京晴空塔門票預約證明", "東京スカイツリー展望台 日期：2026年11月10日，入場時間 15:30，成人票 2張。", "Tokyo Skytree observation deck ticket reservation"),
        ("mem-cl-04", "SHARED_TEXT", "How to fix git merge conflicts easily", "Use git checkout --ours or git checkout --theirs to accept specific branch changes, then git add and commit.", "如何解決 git 分支合併衝突"),
        ("mem-cl-05", "SCREENSHOT", "台北萬豪酒店客房住宿確認單", "入住日期 2026年12月24日，房型經典客房大床，含雙人自助早餐，預訂編號 TPE-88419。", "Taipei Marriott Hotel room reservation confirmation"),
        ("mem-cl-06", "SHARED_URL", "Recipe for Traditional Italian Carbonara", "Guanciale, fresh egg yolks, Pecorino Romano cheese, black pepper, spaghetti. No heavy cream allowed.", "道地義大利培根蛋麵做法食譜"),
        ("mem-cl-07", "RECORDING", "海外出差英語商業簡報演練", "Today I would like to introduce our Q4 product roadmap focusing on low-latency edge AI computing.", "第四季邊緣運算產品規劃英文簡報錄音"),
        ("mem-cl-08", "SCREENSHOT", "新光三越週年慶退稅服務處須知", "外籍旅客單日消費滿 NT$2,000 可至 B2 服務中心辦理現場小額退稅，手續費為 14%。", "tax refund counter instructions for foreign travelers"),
        ("mem-cl-09", "SHARED_TEXT", "Best practices for designing RESTful APIs", "Use HTTP verbs (GET, POST, PUT, DELETE), noun-based resource URLs, and proper HTTP status codes like 404 and 409.", "REST API 介面設計最佳實踐規範"),
        ("mem-cl-10", "PHOTO", "法式可頌麵包烘焙坊營業時間", "Boulangerie Artisanale 開店時間：週三至週日 07:30 - 18:00，週一週二公休。", "French croissant bakery opening hours"),
        ("mem-cl-11", "SCREENSHOT", "台灣高鐵商務車廂票根", "高鐵車次 0609，南港至台南，商務車廂免費供應熱咖啡與點心一份。", "Taiwan High Speed Rail business class ticket stub"),
        ("mem-cl-12", "SHARED_TEXT", "Home workout full body routine without equipment", "3 sets of 20 pushups, 30 bodyweight squats, 15 lunges each leg, and 60 seconds plank.", "在家徒手無器材全身訓練動作菜單"),
        ("mem-cl-13", "PHOTO", "日本京都清水寺御守說明書", "健康長壽御守，請隨身佩戴於包包中，一年後可送回本寺納札所化納焚燒祈福。", "Kiyomizu-dera temple health amulet instructions"),
        ("mem-cl-14", "SHARED_URL", "Guide to Setting Up Prometheus and Grafana", "Configure Prometheus scrape targets in prometheus.yml, import Grafana dashboard ID 1860 for node monitoring.", "如何安裝設定普羅米修斯與 Grafana 監控"),
        ("mem-cl-15", "SCREENSHOT", "好市多加油站發票收據", "中油聯名卡好市多加油站，95無鉛汽油 35公升，每公升折讓 3元，實付金額 NT$980。", "Costco gas station unleaded fuel receipt"),
        ("mem-cl-16", "SHARED_TEXT", "Essential rules for clean code in TypeScript", "Prefer interfaces over types for public APIs, avoid any type, enforce strictNullChecks in tsconfig.json.", "TypeScript 寫出乾淨程式碼的核心規範"),
        ("mem-cl-17", "RECORDING", "德文基礎發音日常問候會話", "Guten Morgen (早安), Wie geht es Ihnen? (您好嗎?), Auf Wiedersehen (再見)。", "German daily greetings conversation audio note"),
        ("mem-cl-18", "SCREENSHOT", "星宇航空豪華經濟艙行李額度通知", "托運行李額度每人兩件，每件限重 23公斤，手提隨身行李一件限重 7公斤。", "STARLUX Airlines premium economy baggage allowance"),
        ("mem-cl-19", "PHOTO", "美式煙燻豬肋排低溫慢烤溫度表", "Smoker temperature at 225°F (107°C) for 3 hours unwrapped, 2 hours wrapped with foil, 1 hour sauced.", "美式烤肉煙燻豬肋排溫度與時間設定"),
        ("mem-cl-20", "SHARED_URL", "Understanding SQLite WAL Mode Concurrency", "Write-Ahead Logging allows concurrent readers alongside a writer, improving transaction throughput significantly.", "SQLite WAL 模式並行寫入與讀取原理")
    ]

    # 4. Precise Attribute Matches (25 targets)
    # Questions ask for exact numbers, dates, addresses, IDs
    precise_seeds = [
        ("mem-pre-01", "SCREENSHOT", "台大醫院牙科門診預約通知單", "預約看診日期：2026年10月28日 下午 14:15，診間：兒童大樓四樓牙髓病科第三診間。", "10月28日的牙科預約時間是幾點？"),
        ("mem-pre-02", "SCREENSHOT", "機車排氣管更換維修估價單", "光陽機車原廠排氣管更換 NT$3,850，後煞車線更換 NT$450，總計工資與零件費用 NT$4,300。", "排氣管維修費用單獨是多少錢？"),
        ("mem-pre-03", "PHOTO", "信義路四段老宅咖啡廳名片", "咖啡廳店名：琥珀自烘咖啡，地址：台北市大安區信義路四段199巷12號，電話：02-2708-9911。", "信義路四段那間咖啡店地址在幾巷？"),
        ("mem-pre-04", "SCREENSHOT", "中華航空機票確認單 CI100", "班機號碼：CI100，台北桃園 (TPE) 至東京成田 (NRT)，起飛時間 08:55，登機門 D7。", "中華航空往東京的班機編號是多少？"),
        ("mem-pre-05", "SHARED_TEXT", "房東匯款銀行帳號確認簡訊", "房租轉帳資訊：國泰世華銀行 (代碼 013)，帳號 058-50-612349-8，戶名：陳建宏。", "房東國泰世華銀行的匯款帳號是多少？"),
        ("mem-pre-06", "SCREENSHOT", "中華電信光世代寬頻光纖月租費帳單", "客戶號碼 HN84920155，本期繳費金額 NT$999，合約速率 500M/500M 雙向對稱頻寬。", "光世代寬頻每個月月租費是多少？"),
        ("mem-pre-07", "PHOTO", "辦公室大樓總機電話與分機表", "總機代表號：02-8765-4321，資訊技術部主管分機 8102，人資部人事專員分機 6205。", "資訊技術部主管辦公室分機幾號？"),
        ("mem-pre-08", "SCREENSHOT", "路邊停車繳費通知單 8821-QR", "車牌號碼：ABC-1234，停車路段：和平東路二段，開單時間 11:42，應繳金額 NT$60。", "路邊停車費的繳費金額是多少？"),
        ("mem-pre-09", "RECORDING", "社區管委會第四季例會紀錄", "決議自 2026年11月01日起，管理費由每坪 80元調整為每坪 95元，公共用電分攤每戶固定 200元。", "管理費從幾月幾號開始調整成每坪95元？"),
        ("mem-pre-10", "SHARED_TEXT", "朋友新家喬遷暖居派對地址", "地址：新北市新店區央北二路88號12樓，社區名稱：央北品苑，一樓請按對講機 1201。", "朋友新家聚會門牌號碼是央北二路幾號？"),
        ("mem-pre-11", "SCREENSHOT", "全家便利商店店到店包裹寄件代碼", "寄件代碼：FM-93821045，收件人：李雅婷，取件門市：全家台中福星店，費用 NT$60。", "全家店到店包裹的寄件代碼是多少？"),
        ("mem-pre-12", "PHOTO", "冷氣機變頻馬達保固服務卡", "品牌：日立變頻冷暖空調，機型編號 RAS-28HK1，壓縮機保固 10年，全機零件保固 7年。", "冷氣機的壓縮機保固期是幾年？"),
        ("mem-pre-13", "SCREENSHOT", "台灣中油加油發票明細", "交易時間 2026-10-08 19:22，98無鉛汽油 42.1公升，單價 33.2元，發票號碼 FX-81920381。", "加油發票的八位數發票號碼是多少？"),
        ("mem-pre-14", "SHARED_TEXT", "家庭緊急聯絡人清單與醫院電話", "國泰綜合醫院急診室專線：02-2708-2121 分機 3119，救護車請撥 119。", "國泰醫院急診室專線分機是幾號？"),
        ("mem-pre-15", "SCREENSHOT", "PChome 24h 購物發票通知", "訂單編號 202610128910，購買防潮箱 NT$2,490，配送進度：已由黑貓宅急便出貨。", "PChome 買防潮箱花費多少金額？"),
        ("mem-pre-16", "PHOTO", "大樓地下室機械車位限高告示", "本機械停車塔限制車輛規格：車高限 155 公分，車寬限 185 公分，車重限 1,800 公斤。", "地下機械停車位限制車輛高度是多少公分？"),
        ("mem-pre-17", "RECORDING", "寵物醫院獸醫健檢叮嚀錄音", "黃金獵犬體重目前 28.5公斤，每個月15號需口服一顆全能狗S驅蟲藥，心絲蟲篩檢呈陰性。", "狗狗每個月幾號要吃全能狗驅蟲藥？"),
        ("mem-pre-18", "SHARED_TEXT", "公路自行車輪胎型號與氣壓設定", "輪胎規格：馬牌 GP5000 700x25C，前輪氣壓打 85 PSI，後輪氣壓打 90 PSI。", "公路車後輪輪胎氣壓要打多少 PSI？"),
        ("mem-pre-19", "SCREENSHOT", "好市多線上購物取件通知單", "取貨驗證碼：884192，取貨地點：好市多內湖店提貨中心，請出示會員卡與驗證碼領貨。", "好市多內湖店線上提貨驗證碼是多少？"),
        ("mem-pre-20", "PHOTO", "家庭電錶度數拍照記錄", "電錶指針數字讀數：14,892 度，抄表日期 2026年09月30日，比上期增加 418 度。", "九月底抄電錶的讀數是多少度？"),
        ("mem-pre-21", "SHARED_TEXT", "個人健康追蹤血壓測量日誌", "2026-10-14 早上 07:30，收縮壓 118 mmHg，舒張壓 76 mmHg，脈搏心跳每分鐘 68 下。", "10月14日早上測量的收縮壓是多少？"),
        ("mem-pre-22", "SCREENSHOT", "Apple 禮品卡兌換序號備忘", "App Store 禮品卡面額 NT$1,000，卡號代碼：X8K9-M2P4-Q7W1，已於昨日完成兌換入帳。", "Apple 禮品卡的兌換序號代碼是什麼？"),
        ("mem-pre-23", "PHOTO", "熱水器瓦斯管線更換標籤", "更換日期：2026年08月15日，施工技師證號：TG-09281，建議五年後 2031年檢查更換。", "瓦斯熱水器管線建議哪一年檢查更換？"),
        ("mem-pre-24", "RECORDING", "投資理財讀書會重點筆記", "講者分享資產配置核心比例：全球股票 ETF 佔 70%，美國公債 ETF 佔 30%，每半年再平衡一次。", "讀書會建議股票和公債資產配置比例是多少？"),
        ("mem-pre-25", "SHARED_TEXT", "親戚婚宴受邀請帖時間與桌次", "日期：2026年11月22日 星期日 晚間 18:30 入席，地點：台北晶華酒店三樓宴會廳，桌次：第 16 桌。", "11月22日晶華酒店婚宴我們坐在第幾桌？")
    ]

    # 5. Long Transcript Detail Matches (20 targets)
    # 1,200 ~ 2,000 chars transcripts with specific details embedded
    long_seeds = [
        ("mem-long-01", "RECORDING", "2026年度產品開發全體會議詳細逐字稿",
         "大家早安，今天召開產品架構季度審查會議。首先由技術長回顧第三季伺服器架構升級成果，我們的 API 平均延遲從 120ms 降低至 45ms。接著討論第四季雲端基礎設施預算，財務長確認第四季整體伺服器預算核定為新台幣 420 萬元整，其中包含 GPU 推論叢集的 180 萬元。另外在使用者體驗部分，針對搜尋推薦功能，產品團隊提出導入混合檢索機制，預計在明年第一季推向灰度測試。客服主管也補充，上個月使用者對於歷史回顧功能的反饋中，有超過百分之六十希望能自訂時間範圍。最後提醒大家，下週五之前請各組組長提交資安合規檢查報告。",
         "全體產品會議中第四季伺服器預算核定是多少錢？"),
        ("mem-long-02", "RECORDING", "家庭醫學科年度追蹤諮詢完整錄音",
         "王先生您好，請坐。我們來看一下這次的血液檢驗報告。整體來說肝腎功能都很漂亮，肌酸酐只有 0.9，肝指數 GOT 和 GPT 也都在 25 左右。不過在血液常規檢查中，維生素D3的濃度只有 18 ng/mL，低於標準值 30。因此我建議您每天早餐後固定補充高劑量維生素D滴劑 2,000 IU，持續補充三個月後再回診抽血追蹤。飲食方面，多攝取深海魚類例如鮭魚和秋刀魚，也可以適度曬曬早晨十點前的太陽。至於上次提到的胃食道逆流症狀，報告顯示胃幽門螺旋桿菌檢驗為陰性，不需要服用抗生素滅菌療程，只需要睡前三小時不要進食即可。",
         "醫生在錄音中建議每天早餐後補充多少劑量的維生素D？"),
        ("mem-long-03", "SHARED_TEXT", "公司內部技術架構演進白皮書摘要",
         "Cayana 核心系統從單體架構演進至微服務架構，經歷了三個主要里程碑。在第一階段，我們將關聯式資料庫全面拆分，將記憶本體存放於本機 SQLite，並透過 FTS4 全文檢索模組加速文字查詢。第二階段著眼於事件驅動架構，引入以 Coroutine StateFlow 為基礎的響應式管道。在快取策略方面，團隊經過壓力測試後，決定將本機快取最大記憶體上限設定為 128 MB，超出上限則採用 LRU 機制淘汰。這套機制成功讓記憶體崩潰率降低了百分之九十五，並且在低階安卓手機上依然能順暢運作。",
         "技術白皮書中本機快取最大記憶體上限設定為多少？"),
        ("mem-long-04", "RECORDING", "社區大樓住戶規約修訂研討會全程音檔",
         "各位住戶晚安，今天針對社區寵物飼養規約與地下停車場充電樁設置辦法進行討論。主席報告：關於寵物部分，經過管委會決議，進出電梯與公共大廳時，寵物必須全程配戴牽繩或使用推車籃，違者經勸導三次不改將通報動保處。第二個重點是地下室 B1 與 B2 增設電動車充電樁工程，總得標廠商為綠能科技，每位車主安裝獨立分表與私樁的初期建置規費固定為新台幣 28,000 元整，後續電費依照台電時間電價計費。會議在晚間九點四十分結束，感謝大家熱情參與。",
         "充電樁研討會中每位車主安裝私樁的初期建置規費是多少？"),
        ("mem-long-05", "RECORDING", "大學歷史系西洋現代史期末複習課錄音",
         "各位同學好，今天我們進行期末考最後的重點提示。請大家翻到第十二章，關於第一次世界大戰後的凡爾賽和約。特別注意和約簽署的時間是 1919年6月28日，地點在法國巴黎凡爾賽宮鏡廳。考試會考到和約中最具爭議的第 231 條款，也就是所謂的『戰爭罪責條款』，該條款要求德國必須承擔發動戰爭的全部責任與天價賠償金。接著翻到國際聯盟的建立，威爾遜總統提出的十四點和平原則中，第十四點正式確立了國聯的構想，但遺憾的是美國國會最終並未批准加入國聯。",
         "歷史課錄音中凡爾賽和約最具爭議的是第幾條款？"),
        ("mem-long-06", "SHARED_TEXT", "新進員工到職第一週行政手續指南",
         "歡迎加入 Cayana 團隊！請於到職日起三天內完成以下手續：首先至人資系統完成健保投保資料填寫；第二步至財務組提交薪轉戶存摺影本，本公司薪轉合作銀行為玉山銀行商業大樓分行；第三步領取員工識別證與門禁感應卡，請注意識別證遺失補發工本費為 NT$300 元整；第四步至 IT 部門領取工作電腦，IT 部門密碼重置預設有效期限為 48 小時，逾期請撥打總機分機 7701 請求重新產生。祝您在 Cayana 工作愉快！",
         "新進員工手冊中識別證遺失補發工本費是多少錢？"),
        ("mem-long-07", "RECORDING", "創業團隊種子輪融資法務合約溝通紀錄",
         "陳律師與三位創辦人今天就投資協議 (Term Sheet) 關鍵條款達成共識。針對清算優先權部分，投資人同意採 1x 不參與分配優先清算權 (Non-participating liquidation preference)。在董事會席位方面，公司維持五席董事，其中創辦團隊保留三席，投資人指派一席，獨立董事一席。最關鍵的競業禁止條款部分，律師建議將創辦人離職後的競業禁止限制期間訂為 18 個月，地域範圍限定在台灣與日本市場。合約預計在下週三由各方負責人正式簽署。",
         "融資合約會議中創辦人離職後的競業禁止期間定為幾個月？"),
        ("mem-long-08", "RECORDING", "心理諮商對話心得自我反思錄音",
         "今天諮商師引導我觀察自己面對工作焦慮時的身心反應。我發現當收到緊急通知時，我的肩膀會不自覺緊繃，呼吸也會變淺。諮商師教了我一套『4-7-8 呼吸調節法』：吸氣 4 秒，閉氣 7 秒，然後緩慢吐氣 8 秒，連續重複四次。這套方法能有效激活副交感神經，降低心跳速率。今天晚上嘗試做了一次，入睡速度確實比以往快很多。我決定把這項練習排進每天早晨起床與就寢前的固定日程中。",
         "諮商反思錄音中提到的放鬆呼吸法閉氣需要幾秒？"),
        ("mem-long-09", "SHARED_TEXT", "住宅室內防火安全與逃生設備檢查報告",
         "本次住宅防火安全檢查由合格消防設備士王大明於 2026年9月執行完畢。全戶三間臥室與客廳皆已安裝住宅用火災警報器（光電式偵煙型），電池有效期限標示至 2035年。廚房瓦斯爐上方已增設定溫式探熱探測器。客廳角落備有一具 10 型乾粉滅火器，壓力表指針目前位於綠色正常範圍內。檢查員特別提醒：滅火器出廠年份為 2023年，依照法規每三年需進行一次水壓耐壓測試與藥劑檢驗，故下次預計送檢日期為 2026年12月。",
         "消防檢查報告中客廳滅火器下次預計送檢日期是何時？"),
        ("mem-long-10", "RECORDING", "海外留學行前準備與住宿選址討論音檔",
         "跟學姊通話四十分鐘，整理出倫敦大學學院 (UCL) 留學的核心生活指南。住宿方面，學姊強烈建議選在一區或二區北邊的 Bloomsbury 或是 Camden，走路或搭公車二十分鐘內可抵達校區。每個月房租預算大約落在 1,100 英鎊至 1,300 英鎊之間。開戶部分，建議抵達後直接使用 Monzo 或 Revolut 數位網銀，免除實體銀行冗長的預約審核。交通卡部分，記得將學生 Oyster 卡綁定 16-25 青年火車卡，離峰地鐵票價可以享有三分之一的折扣。",
         "留學諮詢錄音中倫敦二區北邊每個月房租預算大概多少英鎊？"),
        ("mem-long-11", "RECORDING", "馬拉松全馬破四配速訓練計畫語音總結",
         "教練今天替我排定了台北馬拉松破四 (Sub-4) 的十六週訓練課表。全馬目標完賽時間為 3小時58分，換算下來的平均目標配速為每公里 5分38秒。週末長距離慢跑 (LSD) 的距離設定為 28 至 32 公里，配速應壓在每公里 6分15秒左右。在補給策略方面，教練要求每跑 45 分鐘固定吞下一包能量膠，每 2.5 公里水站補水 150ml。另外每週二加入間歇跑訓練，以 4分45秒的配速跑 800 公尺重複六趟，提升最大攝氧量。",
         "馬拉松破四訓練計畫中目標平均每公里配速是多少？"),
        ("mem-long-12", "SHARED_TEXT", "智慧家庭 Home Assistant 自動化佈署備忘",
         "今日成功整合客廳與臥室 Zigbee 智慧家庭裝置至 Home Assistant 系統中。網路閘道器採用 Sonoff Zigbee 3.0 USB Dongle Plus，運作通道指定為 Channel 25，以避開 2.4GHz Wi-Fi 的頻率干擾。客廳冷氣自動化規則設定為：當溫濕度感測器偵測到室內溫度高於 28 度且客廳人體存在感測器判定有人在場時，自動透過 Broadlink 紅外線發射器開啟冷氣設定為 26 度弱風模式。系統每日凌晨三點自動將設定檔備份至 NAS 伺服器中。",
         "智慧家庭自動化設定中 Zigbee 運作通道指定為第幾頻道？"),
        ("mem-long-13", "RECORDING", "咖啡店創業商業模式與財務預測訪談音檔",
         "採訪經營獨立精品咖啡館五年的創辦人林老闆。林老闆透露，店面位於捷運站步行三分鐘巷弄內，室內坪數 18 坪，共設有 24 個內用座位。初期裝潢與義式咖啡機設備投資總金額為新台幣 220 萬元。每月固定支出包括店租 65,000 元、水電瓦斯 15,000 元。毛利率部分，單純黑咖啡與拿鐵毛利率高達 75%，但手工甜點因為進口乳酪成本較高，毛利率只有 45%。單店損益兩平點約為每日出杯量 120 杯，平均客單價落在 165 元左右。",
         "精品咖啡店訪談中單店損益兩平點每天需要賣幾杯咖啡？"),
        ("mem-long-14", "RECORDING", "日式庭園造景設計課課堂筆記錄音",
         "今天由日本京都庭園大師講解枯山水 (Karesansui) 的造景精髓。大師強調，枯山水是以石塊象徵山嶽島嶼，以白砂耙紋象徵水波汪洋，追求一種極簡枯寂的禪宗哲學。庭園中的石頭排列絕不使用偶數，常見的是以『三尊石』為核心的三石組合，象徵佛教的三尊佛像。白砂的紋路一般分為直線的水流紋、同心圓的波紋與旋渦紋。在植物搭配上，首選抗修剪性強的羅漢松、真柏與日本黑松，杜絕使用過於豔麗的花卉植物，以保持寧靜冥想氛圍。",
         "庭園造景錄音中枯山水核心石頭組合通常象徵什麼？"),
        ("mem-long-15", "SHARED_TEXT", "自造者機械鍵盤熱插拔軸體更換手冊",
         "本次改裝套件為 Keychron Q1 Pro 75% 鋁合金客製化機械鍵盤。鍵盤軸體全數更換為高特青檸軸 V3（靜音線性軸），觸發壓力 45gf，觸發行程 2.0mm。PCB 電路板下方貼上兩層美紋紙膠帶進行 Tape Mod 改裝，消除金屬空腔音。衛星軸部分拆卸後清洗原廠潤滑脂，重新塗抹 Krytox 205g0 潤滑軸心與假軸接觸面，鋼絲轉折處則點上適量 Permatex 太陽牌高黏度阻尼脂，徹底解決空白鍵左右晃動雜音問題。",
         "鍵盤改裝手冊中更換的靜音線性軸觸發壓力是多少？"),
        ("mem-long-16", "RECORDING", "社區園藝社多肉植物病蟲害防治演講錄音",
         "理事長今天介紹梅雨季多肉植物最常見的介殼蟲與黑腐病防治法。對於初期的粉介殼蟲感染，切勿立即使用強效劇毒農藥，可用 75% 藥用酒精搭配軟毛水彩筆直接點塗蟲體，酒精揮發迅速且不會傷害葉片表面的保護白粉。如果全株大面積爆發，則需稀釋印楝油或苦楝油至 500 倍進行全株噴灑。至於黑腐病是由真菌引發，一旦發現莖部發黑變軟，唯一的急救辦法是立即砍頭分株，切口必須修剪至完全見到綠色健康組織，並塗抹殺菌開花粉或多菌靈粉末陰乾。",
         "多肉園藝演講中初期發現粉介殼蟲推薦用什麼點塗蟲體？"),
        ("mem-long-17", "RECORDING", "歐洲自駕租車跨國過境注意事項交流語音",
         "剛剛跟老爸確認了德奧捷三國自駕的過境高速公路通行費規定。在德國開高速公路 (Autobahn) 目前小客車完全免費，沒有任何過境收費站。但是車輛一進入奧地利境內，必須在上高速前在邊境加油站購買十日通行證貼紙 (Vignette)，一張費用是 9.9 歐元，記得貼在擋風玻璃左上方。到了捷克境內也是強制收費，但捷克現在已全面電子化，需要在官方網站 edalnice.cz 輸入車牌購買電子通行費，十天期費用為 310 捷克克朗。租車公司跨國手續費另外加收 40 歐元。",
         "歐洲自駕語音中奧地利十日高速公路通行證貼紙費用是多少歐元？"),
        ("mem-long-18", "SHARED_TEXT", "家庭音響劇院 Dolby Atmos 5.1.2 聲道擺位指南",
         "客廳劇院聲道配置：主聲道左中右喇叭高度與聆聽者耳朵平齊，中置喇叭置於電視正下方。左右環繞喇叭擺放在沙發兩側稍後方 110 度角位置。天空聲道 (.2) 兩顆吸頂式反射喇叭安裝於沙發正上方天花板，提供垂直天空音場效果。超低音重低音喇叭放置於前牆左前方約三分之一處，避開駐波角落。AV 擴大機使用 Denon AVR-X2800H，透過 Audyssey MultEQ XT 麥克風進行八點自動音場校正，各聲道交叉分頻點設定為 80Hz。",
         "劇院音響指南中各聲道交叉分頻點設定為多少赫茲？"),
        ("mem-long-19", "RECORDING", "高山百岳單攻能高越嶺西段領隊行前叮嚀",
         "明天清晨五點我們準時在屯原登山口集合整裝出發。能高越嶺西段至天池山莊全長 13 公里，步道整體平緩好走，但特別提醒在 6K 與 10K 兩處大崩壁路段，請大家收起登山杖並快速通過，嚴禁在碎石坡中途停下拍照或聊天。全員必須隨身攜帶至少 1.5 公升飲用水與兩餐行動糧。天池山莊海拔 2,860 公尺，下午兩點後氣溫會降到 8 度以下，保暖風雨衣與毛帽必須放在背包最上層以便隨時穿著。若途中出現頭痛反胃等高山反應症狀，務必立即告知壓後嚮導。",
         "登山行前叮嚀中能高越嶺西段哪兩處大崩壁路段嚴禁停留？"),
        ("mem-long-20", "RECORDING", "自製義式生乳捲烘焙師傅教學錄音",
         "做完美蛋糕捲的關鍵在於燙麵戚風蛋糕體的濕潤度與蛋白霜打發程度。蛋白需分三次加入細砂糖 60g，打發至中性發泡（提起打蛋器有彎曲鳥嘴狀軟尖角即可），切忌打過頭變成乾性發泡，否則出爐捲蛋糕時表皮容易裂開。內餡部分使用日本中澤鮮奶油 250g，加入馬斯卡彭乳酪 50g 與糖粉 20g 慢速打至九分發，質地堅挺才能撐起蛋糕捲。烘烤溫度設定上火 170 度、下火 150 度，烘烤時間 25 分鐘，出爐後震出熱氣立即倒扣撕開烘焙紙散熱。",
         "生乳捲教學中蛋白霜要打發到什麼狀態才不會捲的時候裂開？")
    ]

    # 6. Unanswerable & Distractor Queries (25 queries)
    # These have NO ground truth memory, testing false positives!
    # 15 tricky distractors with high surface lexical overlap + 10 totally absent.
    negative_seeds = [
        # Tricky Distractors (high lexical similarity with existing seeds, but wrong semantic answer)
        ("我昨天買的無糖豆漿花了多少錢？", "記憶庫中只有『買人體工學滑鼠』與『超市買機票』，無豆漿花費收據。"),
        ("長榮航空飛往舊金山的航班起飛時間是幾點？", "記憶庫只有 BR198 飛東京成田，沒有舊金山航班。"),
        ("阿基師川菜館的招牌酸菜魚多少錢？", "記憶庫菜單有宮保雞丁、麻婆豆腐、水煮牛肉，沒有酸菜魚。"),
        ("MacBook Pro 14吋的訂單編號是多少？", "記憶庫購買的是 MacBook Pro 16吋，沒有 14吋訂單。"),
        ("高鐵訂票代碼 99887766 的座位在哪裡？", "記憶庫訂票代碼為 08552147，無此代碼。"),
        ("玉山銀行十月份的信用卡帳單應繳多少？", "記憶庫只有九月份帳單，無十月份帳單。"),
        ("辦公室訪客 Wi-Fi 密碼裡面的數字是 2024 嗎？", "記憶庫密碼是 WelcomeSpring2026，非 2024。"),
        ("台大醫院骨科門診預約在哪一天？", "記憶庫預約的是牙髓病科，沒有骨科門診。"),
        ("地下停車位 B1-158 的柱子顏色是什麼？", "記憶庫停車位是 B2-158，沒有 B1 車位。"),
        ("Uber 從大安區搭到新店區花了多少車資？", "記憶庫 Uber 行程是信義路到和平東路，無新店行程。"),
        ("中華電信 1G 光纖寬頻費用是多少？", "記憶庫合約速率是 500M/500M，無 1G 方案。"),
        ("蝦皮買的藍牙耳機送達門市了嗎？", "記憶庫購買的是垂直滑鼠，沒有藍牙耳機。"),
        ("好市多線上購物買的大同電鍋提貨碼？", "記憶庫提貨通知無大同電鍋，只有提貨驗證碼。"),
        ("義大利培根蛋麵做法裡面有加鮮奶油嗎？", "記憶庫食譜明確寫明 No heavy cream allowed，但問的是加多少鮮奶油。"),
        ("凡爾賽和約最具爭議的第 100 條款內容？", "記憶庫記錄的是第 231 條款，無第 100 條款。"),
        
        # Out-of-domain Completely Absent Queries
        ("上週去屏東墾丁浮潛看到的綠蠵龜照片？", "完全無墾丁或浮潛相關記憶。"),
        ("家裡冷氣濾網叫修師傅的手機號碼？", "完全無叫修師傅電話。"),
        ("特斯拉 Model Y 車載充電轉接頭放哪裡？", "完全無特斯拉車輛相關紀錄。"),
        ("媽媽生日聚餐訂位的陶板屋餐廳時間？", "完全無陶板屋餐廳紀錄。"),
        ("上個月去好市多買的牛肉捲退貨退款單？", "完全無退貨單紀錄。"),
        ("家裡的任天堂 Switch 遊戲卡匣借給誰了？", "完全無 Switch 遊戲紀錄。"),
        ("健身房游泳池每週水質消毒公休是星期幾？", "完全無泳池消毒公休資訊。"),
        ("去台南玩住的煙波大飯店房號幾號？", "完全無台南煙波住宿資訊。"),
        ("筆電被偷走去派出所報案的三聯單號碼？", "完全無報案三聯單紀錄。"),
        ("社區大樓中秋節烤肉晚會抽獎獎品是什麼？", "完全無中秋烤肉抽獎紀錄。")
    ]

    # Combine all target anchors
    target_anchors = []
    
    # Process Lexical
    for mem_id, stype, title, text, q_text in lexical_seeds:
        target_anchors.append({
            "id": mem_id, "sourceType": stype, "title": title, "rawText": text,
            "category": "lexical_exact", "query": q_text
        })
    
    # Process Synonyms
    for mem_id, stype, title, text, q_text in synonym_seeds:
        target_anchors.append({
            "id": mem_id, "sourceType": stype, "title": title, "rawText": text,
            "category": "synonym_paraphrase", "query": q_text
        })

    # Process Cross-lingual
    for mem_id, stype, title, text, q_text in crosslingual_seeds:
        target_anchors.append({
            "id": mem_id, "sourceType": stype, "title": title, "rawText": text,
            "category": "cross_lingual", "query": q_text
        })

    # Process Precise
    for mem_id, stype, title, text, q_text in precise_seeds:
        target_anchors.append({
            "id": mem_id, "sourceType": stype, "title": title, "rawText": text,
            "category": "precise_attribute", "query": q_text
        })

    # Process Long
    for mem_id, stype, title, text, q_text in long_seeds:
        target_anchors.append({
            "id": mem_id, "sourceType": stype, "title": title, "rawText": text,
            "category": "long_transcript_detail", "query": q_text
        })

    # Add all 125 target memories to memories pool
    for i, anchor in enumerate(target_anchors):
        mem_ts = base_ts - (i * 3600000 * 6) # Spread over past month
        memories.append({
            "id": anchor["id"],
            "sourceType": anchor["sourceType"],
            "createdAt": mem_ts,
            "capturedAt": mem_ts,
            "title": anchor["title"],
            "rawText": anchor["rawText"],
            "normalizedText": anchor["rawText"],
            "sourceUri": f"content://cayana/storage/{anchor['id']}",
            "sourceUrl": f"https://example.com/memories/{anchor['id']}" if anchor["sourceType"] == "SHARED_URL" else None,
            "metadata": {"displayName": anchor["title"], "tag": anchor["category"]},
            "entities": ["Cayana", "Taipei"] if "台北" in anchor["rawText"] else [],
            "eventCandidates": []
        })

        queries.append({
            "query_id": f"Q{len(queries)+1:03d}",
            "query_text": anchor["query"],
            "category": anchor["category"],
            "target_memory_ids": [anchor["id"]],
            "expected_reasoning": f"Target memory {anchor['id']} specifically contains the answer for category {anchor['category']}."
        })

    # Add the 25 Negative / Distractor queries (target_memory_ids = [])
    for q_text, reasoning in negative_seeds:
        queries.append({
            "query_id": f"Q{len(queries)+1:03d}",
            "query_text": q_text,
            "category": "unanswerable_negative",
            "target_memory_ids": [],
            "expected_reasoning": reasoning
        })

    print(f"Annotated 150 test queries: {len([q for q in queries if q['target_memory_ids']])} positive, {len([q for q in queries if not q['target_memory_ids']])} negative.")

    # -------------------------------------------------------------------------
    # Generate Remaining Background Memories to reach exactly 2,000 memories
    # -------------------------------------------------------------------------
    # Realistic topics: tech, food, sports, daily life, receipts, photos, chats
    remaining_count = 2000 - len(memories)
    print(f"[2/3] Generating {remaining_count} diverse background filler memories...")

    topics = [
        ("日常便利商店消費發票", "全家便利商店咖啡與茶葉蛋發票，金額 NT$79。"),
        ("捷運搭乘扣款紀錄", "台北捷運悠遊卡自動加值 NT$500，扣款刷卡搭乘淡水信義線。"),
        ("早餐店點餐明細", "鮪魚蛋餅加起司與冰豆漿大杯，共 65元，外帶內用皆可。"),
        ("午餐外送平台訂單", "UberEats 訂購健康水煮便當，嫩煎雞胸肉搭配紫米飯，費用 NT$160。"),
        ("下午茶手搖飲料品項", "微糖微冰四季春茶加椰果，售價 NT$45，大杯環保杯折 5元。"),
        ("超市採買日用品明細", "全聯福利中心購買三層抽取式衛生紙一串、無糖優格與全脂鮮奶。"),
        ("圖書館借閱書籍到期通知", "您借閱的《原子習慣》與《刻意練習》即將於下週三到期，請記得線上續借。"),
        ("電影院影城訂票紀錄", "威秀影城 IMAX 影廳數位版，第 H 排 12, 13 號，開演時間 19:30。"),
        ("加油站洗車體驗筆記", "水刀泡沫洗車加輪胎上亮光蠟，洗車卡扣點一次，吹乾效果佳。"),
        ("藥局購買維他命C發泡錠", "德國進口發泡錠一管 20錠，每日早晨泡水一杯飲用，補充元氣。"),
        ("網購書籍包裹已出貨", "博客來網路書店訂單已出貨，預計明日中午前送達指定門市。"),
        ("停車場繳費機發票收據", "嘟嘟房停車場每小時 50元，停放 2小時15分，實繳 NT$150。"),
        ("理髮院設計師剪髮預約", "週六下午三點預約剪髮加頭皮去角質深層護理，設計師 Ken。"),
        ("鞋店購買慢跑鞋收據", "Nike 門市購入 Pegasus 系列日常慢跑鞋一雙，折扣後 NT$2,880。"),
        ("書店隨筆摘錄心得", "只要每天進步百分之一，持續一年就能獲得三十七倍的複利成長。"),
        ("朋友聚餐分帳備忘錄", "週五熱炒聚餐總共 3,600元，現場六人均分每人實付 600元。"),
        ("洗車場自助洗車紀錄", "高壓水槍十元、泡沫十元、低壓清水十元，合計花費三十元清洗乾淨。"),
        ("健身房慢跑機運動數據", "室內跑步機慢跑五公里，花費時間 28分30秒，消耗熱量 320大卡。"),
        ("居家清潔打掃備忘清單", "週日清理抽油煙機濾網、更換床單被套、使用吸塵器除塵蟎。"),
        ("文具店購買鋼筆墨水", "百樂色彩雫系列墨水『月夜』一瓶 50ml，墨色沉穩深邃。")
    ]

    source_types = ["SCREENSHOT", "PHOTO", "RECORDING", "SHARED_TEXT", "SHARED_URL"]

    for i in range(remaining_count):
        topic_idx = i % len(topics)
        base_title, base_desc = topics[topic_idx]
        mem_id = f"mem-bg-{i+1:04d}"
        stype = source_types[i % len(source_types)]
        
        # Add random variations to make text natural and unique
        rand_var = random.randint(100, 9999)
        title = f"{base_title} #{rand_var}"
        text = f"{base_desc} 流水號碼 {rand_var}，日常記錄時間標記於備忘錄之中。"
        
        # Random timestamp across past 6 months
        mem_ts = base_ts - random.randint(1, 180) * day_ms - random.randint(0, day_ms)

        memories.append({
            "id": mem_id,
            "sourceType": stype,
            "createdAt": mem_ts,
            "capturedAt": mem_ts,
            "title": title,
            "rawText": text,
            "normalizedText": text,
            "sourceUri": f"content://cayana/storage/{mem_id}",
            "sourceUrl": f"https://example.com/articles/{rand_var}" if stype == "SHARED_URL" else None,
            "metadata": {"displayName": title, "tag": "background_filler"},
            "entities": ["Cayana"],
            "eventCandidates": []
        })

    print(f"Total memories generated: {len(memories)}")
    assert len(memories) == 2000, f"Expected 2000 memories, got {len(memories)}"
    assert len(queries) == 150, f"Expected 150 queries, got {len(queries)}"

    # -------------------------------------------------------------------------
    # Save to disk
    # -------------------------------------------------------------------------
    print("[3/3] Saving dataset to experiments/retrieval-7b/dataset/...")
    dataset_dir = os.path.dirname(os.path.abspath(__file__))
    
    memories_path = os.path.join(dataset_dir, "memories.json")
    with open(memories_path, "w", encoding="utf-8") as f:
        json.dump(memories, f, ensure_ascii=False, indent=2)
        
    queries_path = os.path.join(dataset_dir, "queries.json")
    with open(queries_path, "w", encoding="utf-8") as f:
        json.dump(queries, f, ensure_ascii=False, indent=2)

    print(f"Saved {len(memories)} memories -> {memories_path}")
    print(f"Saved {len(queries)} queries -> {queries_path}")
    print("Dataset generation completed successfully!")

if __name__ == "__main__":
    generate_benchmark_dataset()

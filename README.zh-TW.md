# Openbone

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

Openbone 是一款以 Spine 工作流程為參考的桌面 2D 骨骼動畫編輯器，面向遊戲角色、道具與技能特效的製作與預覽。專案使用 Java 17、Swing 與 libGDX / LWJGL，實現從骨骼搭建、PNG 附件綁定、網格與權重編輯，到關鍵影格動畫與資源包匯出的工作流程。

專案對標 Spine 的骨骼、插槽、外觀、附件、時間軸與約束等核心概念，並以 **Spine 4.2.22 JSON 匯出格式的受支援子集**作為交換格式。目前版本為 `0.1.0-SNAPSHOT`，仍在開發中；相容範圍以目前實作為準，不能視為對 Spine 全功能、全版本的完整替代。

## 主要功能

| 模組 | 目前能力 |
| --- | --- |
| 骨骼編輯 | 建立骨骼、調整父子關係、平移 / 旋轉 / 縮放、局部與世界座標操作 |
| 插槽與附件 | PNG 圖片匯入、Region 附件、插槽綁定、普通與疊加混合 |
| 網格與權重 | Region 轉網格、頂點與 UV 編輯、網格剖分、骨骼綁定與頂點權重編輯 |
| 動畫時間軸 | Setup / Animate 模式、動畫新建與複製、TRS 關鍵影格、網格 deform、關鍵影格複製 / 貼上 / 拖動 |
| 曲線與播放 | 線性、階梯與貝茲曲線，播放、循環、時間定位與吸附 |
| 資源組織 | 多外觀、附件切換關鍵影格、繪製順序關鍵影格、事件定義與事件關鍵影格 |
| 約束 | 單骨 / 雙骨 IK、變換約束、路徑約束、物理約束及受支援的參數時間軸 |
| 裁剪與路徑 | 裁剪附件、路徑附件與 Setup 控制點編輯 |
| 專案管理 | 復原 / 重做、專案儲存、含 PNG 的可攜專案、自動復原、視窗佈局記憶 |
| 多語言 | 英文、簡體中文、繁體中文 |

## 與 Spine 的關係及格式相容

Openbone 使用自己的可編輯專案格式，支援匯入與匯出限定範圍內的 Spine JSON 資料。專案未包含官方 Spine Runtime 的類別或原始碼。

### 支援的交換格式

- **骨架 JSON**：版本欄位 `skeleton.spine`。
- **貼圖**：PNG，可透過 JSON 中的 `skeleton.images` 與附件路徑載入散圖。
- **圖集**：讀取與 JSON 同目錄、同名的 `.atlas`，例如 `character.json` 對應 `character.atlas`；支援受限的旋轉與裁邊資訊。
- **匯出資源包**：包含 `skeleton.json`、`skeleton.atlas` 與 PNG 圖片，可用於匹配格式的執行時接入。

### 目前相容邊界

| 項目 | 限制 |
| --- | --- |
| 版本與檔案類型 | 不支援直接開啟 Spine 原生 `.spine` 專案或二進位 `.skel`；其他版本 JSON 不在目前相容範圍內 |
| 骨骼 | 僅支援 `normal` 繼承模式；不支援骨骼 shear 欄位及 shear 動畫軌道 |
| 附件 | 支援 Region、Mesh、Clipping、Path；不支援 linked mesh、bounding box、point 等其他附件類型 |
| 顏色與混合 | 插槽與貼圖附件僅接受白色顏色值；混合模式限 `normal` / `additive`，不支援顏色動畫 |
| 獨立 X / Y 軌道 | 同組獨立 X / Y 軌道的關鍵影格時間必須一致 |
| Deform | 支援網格 deform；路徑與裁剪附件的 deform 尚未實作，不支援加權裁剪 |
| 約束 | 存在求解順序、依賴關係與數值範圍限制，不支援依賴外觀啟用的約束及全域物理時間軸 |
| 圖集 | 要求 PNG、`RGBA8888`、非預乘 Alpha、`Nearest` 過濾與無重複；旋轉僅支援 0° / 90°，區域 `index` 為 `-1`，不支援自訂欄位 |
| 匯出 | 骨架必須恰好有一個根骨骼；專案資料還需通過執行時 float32 範圍等校驗 |

接入遊戲時，請使用匹配目標格式的執行時，並在目標引擎中驗證實際播放效果。Openbone 的專案 JSON、資源包入口 `current.json` 與 Spine 骨架 JSON 是不同用途的檔案。

## 建置與執行

### 環境需求

- JDK 17 或更高版本。
- Maven，且 `mvn` 已加入 `PATH`；倉庫目前未提供 Maven Wrapper。
- 能執行 Swing 桌面視窗與 LWJGL OpenGL 視埠的圖形環境。

首次建置需要下載 Maven 依賴。專案包含桌面本地函式庫，具體作業系統與驅動的可用性需在目標環境驗證。

### 從原始碼啟動

在專案根目錄執行：

```shell
mvn clean package
java -jar target/openbone-0.1.0-SNAPSHOT.jar
```

建置會產生包含依賴的可執行 JAR。不要使用 `original-openbone-0.1.0-SNAPSHOT.jar` 作為獨立發行包。

指定繁體中文介面：

```shell
java -Dopenbone.lang=zh-TW -jar target/openbone-0.1.0-SNAPSHOT.jar
```

語言參數支援 `en`、`zh-CN`、`zh-TW`。也可透過介面的語言選單切換，重新啟動後生效；首次啟動預設使用英文。

### 命令列選項

```shell
# 查看版本
java -jar target/openbone-0.1.0-SNAPSHOT.jar --version

# 查看 Java、作業系統、螢幕與使用者狀態目錄等環境資訊
java -jar target/openbone-0.1.0-SNAPSHOT.jar --environment

# 啟動並開啟 Openbone 專案
java -jar target/openbone-0.1.0-SNAPSHOT.jar --open "path/to/character.json"

# 校驗 Openbone 專案，輸出 schema 版本、物件數量與正規化 SHA-256
java -jar target/openbone-0.1.0-SNAPSHOT.jar --validate-project "path/to/character.json"
```

`--open` 與 `--validate-project` 接受 Openbone 專案檔案。匯入 Spine 匯出的 JSON 請使用編輯器中的「匯入 4.2.22 JSON」。專案校驗失敗時行程返回結束碼 `2`。

## 快速上手

以下選單與按鈕名稱以繁體中文介面為例。

### 1. 搭建骨架並匯入圖片

1. 啟動編輯器，透過「檔案 → 新建」建立空白專案，保持在「設置 Setup」模式。
2. 按 `N` 切換到建骨工具，在視埠中拖動建立根骨骼。選取已有骨骼後繼續建骨，可建立其子骨骼。
3. 選取骨骼，使用 `W` / `E` / `R` 調整位置、旋轉與縮放，也可在屬性面板輸入數值。
4. 選取目標骨骼，透過「檔案 → 匯入 PNG…」或 `Ctrl+I` 匯入圖片。編輯器會依目前選取使用或建立插槽，並新增圖片附件。
5. 調整附件的位置與大小；需要柔性變形時，在附件屬性中使用「轉換為可編輯網格」，再編輯頂點、綁定骨骼並設定權重。

建議將整個角色組織在一個根骨骼下，以滿足執行時匯出的要求。

### 2. 製作關鍵影格動畫

1. 在動畫面板新建動畫，切換到「動畫 Animate」模式。
2. 選取骨骼，將時間定位到 `0` 秒，使用「錄 TRS」記錄起始姿態。
3. 將時間移動到另一個位置，調整骨骼姿態，再次記錄關鍵影格。
4. 在時間軸選取關鍵影格，透過「曲線…」設定線性、階梯或貝茲插值；按住 `Ctrl` 可多選關鍵影格。
5. 點擊「播放」，按需啟用「循環」。透過「外觀 / 附件 / 事件 / 順序…」管理相關資源與離散關鍵影格。

Setup 用於編輯基礎結構與初始姿態，Animate 用於製作動畫。調整骨骼後請明確記錄所需的關鍵影格。

### 3. 儲存可繼續編輯的專案

- **儲存 / 另存新檔**：儲存 Openbone 專案 JSON。一般專案引用外部圖片，移動專案時需同時維護圖片路徑。
- **儲存可攜專案（含 PNG）**：儲存專案並複製所需 PNG 到相鄰的 `<專案檔名>.assets/` 目錄。分享或遷移時，請將專案 JSON 與該目錄一起打包。
- **復原未儲存的編輯**：透過檔案選單復原已有快照，復原後另存新檔。自動復原每 15 秒記錄已提交的編輯，未提交的輸入框內容與拖動預覽不在快照中。

自動復原與佈局資料預設位於 Windows 的 `%LOCALAPPDATA%/Openbone`；無 `LOCALAPPDATA` 環境變數時使用 `~/.openbone`。可用 JVM 參數 `-Dopenbone.userState=路徑` 指定其他目錄。

### 4. 匯入既有 Spine JSON

1. 準備版本為 `4.2.22` 且符合上述相容範圍的匯出 JSON。
2. 將同名 `.atlas` 及其引用的 PNG 放到對應位置；使用散圖時，保持 `skeleton.images` 與附件路徑能正確解析。相對資源路徑需位於匯入資源目錄內。
3. 選擇「檔案 → 匯入 4.2.22 JSON…」，選取骨架 JSON。也可選擇 Openbone 匯出資源包的 `current.json`，編輯器會解析並校驗資源包。
4. 匯入成功後會產生新的未儲存專案，請儲存為 Openbone 專案，便於後續編輯。

若匯入被拒絕，可根據錯誤中的欄位路徑檢查版本、元資料、附件類型、圖集設定及約束限制。僅修改版本號不能轉換其他版本的資料。

### 5. 匯出遊戲資源

選擇「檔案 → 匯出 4.2.22 資源包…」，指定輸出目錄。匯出結果範例：

```text
export/
├── current.json
└── generation-<uuid>/
    ├── skeleton.json
    ├── skeleton.atlas
    ├── manifest.json
    └── images/
        ├── region_0.png
        └── ...
```

`current.json` 指向最新匯出批次，`manifest.json` 記錄資源校驗資訊。向遊戲引擎交付時，使用對應 `generation-<uuid>/` 下的 `skeleton.json`、`skeleton.atlas` 與 `images/`，保持它們的相對目錄結構；不要將 `current.json` 作為執行時骨架檔案載入。

匯出器會保留舊批次，透過更新入口檔案發布新批次。目前按貼圖附件複製 PNG 或原圖集頁面，不會自動將散圖重新緊密打包為單張圖集。

## 常用操作

| 操作 | 快速鍵 / 滑鼠 |
| --- | --- |
| 新建 / 開啟 / 儲存 | `Ctrl+N` / `Ctrl+O` / `Ctrl+S` |
| 另存新檔 / 儲存可攜專案 | `Ctrl+Shift+S` / `Ctrl+Alt+S` |
| 匯入 PNG | `Ctrl+I` |
| 匯入 JSON / 匯出資源包 | `Ctrl+Alt+O` / `Ctrl+Alt+E` |
| 復原 / 重做 | `Ctrl+Z` / `Ctrl+Y` |
| 選取 / 建骨 | `Q` / `N` |
| 移動 / 旋轉 / 縮放 | `W` / `E` / `R` |
| 刪除所選 | `Delete` |
| 取消目前拖動 | `Esc` |
| 平移視埠 / 縮放視埠 | 右鍵或中鍵拖動 / 滑鼠滾輪 |

## 專案結構

```text
src/main/java/io/github/samlinb/openbone/
├── EditorApplication.java  # 桌面與命令列入口
├── application/           # 編輯命令、歷史記錄、專案狀態與播放控制
├── core/                  # 資料模型、動畫取樣、數學與約束求解
├── desktop/               # Swing 編輯器、屬性面板與時間軸
├── viewport/              # OpenGL 視埠、貼圖渲染與拾取
├── io/                    # 專案讀寫、Spine JSON 子集、圖集與資源包
└── i18n/                  # 介面語言載入
src/main/resources/        # 翻譯、第三方聲明與授權文字
packaging/installer/       # Windows 安裝程式原始碼
```

開發時可執行 `mvn verify` 執行 Maven 設定中的測試與整合驗證流程；實際執行的測試取決於本地檢出的測試原始碼。

## 常見問題

**提示找不到 `mvn` 或 `java`？**

安裝 Maven 與 JDK 17+，設定 `PATH` 後重新開啟終端機，用 `mvn -version` 與 `java -version` 檢查。

**圖片遺失或遷移專案後看不到附件？**

檢查來源 PNG 路徑；選取附件後重新定位圖片，再使用「重新載入圖片」。跨機器分享優先使用可攜專案，並攜帶相鄰的 `.assets` 目錄。

**匯入版本正確，卻提示不支援的欄位？**

目前實作嚴格接受受支援子集，包括對額外元資料的檢查。請按錯誤提示確認資料是否在相容範圍內；版本相同不代表檔案中的所有功能均已支援。

**儲存成功，匯出卻失敗？**

專案儲存與執行時匯出使用不同的校驗範圍。檢查是否只有一個根骨骼、PNG 是否可讀取，以及約束、動畫與數值是否滿足匯出限制。

## 第三方聲明

依賴聲明見 [pom.xml](pom.xml)，第三方授權與聲明見 [THIRD_PARTY.txt](src/main/resources/THIRD_PARTY.txt) 與 [licenses](src/main/resources/licenses/)。Spine 名稱用於說明工作流程參考與資料格式相容目標。

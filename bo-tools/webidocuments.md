# Webi rapor envanteri (WebiDocumentsApp)

Public Folders altındaki Webi raporlarının sorgu (data provider) ve universe bilgilerini
`POPR.SDK_METADATA_WEBIDOCUMENTS_JOB` tablosuna yazar. **Bir satır = bir raporun bir sorgusu.**

## Dosyalar (`src/com/akbank/analytics/applications/bo/webidocuments/`)
| Dosya | İçerik |
|---|---|
| `WebiDocumentsApp.java` | Ana sınıf: parametreler → CMS → REST → CSV → (--apply) DB → doğrulama |
| `CmsWebiReader.java` | CMS sorguları: klasörler, Webi raporları (sayfalı), veri kaynağı SI_ID → CUID / ad |
| `CmsFolderPaths.java` | Klasör ID → `/Public Folders/...` yolu; kök = SI_ID 23 ya da SI_PARENTID 0 (bu CMS'te üst klasörler 0 geliyor); Public dışı (kişisel, Inbox) için null |
| `RestEndpointConfig.java` | credentials.json'dan `biprwsUrl` / `restToken` |
| `WebiRestClient.java` | RESTful (raylight): `/documents/{id}/dataproviders`, dokümanı kapatma, token yenileme |
| `WebiDocumentsDao.java` | Oracle: SYSTEM başına DELETE + batch INSERT, tek transaction |
| `WebiDocument.java`, `WebiDocumentRow.java` | Rapor ve satır modelleri |

**Main class:** `com.akbank.analytics.applications.bo.webidocuments.WebiDocumentsApp`
(VM options ve working directory için bkz. `README.md`). Toplu çalıştırma: `run_webidocuments.bat`.

## Alanların kaynağı
| Kolon | Kaynak |
|---|---|
| CUID, NAME, DESCRIPTION, KEYWORD | `CI_INFOOBJECTS` `SI_KIND='Webi'`, `SI_INSTANCE=0` |
| OWNER | `SI_OWNER` |
| CREATION_TIME / UPDATED_TIME | `SI_CREATION_TIME` / `SI_UPDATE_TS` |
| PATH | Klasör yolu, rapor adı hariç: `/Public Folders/AKBANK/Risk` |
| QUERY_ID, QUERY_NAME | REST `GET /raylight/v1/documents/{SI_ID}/dataproviders` → `id` (DP0), `name` |
| DATASOURCE_CUID, DATASOURCE_NAME | Sorgunun `dataSourceId` (SI_ID) → CMS'te `CI_APPOBJECTS`, yoksa `CI_INFOOBJECTS` |
| SYSTEM | CMS (`HOST:6400`) |
| EXPORT_DATE | Çalıştırma başlangıç zamanı (tüm satırlarda aynı) |
| ROW_ID | Çalıştırma içindeki sıra numarası (1..n), primary key değil |

REST bir raporu okuyamazsa rapor atlanmaz: CMS'teki `SI_UNIVERSE` (UNV) + `SI_DSL_UNIVERSE` (UNX) ile
universe başına bir satır yazılır, QUERY alanları boş kalır. Sorgusu / universe'ü olmayan rapor da tek satır olarak yazılır.

## Parametreler
```
<credentials.json> [--env HOST:6400] [--rest http://HOST:6405/biprws] [--folder "/Public Folders/..."]
                   [--limit N] [--no-query] [--table SEMA.TABLO] [--apply] [--force]
```
- `--rest`: RESTful adresi. Öncelik: `--rest` > `credentials.json` `biprwsUrl` > `http://<CMS sunucusu>:6405/biprws`.
  Postman'de çalışan adres aynen verilebilir: sonundaki `/logon/long` vb. atılır, `/biprws` yoksa eklenir.
  Hangi adresin kullanıldığı logda `RESTful adresi: ... (kaynak)` satırında yazar.
- `credentials.json`: mevcut dosyalardaki `biprwsUrl` kullanılır (eski ad `rest` de kabul edilir). Sırayla aranır:
  eşleşen ortamın `system` nesnesi → ortamın kendisi → dosyanın kökü. `RestEndpointConfig` okur (ortak `AppConfig`'e dokunulmadı).
  ```json
  "system": { "cms": "BO-SUNUCU:6400", "biprwsUrl": "https://REST-SUNUCU:6405/biprws", "restToken": "rest" }
  ```
  `restToken` verilirse sadece o yöntem kullanılır (çalışmayan yöntemler boşuna denenmez).
- `--folder`, `--limit`: test için. **`--apply` ile kullanılamaz** (SYSTEM'in tüm satırları silinip bir kısmı yazılırdı).
- `--no-query`: REST'e gitmeden yalnız CMS (hızlı); satır = rapor × universe, QUERY alanları boş.
- `--apply`: **verilmezse DRY-RUN**, DB'ye yazılmaz, sadece CSV.
- `--force`: REST hatası raporların %10'unu geçerse DB'ye yazılmaz; yine de yazmak için.
- REST token: ilk raporun sorguları okunarak sırayla denenir, çalışan kullanılır (log: "token yöntemi: ..."):
  1. `logon`: CMS oturumundan `createLogonToken`
  2. `serialized`: CMS oturumunun serialize hali
  3. `rest`: REST'in kendi logon'u (`POST /logon/long`, aynı BO kullanıcısı). **+1 oturum/lisans**; iş sonunda logoff.
  Bu sunucuda 1. yöntem `WSR 00999 ... ReportEngineContext ... 'reCtx'` hatası verdi.
  Tek bir yöntemi zorlamak için: `credentials.json` `restToken` ya da VM option `-Dbo.restToken=logon|serialized|rest` (VM option önceliklidir).

## Çıktılar
- `reports\webidocuments_<zaman>.csv`: tablodaki kolonlar + `DURUM`, `DATASOURCE_TYPE`, `DATASOURCE_ID`, `NOT`.
- `logs\webidocuments_*.log`

**Durumlar:** `QUERY` (REST'ten sorgu okundu), `NO_QUERY` (raporda sorgu yok), `CMS_ONLY` (--no-query),
`REST_FAILED` (REST hatası; universe CMS'ten).

## DB
- `--apply` verildiyse uzun işten **önce** DB'ye bağlanıp mevcut satır sayısı okunur (tablo / yetki hatası erken çıkar).
- Yazım: `DELETE ... WHERE SYSTEM = ?` + 500'lük batch INSERT, tek transaction; hata olursa rollback, eski veri kalır.
- Sonra `COUNT(*)` ile doğrulanır. Hiç rapor bulunamadıysa DB'ye dokunulmaz.
- Uzun metinler kolon boyuna kırpılır (`WebiDocumentsDao.LEN_*`); tablo farklı boyutlardaysa sabitleri güncelleyin.

Önerilen DDL (tablo zaten varsa kolon boylarını karşılaştırın):
```sql
CREATE TABLE POPR.SDK_METADATA_WEBIDOCUMENTS_JOB (
  ROW_ID          NUMBER,
  SYSTEM          VARCHAR2(100),
  EXPORT_DATE     DATE,
  CUID            VARCHAR2(64),
  OWNER           VARCHAR2(255),
  NAME            VARCHAR2(500),
  PATH            VARCHAR2(2000),
  DESCRIPTION     VARCHAR2(4000),
  KEYWORD         VARCHAR2(2000),
  CREATION_TIME   DATE,
  UPDATED_TIME    DATE,
  QUERY_ID        VARCHAR2(50),
  QUERY_NAME      VARCHAR2(500),
  DATASOURCE_CUID VARCHAR2(64),
  DATASOURCE_NAME VARCHAR2(500)
);
CREATE INDEX POPR.IX_WEBIDOC_SYSTEM ON POPR.SDK_METADATA_WEBIDOCUMENTS_JOB (SYSTEM);
```
Okurken Türkçe metinleri `TO_NCHAR(NAME)` vb. ile seçin (bkz. CLAUDE.md, WE8ISO8859P9).

## Önerilen akış
1. `--limit 20` ile dry-run: CSV'de QUERY / DATASOURCE kolonlarını kontrol edin.
2. `--no-query` ile tam dry-run: toplam rapor sayısını ve süreyi görün.
3. Tam dry-run (REST'li), mesai sonrası. Log'daki "kalan tahmini" süreye bakın.
4. Uygunsa `--apply` (mesai sonrası, `run_webidocuments.bat`).

## Gerçek SDK'da doğrulanacaklar
- `SI_OWNER`, `SI_CREATION_TIME`, `SI_UPDATE_TS` değerlerinin `properties().getProperty(...)` ile String / Date geldiği.
- `SI_UNIVERSE` / `SI_DSL_UNIVERSE` torbalarının `SI_TOTAL` + `"1"`, `"2"` anahtarlarıyla geldiği.
- `getLogonTokenMgr().createLogonToken(...)` token'ının REST'te kabul edildiği ve ek lisans harcamadığı.
- REST yanıtında `dataSourceId`'nin universe SI_ID'si olduğu (UNV ve UNX için).

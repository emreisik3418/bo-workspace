# Webi rapor envanteri (WebiDocumentsApp)

Public Folders altındaki Webi raporlarının sorgu (data provider) ve universe bilgilerini
`EXT.EXT_TBOE_SDK_METADATA_WEBIREP` tablosuna yazar. **Bir satır = bir raporun bir sorgusu.**
Günlük çalışacak şekilde **artımlıdır** (bkz. "Günlük çalışma").

## Günlük çalışma (artımlı)
Her ortamda önce DB'deki mevcut durum (`CUID` → `UPDATED_TIME`) okunur, CMS listesiyle karşılaştırılır:

| Durum | Ne olur |
|---|---|
| `NEW`: DB'de yok | REST'ten sorgular okunur, yazılır |
| `CHANGED`: `UPDATED_TIME` farklı | Raporun eski satırları silinir, yenileri yazılır |
| `RETRY`: DB'de sorgusu boş satırı var | Tekrar denenir (önceki REST hatası, `--no-query`, sorgusuz rapor) |
| `UNCHANGED`: aynı | **Dokunulmaz, REST'e gidilmez** (günlük çalışmayı hızlandıran kısım) |
| CMS listesinde yok | DB'den silinir (`DELETED`); sadece liste tam okunduysa |

- **Her rapor işlenir işlenmez DB'ye yazılır** (rapor başına COMMIT) ve **hemen sunucuda kapatılır** (hata olsa da).
  İş yarıda kesilirse o ana kadar yazılanlar kalır; ertesi çalıştırma onları `UNCHANGED` sayar, kalanlardan devam eder.
- REST hatası: **yeni** rapor CMS universe bilgisiyle yazılır (QUERY boş → ertesi gün `RETRY`);
  **DB'de olan** rapora dokunulmaz, eski satırlar korunur (`SKIPPED`), ertesi gün tekrar denenir.
- **Bozuk raporlar:** `WIS 30951` ("document cannot be retrieved from the File Repository Server": dosya depoda yok)
  raporun kendisinin sorunudur: tekrar denenmez, art arda hata sayacına girmez (WARN; CSV `NOT` kolonunda kod).
  İlk tam çalıştırmada GMP03, SI_ID'leri ardışık bozuk bir grup yüzünden 11.309. rapordan sonra durmuştu.
- **REST oturumu yenileme:** diğer REST hatalarında oturum yenilenip (eski oturum logoff) rapor bir kez daha denenir;
  ayrıca 1000 raporda bir oturum düzenli yenilenir.
- Korumalar: art arda 20 raporda REST hatası (sunucu düşmüş olabilir) ya da 5 raporda DB hatası → ortam durur,
  silme yapılmaz. Silinecek rapor mevcutların %10'unu geçerse (≥100 raporda) silinmez → `DELETE_SKIPPED`, `--force` gerekir.
- İlk çalıştırma: tablo boşsa tüm raporlar `NEW` (uzun sürer, mesai sonrası). Sonraki günler sadece fark işlenir.
- Eski (tam silip yazan) sürümle dolmuş tablo olduğu gibi kullanılabilir.

## Dosyalar (`src/com/akbank/analytics/applications/bo/webidocuments/`)
| Dosya | İçerik |
|---|---|
| `WebiDocumentsApp.java` | Ana sınıf: parametreler → CMS → REST → CSV → (--apply) DB → doğrulama |
| `CmsWebiReader.java` | CMS sorguları: klasörler, Webi raporları (sayfalı), veri kaynağı SI_ID → CUID / ad |
| `CmsFolderPaths.java` | Klasör ID → `/Public Folders/...` yolu; kök = SI_ID 23 ya da SI_PARENTID 0 (bu CMS'te üst klasörler 0 geliyor); Public dışı (kişisel, Inbox) için null |
| `RestEndpointConfig.java` | credentials.json'dan `biprwsUrl` / `restToken` |
| `WebiRestClient.java` | RESTful (raylight): `/documents/{id}/dataproviders`, dokümanı kapatma, token yenileme |
| `WebiDocumentsDao.java` | Oracle: mevcut durum, rapor başına sil + yaz (COMMIT), silinen raporlar |
| `SyncPlan.java` | Günlük plan: NEW / CHANGED / RETRY / UNCHANGED / silinecek |
| `CsvOut.java` | Rapor rapor yazılan CSV |
| `DateText.java` | Metin tipindeki tarih kolonlarını okuma / yazma |
| `WebiDocument.java`, `WebiDocumentRow.java` | Rapor ve satır modelleri |

**Main class:** `com.akbank.analytics.applications.bo.webidocuments.WebiDocumentsApp`
(VM options ve working directory için bkz. `README.md`). Toplu çalıştırma: `run_webidocuments.bat`.

## Alanların kaynağı
| Kolon | Kaynak |
|---|---|
| CUID, NAME, DESCRIPTION, KEYWORD | `CI_INFOOBJECTS` `SI_KIND='Webi'`, `SI_INSTANCE=0` |
| OWNER | `SI_OWNER` |
| CREATION_TIME / UPDATED_TIME | `SI_CREATION_TIME` / `SI_UPDATE_TS` |
| PATH | Klasör yolu, rapor adı hariç, başta `/` yok (eski uygulamayla aynı): `Public Folders/AKBANK/Risk` |
| QUERY_ID, QUERY_NAME | REST `GET /raylight/v1/documents/{SI_ID}/dataproviders` → `id` (DP0), `name` |
| DATASOURCE_CUID, DATASOURCE_NAME | Sorgunun `dataSourceId` (SI_ID) → CMS'te `CI_APPOBJECTS`, yoksa `CI_INFOOBJECTS` |
| SYSTEM | CMS sunucusu, **portsuz** (eski uygulamayla aynı): `WBO42GMP03`. Önceki sürümün portlu yazdığı satırlar ilk `--apply`'da bir kez taşınır (UPDATE; yetki yoksa INSERT ... SELECT + DELETE) |
| EXPORT_DATE | Raporun **en son yazıldığı** çalıştırmanın zamanı (değişmeyen raporlarda eski tarih kalır) |
| ROW_ID | Sıra numarası; her yazımda SYSTEM'deki en büyük ROW_ID'nin üstünden devam eder, primary key değil |

REST yeni bir raporu okuyamazsa rapor atlanmaz: CMS'teki `SI_UNIVERSE` (UNV) + `SI_DSL_UNIVERSE` (UNX) ile
universe başına bir satır yazılır, QUERY alanları boş kalır. Sorgusu / universe'ü olmayan rapor da tek satır olarak yazılır.

## Parametreler
```
<credentials.json> [--env HOST:6400 | --env all] [--rest http://HOST:6405/biprws] [--folder "/Public Folders/..."]
                   [--limit N] [--no-query] [--full] [--table SEMA.TABLO] [--apply] [--force]
```
- `--env`: ortam (`system.cms` değeri). **`--env all`: credentials.json'daki tüm ortamlar sırayla**, tek JVM'de.
  Her ortam kendi CMS oturumu, kendi `biprwsUrl`'i, kendi CSV'si (`webidocuments_<HOST_6400>_<zaman>.csv`) ve
  kendi DB yazımıyla (`SYSTEM` = o ortam) işlenir. Bir ortam hata verirse atlanır, diğerleri devam eder;
  sonda `Özet` bölümünde ortam başına sonuç yazar. Çıkış kodu: hepsi başarılı 0, hiçbiri işlenemedi 2, diğer 1.
- `--rest`: RESTful adresi. Öncelik: `--rest` > `credentials.json` `biprwsUrl` > `http://<CMS sunucusu>:6405/biprws`.
  Postman'de çalışan adres aynen verilebilir: sonundaki `/logon/long` vb. atılır, `/biprws` yoksa eklenir.
  Hangi adresin kullanıldığı logda `RESTful adresi: ... (kaynak)` satırında yazar.
- `credentials.json`: mevcut dosyalardaki `biprwsUrl` kullanılır (eski ad `rest` de kabul edilir). Sırayla aranır:
  eşleşen ortamın `system` nesnesi → ortamın kendisi → dosyanın kökü. `RestEndpointConfig` okur (ortak `AppConfig`'e dokunulmadı).
  ```json
  "system": { "cms": "BO-SUNUCU:6400", "biprwsUrl": "https://REST-SUNUCU:6405/biprws", "restToken": "rest" }
  ```
  `restToken` verilirse sadece o yöntem kullanılır (çalışmayan yöntemler boşuna denenmez).
- `--folder`, `--limit`: test için. `--apply` ile kullanılabilir (sadece o raporlar yazılır); liste eksik olduğu için **silme yapılmaz**.
- `--no-query`: REST'e gitmeden yalnız CMS (hızlı); satır = rapor × universe, QUERY alanları boş.
- `--apply`: **verilmezse DRY-RUN**, DB'ye yazılmaz, sadece CSV.
- `--full`: değişmemiş raporlar da yeniden işlenir (ör. kolon mantığı değiştiyse).
- `--force`: silinecek rapor mevcutların %10'unu geçse de sil.
- REST token: işlenecek ilk 5 rapordan biri okunarak sırayla denenir (tek bir bozuk rapor bağlantıyı düşürmesin), çalışan kullanılır (log: "token yöntemi: ..."):
  1. `logon`: CMS oturumundan `createLogonToken`
  2. `serialized`: CMS oturumunun serialize hali
  3. `rest`: REST'in kendi logon'u (`POST /logon/long`, aynı BO kullanıcısı). **+1 oturum/lisans**; iş sonunda logoff.
  Bu sunucuda 1. yöntem `WSR 00999 ... ReportEngineContext ... 'reCtx'` hatası verdi.
  Tek bir yöntemi zorlamak için: `credentials.json` `restToken` ya da VM option `-Dbo.restToken=logon|serialized|rest` (VM option önceliklidir).

## Çıktılar
- `reports\webidocuments_<HOST_6400>_<zaman>.csv` (ortam başına bir dosya, **rapor rapor yazılır**): tablodaki kolonlar +
  `ISLEM`, `DURUM`, `DATASOURCE_TYPE`, `DATASOURCE_ID`, `NOT`. Değişmeyen raporlar CSV'ye yazılmaz (sadece sayısı logda).
- `logs\webidocuments_*.log`

**ISLEM:** `NEW`, `CHANGED`, `RETRY` (yazıldı / dry-run'da yazılacak), `FAILED` (DB hatası), `SKIPPED` (REST hatası,
DB'deki satırlar korundu), `DELETED` / `DELETE` (dry-run) / `DELETE_SKIPPED` (silme koruması).

**DURUM (satır):** `QUERY` (REST'ten sorgu okundu), `NO_QUERY` (raporda sorgu yok), `CMS_ONLY` (--no-query),
`REST_FAILED` (REST hatası; universe CMS'ten).

## DB
- Uzun işten **önce** DB'den mevcut durum okunur (tablo / yetki hatası erken çıkar). Dry-run'da da okunur
  (karşılaştırma için); dry-run'da okunamazsa uyarı verilir, tüm raporlar yeni sayılır.
- Rapor başına: `DELETE ... WHERE SYSTEM = ? AND CUID = ?` + INSERT, hemen COMMIT; hata olursa sadece o rapor rollback.
- DB bağlantısı koparsa bir kez yeniden bağlanıp tekrar denenir.
- Hiç rapor bulunamadıysa DB'ye dokunulmaz.
- **Kolon tipleri otomatik tespit edilir** (log: `DB kolon tipleri: ...`). Eski uygulamanın tablosunda tarih / ROW_ID
  kolonları metin (VARCHAR2) olabilir: o zaman metin olarak okunur, **mevcut veride en sık görülen biçimde** yazılır
  (log: `örnek UPDATED_TIME = "...", yazma biçimi: ...`). Biçimi zorlamak için VM option `-Dbo.dateFormat="dd.MM.yyyy HH:mm:ss"`.
  Okunamayan tarihli raporlar yeniden yazılır (kendiliğinden düzelir); logda örnek değer yazar.
  Tarihler **saniye kesri olmadan** yazılır (`04/07/2018 14:26:29`); tablo boşsa biçim `dd/MM/yyyy HH:mm:ss`.
- İlk sürüm metin kolonlara Oracle'ın varsayılan biçimiyle kesirli yazmıştı (`04/07/2018 14:26:29,176000000`,
  PATH başında `/`). Kesirli tarihi olan raporlar **kendiliğinden bir kez yeniden yazılır** (log: `... satırda tarih saniye
  kesirli ...`); UPDATE yetkisi gerekmez (uygulama zaten DELETE + INSERT kullanır).
- Uzun metinler kolon boyuna kırpılır (`WebiDocumentsDao.LEN_*`); tablo farklı boyutlardaysa sabitleri güncelleyin.

Referans DDL (tablo zaten var; mevcut tabloda tarih / ROW_ID kolonları metin olabilir, uygulama tipleri kendisi tespit eder):
```sql
CREATE TABLE EXT.EXT_TBOE_SDK_METADATA_WEBIREP (
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
-- Rapor bazında silme/yazma için (SYSTEM, CUID) indeksi önemli; yoksa DBA eklemeli
CREATE INDEX EXT.IX_WEBIREP_SYS_CUID ON EXT.EXT_TBOE_SDK_METADATA_WEBIREP (SYSTEM, CUID);
```
Okurken Türkçe metinleri `TO_NCHAR(NAME)` vb. ile seçin (bkz. CLAUDE.md, WE8ISO8859P9).

## Önerilen akış
1. `--limit 20` ile dry-run: CSV'de QUERY / DATASOURCE kolonlarını kontrol edin.
2. `--no-query` ile tam dry-run: toplam rapor sayısını ve süreyi görün.
3. Tam dry-run (REST'li), mesai sonrası. Log'daki "kalan tahmini" süreye bakın.
4. Uygunsa `--apply` (mesai sonrası): `run_webidocuments.bat --env all --apply`.
5. Günlük zamanlanmış görev: aynı komut. İlk gün uzun sürer, sonraki günler sadece fark işlenir.

## Gerçek SDK'da doğrulanacaklar
- `SI_OWNER`, `SI_CREATION_TIME`, `SI_UPDATE_TS` değerlerinin `properties().getProperty(...)` ile String / Date geldiği.
- `SI_UNIVERSE` / `SI_DSL_UNIVERSE` torbalarının `SI_TOTAL` + `"1"`, `"2"` anahtarlarıyla geldiği.
- `getLogonTokenMgr().createLogonToken(...)` token'ının REST'te kabul edildiği ve ek lisans harcamadığı.
- REST yanıtında `dataSourceId`'nin universe SI_ID'si olduğu (UNV ve UNX için).

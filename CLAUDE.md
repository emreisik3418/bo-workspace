# SAP BusinessObjects 4.3 SDK projeleri — çalışma notları

Bu dosya, bu repoda (ve aynı ortamdaki yeni BO projelerinde) çalışırken öğrenilmiş her şeyin özetidir.
Yeni bir oturuma başlarken önce bunu oku. Kullanıcıyla Türkçe konuş.

## Bu repo (bo-workspace)
- Genel BO SDK işleri. Java işleri tek IntelliJ projesinde: `bo-tools/` (kullanıcıda `C:\dev\bo-workspace\bo-tools`),
  her iş kendi paketinde (`com.akbank.analytics.applications.bo.<iş>`), dokümanı `bo-tools/<iş>.md`.
- Universe obje/tablo eşlemesi ayrı iş olarak `universe-object-mapping` reposunda kalır; onunla ilgili işler oraya.
- `bo-tools` içindeki `app`, `config`, `credential`, `model`, `sdk` paketleri universe-object-mapping'dekinin kopyası;
  birinde düzeltme yapılırsa diğerine de taşı. Ortam seçimi + yeniden denemeli logon: `common.BoLogon`.
- Toplu çalıştırma: `bo-tools\run_*.bat` → `C:\batchs\BusinessObjects\BOTOOLS\lib\bo-tools.jar`.
- İşler:
  - `keyword` `ReportKeywordApp`: txt'deki rapor CUID'lerinin Keywords alanına etiket (`Arşiv`) ekler; dry-run / `--apply`,
    CSV yedek + doğrulama. SDK: `IInfoObject.getKeyword/setKeyword/save` (gerçek SDK'da henüz doğrulanmadı).
  - `webidocuments` `WebiDocumentsApp`: Public Folders altındaki Webi raporlarının sorgu (data provider) + universe
    bilgilerini `POPR.SDK_METADATA_WEBIDOCUMENTS_JOB` tablosuna yazar (satır = rapor x sorgu, SYSTEM başına delete+insert).
    Sorgular Webi RESTful (raylight, port 6405). CMS SI_PARENTID: üst klasörler 23 değil 0 geliyor. SDK `createLogonToken`
    ile raylight WSR 00999 (`reCtx` null) verdi → token yöntemleri sırayla denenir (logon / serialized / rest `/logon/long`);
    REST adresi credentials.json'daki mevcut `biprwsUrl` alanından (CMS sunucusundan farklı olabilir).
    REST hatasında CMS `SI_UNIVERSE`/`SI_DSL_UNIVERSE` yedeği. Dry-run = CSV; `--apply` = DB. Ayrıntı: `bo-tools/webidocuments.md`.

## Çalışma şekli (kullanıcı ortamı)
- Kullanıcı bir banka iş bilgisayarında çalışıyor. GitHub'dan **indiremiyor**; dosyaları repodan elle kopyala-yapıştır yapıyor.
  - Dosya güncellerken her zaman "Ctrl+A ile tümünü seçip yapıştırın" de (yoksa eski içerik altta kalıyor → CS1529 / duplicate class).
  - Hangi dosyaların değiştiğini her seferinde tablo halinde listele.
- SDK'yı burada derleyemiyoruz: değişiklikleri **SDK stub'larıyla** `javac --release 8` ile derle, saf mantığı birim test et,
  ama gerçek SDK imzalarından emin değilsen bunu açıkça söyle. Kullanıcı hataları ekran fotoğrafıyla gönderir.
- **Asla repoya koyma:** `credentials.json`, sunucu adları, Sbox endpoint, kullanıcı adları, şifreler.
- BO sunucusunda **580 eşzamanlı lisans** var, gündüz doluyor → uzun işler mesai sonrası; logon'larda yeniden deneme şart.
- Üretim universe'ünü değiştiren her iş: varsayılan **dry-run**, açık `--apply`, önce **yedek**, sonra **doğrulama**.

## Java (UNX, SL SDK) — ortam
- IDE: IntelliJ. JDK: **SAP JVM 8** → `C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0\win64_x64\sapjvm`
  (Java 7 JRE veya başka JDK ile çalışmaz). Kod Java 8 ile uyumlu olmalı.
- IntelliJ projesini **iç klasörden** aç (repo klasörünün içindeki proje klasörü; ör. eski repoda
  `C:\dev\universe-object-mapping\universe-object-mapping`). Dıştan açınca kütüphaneler yüklenmiyor.
- Kütüphaneler:
  - `BO_SDK` = **sadece** `SL SDK\java\sl_sdk.jar` (Compile). Manifest Class-Path'i tüm BO jar'larını yükler.
    Eski "OLD" jar'ları veya tek tek BO jar'larını ekleme → CORBA / `CrystalEnterprise` bulunamadı hataları.
  - `BO_SDK_EDITOR` = SL SDK plugins klasörleri, scope **Provided** (sadece editörde tip çözümü için).
  - `SOAP_AXIS` (Sbox stub'ları), `ORACLE_JDBC`, `JSON` (chargebee org.json — `JSONException` checked!).
- VM options:
  ```
  -Djava.library.path="<BO>\win64_x64"
  -Dbusinessobjects.connectivity.directory="<BO>\dataAccess\connectionServer"
  -Xmx2g
  ```
  Working directory: `...\work` (retrieve/log/rapor klasörleri buraya).
- Toplu çalıştırma: `unx-java\run_unx.bat`, `run_description.bat` (sapjvm java.exe + aynı VM options + `lib\*`).
- `main` sonunda `System.exit(kod)` çağır (BO SDK thread'leri JVM'i açık tutar). Çıkış kodu: 0 ok, 1 kısmi, 2 hata.

## SL SDK kalıpları (doğrulanmış)
- Oturum: `CrystalEnterprise.getSessionMgr().logon(user, pass, "HOST:6400", "secEnterprise")` → try-with-resources, `logoff()` garanti.
- SL bağlamı: `SlContext.create()`; `context.getService(CmsSessionService.class).setSession(enterpriseSession)`.
  Servisler: `CmsResourceService`, `LocalResourceService`, `DataFoundationService`. İşin sonunda `context.close()`.
- Universe listesi: `CI_APPOBJECTS` → `SI_KIND='DSL.Universe'` (UNX), klasörler `SI_KIND='Folder'`; kök klasör ID **95** ("Universes").
  Yolu bellekte kur; ad `.unx` ile bitmiyorsa ekle; "Universes" iki kez yazılmasın. CMS yolu: `/Universes/AKBANK/.../Ad.unx`.
- İndirme: `cmsService.retrieveUniverse(cmsPath, localDir, true)` → `.blx` yolu döner (`saveForAllUsers=true`).
- Açma: `(BusinessLayer) localService.load(blxPath)`; kapatma: `localService.close(resource)`.
- Data foundation: `((RelationalBusinessLayer) bl).getDataFoundationPath()` → `load`. Tablo tam adı:
  `dfService.getTableFullName(t)` + `splitTableFullName` → `parts[1] + "." + parts[2]` = ŞEMA.TABLO.
  `DerivedTable.getExpression()`, `AliasTable.getAliasedTable()` (zincir için derinlik sınırı).
- Business layer gezinme: `bl.getRootFolder().getChildren()`; `BusinessObject` (Dimension/Measure/Attribute) hem obje
  hem `BlContainer` olabilir (altında attribute olan dimension). Yol ayracı `\`.
- Kaydetme: `localService.save(resource, blxPath, true)` (3 parametre!). Yayın: `cmsService.publish(blxPath, cmsFolder, true)`
  (`publishUniverse` değil).
- Bilinen tuzak: **SLS 12077** "The query script property CHECK_INTEGRITY already exists" → universe değişikliksiz bile
  SDK'da kaydedilemiyor. UNV'den dönüştürülmüş universe'lerde BL → Properties → Parameters (Query Script Parameters)
  listesinde standart dışı `CHECK_INTEGRITY=false` vardı; IDT'den silip yayınlamak deneniyor (sonuç: bkz. git log / kullanıcı).
  Teşhis için `DescriptionUpdateApp --diag` (değişikliksiz yerel kaydetme + API dökümü).

## Oracle (Java, ojdbc thin)
- DB karakter seti **WE8ISO8859P9**; `orai18n.jar` YOK. Sonuçları:
  - Metni `TO_NCHAR(...)` (kısa) / `TO_NCLOB(...)` (uzun; NVARCHAR2 2000 sınırı ORA-12899 verir) ile seç,
  - kolonları **sırayla** oku (`rs.getString(1)`) — **adıyla okuma** (`getString("AD")`) "Non supported character set" atar,
  - SQL metninde (yorumlar dahil) Türkçe karakter olmasın. Yazma (bind ile INSERT) sorunsuz çalışıyor.
- Kimlik bilgisi Sbox SOAP servisinden (`SboxCredentialProvider`), `credentials.json`:
  `endpoint, username, password, environments[{appName, subAppName, ip, sensitiveDataName, system:{cms:"HOST:6400"}}], db[{..., jdbcUrl}]`.
  `cms` değeri `HOST:6400` olmalı; DB'de `SYSTEM` kolonu olarak da kullanılıyor.

## Mevcut uygulamalar (`universe-object-mapping` reposunda)
| Uygulama | Ne yapar |
|---|---|
| `unx-java` `UniverseObjectMappingApp` | UNX obje (OM) + tablo (TM) eşlemesi → `POPR.SDK_METADATA_UNIVERSEOM_JOB` / `..._UNIVERSETM_JOB`; SYSTEM başına delete+insert, sadece `.unx` satırları |
| `unx-java` `DescriptionUpdateApp` | Bir UNX'in obje açıklamalarını Oracle sorgusundan (OBJECT_PATH, DESCRIPTION) günceller; dry-run / `--apply` / `--diag`, yedek + doğrulama |
| `unv-csharp` (.NET 4.8 x86, Designer COM `dynamic`) | UNV OM + TM; universe başına yazım, `run` / `retry` / `dbreport` / `purge` |

Ayrıntılar: o reponun kök `README.md` ve `unx-java/README.md` dosyaları.

## Yeni proje / yeni iş eklerken
- Universe eşleme dışı işler `bo-tools` projesine yeni paket + yeni main class olarak eklenir (config, Sbox, BoSession, RunLog ortak kalsın);
  Run Configuration'ı kopyala (Ctrl+D), sadece main class ve argümanları değiştir.
- Ortak kalıplar: `logonWithRetry` (4 deneme, 2 dk), `RunLog` (konsol + `logs\<önek>_*.log`, stack trace sadece dosyada),
  hata veren universe'ü atla ve devam et, boş liste / hiç okunamadıysa DB'ye dokunma.

# bo-tools — genel BO SDK araçları (Java)

Universe eşleme dışındaki BO işleri bu IntelliJ projesinde, her iş kendi paketinde durur.
Universe obje/tablo eşlemesiyle ilgili işler `universe-object-mapping` projesinde kalır.

## Paketler (`src/com/akbank/analytics/applications/bo/...`)
| Paket | İçerik |
|---|---|
| `app` | RunLog (konsol + `logs\<önek>_*.log`) |
| `config` | AppConfig (credentials.json) |
| `credential` | Sbox istemcisi |
| `model` | Credential, BoEnvironment, SboxTarget |
| `sdk` | BoSession (garanti logoff) |
| `common` | BoLogon: ortam seçimi (`--env`) ve yeniden denemeli logon (4 deneme, 2 dk) |
| `keyword` | **ReportKeywordApp**: rapor Keywords alanına etiket ekler → `keyword.md` |

`app`, `config`, `credential`, `model`, `sdk` dosyaları universe-object-mapping'deki ile **aynı**; oradan kopyalandı.
Bir hata düzeltmesi yapılırsa iki projede de güncellenmeli.

## İlk kurulum (bir kez)
1. IntelliJ → New Project → Java, **JDK: SAP JVM 8**
   (`C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0\win64_x64\sapjvm`).
   Proje klasörü: `C:\dev\bo-workspace\bo-tools`. Projeyi her zaman bu klasörden açın.
2. `src` altına bu repodaki paketleri aynı yollarla oluşturun ve dosyaları yapıştırın.
3. **Repoda olmayanlar:** `org.tempuri` ve `org.datacontract` (WSDL'den üretilmiş Sbox stub'ları).
   universe-object-mapping projenizin `src` klasöründen bu iki paketi olduğu gibi kopyalayın.
   `SboxCredentialProvider.java` içindeki 4 DVO sınıfının import'u eksik: Alt+Enter ile ekleyin.
4. Kütüphaneler (Project Structure → Libraries), universe-object-mapping'dekiyle aynı:
   - `BO_SDK` = **sadece** `SL SDK\java\sl_sdk.jar` (Compile)
   - `BO_SDK_EDITOR` = SL SDK plugins klasörleri (Provided)
   - `SOAP_AXIS`, `ORACLE_JDBC`, `JSON` (chargebee org.json)
5. Run Configuration:
   - VM options:
     ```
     -Djava.library.path="C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0\win64_x64"
     -Dbusinessobjects.connectivity.directory="C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0\dataAccess\connectionServer"
     -Xmx2g
     ```
   - Working directory: `C:\dev\bo-workspace\bo-tools\work` (log ve rapor klasörleri buraya yazılır)
   - Her yeni uygulama için bu konfigürasyonu Ctrl+D ile kopyalayıp main class ve argümanları değiştirin.
6. `credentials.json`: `credentials.example.json` biçiminde; universe-object-mapping'de kullandığınız dosya aynen kullanılabilir.
   Repoya **konmaz**.

## Toplu çalıştırma (bat)
`run_*.bat` dosyaları `C:\batchs\BusinessObjects\BOTOOLS` altında şu yapıyı bekler:
`lib\bo-tools.jar` (proje jar'ı), `lib\axis\*`, `lib\oracle\*`, `lib\json\*`, `config\credentials.json`, `work\`.

## Yeni iş eklerken
- Yeni paket + main class; logon için `BoLogon`, log için `RunLog` kullanın.
- Değişiklik yapan her iş: varsayılan dry-run, açık `--apply`, önce yedek (CSV / dosya), sonra doğrulama.
- `main` sonunda `System.exit(kod)`: 0 başarılı, 1 kısmi, 2 hata.

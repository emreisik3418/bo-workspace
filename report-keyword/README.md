# Rapor keyword etiketi (ReportKeywordApp)

Bir txt listesindeki CUID'lere ait raporların **Properties > Keywords** alanına etiket ekler (varsayılan `Arşiv`).

## Kurulum (IntelliJ)
`src/com/akbank/analytics/applications/bo/keyword/` altındaki 3 dosyayı mevcut projenizin (universe-object-mapping)
`src` klasörüne aynı paket yoluyla kopyalayın. Ortak sınıflar (`AppConfig`, `SboxCredentialProvider`, `BoSession`, `RunLog`)
oradan kullanılır, yeni kütüphane gerekmez.

| Dosya | İçerik |
|---|---|
| `ReportKeywordApp.java` | Ana sınıf: liste → CMS sorgusu → plan CSV → (--apply) kaydet → doğrula |
| `CuidList.java` | txt okuma: satır başına bir CUID, boş / `#` satırlar atlanır, tekrarlar bir kez işlenir |
| `KeywordTag.java` | Etiket kuralları: varsa dokunma, yoksa mevcut keyword'lerin sonuna `, Arşiv` ekle |

Run Configuration: `DescriptionUpdateApp`'inkini kopyalayın (Ctrl+D), main class'ı
`com.akbank.analytics.applications.bo.keyword.ReportKeywordApp` yapın. VM options ve working directory aynı kalır.

## Parametreler
```
<credentials.json> <cuid_listesi.txt> [--env HOST:6400] [--tag Etiket] [--apply]
```
- `cuid_listesi.txt`: her satırda bir CUID. CUID'den sonra TAB / `;` / `,` ile başka bilgi gelebilir (ilk parça alınır).
- `--env`: credentials.json'da birden fazla ortam varsa zorunlu.
- `--tag`: başka bir etiket eklemek için (boşluk / virgül içeremez).
- `--apply`: **verilmezse DRY-RUN**, CMS'te hiçbir şey değişmez.

## Çıktılar
- `reports\keyword_<zaman>.csv`: `DURUM;CUID;SI_ID;AD;TUR;ESKI_KEYWORD;YENI_KEYWORD;NOT` (Excel'de açılır).
  `ESKI_KEYWORD` kolonu yedektir: geri almak gerekirse eski değerler buradan yazılır.
- `logs\keyword_*.log`

**Durumlar:** `UPDATE` (dry-run'da eklenecek), `ALREADY_TAGGED` (etiket zaten var, dokunulmadı), `NOT_FOUND` (CUID CMS'te yok),
`INVALID_LINE` (CUID olmayan satır), `UPDATED` (kaydedildi ve tekrar okununca doğrulandı), `FAILED` (kaydedilemedi),
`VERIFY_FAILED` (kaydedildi ama tekrar okununca etiket görülmedi).

## Önerilen akış
1. Dry-run çalıştırın, CSV'de `TUR` kolonuna ve `NOT_FOUND` satırlarına bakın.
2. Uygunsa aynı komutu `--apply` ile çalıştırın (tercihen mesai sonrası).
3. Tekrar çalıştırmak güvenlidir: etiketi olan raporlar `ALREADY_TAGGED` olur, iki kez eklenmez.

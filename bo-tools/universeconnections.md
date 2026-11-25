# Universe connection envanteri (UniverseConnectionsApp)

UNX ve UNV universe'lerin connection bilgilerini tabloya yazar. **Bir satır = bir universe'ün bir connection'ı**
(çok kaynaklı UNX'te universe başına birden fazla satır). Connection'ı olmayan universe ve hiçbir universe'ün
kullanmadığı connection da (karşı taraf boş) yazılır.

Kolonlar: `SYSTEM, UNIVERSE_CUID, UNIVERSE_NAME, UNIVERSE_PATH, CONNECTION_CUID, CONNECTION_NAME, DBMS, DATABASE,
DATABASE_USER, NETWORK_LAYER` (ör. DBMS `Oracle 12c`, DATABASE `EXADATA_STANDBY`). SYSTEM portsuz (`WBO42GMP03`).

## Durum: teşhis aşaması
DBMS / DATABASE / DATABASE_USER / NETWORK_LAYER ve universe -> connection bağlantısının hangi CMS özelliğinde
olduğu henüz doğrulanmadı. Önce teşhis modu çalıştırılır, çıktıya göre asıl yazım eklenir.

**Main class:** `com.akbank.analytics.applications.bo.universeconnections.UniverseConnectionsApp`
(VM options ve working directory webidocuments ile aynı).

```
<credentials.json> --diag [--env HOST:6400] [--count N] [--cuid CUID]
```
- `--diag`: `DSL.Universe`, `Universe`, `CCIS.DataConnection`, `MetaData.DataConnection` türlerinin her birinden
  son N nesnenin (varsayılan 2) **tüm** CMS özelliklerini loga döker; universe'lerde adında `CONN` geçen
  özelliklerdeki SI_ID'leri bulup o nesneleri de döker. Hiçbir yere yazmaz.
- `--cuid`: sadece bu nesne (ve bağlı connection'ları).
- Log: `logs\universeconnections_*.log`.

## Planlanan yazım
- Ortam başına (`--env all`) tüm satırlar silinip yeniden yazılır (universe sayısı az), tek transaction.
- Dry-run varsayılan (CSV), `--apply` ile DB. Kolon tipleri webidocuments'taki gibi otomatik tespit edilir.

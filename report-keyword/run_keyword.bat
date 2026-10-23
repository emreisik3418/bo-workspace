@echo off
rem Listedeki raporlarin Properties > Keywords alanina etiket ekler (varsayilan "Arsiv").
rem Kullanim:
rem   run_keyword.bat <cuid_listesi.txt> [--env HOST:6400] [--tag Etiket] [--apply]
rem --apply verilmezse DRY-RUN: CMS'te hicbir sey degismez, sadece reports\ altina CSV yazilir.
rem Cikis kodu: 0 basarili, 1 kismi (bulunamayan / hata / dogrulanamayan var), 2 hata.
setlocal
set BO=C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0
set APP=C:\batchs\BusinessObjects\UNIVERSEOBJECTMAPPING
set CP=%APP%\lib\universe-object-mapping.jar;%BO%\SL SDK\java\sl_sdk.jar;%APP%\lib\axis\*;%APP%\lib\oracle\*;%APP%\lib\json\*

if not exist "%APP%\work" mkdir "%APP%\work"
cd /d "%APP%\work"

"%BO%\win64_x64\sapjvm\bin\java.exe" -Xmx2g ^
  -Djava.library.path="%BO%\win64_x64" ^
  -Dbusinessobjects.connectivity.directory="%BO%\dataAccess\connectionServer" ^
  -cp "%CP%" com.akbank.analytics.applications.bo.keyword.ReportKeywordApp "%APP%\config\credentials.json" %*

exit /b %ERRORLEVEL%

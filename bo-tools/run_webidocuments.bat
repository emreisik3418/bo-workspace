@echo off
rem Public Folders altindaki Webi raporlarinin sorgu ve universe bilgilerini
rem POPR.SDK_METADATA_WEBIDOCUMENTS_JOB tablosuna yazar.
rem Kullanim:
rem   run_webidocuments.bat [--env HOST:6400] [--rest URL] [--no-query] [--apply] [--force]
rem --apply verilmezse DRY-RUN: DB'ye yazilmaz, sadece reports\ altina CSV yazilir.
rem Cikis kodu: 0 basarili, 1 kismi (REST hatasi olan rapor var / DB'ye yazilmadi), 2 hata.
setlocal
set BO=C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0
set APP=C:\batchs\BusinessObjects\BOTOOLS
set CP=%APP%\lib\bo-tools.jar;%BO%\SL SDK\java\sl_sdk.jar;%APP%\lib\axis\*;%APP%\lib\oracle\*;%APP%\lib\json\*

if not exist "%APP%\work" mkdir "%APP%\work"
cd /d "%APP%\work"

"%BO%\win64_x64\sapjvm\bin\java.exe" -Xmx2g ^
  -Djava.library.path="%BO%\win64_x64" ^
  -Dbusinessobjects.connectivity.directory="%BO%\dataAccess\connectionServer" ^
  -cp "%CP%" com.akbank.analytics.applications.bo.webidocuments.WebiDocumentsApp "%APP%\config\credentials.json" %*

exit /b %ERRORLEVEL%

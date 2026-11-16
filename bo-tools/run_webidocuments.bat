@echo off
rem Public Folders altindaki Webi raporlarinin sorgu ve universe bilgilerini
rem EXT.EXT_TBOE_SDK_METADATA_WEBIREP tablosuna yazar.
rem Kullanim:
rem   run_webidocuments.bat --env all [--no-query] [--full] [--apply] [--force]
rem   run_webidocuments.bat --env HOST:6400 [--rest URL] [--no-query] [--apply] [--force]
rem --env all: credentials.json'daki tum ortamlar sirayla; biri hata verirse digerleri devam eder.
rem Artimli: degismeyen raporlara dokunulmaz, yeni/degisen yazilir, CMS'te olmayan silinir (gunluk calisma icin).
rem Her rapor islenince DB'ye yazilir ve kapatilir; is yarida kesilirse ertesi calisma kaldigi yerden devam eder.
rem --apply verilmezse DRY-RUN: DB'ye yazilmaz, sadece reports\ altina CSV yazilir.
rem Cikis kodu: 0 tum ortamlar basarili, 1 kismi, 2 hicbir ortam islenemedi.
setlocal
set BO=C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0
set APP=C:\batchs\BusinessObjects\WEBIDOCUMENTS
rem Klasor yapisi (IntelliJ > Build Artifacts, bo-tools.jar = sadece modul ciktisi; kutuphaneler elle kopyalanir):
rem   %APP%\lib\bo-tools.jar           bizim siniflar
rem   %APP%\lib\bo-tools_lib\*.jar     axis, ojdbc, json kutuphaneleri (bir kez kopyalanir)
rem   %APP%\config\credentials.json
rem sl_sdk.jar BO kurulumundan ve EN BASTA verilir: manifest Class-Path'i diger BO jar'larini
rem kurulum klasorune gore yukler. bo-tools_lib altina kopyalanan sl_sdk.jar silinmeli.
rem java -jar KULLANILMAZ (kopyalanan sl_sdk.jar'in Class-Path'i bozulur); -cp + main class verilir.
set CP=%BO%\SL SDK\java\sl_sdk.jar;%APP%\lib\bo-tools.jar;%APP%\lib\bo-tools_lib\*

if exist "%APP%\lib\bo-tools_lib\sl_sdk.jar" (
  echo HATA: %APP%\lib\bo-tools_lib\sl_sdk.jar silinmeli, BO kurulumundaki kullanilir.
  exit /b 2
)
if not exist "%APP%\work" mkdir "%APP%\work"
cd /d "%APP%\work"

"%BO%\win64_x64\sapjvm\bin\java.exe" -Xmx2g ^
  -Djava.library.path="%BO%\win64_x64" ^
  -Dbusinessobjects.connectivity.directory="%BO%\dataAccess\connectionServer" ^
  -cp "%CP%" com.akbank.analytics.applications.bo.webidocuments.WebiDocumentsApp "%APP%\config\credentials.json" %*

exit /b %ERRORLEVEL%

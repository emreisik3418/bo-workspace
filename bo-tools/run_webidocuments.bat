@echo off
rem Public Folders altindaki Webi raporlarinin sorgu ve universe bilgilerini
rem EXT.EXT_TBOE_SDK_METADATA_WEBIREP tablosuna yazar.
rem Kullanim:
rem   run_webidocuments.bat --env all [--no-query] [--full] [--apply] [--force]
rem   run_webidocuments.bat --env HOST:6400 [--rest URL] [--no-query] [--apply] [--force]
rem --apply verilmezse DRY-RUN: DB'ye yazilmaz, sadece reports altina CSV yazilir. Log: logs altinda.
rem Cikis kodu: 0 tum ortamlar basarili, 1 kismi, 2 hata.
rem Klasorler: lib\bo-tools.jar, lib\bo-tools_lib\*.jar - axis, ojdbc, json - config\credentials.json
rem sl_sdk.jar BO kurulumundan ve classpath'te en basta; bo-tools_lib altinda kopyasi olmamali.
rem Not: parantezli blok ve satir bolen isaret kullanilmadi: BO yolundaki x86 parantezleri sorun cikarmasin.
setlocal
set "BO=C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0"
set "APP=C:\batchs\BusinessObjects\WEBIDOCUMENTS"
set "CP=%BO%\SL SDK\java\sl_sdk.jar;%APP%\lib\bo-tools.jar;%APP%\lib\bo-tools_lib\*"
set "MAIN=com.akbank.analytics.applications.bo.webidocuments.WebiDocumentsApp"

if exist "%APP%\lib\bo-tools_lib\sl_sdk.jar" goto kopya_sdk
cd /d "%APP%"

"%BO%\win64_x64\sapjvm\bin\java.exe" -Xmx2g "-Djava.library.path=%BO%\win64_x64" "-Dbusinessobjects.connectivity.directory=%BO%\dataAccess\connectionServer" -cp "%CP%" %MAIN% "%APP%\config\credentials.json" %*
exit /b %ERRORLEVEL%

:kopya_sdk
echo HATA: lib\bo-tools_lib\sl_sdk.jar silinmeli, BO kurulumundaki kullanilir.
exit /b 2

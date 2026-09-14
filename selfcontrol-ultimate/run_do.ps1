$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$PKG = "com.jo.selfcontrol.ultimate"
$RECEIVER = "$PKG/com.jo.selfcontrol.ultimate.AdminReceiver"
$xml = "<?xml version=`"1.0`" encoding=`"utf-8`" standalone=`"yes`" ?>`n<root>`n    <device-owner package=`"$PKG`" name=`"Custos`" component=`"$RECEIVER`" userUserId=`"0`" canAccessDeviceIds=`"true`" />`n</root>"
Set-Content -Path "device_owner_2_tmp.xml" -Value $xml
& $adb push device_owner_2_tmp.xml /data/local/tmp/device_owner_2.xml
& $adb shell "su -c 'cp /data/local/tmp/device_owner_2.xml /data/system/device_owner_2.xml && chown system:system /data/system/device_owner_2.xml && chmod 600 /data/system/device_owner_2.xml'"
& $adb reboot

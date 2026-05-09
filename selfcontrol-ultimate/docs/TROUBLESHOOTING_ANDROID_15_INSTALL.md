# Troubleshooting Android 15 Device Owner Install Restrictions

## The Problem

When attempting to install the app via ADB (`.\gradlew installDebug`), the build failed with the following error:

```
java.util.concurrent.ExecutionException: com.android.builder.testing.api.DeviceException: com.android.ddmlib.InstallException: Unknown failure: Exception occurred while executing 'install':
java.lang.SecurityException: User restriction prevents installing
```

### Cause 1: Device Owner Policies
The target device is under a Custom Device Owner policy (`SelfControl Ultimate`), which explicitly restricts app installation via:
- `UserManager.DISALLOW_INSTALL_APPS`
- `UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES`

As documented in `ADB_ONLY_INSTALLS.md`, these restrictions are purposefully set to block indirect installs (Drive, OneDrive, browser downloads, Play Store).

### Cause 2: Android 15 Implicit Broadcast Limitations
To bypass the restriction locally via PC, the `DeviceOwnerHelper` is designed to listen to a backdoor broadcast (`com.jo.selfcontrol.ultimate.ALLOW_INSTALL`). 
However, running the standard command:
```powershell
adb shell am broadcast -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
```
resulted in `result=0`. 

In **Android 15**, implicit broadcasts (broadcasts without a specific target package) are heavily restricted or ignored. The `SelfControl Ultimate` app was not receiving the broadcast, meaning the restrictions were never actually lifted.

### Cause 3: WSL vs Windows Path Conflicts
Attempting to run `./gradlew installDebug` in a WSL terminal failed because the `local.properties` points to a Windows Android SDK path, resolving in `Directory does not exist`.

## The Solution

**1. Lift the Install Restriction (Android 15 Compatible)**
Send the broadcast by explicitly defining the target package using the `-p` parameter so that the intent is explicitly routed to the `SelfControl Ultimate` background receiver:
```powershell
.\adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.ALLOW_INSTALL
```

**2. Install the App via PowerShell**
Use PowerShell instead of WSL so that the Windows Android SDK path inside `local.properties` resolves correctly:
```powershell
.\gradlew installDebug
```

**3. Restore the Security Policies**
After the successful installation, re-enable the installation restrictions using the explicit target broadcast:
```powershell
.\adb shell am broadcast -p com.jo.selfcontrol.ultimate -a com.jo.selfcontrol.ultimate.BLOCK_INSTALL
```

## Update to Scripts
Consider updating `/platform-tools/install_xapk.ps1` or any automation scripts to ensure that `adb shell am broadcast` commands append the `-p com.jo.selfcontrol.ultimate` package parameter to maintain compatibility with Android 15.
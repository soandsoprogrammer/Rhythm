# Requires PowerShell 7 and the Android SDK build-tools.
[CmdletBinding(DefaultParameterSetName = 'File')]
param(
    [Parameter(ParameterSetName = 'File')] [string] $ApkPath,
    [Parameter(Mandatory, ParameterSetName = 'Run')] [long] $RunId,
    [Parameter(Mandatory, ParameterSetName = 'Initialize')] [switch] $Initialize,
    [Parameter(Mandatory, ParameterSetName = 'Recovery')] [switch] $CopyRecoveryPassword,
    [Parameter(Mandatory, ParameterSetName = 'Restore')] [string] $RestoreKey,
    [Parameter(Mandatory, ParameterSetName = 'Restore')] [string] $ExpectedFingerprint,
    [Parameter(ParameterSetName = 'File')]
    [Parameter(ParameterSetName = 'Run')] [string] $OutputDirectory,
    [string] $JavaDirectory = 'C:\Program Files\Android\Android Studio\jbr',
    [string] $AndroidSdk = (Join-Path $env:USERPROFILE 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$keyRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'ShenyeSigning'
$backupRoot = Join-Path ([Environment]::GetFolderPath('MyDocuments')) '声页签名备份'
$keystore = Join-Path $keyRoot 'shenye-release.p12'
$passwordFile = Join-Path $keyRoot 'password.dpapi'
$fingerprintFile = Join-Path $keyRoot 'certificate-sha256.txt'
$aliasName = 'shenye-release'
$java = Join-Path $JavaDirectory 'bin\java.exe'
$keytool = Join-Path $JavaDirectory 'bin\keytool.exe'

function New-PrivateDirectory([string] $Directory) {
    if (-not (Test-Path -LiteralPath $Directory)) {
        New-Item -ItemType Directory -Path $Directory | Out-Null
    }
    $item = Get-Item -LiteralPath $Directory
    if (-not $item.PSIsContainer -or $item.Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw "Unsafe signing directory: $Directory"
    }
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($owner)
    $acl.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($sid in @($owner, $system)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid, 'FullControl', $inherit, 'None', 'Allow'
        )
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Directory -AclObject $acl
}

function Import-Password {
    if (-not (Test-Path -LiteralPath $keystore) -or -not (Test-Path -LiteralPath $passwordFile)) {
        throw 'The fixed Shenye signing key is unavailable. Restore it instead of generating a replacement.'
    }
    return ((Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString)
}

function Invoke-WithPassword([Security.SecureString] $Password, [scriptblock] $Action) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
    try {
        $env:SHENYE_APK_KEYSTORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        & $Action
    } finally {
        Remove-Item Env:SHENYE_APK_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Get-CertificateFingerprint([string] $Store) {
    $cert = Join-Path $keyRoot ("cert-" + [guid]::NewGuid().ToString('N') + '.der')
    try {
        & $keytool -exportcert -alias $aliasName -keystore $Store -storetype PKCS12 -storepass:env SHENYE_APK_KEYSTORE_PASSWORD -file $cert 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $cert)) {
            throw 'Could not read the Shenye signing certificate.'
        }
        return (Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash.ToLowerInvariant()
    } finally {
        if (Test-Path -LiteralPath $cert) { Remove-Item -LiteralPath $cert }
    }
}

function Write-KeyBackup {
    New-PrivateDirectory $backupRoot
    $copy = Join-Path $backupRoot 'shenye-release.p12'
    $digest = (Get-FileHash -LiteralPath $keystore).Hash
    if (Test-Path -LiteralPath $copy) {
        if ((Get-FileHash -LiteralPath $copy).Hash -ne $digest) {
            throw 'A different keystore already exists at the backup location.'
        }
    } else {
        Copy-Item -LiteralPath $keystore -Destination $copy
    }
    Copy-Item -LiteralPath $fingerprintFile -Destination (Join-Path $backupRoot 'certificate-sha256.txt')
}

if ($CopyRecoveryPassword) {
    $password = Import-Password
    Invoke-WithPassword $password { Set-Clipboard -Value $env:SHENYE_APK_KEYSTORE_PASSWORD }
    Write-Output 'Recovery password copied to your Windows clipboard. Save it separately from the encrypted key in your password manager.'
    return
}

if (-not (Test-Path -LiteralPath $keytool) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Java runtime or keytool not found. Pass -JavaDirectory if Android Studio is installed elsewhere.'
}

if ($Initialize -or $RestoreKey) {
    New-PrivateDirectory $keyRoot
    if ($RestoreKey) {
        if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $passwordFile)) {
            throw 'A local signing identity already exists.'
        }
        $source = (Resolve-Path -LiteralPath $RestoreKey).Path
        $password = Read-Host 'Enter the key backup password' -AsSecureString
        $fingerprint = Invoke-WithPassword $password { Get-CertificateFingerprint $source }
        if ($fingerprint -ne (($ExpectedFingerprint -replace ':','').ToLowerInvariant())) {
            throw 'The restored key does not match the expected certificate.'
        }
        Copy-Item -LiteralPath $source -Destination $keystore
    } elseif ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $passwordFile)) {
        $password = Import-Password
        $fingerprint = Invoke-WithPassword $password { Get-CertificateFingerprint $keystore }
        if ((Get-Content -LiteralPath $fingerprintFile -Raw).Trim() -ne $fingerprint) {
            throw 'The signing key no longer matches its original fingerprint.'
        }
        Write-KeyBackup
        Write-Output "Shenye signing certificate SHA-256 $fingerprint"
        return
    } else {
        $secret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $password = ConvertTo-SecureString -String $secret -AsPlainText -Force
        $secret = $null
        $tempKey = Join-Path $keyRoot 'new-shenye-release.p12'
        if (Test-Path -LiteralPath $tempKey) { throw 'An unfinished key already exists.' }
        Invoke-WithPassword $password {
            & $keytool -genkeypair -noprompt -keystore $tempKey -storetype PKCS12 -storepass:env SHENYE_APK_KEYSTORE_PASSWORD -alias $aliasName -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 18250 -dname 'CN=Shenye APK Release'
            if ($LASTEXITCODE -ne 0) { throw 'Could not create the Shenye signing key.' }
        }
        $fingerprint = Invoke-WithPassword $password { Get-CertificateFingerprint $tempKey }
        Move-Item -LiteralPath $tempKey -Destination $keystore
    }
    ConvertFrom-SecureString -SecureString $password | Set-Content -LiteralPath $passwordFile -Encoding utf8NoBOM
    Set-Content -LiteralPath $fingerprintFile -Value $fingerprint -Encoding utf8NoBOM
    Write-KeyBackup
    Write-Output "Shenye signing certificate SHA-256 $fingerprint"
    return
}

$password = Import-Password
$expected = (Get-Content -LiteralPath $fingerprintFile -Raw).Trim()
$tools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'lib\apksigner.jar')) } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $tools) { throw 'Android SDK build-tools not found.' }
$signer = Join-Path $tools.FullName 'lib\apksigner.jar'
$aapt = Join-Path $tools.FullName 'aapt2.exe'
$zipalign = Join-Path $tools.FullName 'zipalign.exe'

$staging = Join-Path $keyRoot ('staging-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    if ($RunId) {
        gh run download $RunId --repo soandsoprogrammer/Rhythm --name Shenye-android-unsigned --dir $staging
        if ($LASTEXITCODE -ne 0) { throw 'GitHub Actions could not download the unsigned APK.' }
        $ApkPath = Join-Path $staging 'Shenye-android-unsigned.apk'
        $checksum = [regex]::Match((Get-Content -LiteralPath (Join-Path $staging 'SHA256SUMS.txt') -Raw), '(?im)^([a-f0-9]{64})\s+\*?Shenye-android-unsigned\.apk\s*$')
        if (!$checksum.Success -or (Get-FileHash -LiteralPath $ApkPath).Hash -ine $checksum.Groups[1].Value) {
            throw 'APK checksum did not match the CI artifact.'
        }
    }
    if (!$ApkPath) { throw 'Pass an APK path or a GitHub Actions run ID.' }
    $inputApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $badging = (& $aapt dump badging $inputApk) -join [char]10
    if ($LASTEXITCODE -ne 0) { throw 'The APK manifest could not be read.' }
    $manifest = [regex]::Match($badging, "(?m)^package: name='app\.shenye\.music' versionCode='\d+' versionName='([\d.]+)(?:-gh)?'")
    if (!$manifest.Success) { throw 'APK is not a Shenye build with a supported version.' }
    & $zipalign -c 4 $inputApk
    if ($LASTEXITCODE -ne 0) { throw 'Input APK is not aligned.' }

    if (!$OutputDirectory) {
        $downloads = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
        $downloadsId = '{374DE290-123F-4565-9164-39C4925E467B}'
        $OutputDirectory = [Environment]::ExpandEnvironmentVariables((Get-ItemProperty -LiteralPath $downloads -Name $downloadsId).$downloadsId)
    }
    if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) { throw 'Output directory does not exist.' }

    $unsignedVersion = $manifest.Groups[1].Value
    $temporaryApk = Join-Path $staging 'signed.apk'
    Invoke-WithPassword $password {
        $fingerprint = Get-CertificateFingerprint $keystore
        if ($fingerprint -ne $expected) { throw 'The signing key does not match its original certificate.' }
        & $java -jar $signer sign --ks $keystore --ks-type PKCS12 --ks-key-alias $aliasName --ks-pass 'env:SHENYE_APK_KEYSTORE_PASSWORD' --out $temporaryApk $inputApk
        if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    }

    $verified = (& $java -jar $signer verify --print-certs $temporaryApk) -join [char]10
    if ($LASTEXITCODE -ne 0) { throw 'The signed APK failed verification.' }
    $signers = [regex]::Matches($verified, '(?im)^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$')
    if ($signers.Count -ne 1 -or $signers[0].Groups[1].Value -ine $expected) {
        throw 'The resulting APK does not have the expected signing certificate.'
    }

    $destination = Join-Path $OutputDirectory "Shenye-android-$unsignedVersion-stable.apk"
    if (Test-Path -LiteralPath $destination) {
        if ((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $temporaryApk).Hash) {
            throw "A different APK already exists: $destination"
        }
    } else {
        Copy-Item -LiteralPath $temporaryApk -Destination $destination
    }
    Write-Output $destination
    Write-Output "Verified signing certificate SHA-256 $expected"
} finally {
    $safeRoot = (Resolve-Path -LiteralPath $keyRoot).Path.TrimEnd('\') + '\'
    $stagePath = (Resolve-Path -LiteralPath $staging).Path
    if ($stagePath.StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase) -and
        ((Split-Path -Leaf $stagePath) -match '^staging-[a-f0-9]{32}$')) {
        Remove-Item -LiteralPath $stagePath -Recurse -Force
    }
}


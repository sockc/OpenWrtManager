# 固定签名规则

本项目的正式 APK 必须始终使用同一把 release keystore。Android 更新校验同时依赖 `applicationId` 与签名证书；任意一个变化都会被视为不同应用或无法覆盖更新。

固定值：

- applicationId: `com.openwrtmanager.mobile`
- release key alias: `openwrt-manager`
- 正式签名：使用交付的 `openwrt-manager-release.jks`

## 本地构建

把签名备份包中的 JKS 放到项目 `release-signing/`，再把凭据写入根目录 `signing.properties`：

```properties
storeFile=release-signing/openwrt-manager-release.jks
storePassword=...
keyAlias=openwrt-manager
keyPassword=...
```

然后：

```bash
gradle :app:assembleRelease
```

## GitHub Actions

仓库 Secrets 需要固定保存以下四项：

- `SIGNING_KEY_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

其中 `SIGNING_KEY_BASE64` 由：

```bash
./scripts/keystore_to_base64.sh /path/to/openwrt-manager-release.jks
```

生成。

**不要每个版本重新生成 keystore。** 丢失 release keystore 后，已安装正式版将无法通过普通 APK 覆盖升级。

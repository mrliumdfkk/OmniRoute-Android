# OmniRoute Android APK — 构建笔记

> 从零构建 OmniRoute Android 单机网关 APK 的完整流程与踩坑记录。
> 最终产物：`app/build/outputs/apk/debug/app-debug.apk` (~745MB)

---

## 工具链位置（重要）

沙箱会周期性清空 `/opt` 和 `/tmp`，工具链**不能**放在那里。所有构建工具统一放在：

```
/workspace/toolchain/
├── gradle/                      Gradle 8.14.3（146MB）
├── gradle.zip                   原始压缩包，便于重建
├── android-sdk/                 build-tools 36.0.0 + platforms android-36 + platform-tools
├── x86_64-libs/                 aapt2 所需的 x86_64 glibc
│   ├── root/                    解包内容
│   └── debs/                    原始 .deb（libc6/libstdc++6/libgcc-s1/zlib1g）
├── aapt2-shim/aapt2             QEMU 包装脚本（相对路径解析，不硬编码）
└── setup-toolchain.sh           一键恢复脚本
```

### 被清空后如何恢复

```bash
bash /workspace/toolchain/setup-toolchain.sh
```

脚本会：安装/确认 JDK 17 → 解压 Gradle → 安装 QEMU → 恢复 x86_64 库 → 验证 aapt2 shim → 重写 `local.properties` 和 `gradle.properties` 的路径。

> 唯一必需的例外：x86_64 的 glibc 库必须落在系统路径 `/lib/x86_64-linux-gnu` 和 `/lib64`（QEMU 硬编码查找），所以脚本从 `/workspace/toolchain/x86_64-libs/debs` 重新解包安装。`.deb` 源在 workspace 里，可随时重建。

### 构建命令

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/workspace/toolchain/android-sdk
export PATH=/workspace/toolchain/gradle/bin:$JAVA_HOME/bin:$PATH
cd /workspace/omniroute-android && gradle :app:assembleDebug
```

---

## 架构决策：proot + PROOT_LOADER（SIGSYS 的根因与解法）

这一节记录本项目最曲折的一段排查。**结论先行**：Android 上跑 proot 必须通过
`PROOT_LOADER` 环境变量提供一个独立的 loader 文件，否则必然死于 SIGSYS / SIGSEGV。

### 症状演变

| 阶段 | 表现 |
|---|---|
| 早期 | `proot exited with code 159`，App 日志**完全空白** |
| 加诊断后 | `node killed by signal 31`（SIGSYS） |
| 最终根因 | `proot -v 9` 显示 `loader: /tmp/prooted-XXXX` 之后 **SIGSEGV (signal 11)** |

### 排查过程中被推翻的假设

这些方向都试过，**全是错的**，记录下来避免重走：

| 假设 | 验证方式 | 实际结果 |
|---|---|---|
| seccomp 拦截 `process_vm_*` / `ptrace` | 静态探针逐个调用 30 个系统调用 | **全部 ALLOW**，无一个被杀 |
| targetSdk ≥ 29 禁止 exec 私有目录 | 降到 28，查 logcat | `avc: granted { execute }` — 允许 |
| proot 加速器冲突 | 编译掉 `CHECK_FEATURES`、设 `PROOT_NO_SECCOMP=1` | 仍然失败 |
| 应该放弃 proot，直接执行 node | 实测本机可跑 | 本机可行但手机仍失败，且丢失文件系统隔离 |
| 必须降 targetSdk | 参考 RikkaHub | **它用 targetSdk 37 跑正常** |

### 真正的根因

proot 默认把**内置 loader** 写到临时文件：

```
proot info: loader: /tmp/prooted-28519-s57WpN
proot info: vpid 1: terminated with signal 11
```

这段加载逻辑需要**匿名内存的 `PROT_EXEC` 映射**。Android 应用沙箱的 W^X 策略
（`execmem`）直接拒绝，guest 进程在打印任何内容前就段错误。

这是 **loader 加载方式**的问题，不是 proot 版本、不是 seccomp、也不是权限，
所以任何编译选项和 targetSdk 调整都无效。

### 解法来源：逆向 RikkaHub

RikkaHub（`rikkahub-2.5.5.zip`）在 Android 上稳定运行 proot，它用了**两个独立 .so**：

| 文件 | 大小 | 类型 | 作用 |
|---|---|---|---|
| `libproot_exec.so` | 267,752 B | `DYN`，NEEDED `liblog.so` / `libc.so` | proot 主程序（Termux 版） |
| `libproot_loader.so` | **18,136 B** | **`EXEC` 静态**，入口 `0x2000000000` | syscall 注入 loader |

关键调用只有一行（`ProotShellRunner.kt`）：

```kotlin
environment()["PROOT_LOADER"] = loader.absolutePath
```

**为什么这能规避 W^X**：`libproot_loader.so` 是一个预先存在的真实文件，
proot 直接**文件映射**它，不需要构造匿名可执行页。看它的反汇编，只做两个系统调用：

```asm
mov w8, #0xde      // 222 = execveat
mov w8, #0x38      // 56  = openat
svc #0x0
```

**没有任何 `mmap`**。而 proot 源码里的优先级是（`src/execve/enter.c:566`）：

```c
loader_path = loader_path ?: getenv("PROOT_LOADER") ?: extract_loader(tracee, false);
```

即：先用环境变量，找不到才回退到"自己写临时文件 + 映射"的老路——而那条路在
Android 上必死。

### loader 的 ELF 特征（可作为完整性校验）

```asm
Type:    EXEC (Executable file)
Machine: AArch64
Entry:   0x2000000000
LOAD    0x0000  0x2000000000  R E   ← 固定加载地址，Align 0x4000
无 dynamic section（纯静态）
```

MD5（RikkaHub 2.5.5 版本）：

```
libproot_loader.so   64d034acfeff8662706db0ee9f18283d
libproot_exec.so     4a268714a597b0aea26b99924a760a5b
```

### 实施要点

**1. 两个 .so 必须放 `nativeLibraryDir`**

Android 只允许 exec 该目录和系统路径。放到 `filesDir` 的文件带 `app_data_file`
SELinux 标签，会被拒绝（实测报错 `execve: Permission denied`）。

之所以它们能放 `jniLibs`，是因为同名 `lib*.so` 会被 AGP 当作原生库处理，
安装时解压到 `nativeLibraryDir`。

**2. 必须开 `extractNativeLibs`**

AGP 默认 `useLegacyPackaging=false`，原生库会以压缩形式留在 APK 里，
`nativeLibraryDir` 运行时是空的（这正是早期 `libproot.so missing` 的原因）。

```gradle
packaging {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

**3. 启动命令**

```java
List<String> command = new ArrayList<>();
command.add(proot.getAbsolutePath());
command.add("--root-id");
command.add("--link2symlink");
command.add("--kill-on-exit");
command.add("-r"); command.add(rootfs.getAbsolutePath());
command.add("-w"); command.add("/");
// 持久化数据挂进 guest，rootfs 升级不会丢数据
command.add("-b"); command.add(dataDir.getAbsolutePath() + ":" + GUEST_DATA_DIR);
for (String path : new String[]{"/dev", "/proc", "/sys"}) {
    if (new File(path).exists()) { command.add("-b"); command.add(path); }
}
command.add("/bin/sh");
command.add("/entrypoint.sh");
```

环境变量：

```java
env.put("PROOT_LOADER",  loader.getAbsolutePath());   // ★ 决定性的一行
env.put("PROOT_TMP_DIR", dataDir.getAbsolutePath());
env.put("TMPDIR",        dataDir.getAbsolutePath());
env.put("HOME",          GUEST_DATA_DIR);
env.put("DATA_DIR",      GUEST_DATA_DIR);
env.put("PORT",          "20128");
env.put("NODE_ENV",      "production");
```

**4. targetSdk 不需要降级**

用 `targetSdk 37` 正常工作。之前降到 28 是基于错误的假设，已回退。

### 日志可读性修复（顺带）

proot 的致命错误**不带换行**，`BufferedReader.readLine()` 会永久阻塞，
导致失败时日志全空。改为按块 `read()` 且每次 flush：

```java
char[] buffer = new char[4096];
int count;
while ((count = reader.read(buffer)) != -1) {
    writer.write(buffer, 0, count);
    writer.flush();
}
```

同时 `exitDetail()` 会把信号名翻译成人话：

```
(proot killed by signal 31 = SIGSYS: a syscall was blocked by the app's seccomp policy)
```

### 最终验证结果

真机安装后：

```
listening on 20128
```

仪表盘正常加载并显示登录页。**问题从"核心失败"变为"功能可用"。**

### 排查工具（可复用）

| 工具 | 位置 | 用途 |
|---|---|---|
| 系统调用探针 | `/workspace/syscall-probe/probe.c` | 逐个测试 30 个 syscall 是否被 seccomp 杀 |
| 静态 proot | `/workspace/proot-build/proot/` | 对比验证用的静态编译版 |
| RikkaHub 参考实现 | `/workspace/rikka-src/` | 逆向来源，看 `ProotShellRunner.kt` |

---

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| 宿主机 | Ubuntu 24.04 LTS (arm64 / aarch64) | x86_64 亦可，但需 QEMU 用户态模拟或交叉编译 |
| JDK | OpenJDK 17 | `apt install openjdk-17-jdk` |
| Android SDK | build-tools 36.0.0, platforms android-36 | `sdkmanager` 安装 |
| Gradle | 8.14.3 | 位于 `/workspace/toolchain/gradle`（勿放 `/opt`，会被清空） |
| debootstrap | 1.0.134ubuntu2 | `apt install debootstrap` |
| Node.js | 22.23.3 (arm64) | 从 nodejs.org 下载 tar.xz |
| 磁盘 | ≥ 30G 可用 | rootfs 解压后约 3.4G，构建临时文件约 2G |

---

## 构建步骤

### Step 0: 恢复工具链（先做这一步）

```bash
bash /workspace/toolchain/setup-toolchain.sh
```

脚本会自动完成 JDK 17、Gradle 8.14.3、Android SDK、QEMU、x86_64 glibc、
aapt2 shim 的安装与路径配置。**不要手工装到 `/opt`**，那个目录会被沙箱清空。

细节见上文「工具链位置」章节。

### Step 1 & 2: JDK / Android SDK / aapt2（由 setup-toolchain.sh 处理）

如果需要理解脚本做了什么，或要手工复现：

**JDK + Android SDK**

```bash
apt-get install -y openjdk-17-jdk build-essential python3 curl unzip

mkdir -p /workspace/toolchain/android-sdk/cmdline-tools
cd /workspace/toolchain/android-sdk/cmdline-tools
curl -OL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest

export ANDROID_HOME=/workspace/toolchain/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "build-tools;36.0.0" "platforms;android-36" "platform-tools"
```

**aapt2 架构问题（仅 arm64 构建机）**

Google 只发布 x86_64 的 aapt2，arm64 上必须经 QEMU 用户态模拟：

```bash
apt-get install -y qemu-user-static

cat > /workspace/toolchain/aapt2-shim/aapt2 <<'SHIM'
#!/bin/bash
# 相对自身位置解析，工具链可整体搬移
SHIM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN_DIR="$(cd "$SHIM_DIR/.." && pwd)"
exec /usr/bin/qemu-x86_64-static \
  "$TOOLCHAIN_DIR/android-sdk/build-tools/36.0.0/aapt2" "$@"
SHIM
chmod +x /workspace/toolchain/aapt2-shim/aapt2
```

再让 Gradle 使用它（`gradle.properties`）：

```
android.aapt2FromMavenOverride=/workspace/toolchain/aapt2-shim/aapt2
```

**QEMU 还需要 x86_64 的 glibc**，而 arm64 的 apt 源没有 amd64 包，
所以要手动解包 `.deb` 到系统路径（`/lib/x86_64-linux-gnu`、`/lib64`），
`.deb` 缓存于 `/workspace/toolchain/x86_64-libs/debs/`，`setup-toolchain.sh` 会自动重装。

> 踩坑：Gradle 默认下载 x86_64 aapt2 二进制，在 arm64 上 `exec format error`。
> QEMU 透明二进制模拟（binfmt）在这个 proot 容器里挂不上，所以用 shim 包装脚本。

### Step 3: 获取 proot 二进制（libproot_exec.so + libproot_loader.so）

**不要自己编译 proot。** 必须使用带 `PROOT_LOADER` 支持且配套独立 loader 的版本，
目前验证可用的是 RikkaHub 内置的 Termux 版：

```bash
# 从 RikkaHub APK/源码包提取
RIKKA=/workspace/rikka-src/rikkahub-2.5.5/workspace/src/main/jniLibs/arm64-v8a
DST=/workspace/omniroute-android/app/src/main/jniLibs/arm64-v8a
mkdir -p "$DST"
cp "$RIKKA/libproot_exec.so"   "$DST/"
cp "$RIKKA/libproot_loader.so" "$DST/"

# 完整性校验（对照上文 MD5）
md5sum "$DST"/libproot_*.so
```

> 为什么不能自己编译：早期用 `make STATIC=1` 编出的单一静态 proot 在 Android 上
> **必死**（loader 走匿名 PROT_EXEC 路径）。问题不在编译参数，而在 loader 的加载方式，
> 详见上文「架构决策」章节。

> 命名规则：必须叫 `lib*.so` 才会被 AGP 当作原生库并解压到 `nativeLibraryDir`，
> 而 Android 只允许 exec 该目录。

### Step 4: 下载 Node.js (arm64)

```bash
curl -fsSL https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-arm64.tar.xz \
  -o /workspace/pkgs/node-22.23.3-linux-arm64.tar.xz
```

> OmniRoute engines 要求 Node ≥ 22.22.2。22.23.3 是 2026-10-02 可用最新 LTS。

### Step 5: 下载 + 预构建 OmniRoute

```bash
# 下载 npm 发布包（自带预构建的 dist/server.js，省 Next.js 编译）
npm pack omniroute@latest
# → omniroute-3.8.51.tgz, ~132MB

# 解包
mkdir -p /workspace/pkgs/omniroute-pkg
cd /workspace/pkgs/omniroute-pkg
tar xzf /workspace/pkgs/omniroute-3.8.51.tgz --strip-components=1

# 仅装生产依赖（跳过 dev 和可选依赖，排除 Node 原生模块）
npm install --omit=dev --omit=optional --ignore-scripts --legacy-peer-deps

# better-sqlite3 是 optionalDependency，必须手动装
npm install better-sqlite3@13.0.3 --ignore-scripts --no-save --legacy-peer-deps

# 安装 arm64 预编译原生模块
mkdir -p node_modules/better-sqlite3/build/Release
cp node_modules/better-sqlite3/prebuilds/linux-arm64.node \
   node_modules/better-sqlite3/build/Release/better_sqlite3.node
```

> 踩坑 1：`npm install --omit=optional` 跳过了 better-sqlite3，它是可选依赖。
> better-sqlite3 的 prebuilds 自带 arm64 原生二进制，不需要源码编译。
>
> 踩坑 2：npm 包 dist/ 不含 node_modules。Next standalone 的 `server.js` 期望
> 在 working directory（或 `NODE_PATH`）能找到 `node_modules`。需要把平台
> node_modules 安置在 `/opt/omniroute/node_modules`。
>
> 踩坑 3：`marked-terminal@7.3.0` 有 peer dep 冲突，必须用 `--legacy-peer-deps`。
> 上游 CI 就是 `npm ci --legacy-peer-deps`。

### Step 5a: 运行 upstream postinstall

```bash
node scripts/build/postinstall.mjs
```

该脚本做：
- 把 `better-sqlite3`/`wreq-js`/`sql.js`/`node-machine-id` 的原生二进制从平台 `node_modules/` 拷入 `dist/node_modules/`
- 复制 LLMLingua SLM 可选依赖
- 补丁 `playwright-core` Android 兼容
- 生成 `.env`　← **⚠️ 见 Step 7b（但注意：`.env` 不是解决首装闸门的正确手段）**

> **踩坑（2026-10-07）**：手工组装全量包时，如果只有 `.env.example` 而没有 `.env`，
> 仪表盘首装向导会卡在「此连接未被识别为本地连接」那道闸门上。
>
> 但**"补一个 `.env` 文件"通常并不能修好它** —— 那道闸门读的是
> `process.env.INITIAL_PASSWORD`，而 `.env` 要被加载成环境变量，
> 必须经过官方的 `bootstrapEnv()`，且文件位置要对。
> 可靠做法是由 App 启动时**直接注入进程环境变量**，详见 **Step 7b**。

### Step 6: Bootstrap Debian rootfs

```bash
mkdir -p /workspace/rootfs-tmp
debootstrap --arch=arm64 --variant=minbase \
  --include=ca-certificates \
  bookworm /workspace/rootfs-tmp

# 装 Node 到 rootfs
mkdir -p /workspace/rootfs-tmp/usr/local
tar xJf /workspace/pkgs/node-22.23.3-linux-arm64.tar.xz \
  --strip-components=1 -C /workspace/rootfs-tmp/usr/local/
```

> 遇到 `W: Failure trying to run: chroot /workspace/rootfs-tmp /sbin/ldconfig`
> 可忽略——在 proot 容器里 ldconfig 以 CAP_SYS_ADMIN 失败，proot 运行时会自己搞定。

### Step 7: 拷贝 OmniRoute 进 rootfs + 裁剪体积

```bash
# dist/ + node_modules/ 流式拷贝（cp -a 太慢，tar 管道更快）
cd /workspace/pkgs/omniroute-pkg
tar cf - dist node_modules .env | tar xf - -C /workspace/rootfs-tmp/opt/omniroute/ \
  --transform 's|^dist|.|' --transform 's|^node_modules|node_modules|' --transform 's|^\.env|.env|'

# 入口脚本
cat > /workspace/rootfs-tmp/entrypoint.sh <<'EOF'
#!/bin/sh
set -e
export NODE_ENV=production
export PORT="${PORT:-20128}"
export DATA_DIR="${DATA_DIR:-/data}"
export HOSTNAME="${HOSTNAME:-127.0.0.1}"
mkdir -p "$DATA_DIR" 2>/dev/null || true
cd /opt/omniroute
exec /usr/local/bin/node server.js
EOF
chmod +x /workspace/rootfs-tmp/entrypoint.sh
```

### Step 7b: `.env` 必须随包分发 — 否则触发首装闸门（★ 重要）

> 本节记录 2026-10-07 真机调试踩到的坑。**全量包组装时最容易漏掉这一步**，
> 症状具有迷惑性（看起来像网络/权限问题），实际只是少了一个文件。

#### 症状

全量包导入成功、服务正常启动（日志里 `✓ Ready in 0ms`、192 条迁移全应用、
`[EmbedWsProxy] Listening on 127.0.0.1:20131`、`[LiveWS] Listening on 127.0.0.1:20132` 全部正常），
但仪表盘首装向导卡在最后一步，显示：

```
此连接未被识别为本地连接（使用 Docker 端口转发时很常见）。
请在服务器/容器日志中查找一次性引导令牌，并将其粘贴到上方以继续。
```

服务端日志同时打印：

```
[BOOTSTRAP] Fresh install detected from a non-loopback peer
(e.g. a Docker port-forwarded connection) with no password configured yet.
Paste this ONE-TIME bootstrap token into the onboarding wizard to continue: <token>
```

#### 根因

OmniRoute 的仪表盘鉴权中间件里有这样一段判定（从 `dist/.build/next/server/chunks/` 反查得到）：

```js
if (!hasPassword(settings) && !hasOidc(settings)
    && !process.env.INITIAL_PASSWORD) {
    // 未设密码 + 连接未被判为 loopback → 要求一次性引导令牌
}
```

闸门触发需要**同时满足两条**：

1. **尚未设置仪表盘密码** —— 即 `process.env.INITIAL_PASSWORD` 未设置
2. **连接被判为非 loopback** —— 判定链为
   `x-omniroute-peer-ip` → `x-omniroute-peer-locality` → `request.ip ?? request.socket.remoteAddress`
   交给 `classifyHostLocality()`

第 2 条在 Android WebView 里**必然成立**：WebView 发出的请求里 `request.ip` 通常是 `undefined`，
又不带 `x-forwarded-for` 之类的转发头，于是落到 `remote` 分支。它与 Docker 端口转发是同一个机制 ——
**你确实是从本机连的，只是服务端没拿到可信的 loopback 地址。**

#### ★ 为什么"放个 `.env` 进去"通常没用（关键坑）

注意上面判定读的是 **`process.env.INITIAL_PASSWORD`（进程环境变量）**，
**不是**"某个文件里写了这一行"。把 `.env` 拷进 rootfs 某个目录，并不等于它会被加载成环境变量。

官方启动链是这样的（`opt/OmniRoute/resources/app`）：

```
run-standalone.mjs                       ← 官方真正的入口
  ├─ import { bootstrapEnv } from "./build/bootstrap-env.mjs"
  ├─ const env = bootstrapEnv()          ← 这一句才把 .env 读进环境变量
  └─ spawn(process.execPath, [entry])    ← entry = server-ws.mjs（无则 server.js）
```

而 `bootstrapEnv()` 的 `.env` 查找顺序是（`bootstrap-env.mjs:53-64`）：

```
1. $DATA_DIR/.env
2. ~/.omniroute/.env
3. $(pwd)/.env
```

**Next.js standalone 的 `server.js` 自身并不加载 `.env`** ——
`dist/.build/next/server/chunks/` 里搜不到 `loadEnvConfig`，
`1155.js` 中出现的 `dotenv` 只是 i18n 词表里的字面量。

**结论：如果 `entrypoint.sh` 直接 `exec node server.js`，就绕过了 `run-standalone.mjs`，
`bootstrapEnv()` 从未执行，`.env` 里写什么都没用。**

#### 为什么裁剪版不会出现这个提示

裁剪版是用 `npm install` 装出来的，上游的 `postinstall.mjs` 会自动跑 `sync-env`：

```
[sync-env] ✨ Created .env from .env.example (46 keys, 0 secrets generated)
```

于是在合适位置生成了 `.env`，且它的启动链（`npm start` → `run-next.mjs`）
**同样会走 `bootstrapEnv()`**，所以变量真正进了 `process.env`。

手工组装全量包时如果只是"把 `.env.example` 拷过去"，既没有生成 `.env`、
启动链又绕过了加载逻辑，闸门就会被触发。这就是"裁剪版正常、全量包异常"的差异。

#### 佐证

官方 release 的 `omniroute-desktop_3.8.51_arm64.deb` 里，
`opt/OmniRoute/resources/app/.env` 是**随包发布**的，第 28 行就是：

```
INITIAL_PASSWORD=CHANGEME
```

即官方自己也把 `.env` 当作运行时的一部分，不是可选项。

#### 直接解法

> ⚠️ **先纠正一个曾写错的说法**：向导里那个"引导令牌"输入框，**填 `changeme` 是进不去的**。
> 令牌校验用的是 `timingSafeEqual(内存随机串, 输入)`，见下文"为什么抓令牌不可靠"；
> `CHANGEME` 只是 `INITIAL_PASSWORD` 的**默认值提示**，并不是令牌，也不等于"密码已被采纳"。

**办法一（推荐）：由 App 注入进程环境变量**

这是最可靠的一条，因为它不依赖文件位置、也不依赖启动脚本是否加载 `.env`。
`process.env` 在 `bootstrapEnv()` 的优先级表里是**最高级**。

`OmniRouteServer` 在 spawn proot 时注入：

```java
builder.environment().put("INITIAL_PASSWORD",
        RootfsInstaller.DEFAULT_DASHBOARD_PASSWORD);   // "omniroute"
```

**0.3.1（versionCode 13）起已内置。** 装这个版本后，无需重新导入全量包 ——
对**已经导入的现有树**同样生效，因为它是启动时注入，不是打包时写文件。

| 文件 | 位置 |
| --- | --- |
| `OmniRouteServer.java` | `ProcessBuilder` 环境变量区，`INITIAL_PASSWORD` 一行 |
| `RootfsInstaller.java` | `DEFAULT_DASHBOARD_PASSWORD = "omniroute"` |

> 注入的默认密码是 `omniroute`，登录后请立即到
> **Dashboard → Settings → Security** 修改。

**办法二：改 `entrypoint.sh` 走官方启动脚本**

若坚持用 `.env` 文件，必须让启动链经过 `bootstrapEnv()`：

```sh
# 不要直接 exec server.js（会绕过 .env 加载）
cd /opt/omniroute/dist            # 或 dist 所在目录
exec /usr/local/bin/node dev/run-standalone.mjs
```

并且 `.env` 要放在 `bootstrapEnv()` 会查找的三个位置之一：

```
$DATA_DIR/.env          ← 本 App 里 DATA_DIR=/data（绑定到 files/omniroute-data/）
~/.omniroute/.env        ← 本 App 里 HOME=/data
$(pwd)/.env
```

**办法三：手工补 `.env`（位置要正确）**

在 `files/omniroute-data/.env`（宿主侧路径）写入：

```
INITIAL_PASSWORD=<你的密码>
```

**注意**：此办法**必须配合办法二**才有效 ——
若 `entrypoint.sh` 仍是直接 `exec node server.js`，写在哪都不会被读到。

#### ⚠️ 0.3.0 的 `ensureRuntimeEnv()` 是无效实现（已废弃）

0.3.0 里加过一个 `RootfsInstaller.ensureRuntimeEnv()`，把 `.env` 写到
`<rootfs>/opt/omniroute/.env`。**这个位置是错的**，两个原因：

1. `bootstrapEnv()` 的查找列表里**没有** `<app>/opt/omniroute/.env` 这一项
2. 即便位置对了，当时的 `entrypoint.sh` 直接 `exec node server.js`，
   **绕过了 `bootstrapEnv()`**，文件根本不会被读

0.3.1 改为进程环境变量注入后，这个写文件的逻辑已无必要。
保留它只是无害的冗余，**不要依赖它**。

#### 补充：为什么不要指望"从日志里抓引导令牌"

令牌是**按进程生成**的，服务每次重启都会换一个新的。实测日志：

```
12:44:42 → gmp0vGZlrQ0xTz6hSpXR9VHtItag7LGv
12:49:59 → fu3fpDNsfjV_VzSUn8c_oGeF8oEzCy37
12:58:46 → mr04BbyFsOnSkazR1flmTghrTesLzqET
13:09:50 → jQDw8W0Wx9KUEPSSzpEONtcdJcyVvEDp
```

服务反复重启时，手工粘贴永远追不上。而且令牌本身在启动早期只打印一次，
随后就被运行时自己的输出（数百行）淹没，**扫描日志尾部抓不到**。
设好 `INITIAL_PASSWORD` 让闸门不触发，才是正解。

#### 安全提醒

`CHANGEME` 是官方文档明确标注的不安全默认值：

```
# Initial admin login password — CHANGE THIS before first use!
# Default: CHANGEME (insecure, for local dev only)
INITIAL_PASSWORD=CHANGEME
```

服务端启动时也会专门告警：

```
[AUTH][SECURITY] Management password is set to the well-known default "CHANGEME"
(INITIAL_PASSWORD in .env.example). Anyone can sign in...
```

App 默认绑定 `127.0.0.1`，风险有限；但一旦开启「允许局域网访问」，
这个默认密码等于把面板敞开。**登录后第一件事就是改密码。**

### Step 7a: 体积裁剪（2.8G → 1.9G node_modules）

OmniRoute 的生产依赖约 3.8G，含大量 Android proot 环境用不上的 CLI 二进制和平台特定文件。

**删除多平台二进制（只留 arm64-gnu）：**
```bash
cd /workspace/rootfs-tmp/opt/omniroute/node_modules
rm -rf @openai/codex-linux-arm64-musl                  # musl 版
rm -rf @anthropic-ai/claude-agent-sdk-linux-arm64-musl # musl 版
rm -rf opencode-linux-arm64-musl opencode-linux-x64*   # musl + 异平台
rm -rf @oven/bun-*                                     # bun 运行时（各平台 ~76MB）
rm -rf @anthropic-ai/claude-agent-sdk-linux-x64*       # x86 claude
rm -rf @openai/codex-security/node_modules             # 内嵌 codex-duplicate (330MB)
rm -rf @azure                                           # 云管理 SDK (117MB)
rm -rf promptfoo                                        # 测试框架 (58MB)
rm -rf wordnet-db                                       # 自然语言词典 (34MB)
rm -rf onnxruntime-node onnxruntime-web                 # ONNX 无 Android 支持 (429MB)
rm -rf @napi-rs/canvas @napi-rs/wasm-runtime            # 非 arm64 napi 包
# sharp: 只留 linux-arm64，删除 musl 和 wasm 版
rm -rf @img/sharp-libvips-linuxmusl-arm64 @img/sharp-linuxmusl-arm64 @img/sharp-wasm32
```

**删除非运行时文件：**
```bash
# sourcemap
find . \( -name '*.js.map' -o -name '*.mjs.map' -o -name '*.cjs.map' \) -delete
# 类型声明
find . \( -name '*.d.ts' -o -name '*.d.mts' \) -delete
# 文档 + 测试
find . \( -name 'README*' -o -name 'CHANGELOG*' -o -name 'LICEN[CS]E*' \) -delete
find . -maxdepth 2 -type d \( -name 'test' -o -name 'tests' -o -name '__tests__' \
  -o -name 'docs' -o -name 'examples' \) -exec rm -rf {} + 2>/dev/null
rm -rf monaco-editor/dev node-sql-parser/umd
```

> 踩坑：`rm -rf node_modules` 在 node_modules 内部有 `node_modules/` 嵌套目录（npm 扁平化失败）且权限为 700 时会 "Directory not empty" 删不掉。
> 解决：先 `chmod -R 755` 或用 Python `shutil.rmtree(ignore_errors=True)` 暴力清。

**裁剪后必须验证：**
```bash
cd /workspace/rootfs-tmp/opt/omniroute
DATA_DIR=/workspace/rootfs-tmp/data PORT=20128 NODE_ENV=production \
  timeout 30 /workspace/rootfs-tmp/usr/local/bin/node server.js

# 验证 dashboard 200 + /api/v1/models 401（鉴权正常）
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:20128/
# → 200 ✓
curl -s http://127.0.0.1:20128/api/v1/models | head -c 100
# → {"error":{"message":"Authentication required",... ✓
```

### Step 8: 打 rootfs tar.gz + 版本标记

> **打包前必查**（2026-10-07 真机踩出来的）：
>
> 1. **`.l2s.*` 清没清**：`ls rootfs-tmp/usr/bin/.l2s.*`。
>    这些是容器文件系统生成的硬链接影子条目，Android 端解包器处理它们会抛异常，
>    整包中止并只报一句笼统的 `failed to extract rootfs`。
> 2. **`.env` 不是必需品**：首装闸门由 App 启动时注入 `INITIAL_PASSWORD` 解决
>    （见 **Step 7b**），**不需要**把 `.env` 打进 rootfs 归档。
>    若仍想随包附带，注意它**不在** `opt/omniroute/`，且需配合启动链改动才生效 ——
>    详见 Step 7b 的"办法二/三"。

```bash
# /tmp 等不稳定路径不能存放关键资产，全部走 /workspace
rm -rf /workspace/rootfs-tmp/data /workspace/rootfs-tmp/root/.omniroute
rm -f /workspace/rootfs-tmp/usr/bin/.l2s.*

# 打包前自检：确认影子条目已清干净
find /workspace/rootfs-tmp -name '.l2s.*' 2>/dev/null | head \
  && echo '!! 仍有 .l2s.* 残留'

cd /workspace/rootfs-tmp
tar --no-same-owner --ignore-failed-read \
  -czf /workspace/omniroute-android/app/src/main/assets/rootfs/omniroute-rootfs.bundle .

# 版本标记（每次构建更新）
printf '%s\n' "v1-$(date +%Y%m%d)" \
  > /workspace/omniroute-android/app/src/main/assets/rootfs/omniroute-rootfs.version
```

> 踩坑 1：`/tmp` 在 Codex 沙箱里会被周期性清除。所有中间产物必须放 `/workspace`。
>
> 踩坑 2：debootstrap 做的 rootfs 里 `/usr/bin/perl`、`/usr/bin/gunzip` 等
> 是对 `.l2s.*` 文件的损坏符号链接（容器文件系统加的保护）。删 `.l2s.*` 文件
> 和对应的符号链接后 `tar --ignore-failed-read` 继续打包。
>
> ⚠️ **删除 `.l2s.*` 时注意硬链接陷阱**：本沙箱的 proot 里，删除一个与真实文件
> **共享 inode** 的 `.l2s.*` 名字，会把真实文件一并删掉（实测 `perl`、`gunzip`、
> `esbuild` 都中过招）。删除前先用 `stat -c '%h'` 确认 `nlink`；若 `nlink > 1`，
> 先 `cp` 一份实体到临时名、`unlink` 影子、再 `mv` 回来，不要直接 `rm`。
>
> 踩坑 3：`tar` 可能报 "file changed as we read it" 对 node_modules——正常，gzip 时 Node
> 文件时间戳变化触发此警告，不丢数据。

### Step 9: 构建 APK

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/workspace/toolchain/android-sdk
export PATH=/workspace/toolchain/gradle/bin:$JAVA_HOME/bin:$PATH
cd /workspace/omniroute-android
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk` (~745MB)

> 踩坑 1：Gradle 下载 AGP/Maven 时 JVM TLS 握手可能被透明代理拦截。如果遇到
> SSL 错误，用 `curl` 单独验证 `https://dl.google.com` 和 `https://repo.maven.apache.org`
> 的 TLS 可达性，确认不是代理/DNS 问题后再试。
>
> 踩坑 2：buildToolsVersion 必须显式指定为 `36.0.0`（AGP 8.13 默认找 35.0.0
> 但我们装的是 36.0.0）。`build.gradle` 里已有这行。
>
> 踩坑 3：`No space left on device`。rootfs 含 dev 依赖时会有 2.3G，导致
> `mergeDebugAssets` 阶段磁盘满。**务必先执行 Step 7a 裁剪。**
>
> 踩坑 4：`Unable to strip ... so` 是正常警告——这些 `.so` 不是 JNI 库，
> 没有可 strip 的符号表。不阻止 APK 生成。
>
> 踩坑 5：**必须确认两个 proot `.so` 真的进了 APK 的 `lib/arm64-v8a/`**。
> 漏了会运行时报 `libproot_exec.so missing from nativeLibraryDir`：
>
> ```bash
> python3 -c "
> import zipfile
> z = zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk')
> for i in z.infolist():
>     if i.filename.startswith('lib/'):
>         print(i.filename, i.file_size)
> "
> ```

---

## 构建产物验证

```bash
python3 -c "
import zipfile
z = zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk')
names = z.namelist()

print('=== 关键资产 ===')
for n in names:
    if 'rootfs' in n:
        print(f'  {n}  {z.getinfo(n).file_size/1024/1024:.1f}MB')

print()
print('=== 原生库（必须两个都在）===')
found = []
for i in z.infolist():
    if i.filename.startswith('lib/'):
        mode = i.external_attr >> 16
        print(f'  {i.filename}  {i.file_size}B  mode={oct(mode)}')
        found.append(i.filename)
assert any('libproot_exec.so' in f for f in found), 'libproot_exec.so 缺失!'
assert any('libproot_loader.so' in f for f in found), 'libproot_loader.so 缺失!'
print()
print('✓ 两个 proot 库都在')
"
```

> APK 内 `.so` 显示为压缩存储且无执行位是**正常的**——`extractNativeLibs="true"`
> 生效时，系统会在**安装时**把它们解压到 `nativeLibraryDir` 并赋予执行权限。
> 关键校验是：文件必须在 `lib/arm64-v8a/` 下，且 manifest 里有
> `android:extractNativeLibs="true"`。

---

## 装机测试

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动流程：
1. `RootfsInstaller.ensureInstalled()` 把 745MB tar.gz 流式解压到 `files/rootfs/v1-20261003/`
2. `OmniRouteServer.start()` 用 proot 拉起 Debian + node server.js
3. `awaitReady(90)` 每 1s 轮询 `testLocalhost:20128`，约 10-15s 就绪
4. WebView 加载 `http://127.0.0.1:20128` 打开 Dashboard

如果 proot 起不来，拉日志：
```bash
adb shell cat /data/data/com.example.omniroute.debug/files/logs/omniroute.log
```

---

## 体积优化摘要

| 阶段 | node_modules | rootfs tar.gz | 累计节省 |
|---|---|---|---|
| 原始（全量依赖） | 3.8G | 1.6G | - |
| 删多平台二进制 | 3.5G | - | -8% |
| 删 ONNX (429MB) | 3.1G | - | -18% |
| 删 @azure / promptfoo / wordnet-db | 2.8G | - | -26% |
| 删 sourcemap + .d.ts + README | 2.2G | - | -42% |
| **最终** | **1.9G** | **745MB** | **-50%** |

---

## 已知限制

1. **APK 体积**：745MB，远超 Google Play 150MB 限额。后续需要 Play Asset Delivery
   或启动时按需下载 rootfs（工程已预留 `stagingArchive` 下载路径）。
2. **内存**：轻量 dashboard ~1GB 堆够用，但 coding agent `/v1/responses` 建议 ≥ 8GB 内存。
3. **厂商 ROM**：小米/OPPO/华为的电池优化更狠，务必引导用户关掉（App 内有按钮）。
4. **功能不足**：
   - `playwright`（Claude Web/Turnstile 路由）不可用，Android 不能跑浏览器
   - `wreq-js`（TLS 指纹伪装）无 Android 原生二进制
   - `onnxruntime-node`（LLMLingua 压缩引擎）无 Android 支持
   - MITM / TPROXY 需要 root + 系统证书注入，不可用
5. **监听地址**：绑定 `127.0.0.1`，仅本机可访问。如需局域网共享要改为 `0.0.0.0`
   并设置强密码。
6. **Node 更新**：上游每次 release 可能需要更新 npm 包和 better-sqlite3 原生二进制
   （`prebuilds/linux-arm64.node`），但流程不变。
7. **proot 二进制来源**：当前复用 RikkaHub 的 `libproot_exec.so` / `libproot_loader.so`。
   如需自行构建，必须同时提供配套的独立 loader 并通过 `PROOT_LOADER` 传入，
   否则会重现 SIGSYS。
8. **首装闸门（引导令牌）**：仪表盘在「未设密码 + 连接非 loopback」时会索要一次性令牌。
   WebView 请求必然落到 `remote` 分支，所以**实质是要求密码必须被设置**。
   注意闸门读的是 `process.env.INITIAL_PASSWORD`，**写 `.env` 文件通常无效** ——
   除非走官方 `run-standalone.mjs`（内含 `bootstrapEnv()`）且文件位置正确。
   0.3.1 起由 `OmniRouteServer` 启动时**注入进程环境变量**解决，详见 **Step 7b**。
9. **`.l2s.*` 影子条目**：容器文件系统生成的硬链接影子会被打进 rootfs 归档，
   Android 端解包时抛异常并中止整包（只报笼统的 `failed to extract rootfs`）。
   打包前必须清理；清理时注意 **共享 inode 的删除陷阱**（见 Step 8 踩坑 2）。

---

## 快速复现

**务必用 `setup-toolchain.sh`，不要手工装工具链**（`/opt` 会被清空）：

```bash
# 1. 恢复构建工具链
bash /workspace/toolchain/setup-toolchain.sh

# 2. 准备 rootfs（首次或需要大版本更新时）
#    产物放 app/src/main/assets/rootfs/omniroute-rootfs.bundle
bash /workspace/omniroute-android/scripts/build-rootfs.sh

# 3. 构建 APK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/workspace/toolchain/android-sdk
export PATH=/workspace/toolchain/gradle/bin:$JAVA_HOME/bin:$PATH
cd /workspace/omniroute-android && gradle :app:assembleDebug
```

> `scripts/build-proot.sh` 已废弃——proot 不再自行编译，改为从 RikkaHub
> 提取现成的 `libproot_exec.so` + `libproot_loader.so`（见 Step 3）。

---

## 版本历史

- **v1-20261003**: 首个成功构建。OmniRoute v3.8.51 / Node v22.23.3 / Debian bookworm arm64。
- **v1-20261003 (proot 修复)**: 改用 `PROOT_LOADER` + 独立 loader 方案，
  解决 SIGSYS；targetSdk 回调至 37。真机验证 `listening on 20128`。
- **0.2.x（全量包导入）**: APK 不再内置 rootfs（745MB → 约 300KB），
  改为「设置 → 运行时导入」从外部选 `.tar.gz` 全量包。
  同期修复解包器的一系列问题：词法逃逸检查（不再 `getCanonicalPath()` 跟随
  Debian 的绝对符号链接）、符号链接单条失败容错、硬链接失败回退拷贝、
  目录权限延迟套用、解包进度回调、真实错误原因透传。
- **0.2.7（执行位自愈）**: 修掉 `File.setExecutable()` 静默失效的问题，
  改为 `Os.chmod`；并把 **ELF 解释器 `ld-linux-aarch64.so.1`** 纳入修复范围
  （Debian 把它打成 0644，导致 `execve("/usr/bin/sh")` 报 `EACCES`，
  而报错信息指向 shell，误导排查方向）。
- **0.2.9（引导令牌自动抓取）**: 日志泵边读边抓 `[BOOTSTRAP]` 令牌并持久化，
  设置页顶部展示 + 自动复制。
- **0.3.0（尝试自动补 `.env`）**: 加过 `RootfsInstaller.ensureRuntimeEnv()`，
  把 `.env` 写到 `<rootfs>/opt/omniroute/`。**事后证明位置错误且启动链不读它，
  属于无效实现**（见 Step 7b）。
- **0.3.1（注入 `INITIAL_PASSWORD`）**: 改为在 `OmniRouteServer` spawn proot 时
  直接把 `INITIAL_PASSWORD` 放进**进程环境变量**。
  这是 `bootstrapEnv()` 优先级表里的最高级，不依赖任何文件，
  因此对**已导入的现有树**同样生效，无需重新导入全量包。
  **从根上关闭首装闸门** —— 见 **Step 7b**。
- **构建机**：Ubuntu 24.04 LTS arm64 / OpenJDK 17 / Gradle 9.5.0 / Android SDK 36.0.0。

> **Gradle 版本注意**：Gradle 9.6.0 与 AGP 8.x 不兼容
> （`InternalProblems` 内部 API 被移除），需用 **Gradle 9.5.0** 配 AGP 8.13.0。

---

## 环境总结

```
/workspace/
├── omniroute-android/                 ← 本工程根目录
│   ├── app/
│   │   ├── src/main/java/...          ← 11 个 Java 源文件
│   │   ├── src/main/assets/rootfs/    ← omniroute-rootfs.bundle + .version
│   │   ├── src/main/jniLibs/arm64-v8a/
│   │   │   ├── libproot_exec.so       ← proot 主程序（RikkaHub/Termux 版）
│   │   │   └── libproot_loader.so     ← 独立 loader，经 PROOT_LOADER 传入 ★
│   │   └── build/outputs/apk/debug/app-debug.apk  ← 最终产物（745MB）
│   ├── scripts/                       ← build-rootfs.sh, package-apk.sh
│   ├── gradle.properties              ← aapt2 shim override
│   └── BUILD_NOTES.md                 ← 本文件
├── toolchain/                         ← 构建工具链，勿放 /opt（会被清空）
│   ├── gradle/                        ← Gradle 8.14.3
│   ├── android-sdk/                   ← build-tools 36.0.0 + platforms android-36
│   ├── x86_64-libs/                   ← aapt2 所需的 x86_64 glibc（含 .deb 源）
│   ├── aapt2-shim/aapt2               ← QEMU 包装脚本
│   └── setup-toolchain.sh             ← 一键恢复脚本
├── pkgs/
│   ├── node-22.23.3-linux-arm64.tar.xz   ← Node.js 运行时
│   ├── omniroute-3.8.51.tgz              ← npm 发布包（含预构建 dist/）
│   └── omniroute-pkg/                    ← 解包目录（装完依赖后可删）
├── rootfs-tmp/                        ← Debian rootfs 构建目录（打包后可删）
├── rikka-src/                         ← RikkaHub 参考实现（proot 方案来源）
└── syscall-probe/                     ← seccomp 系统调用探针（排查工具）
```
# Third-party notices: Dead Drop Server

Dead Drop Server is licensed under the Apache License, Version 2.0 (see LICENSE and NOTICE). This file covers
everything its container image (ghcr.io/crazy8697/deaddrop-server) ships: the server's runtime classpath (checked by
the build's license gate, which refuses to build a component missing from this file), the native code inside those
libraries, the Java runtime, and every package of the Ubuntu base image (checked against the built image before a
release). Each Java library's license was read from its POM and its upstream repository at that version; each
package's from its own copyright file in the image. The image carries this file, LICENSE, NOTICE and the licenses
folder in /opt/deaddrop-server.

## Ktor (JetBrains), the HTTP server

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/ktorio/ktor

| Component | Version | License (from its POM) |
|---|---|---|
| `io.ktor:ktor-events-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-events` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-http-cio-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-http-cio` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-http-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-http` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-io-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-io` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-network-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-network` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-serialization-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-serialization` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-server-cio-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-server-core-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-server-core` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-utils-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-utils` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-websockets-jvm` | 3.6.0 | The Apache Software License, Version 2.0 |
| `io.ktor:ktor-websockets` | 3.6.0 | The Apache Software License, Version 2.0 |

## Kotlin standard library, kotlinx libraries and JetBrains annotations (JetBrains)

License: Apache-2.0 (licenses/Apache-2.0.txt). NOTICE texts: licenses/Kotlin-NOTICE.txt and
licenses/kotlinx-io-NOTICE.txt (repeated in NOTICE).  
Upstream: https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines,
https://github.com/Kotlin/kotlinx-io, https://github.com/Kotlin/kotlinx.serialization,
https://github.com/JetBrains/java-annotations

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-reflect` | 2.3.21 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-core` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.12.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.12.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.12.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.12.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.12.0 | Apache-2.0 |
| `org.jetbrains:annotations` | 23.0.0 | The Apache Software License, Version 2.0 |

## Typesafe Config (Lightbend), Ktor's configuration reader

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/lightbend/config

| Component | Version | License (from its POM) |
|---|---|---|
| `com.typesafe:config` | 1.4.9 | Apache-2.0 |

## sqlite-jdbc (Xerial) and SQLite, the server's database

License: Apache-2.0 for sqlite-jdbc (licenses/Apache-2.0.txt), with the BSD-2-Clause notice of its original author
(licenses/BSD-2-Clause-sqlite-jdbc-zentus.txt) and its NOTICE (licenses/sqlite-jdbc-NOTICE.txt); SQLite itself is
in the public domain (licenses/SQLite-Public-Domain.txt).  
Upstream: https://github.com/xerial/sqlite-jdbc, https://www.sqlite.org

The jar carries SQLite 3.54.1 built for several systems; the image loads the Linux one.

| Component | Version | License (from its POM) |
|---|---|---|
| `org.xerial:sqlite-jdbc` | 3.54.1.0 | The Apache Software License, Version 2.0 |

## Lazysodium for Java and libsodium, the encryption

License: Mozilla Public License 2.0 for Lazysodium (licenses/MPL-2.0-lazysodium.md, its own LICENSE.md); ISC for
libsodium (licenses/ISC-libsodium.txt); MIT for its resource loader (licenses/MIT-resource-loader.txt).  
Upstream: https://github.com/terl/lazysodium-java, https://github.com/jedisct1/libsodium,
https://github.com/terl/resource-loader

Every message between a device and the server is a libsodium envelope (crypto_box and crypto_box_seal), and the
server's backups use libsodium's secretstream; Lazysodium calls libsodium through JNA. The lazysodium-java jar carries
libsodium 1.0.20 built for several systems; the image loads the Linux one. Lazysodium is not changed from upstream;
MPL-2.0's source is at the upstream URL.

| Component | Version | License (from its POM) |
|---|---|---|
| `com.goterl:lazysodium-java` | 5.2.0 | Mozilla Public License, Version 2.0 |
| `com.goterl:resource-loader` | 2.1.0 | MIT |

## JNA, Java Native Access

License: Apache-2.0 (licenses/Apache-2.0.txt). JNA is offered under LGPL-2.1-or-later or Apache-2.0, at the user's
choice (licenses/JNA-LICENSE.txt); Dead Drop Server uses it under Apache-2.0. Its native dispatch library includes
libffi, under the MIT license (licenses/MIT-libffi.txt).  
Upstream: https://github.com/java-native-access/jna

| Component | Version | License (from its POM) |
|---|---|---|
| `net.java.dev.jna:jna` | 5.19.1 | LGPL-2.1-or-later or Apache-2.0 |

## Logback and SLF4J, the server's log

License: Logback is offered under the Eclipse Public License 2.0 or LGPL-2.1, at the user's choice
(licenses/logback-LICENSE.txt); Dead Drop Server uses it under EPL-2.0 (licenses/EPL-2.0.txt). SLF4J's API is MIT
(licenses/MIT-SLF4J.txt).  
Upstream: https://github.com/qos-ch/logback, https://github.com/qos-ch/slf4j

Logback is not changed from upstream; EPL-2.0's source is at the upstream URL.

| Component | Version | License (from its POM) |
|---|---|---|
| `ch.qos.logback:logback-classic` | 1.6.6 | EPL-2.0 or LGPL-2.1-only |
| `ch.qos.logback:logback-core` | 1.6.6 | EPL-2.0 or LGPL-2.1-only |
| `org.slf4j:slf4j-api` | 2.0.19 | MIT |

## The Java runtime (Eclipse Temurin, OpenJDK 21)

License: GNU General Public License, version 2, with the Classpath Exception (licenses/temurin/java.base-LICENSE.txt,
licenses/temurin/java.base-ASSEMBLY_EXCEPTION.txt and licenses/temurin/java.base-ADDITIONAL_LICENSE_INFO.txt), and the
Eclipse Temurin notice (licenses/temurin/NOTICE.txt).  
Upstream: https://adoptium.net, https://openjdk.org

The image is built on the official eclipse-temurin 21 JRE image for Ubuntu 24.04, pinned by digest in the
Dockerfile. The runtime carries its own notices for the components it includes in /opt/java/openjdk/legal, one folder
per module, exactly as Temurin ships them.

## The Ubuntu base image

Each package's license is its own copyright file, copied byte for byte from the image into licenses/ubuntu (the image
also keeps it at /usr/share/doc/PACKAGE/copyright). The license texts those files refer to (/usr/share/common-licenses
in the image) are in licenses/ubuntu/common-licenses. The packages are unchanged from Ubuntu; their source is at
https://launchpad.net/ubuntu/+source/PACKAGE.

<!-- packages: generated by tools/image_licenses.py --collect; do not edit by hand -->

Base image: Ubuntu 24.04.5 LTS with Eclipse Temurin 21.0.12.1+1-LTS (the Java runtime). 136 packages.

| Package | Version | License (from its copyright file) | Copyright file |
|---|---|---|---|
| `adduser` | 3.137ubuntu1 | GPL-2+ | licenses/ubuntu/adduser.copyright |
| `apt` | 2.8.3 | GPL-2+, GPL-2, BSD-3-clause, Expat | licenses/ubuntu/apt.copyright |
| `base-files` | 13ubuntu10.5 | free-form copyright file; refers to GPL | licenses/ubuntu/base-files.copyright |
| `base-passwd` | 3.6.3build1 | GPL-2, public-domain | licenses/ubuntu/base-passwd.copyright |
| `bash` | 5.2.21-2ubuntu4 | GPL-3+, GPL-3+ with Bison exception, GPL-2+, GFDL-NIV-1.3, Latex2e, BSD-4-clause-UC and MIT-like, BSD-4-clause-UC, MIT-like, permissive | licenses/ubuntu/bash.copyright |
| `bsdutils` | 1:2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/bsdutils.copyright |
| `ca-certificates` | 20260816ubuntu0.24.04.1 | GPL-2+, MPL-2.0 | licenses/ubuntu/ca-certificates.copyright |
| `coreutils` | 9.4-3ubuntu6.3 | GPL-3+, GPL-3+ and BSD-4-clause-UC, BSD-4-clause-UC, GPL-3+ and ISC, ISC, FSFULLR, GFDL-NIV-1.3 | licenses/ubuntu/coreutils.copyright |
| `curl` | 8.5.0-2ubuntu10.16 | curl, OLDAP-2.8, ISC, GPL-2+ with Autoconf-data exception, GPL-3+ with Autoconf-data exception, GPL-2+ with Libtool exception, BSD-3-clause, BSD-4-Clause-UC, FSFULLR, X11, BSD-3-Clause | licenses/ubuntu/curl.copyright |
| `dash` | 0.5.12-6ubuntu5 | BSD-3-Clause, public-domain, GPL-2+, BSD-3-clause | licenses/ubuntu/dash.copyright |
| `debconf` | 1.5.86ubuntu1 | BSD-2-clause | licenses/ubuntu/debconf.copyright |
| `debianutils` | 5.17build1 | GPL-2+, public-domain, SMAIL-GPL | licenses/ubuntu/debianutils.copyright |
| `diffutils` | 1:3.10-1ubuntu0.1 | GPL-3+, FSFULLR, GPL-3+ and FSFULLR, LGPL-2.1+, GPL-3+ with autoconf exception, GPL-3+ with texinfo exception, LGPL-2.0+, GPL-2+, X11, FSFAP, GFDL-NIV-1.3, LGPL-3.0+, LGPL-3.0+ or GPL-2+, public-domain | licenses/ubuntu/diffutils.copyright |
| `dirmngr` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/dirmngr.copyright |
| `dpkg` | 1.22.6ubuntu6.6 | GPL-2+, public-domain-s-s-d | licenses/ubuntu/dpkg.copyright |
| `e2fsprogs` | 1.47.0-2.4~exp1ubuntu4.1 | GPL-2, LGPL-2, BSD-3-Clause, Apache-2, ISC, GPL or MIT-US-export, Kazlib, Latex2e, GPL-2+ with Texinfo exception | licenses/ubuntu/e2fsprogs.copyright |
| `findutils` | 4.9.0-5build1 | GFDL-NIV-1.3+, GPL-3+, FSFAP, GPL-3+ with Autoconf-data exception, FSFULLR, GPL-2+ with Autoconf-data exception, GPL-2+, X11, public-domain, GPL with automake exception, LGPL-2.1+, LGPL-2+, LGPL-3+, BSD-3-clause and/or GPL-3+, GPL-3+ with Bison-2.2 exception, LGPL-3, ISC and/or LGPL-2.1+, BSD-3-clause, ISC | licenses/ubuntu/findutils.copyright |
| `fontconfig` | 2.15.0-1.1ubuntu2 | free-form copyright file | licenses/ubuntu/fontconfig.copyright |
| `fontconfig-config` | 2.15.0-1.1ubuntu2 | free-form copyright file | licenses/ubuntu/fontconfig-config.copyright |
| `fonts-dejavu-core` | 2.37-8 | bitstream-vera, GPL-2+ | licenses/ubuntu/fonts-dejavu-core.copyright |
| `fonts-dejavu-mono` | 2.37-8 | bitstream-vera, GPL-2+ | licenses/ubuntu/fonts-dejavu-mono.copyright |
| `gcc-14-base` | 14.2.0-4ubuntu2~24.04.1 | free-form copyright file; refers to GPL, GPL-3, GFDL-1.2, Artistic, LGPL | licenses/ubuntu/gcc-14-base.copyright |
| `gnupg` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gnupg.copyright |
| `gnupg-utils` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gnupg-utils.copyright |
| `gpg` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gpg.copyright |
| `gpg-agent` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gpg-agent.copyright |
| `gpgconf` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gpgconf.copyright |
| `gpgsm` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gpgsm.copyright |
| `gpgv` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/gpgv.copyright |
| `grep` | 3.11-4build1 | GPL-3+ | licenses/ubuntu/grep.copyright |
| `gzip` | 1.12-1ubuntu3.2 | GPL-3+, GFDL-1.3+-no-invariant, FSF-manpages | licenses/ubuntu/gzip.copyright |
| `hostname` | 3.23+nmu2ubuntu2 | GPL-2 | licenses/ubuntu/hostname.copyright |
| `init-system-helpers` | 1.66ubuntu1 | BSD-3-clause, GPL-2+ | licenses/ubuntu/init-system-helpers.copyright |
| `keyboxd` | 2.4.4-2ubuntu17.6 | GPL-3+, permissive, LGPL-2.1+, LGPL-3+ or GPL-2+, Expat, GPL-3+ or BSD-3-clause, LGPL-3+, RFC-Reference, TinySCHEME, CC0-1.0, BSD-3-clause, GPL-2+ | licenses/ubuntu/keyboxd.copyright |
| `libacl1` | 2.3.2-1build1.1 | GPL-2+, LGPL-2+ | licenses/ubuntu/libacl1.copyright |
| `libapt-pkg6.0t64` | 2.8.3 | GPL-2+, GPL-2, BSD-3-clause, Expat | licenses/ubuntu/libapt-pkg6.0t64.copyright |
| `libassuan0` | 2.5.6-1build1 | LGPL-2.1+, GAP~FSF, LGPL-3+, GPL-2+ with libtool exception, GPL-3+, GAP, GPL-2+ | licenses/ubuntu/libassuan0.copyright |
| `libattr1` | 1:2.5.2-1ubuntu0.1 | GPL-2+, LGPL-2+ | licenses/ubuntu/libattr1.copyright |
| `libaudit-common` | 1:3.1.2-2.1ubuntu0.1 | GPL-2, LGPL-2.1 | licenses/ubuntu/libaudit-common.copyright |
| `libaudit1` | 1:3.1.2-2.1ubuntu0.1 | GPL-2, LGPL-2.1 | licenses/ubuntu/libaudit1.copyright |
| `libblkid1` | 2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/libblkid1.copyright |
| `libbrotli1` | 1.1.0-2ubuntu0.1 | MIT | licenses/ubuntu/libbrotli1.copyright |
| `libbz2-1.0` | 1.0.8-5.1ubuntu0.1 | BSD-variant, GPL-2 | licenses/ubuntu/libbz2-1.0.copyright |
| `libc-bin` | 2.39-0ubuntu8.9 | free-form copyright file; refers to LGPL-2.1, GPL-2, GFDL-1.3 | licenses/ubuntu/libc-bin.copyright |
| `libc6` | 2.39-0ubuntu8.9 | free-form copyright file; refers to LGPL-2.1, GPL-2, GFDL-1.3 | licenses/ubuntu/libc6.copyright |
| `libcap-ng0` | 0.8.4-2build2 | LGPL-2.1+, GPL-2+, GPL-3 | licenses/ubuntu/libcap-ng0.copyright |
| `libcap2` | 1:2.66-5ubuntu2.4 | BSD-3-clause or GPL-2, BSD-3-clause or GPL-2+, BSD-3-clause, GPL-2, GPL-2+ | licenses/ubuntu/libcap2.copyright |
| `libcom-err2` | 1.47.0-2.4~exp1ubuntu4.1 | GPL-2, LGPL-2, BSD-3-Clause, Apache-2, ISC, GPL or MIT-US-export, Kazlib, Latex2e, GPL-2+ with Texinfo exception | licenses/ubuntu/libcom-err2.copyright |
| `libcrypt1` | 1:4.4.36-4build1 | free-form copyright file | licenses/ubuntu/libcrypt1.copyright |
| `libcurl4t64` | 8.5.0-2ubuntu10.16 | curl, OLDAP-2.8, ISC, GPL-2+ with Autoconf-data exception, GPL-3+ with Autoconf-data exception, GPL-2+ with Libtool exception, BSD-3-clause, BSD-4-Clause-UC, FSFULLR, X11, BSD-3-Clause | licenses/ubuntu/libcurl4t64.copyright |
| `libdb5.3t64` | 5.3.28+dfsg2-7 | Sleepycat and BSD-3-clause, Sleepycat, BSD-3-clause, Ms-PL, GPL or Artistic, X11, MIT-old, TCL-like, BSD-3-clause-fjord, GPL-3, zlib, Artistic or BSD-3-clause, GPL | licenses/ubuntu/libdb5.3t64.copyright |
| `libdebconfclient0` | 0.271ubuntu3 | BSD-2-Clause, GPL-2+, BSD-2-clause | licenses/ubuntu/libdebconfclient0.copyright |
| `libexpat1` | 2.6.1-2ubuntu0.6 | MIT | licenses/ubuntu/libexpat1.copyright |
| `libext2fs2t64` | 1.47.0-2.4~exp1ubuntu4.1 | GPL-2, LGPL-2, BSD-3-Clause, Apache-2, ISC, GPL or MIT-US-export, Kazlib, Latex2e, GPL-2+ with Texinfo exception | licenses/ubuntu/libext2fs2t64.copyright |
| `libffi8` | 3.4.6-1build1 | Expat, X11, GPL-2+, GPL-3+, MPL-1.1 or GPL-2+ or LGPL-2.1+, public-domain, GPL | licenses/ubuntu/libffi8.copyright |
| `libfontconfig1` | 2.15.0-1.1ubuntu2 | free-form copyright file | licenses/ubuntu/libfontconfig1.copyright |
| `libfreetype6` | 2.13.2+dfsg-1ubuntu0.2 | FTL, FTL and Expat, BSD-3-Clause, FSFAP, GPL-3+, Expat, GPL-2+, Public-Domain, MIT-SMC, BSL-1.0, MIT-Modern-Variant, Zlib, OpenGroup-MIT | licenses/ubuntu/libfreetype6.copyright |
| `libgcc-s1` | 14.2.0-4ubuntu2~24.04.1 | free-form copyright file; refers to GPL, GPL-3, GFDL-1.2, Artistic, LGPL | licenses/ubuntu/libgcc-s1.copyright |
| `libgcrypt20` | 1.10.3-2ubuntu0.2 | free-form copyright file; refers to LGPL, GPL-2 | licenses/ubuntu/libgcrypt20.copyright |
| `libgmp10` | 2:6.3.0+dfsg-2ubuntu6.1 | GPL-2+ or LGPL-3+, GPL-3+, GPL-3+ with Bison exception, GPL-2+, LGPL-3+ | licenses/ubuntu/libgmp10.copyright |
| `libgnutls30t64` | 3.8.3-1.1ubuntu3.6 | The main library is licensed under GNU Lesser, CC0 license, Expat, Apache-2.0, LGPLv3+_or_GPLv2+, LGPLv2.1+, GPLv3+, BSD-3-Clause | licenses/ubuntu/libgnutls30t64.copyright |
| `libgpg-error0` | 1.47-3build2.1 | LGPL-2.1+, LGPL-2.1+ or BSD-3-clause, g10-permissive, GPL-3+, BSD-3-clause | licenses/ubuntu/libgpg-error0.copyright |
| `libgssapi-krb5-2` | 1.20.1-6ubuntu2.10 | free-form copyright file; refers to GPL-2 | licenses/ubuntu/libgssapi-krb5-2.copyright |
| `libhogweed6t64` | 3.9.1-2.2build1.1 | LGPL-3+ or GPL-2+, LGPL-2+, Expat, GPL-2+, GPL-3+ with Autoconf exception, public-domain, GPL-2, GAP, LGPL-3+ | licenses/ubuntu/libhogweed6t64.copyright |
| `libidn2-0` | 2.3.7-2build1.1 | GPL-3+, LGPL-3+ or GPL-2+, Unicode, GPL-2+, LGPL-3+ | licenses/ubuntu/libidn2-0.copyright |
| `libk5crypto3` | 1.20.1-6ubuntu2.10 | free-form copyright file; refers to GPL-2 | licenses/ubuntu/libk5crypto3.copyright |
| `libkeyutils1` | 1.6.3-3build1 | GPL-2+, LGPL-2+ | licenses/ubuntu/libkeyutils1.copyright |
| `libkrb5-3` | 1.20.1-6ubuntu2.10 | free-form copyright file; refers to GPL-2 | licenses/ubuntu/libkrb5-3.copyright |
| `libkrb5support0` | 1.20.1-6ubuntu2.10 | free-form copyright file; refers to GPL-2 | licenses/ubuntu/libkrb5support0.copyright |
| `libksba8` | 1.6.6-1build1 | FSFUL, LGPL-2.1-or-later | licenses/ubuntu/libksba8.copyright |
| `libldap2` | 2.6.10+dfsg-0ubuntu0.24.04.1 | OpenLDAP-2.8, FSF-unlimited and GPL-2+ with Autoconf exception, GPL-3+ with Autoconf exception, FSF-unlimited, GPL-2+ with Libtool exception and GPL-3+ with Libtool exception and GPL-3+, GPL-2+ with Autoconf exception, GPL-2+, OpenLDAP-2.8 and UMich and F5, OpenLDAP-2.8 and UMich, OpenLDAP-2.8 and JCG and UMich, OpenLDAP-2.8 and FSF-unlimited and GPL-2+ with Libtool exception, FSF-unlimited and GPL-2+ with Libtool exception, FSF-unlimited and OpenLDAP-2.8, MIT-XC, NeoSoft-permissive, BSD-3-clause, OpenLDAP-2.8 and BSD-3-clause, OpenLDAP-2.8 and Beerware, public-domain, OpenLDAP-2.8 and BSD-4-clause-California, OpenLDAP-2.8 and BSD-3-clause-variant, OpenLDAP-2.8 and Expat-ISC, OpenLDAP-2.8 and Expat-UNM, OpenLDAP-2.8 and Expat, BSD-3-clause-California, F5, GPL-2+ with Libtool exception, GPL-3+, GPL-3+ with Libtool exception, JCG, UMich, Beerware, BSD-3-clause-variant, Expat-ISC, Expat-UNM, Expat, BSD-4-clause-California | licenses/ubuntu/libldap2.copyright |
| `liblz4-1` | 1.9.4-1build1.1 | BSD-2-clause, GPL-2+, GPL-2+ or BSD-2-clause | licenses/ubuntu/liblz4-1.copyright |
| `liblzma5` | 5.6.1+really5.4.5-1ubuntu0.4 | PD, probably-PD, GPL-2+, LGPL-2.1+, permissive-fsf, Autoconf, permissive-nowarranty, GPL-2, none, config-h, noderivs, PD-debian | licenses/ubuntu/liblzma5.copyright |
| `libmd0` | 1.1.0-2build1.1 | BSD-3-clause, BSD-3-clause-Aaron-D-Gifford, BSD-2-clause, BSD-2-clause-NetBSD, ISC, Beerware, public-domain-md4, public-domain-md5, public-domain-sha1 | licenses/ubuntu/libmd0.copyright |
| `libmount1` | 2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/libmount1.copyright |
| `libncursesw6` | 6.4+20240113-1ubuntu2.2 | MIT/X11, X11, BSD-3-clause | licenses/ubuntu/libncursesw6.copyright |
| `libnettle8t64` | 3.9.1-2.2build1.1 | LGPL-3+ or GPL-2+, LGPL-2+, Expat, GPL-2+, GPL-3+ with Autoconf exception, public-domain, GPL-2, GAP, LGPL-3+ | licenses/ubuntu/libnettle8t64.copyright |
| `libnghttp2-14` | 1.59.0-1ubuntu0.4 | Expat, all-permissive, GPL-3+ with autoconf exception, MIT, BSD-2-clause | licenses/ubuntu/libnghttp2-14.copyright |
| `libnpth0t64` | 1.6-3.1build1 | LGPL-2.1+ | licenses/ubuntu/libnpth0t64.copyright |
| `libp11-kit0` | 0.25.3-4ubuntu2.2 | BSD-3-clause, FSFULLR, GPL-2+ with Autoconf-data exception, GPL-3+ with Autoconf-data exception, X11, ISC, customFSFULLRWD, Apache-2.0, LGPL-2.1+, customFSFUL, FSFAP | licenses/ubuntu/libp11-kit0.copyright |
| `libpam-modules` | 1.5.3-5ubuntu5.7 | BSD-3-clause or GPL, GPL-2, GPL-2+, GPL-3, GPL-3+ with Bison exception, BSD-tcp_wrappers, LGPL-2+, public-domain, Beerware | licenses/ubuntu/libpam-modules.copyright |
| `libpam-modules-bin` | 1.5.3-5ubuntu5.7 | BSD-3-clause or GPL, GPL-2, GPL-2+, GPL-3, GPL-3+ with Bison exception, BSD-tcp_wrappers, LGPL-2+, public-domain, Beerware | licenses/ubuntu/libpam-modules-bin.copyright |
| `libpam-runtime` | 1.5.3-5ubuntu5.7 | BSD-3-clause or GPL, GPL-2, GPL-2+, GPL-3, GPL-3+ with Bison exception, BSD-tcp_wrappers, LGPL-2+, public-domain, Beerware | licenses/ubuntu/libpam-runtime.copyright |
| `libpam0g` | 1.5.3-5ubuntu5.7 | BSD-3-clause or GPL, GPL-2, GPL-2+, GPL-3, GPL-3+ with Bison exception, BSD-tcp_wrappers, LGPL-2+, public-domain, Beerware | licenses/ubuntu/libpam0g.copyright |
| `libpcre2-8-0` | 10.42-4ubuntu2.1 | BSD-3-clause-Cambridge with BINARY LIBRARY-LIKE PACKAGES exception, BSD-3-clause, X11, BSD-2-clause, public-domain | licenses/ubuntu/libpcre2-8-0.copyright |
| `libpng16-16t64` | 1.6.43-5ubuntu0.7 | libpng, expat, GPL-2+ or BSD-like-with-advertising-clause, libpng OR Apache-2.0 OR BSD-3-clause, Apache-2.0, GPL-2+, BSD-like-with-advertising-clause, BSD-3-clause | licenses/ubuntu/libpng16-16t64.copyright |
| `libproc2-0` | 2:4.0.4-4ubuntu3.3 | LGPL-2.1+, LGPL-2.0+, GPL-2.0+ | licenses/ubuntu/libproc2-0.copyright |
| `libpsl5t64` | 0.21.2-1.1build1 | MIT, gnulib, Chromium | licenses/ubuntu/libpsl5t64.copyright |
| `libreadline8t64` | 8.2-4build1 | GPL-3+, GPL-2+, GFDL-NIV-1.3+, ISC-no-attribution, GPL-3 | licenses/ubuntu/libreadline8t64.copyright |
| `librtmp1` | 2.4+20151223.gitfa8646d.1-2build7 | free-form copyright file; refers to GPL-2, LGPL-2.1 | licenses/ubuntu/librtmp1.copyright |
| `libsasl2-2` | 2.1.28+dfsg1-5ubuntu3.1 | BSD-3-Clause-Attribution, BSD-3-clause, BSD-2-clause, GPL-3+, GPL-3, BSD-4-clause-UC, RSA-MD, BSD-3-Clause-Attribution and IBM-as-is, BSD-3-clause-JANET and BSD-3-Clause-Attribution, BSD-3-clause-PADL and MIT-OpenVision, MIT-OpenVision, OpenLDAP, FSFULLR and MIT-CMU, BSD-3-Clause-Attribution and MIT-Export, BSD-2-clause and MIT-CMU, BSD-2.2-clause, FSFULLR, MIT-Export, MIT-CMU, BSD-3-clause-JANET, BSD-3-clause-PADL, IBM-as-is | licenses/ubuntu/libsasl2-2.copyright |
| `libsasl2-modules-db` | 2.1.28+dfsg1-5ubuntu3.1 | BSD-3-Clause-Attribution, BSD-3-clause, BSD-2-clause, GPL-3+, GPL-3, BSD-4-clause-UC, RSA-MD, BSD-3-Clause-Attribution and IBM-as-is, BSD-3-clause-JANET and BSD-3-Clause-Attribution, BSD-3-clause-PADL and MIT-OpenVision, MIT-OpenVision, OpenLDAP, FSFULLR and MIT-CMU, BSD-3-Clause-Attribution and MIT-Export, BSD-2-clause and MIT-CMU, BSD-2.2-clause, FSFULLR, MIT-Export, MIT-CMU, BSD-3-clause-JANET, BSD-3-clause-PADL, IBM-as-is | licenses/ubuntu/libsasl2-modules-db.copyright |
| `libseccomp2` | 2.5.5-1ubuntu3.1 | LGPL-2.1 | licenses/ubuntu/libseccomp2.copyright |
| `libselinux1` | 3.5-2ubuntu2.1 | public-domain, GPL-2 | licenses/ubuntu/libselinux1.copyright |
| `libsemanage-common` | 3.5-1build5 | LGPL-2.1+, GPL-2 | licenses/ubuntu/libsemanage-common.copyright |
| `libsemanage2` | 3.5-1build5 | LGPL-2.1+, GPL-2 | licenses/ubuntu/libsemanage2.copyright |
| `libsepol2` | 3.5-2build1 | LGPL-2.1+, Zlib, GPL-2, GPL-2+ | licenses/ubuntu/libsepol2.copyright |
| `libsmartcols1` | 2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/libsmartcols1.copyright |
| `libsqlite3-0` | 3.45.1-1ubuntu2.8 | public-domain, GPL-2+ | licenses/ubuntu/libsqlite3-0.copyright |
| `libss2` | 1.47.0-2.4~exp1ubuntu4.1 | GPL-2, LGPL-2, BSD-3-Clause, Apache-2, ISC, GPL or MIT-US-export, Kazlib, Latex2e, GPL-2+ with Texinfo exception | licenses/ubuntu/libss2.copyright |
| `libssh-4` | 0.10.6-2ubuntu0.5 | LGPL-2.1+~OpenSSL, public-domain, LGPL-2.1, BSD-2-clause, BSD-3-clause, LGPL-2.1+~OpenSSL or BSD-2-clause or BSD-3-clause | licenses/ubuntu/libssh-4.copyright |
| `libssl3t64` | 3.0.13-0ubuntu3.16 | Apache-2.0, Artistic or GPL-1+, Artistic, GPL-1+ | licenses/ubuntu/libssl3t64.copyright |
| `libstdc++6` | 14.2.0-4ubuntu2~24.04.1 | free-form copyright file; refers to GPL, GPL-3, GFDL-1.2, Artistic, LGPL | licenses/ubuntu/libstdc++6.copyright |
| `libsystemd0` | 255.4-1ubuntu8.18 | LGPL-2.1+, CC0-1.0, GPL-2 with Linux-syscall-note exception, Expat, public-domain, GPL-2+ | licenses/ubuntu/libsystemd0.copyright |
| `libtasn1-6` | 4.19.0-3ubuntu0.24.04.2 | free-form copyright file; refers to LGPL, LGPL-2.1, GPL-3, GFDL-1.3 | licenses/ubuntu/libtasn1-6.copyright |
| `libtinfo6` | 6.4+20240113-1ubuntu2.2 | MIT/X11, X11, BSD-3-clause | licenses/ubuntu/libtinfo6.copyright |
| `libudev1` | 255.4-1ubuntu8.18 | LGPL-2.1+, CC0-1.0, GPL-2 with Linux-syscall-note exception, Expat, public-domain, GPL-2+ | licenses/ubuntu/libudev1.copyright |
| `libunistring5` | 1.1-2build1.1 | LGPL-3+ or GPL-2+, FreeSoftware, GPL-3+ or GFDL-NIV-1.2+, GPL-3+, GPL-2+, GPL-2+ with distribution exception, GPL-2+ with distribution exception, Expat and GPL-2+, X11, LGPL-3+, GFDL-NIV-1.2+ | licenses/ubuntu/libunistring5.copyright |
| `libuuid1` | 2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/libuuid1.copyright |
| `libxxhash0` | 0.8.2-2build1 | BSD-2-clause, GPL-2+, BSD-2-clause or GPL-2+ | licenses/ubuntu/libxxhash0.copyright |
| `libzstd1` | 1.5.5+dfsg2-2build1.1 | BSD-3-clause or GPL-2, zlib, Expat, GPL-2, BSD-3-clause | licenses/ubuntu/libzstd1.copyright |
| `locales` | 2.39-0ubuntu8.9 | free-form copyright file; refers to LGPL-2.1, GPL-2, GFDL-1.3 | licenses/ubuntu/locales.copyright |
| `login` | 1:4.13+dfsg1-4ubuntu3.2 | BSD-3-clause, GPL-1, GPL-2+, public-domain | licenses/ubuntu/login.copyright |
| `logsave` | 1.47.0-2.4~exp1ubuntu4.1 | GPL-2, LGPL-2, BSD-3-Clause, Apache-2, ISC, GPL or MIT-US-export, Kazlib, Latex2e, GPL-2+ with Texinfo exception | licenses/ubuntu/logsave.copyright |
| `mawk` | 1.3.4.20240123-1build1 | GPL-2.0-only, X11, CC-BY-3.0 | licenses/ubuntu/mawk.copyright |
| `mount` | 2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/mount.copyright |
| `ncurses-base` | 6.4+20240113-1ubuntu2.2 | MIT/X11, X11, BSD-3-clause | licenses/ubuntu/ncurses-base.copyright |
| `ncurses-bin` | 6.4+20240113-1ubuntu2.2 | MIT/X11, X11, BSD-3-clause | licenses/ubuntu/ncurses-bin.copyright |
| `openssl` | 3.0.13-0ubuntu3.16 | Apache-2.0, Artistic or GPL-1+, Artistic, GPL-1+ | licenses/ubuntu/openssl.copyright |
| `p11-kit` | 0.25.3-4ubuntu2.2 | BSD-3-clause, FSFULLR, GPL-2+ with Autoconf-data exception, GPL-3+ with Autoconf-data exception, X11, ISC, customFSFULLRWD, Apache-2.0, LGPL-2.1+, customFSFUL, FSFAP | licenses/ubuntu/p11-kit.copyright |
| `p11-kit-modules` | 0.25.3-4ubuntu2.2 | BSD-3-clause, FSFULLR, GPL-2+ with Autoconf-data exception, GPL-3+ with Autoconf-data exception, X11, ISC, customFSFULLRWD, Apache-2.0, LGPL-2.1+, customFSFUL, FSFAP | licenses/ubuntu/p11-kit-modules.copyright |
| `passwd` | 1:4.13+dfsg1-4ubuntu3.2 | BSD-3-clause, GPL-1, GPL-2+, public-domain | licenses/ubuntu/passwd.copyright |
| `perl-base` | 5.38.2-3.2ubuntu0.6 | GPL-1+ or Artistic, Expat, REGCOMP, and GPL-1+ or Artistic, GPL-3+-WITH-BISON-EXCEPTION, Unicode, GPL-1+ or Artistic, and Unicode, BZIP, ZLIB, Artistic, GPL-2+ or Artistic, Expat or GPL-1+ or Artistic, FSFAP, BSD-3-clause-with-weird-numbering, CC0-1.0, TEXT-TABS, GPL-1+ or Artistic, and BSD-4-clause-POWERDOG, GPL-1+ or Artistic, and BSD-3-clause-GENERIC, BSD-3-clause, SDBM-PUBLIC-DOMAIN, DONT-CHANGE-THE-GPL, GPL-1+ or Artistic or Artistic-dist, Artistic-dist, Artistic or GPL-1+ or Artistic-dist, GPL-1+ or Artistic, and Expat, LGPL-2.1, GPL-1+, GPL-2+, Artistic-2, BSD-4-clause-POWERDOG, BSD-3-clause-GENERIC, REGCOMP | licenses/ubuntu/perl-base.copyright |
| `pinentry-curses` | 1.2.1-3ubuntu5 | GPL-2+, GPL-2, X11, LGPL-3+ or GPL-2+, LGPL-3+ | licenses/ubuntu/pinentry-curses.copyright |
| `procps` | 2:4.0.4-4ubuntu3.3 | LGPL-2.1+, LGPL-2.0+, GPL-2.0+ | licenses/ubuntu/procps.copyright |
| `readline-common` | 8.2-4build1 | GPL-3+, GPL-2+, GFDL-NIV-1.3+, ISC-no-attribution, GPL-3 | licenses/ubuntu/readline-common.copyright |
| `sed` | 4.9-2ubuntu0.24.04.1 | GPL-3+, X11, GFDL-NIV-1.3+, GPL-3+ and ISC, ISC, BSD-4-clause-UC, BSL-1, pcre | licenses/ubuntu/sed.copyright |
| `sensible-utils` | 0.0.22 | GPL-2+, All-permissive, configure, installsh, BSD-2-clause | licenses/ubuntu/sensible-utils.copyright |
| `sysvinit-utils` | 3.08-6ubuntu3 | GPL-2.0+, LGPL-2.1+, GPL-3.0, GPL-2.0 | licenses/ubuntu/sysvinit-utils.copyright |
| `tar` | 1.35+dfsg-3ubuntu0.4 | GPL-3+, GPL-3+ with Bison exception, LGPL-2.1+, LGPL-3+, GPL-2+ | licenses/ubuntu/tar.copyright |
| `tzdata` | 2026e-0ubuntu0.24.04.1 | public-domain, ICU | licenses/ubuntu/tzdata.copyright |
| `ubuntu-keyring` | 2023.11.28.1 | free-form copyright file; refers to GPL | licenses/ubuntu/ubuntu-keyring.copyright |
| `unminimize` | 0.2.1 | GPL-2.0+ | licenses/ubuntu/unminimize.copyright |
| `util-linux` | 2.39.3-9ubuntu6.6 | GPL-2+, GPL-2, GPL-3+, public-domain, BSD-4-clause, MIT, BSD-3-clause, BSLA, LGPL-2+, LGPL-2.1+, LGPL, LGPL-3+ | licenses/ubuntu/util-linux.copyright |
| `wget` | 1.21.4-1ubuntu4.5 | free-form copyright file; refers to GPL-3, GFDL-1.2 | licenses/ubuntu/wget.copyright |
| `zlib1g` | 1:1.3.dfsg-3.1ubuntu2.3 | Zlib | licenses/ubuntu/zlib1g.copyright |

<!-- end of packages -->

## Build tools (not shipped)

Gradle, the Kotlin compiler and its Gradle plugins build the server; none of them is in the image.

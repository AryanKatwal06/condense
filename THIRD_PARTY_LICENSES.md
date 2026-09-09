# Third-Party Licenses & Notices

This document details the open-source licenses and copyright notices for all third-party software components included in Condense runtime and native distributions.

Condense is distributed under the [Apache License, Version 2.0](LICENSE).

---

## Summary of Runtime Dependencies

| Component | Group ID : Artifact ID | Version | License | Upstream Project |
|---|---|---|---|---|
| **Quarkus Picocli** | `io.quarkus:quarkus-picocli` | 3.11.0 | Apache-2.0 | https://github.com/quarkusio/quarkus |
| **Quarkus Arc** | `io.quarkus:quarkus-arc` | 3.11.0 | Apache-2.0 | https://github.com/quarkusio/quarkus |
| **Jackson Databind** | `com.fasterxml.jackson.core:jackson-databind` | 2.17.1 | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| **Jackson TOML** | `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` | 2.17.1 | Apache-2.0 | https://github.com/FasterXML/jackson-dataformats-text |
| **SQLite JDBC** | `org.xerial:sqlite-jdbc` | 3.45.3.0 | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |

---

## Approved License Allowlist Policy

Condense enforces a strict license allowlist for all production runtime dependencies. Only non-reciprocal, permissive open-source licenses are permitted:

- **Apache License, Version 2.0**
- **MIT License**
- **BSD 2-Clause / 3-Clause Licenses**
- **ISC License**

Reciprocal/copyleft licenses (including GNU GPL, GNU LGPL, GNU AGPL, SSPL, and EUPL) are strictly forbidden in production runtime distributions.

---

## Third-Party Component Notices & Attributions

### 1. Quarkus (Picocli & Arc)

- **Artifacts:** `io.quarkus:quarkus-picocli`, `io.quarkus:quarkus-arc`
- **Copyright:** Copyright (c) 2018-2026 Red Hat, Inc. and individual contributors.
- **License:** Apache License, Version 2.0
- **Home:** https://quarkus.io / https://github.com/quarkusio/quarkus

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 2. Jackson (Core, Databind, TOML)

- **Artifacts:** `com.fasterxml.jackson.core:jackson-databind`, `com.fasterxml.jackson.dataformat:jackson-dataformat-toml`, `com.fasterxml.jackson.core:jackson-core`, `com.fasterxml.jackson.core:jackson-annotations`
- **Copyright:** Copyright (c) 2007-2026 FasterXML, LLC.
- **License:** Apache License, Version 2.0
- **Home:** https://github.com/FasterXML/jackson

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 3. SQLite JDBC Driver

- **Artifact:** `org.xerial:sqlite-jdbc`
- **Copyright:** Copyright (c) 2008-2026 Taro L. Saito and Xerial project contributors.
- **License:** Apache License, Version 2.0
- **Home:** https://github.com/xerial/sqlite-jdbc

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 4. SQLite (Embedded Native Code)

- **Component:** SQLite Database Engine (bundled inside `sqlite-jdbc`)
- **License:** Public Domain
- **Home:** https://www.sqlite.org/

```
All of the code and documentation in SQLite has been dedicated to the public domain
by the authors. All code authors, and representatives of the companies they work for,
have signed affidavits dedicating their contributions to the public domain and
original signed copies of these affidavits are stored in a firesafe at the main
offices of Hwaci. Anyone is free to copy, modify, publish, use, compile, sell, or
distribute the original SQLite code, either in source code form or as a compiled
binary, for any purpose, commercial or non-commercial, and by any means.
```

### 5. Picocli

- **Artifact:** `info.picocli:picocli` (transitive via `quarkus-picocli`)
- **Copyright:** Copyright (c) 2017-2026 Remko Popma
- **License:** Apache License, Version 2.0
- **Home:** https://picocli.info / https://github.com/remkop/picocli

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

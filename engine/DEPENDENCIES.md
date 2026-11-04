# Engine dependencies

The conversion engine is built from pinned, locally retained inputs.

| Component | Pinned version | Origin | License |
| --- | --- | --- | --- |
| hwp2hwpx | commit `edc05278506b663d5bdd98050a51f54b7ff5e0bc` | <https://github.com/neolord0/hwp2hwpx> | Apache-2.0 |
| hwplib | `1.1.10` | Maven Central, `kr.dogfoot:hwplib:1.1.10` | Apache-2.0 |
| hwpxlib | `1.0.9` | Maven Central, `kr.dogfoot:hwpxlib:1.0.9` | Apache-2.0 |
| Eclipse Temurin JDK | `17.0.20.1+1`, macOS aarch64 | Adoptium Temurin release | GPL-2.0-with-classpath-exception and module-specific notices in `runtime/jdk/legal` |

The upstream hwp2hwpx source is stored in `vendor/`; the Maven artifacts are
stored in `lib/`; the full build JDK and compact application runtime are stored
under `../runtime/`. `CHECKSUMS.sha256` records every downloaded build input.

The program performs conversion and local package editing only. It does not
upload documents or include user documents in its source or test fixtures.

DOCX import uses the bundled JDK ZIP/XML APIs with external entities disabled,
package size/path/duplicate checks, and `hwpxlib`'s `BlankFileMaker` as the base
HWPX package. It adds no dependency or network fetch. OOXML-to-HWPX mapping lives
in `src/main/java/local/nahw/hwpxstudio/DocxConverter.java`. Legacy `.doc` is not
supported by this importer.

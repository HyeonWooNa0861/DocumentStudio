package local.nahw.hwpxstudio;

import kr.dogfoot.hwpxlib.reader.HWPXReader;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class HwpxArchive {
    static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
    static final int MAX_ENTRIES = 10_000;
    private static final String MIMETYPE = "application/hwp+zip";

    private final LinkedHashMap<String, EntryData> entries;

    private HwpxArchive(LinkedHashMap<String, EntryData> entries) {
        this.entries = entries;
    }

    static HwpxArchive read(Path path, boolean libraryValidation) throws EngineException {
        requireReadableFile(path, "HWPX 파일");
        try {
            long size = Files.size(path);
            if (size > MAX_ARCHIVE_BYTES) {
                throw new EngineException("HWPX 파일이 허용 크기(512MB)를 초과합니다.");
            }
        } catch (IOException e) {
            throw new EngineException("HWPX 파일 크기를 확인할 수 없습니다.", e);
        }

        LinkedHashMap<String, EntryData> entries = new LinkedHashMap<String, EntryData>();
        long total = 0L;
        try (InputStream raw = Files.newInputStream(path);
             ZipInputStream zip = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw new EngineException("HWPX 내부 항목 수가 허용 범위를 초과합니다.");
                }
                String name = safeEntryName(entry.getName());
                if (entries.containsKey(name)) {
                    throw new EngineException("HWPX에 중복된 내부 경로가 있습니다: " + name);
                }
                if (entry.getSize() > MAX_ENTRY_BYTES) {
                    throw new EngineException("HWPX 내부 항목이 너무 큽니다: " + name);
                }
                byte[] bytes = readBounded(zip, MAX_ENTRY_BYTES, name);
                total += bytes.length;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new EngineException("압축을 푼 HWPX 내용이 허용 크기(512MB)를 초과합니다.");
                }
                entries.put(name, EntryData.from(entry, bytes));
                zip.closeEntry();
            }
        } catch (ZipException e) {
            throw new EngineException("올바른 HWPX ZIP 문서가 아닙니다.", e);
        } catch (IOException e) {
            throw new EngineException("HWPX 파일을 읽을 수 없습니다.", e);
        }

        EntryData mime = entries.get("mimetype");
        if (mime == null || !MIMETYPE.equals(new String(mime.bytes, StandardCharsets.US_ASCII).trim())) {
            throw new EngineException("HWPX mimetype 항목이 없거나 올바르지 않습니다.");
        }
        HwpxArchive archive = new HwpxArchive(entries);
        archive.validateXmlEntries();
        if (libraryValidation) archive.validateWithLibrary(path);
        return archive;
    }

    List<String> sectionNames() throws EngineException {
        ArrayList<String> result = new ArrayList<String>();
        for (String name : entries.keySet()) {
            if (name.matches("Contents/section[0-9]+\\.xml")) result.add(name);
        }
        Collections.sort(result, new Comparator<String>() {
            public int compare(String left, String right) {
                return Integer.compare(sectionNumber(left), sectionNumber(right));
            }
        });
        if (result.isEmpty()) throw new EngineException("HWPX 본문 section XML을 찾을 수 없습니다.");
        return result;
    }

    Document parse(String name) throws EngineException {
        EntryData entry = entries.get(name);
        if (entry == null) throw new EngineException("HWPX 내부 항목을 찾을 수 없습니다: " + name);
        return parseXml(entry.bytes, name);
    }

    void replaceXml(String name, Document document) throws EngineException {
        EntryData old = entries.get(name);
        if (old == null) throw new EngineException("HWPX 내부 항목을 찾을 수 없습니다: " + name);
        byte[] replacement = serializeXml(document);
        parseXml(replacement, name);
        entries.put(name, old.withBytes(replacement));
    }

    byte[] bytes(String name) {
        EntryData entry = entries.get(name);
        return entry == null ? null : entry.bytes.clone();
    }

    Set<String> names() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    void writeAtomically(Path output) throws EngineException {
        OutputTarget target = OutputTarget.prepare(output);
        try {
            try (OutputStream raw = Files.newOutputStream(target.temp);
                 ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                for (EntryData data : entries.values()) {
                    ZipEntry entry = data.toZipEntry();
                    zip.putNextEntry(entry);
                    zip.write(data.bytes);
                    zip.closeEntry();
                }
            }
            HwpxArchive.read(target.temp, true);
            target.commit();
        } catch (EngineException e) {
            target.cleanup();
            throw e;
        } catch (Exception e) {
            target.cleanup();
            throw new EngineException("HWPX 출력 파일을 저장할 수 없습니다.", e);
        }
    }

    static void writeGeneratedAtomically(byte[] bytes, Path output) throws EngineException {
        OutputTarget target = OutputTarget.prepare(output);
        try {
            Files.write(target.temp, bytes);
            HwpxArchive.read(target.temp, true);
            target.commit();
        } catch (EngineException e) {
            target.cleanup();
            throw e;
        } catch (Exception e) {
            target.cleanup();
            throw new EngineException("변환된 HWPX 파일을 저장할 수 없습니다.", e);
        }
    }

    static void requireReadableFile(Path path, String kind) throws EngineException {
        if (!Files.exists(path)) throw new EngineException(kind + "을(를) 찾을 수 없습니다: " + path);
        if (!Files.isRegularFile(path)) throw new EngineException(kind + "이(가) 일반 파일이 아닙니다: " + path);
        if (!Files.isReadable(path)) throw new EngineException(kind + "을(를) 읽을 수 없습니다: " + path);
    }

    static void ensureDistinctInputOutput(Path input, Path output) throws EngineException {
        Path in;
        try {
            in = input.toRealPath();
        } catch (IOException e) {
            throw new EngineException("입력 파일 경로를 확인할 수 없습니다.", e);
        }
        Path out = output.toAbsolutePath().normalize();
        if (Files.exists(out)) {
            try {
                if (Files.isSameFile(in, out)) throw new EngineException("입력 파일과 출력 파일은 달라야 합니다.");
            } catch (IOException e) {
                throw new EngineException("출력 파일 경로를 확인할 수 없습니다.", e);
            }
            throw new EngineException("기존 파일을 덮어쓰지 않습니다. 다른 출력 이름을 선택하세요: " + out);
        }
        Path parent = out.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new EngineException("출력 폴더가 존재하지 않습니다: " + parent);
        }
        try {
            Path canonicalOut = parent.toRealPath().resolve(out.getFileName());
            if (in.equals(canonicalOut)) throw new EngineException("입력 파일과 출력 파일은 달라야 합니다.");
        } catch (IOException e) {
            throw new EngineException("출력 폴더 경로를 확인할 수 없습니다.", e);
        }
    }

    /**
     * Publishes a complete same-directory temporary file without ever replacing
     * an output path that another process created after the initial checks.
     * Creating the hard link is the atomic no-clobber operation; filesystems
     * without hard-link support fail closed instead of falling back to rename.
     */
    static void publishTempNoClobber(Path temp, Path output) throws EngineException {
        try {
            Files.createLink(output, temp);
        } catch (FileAlreadyExistsException e) {
            throw new EngineException("저장하는 동안 같은 이름의 파일이 만들어졌습니다. 기존 파일은 덮어쓰지 않았습니다: " + output, e);
        } catch (UnsupportedOperationException e) {
            throw new EngineException("이 파일 시스템에서는 안전한 무덮어쓰기 저장을 지원하지 않습니다.", e);
        } catch (IOException e) {
            throw new EngineException("출력 파일을 안전하게 공개할 수 없습니다. 기존 파일은 덮어쓰지 않았습니다.", e);
        }

        try {
            Files.delete(temp);
        } catch (IOException e) {
            // The complete output has already been atomically published. Keep
            // success semantics and arrange cleanup when this process exits.
            temp.toFile().deleteOnExit();
        }
    }

    private void validateXmlEntries() throws EngineException {
        for (EntryData entry : entries.values()) {
            if (entry.name.endsWith(".xml") || entry.name.endsWith(".hpf")) {
                parseXml(entry.bytes, entry.name);
            }
        }
    }

    private void validateWithLibrary(Path path) throws EngineException {
        try {
            if (HWPXReader.fromFilepath(path.toString()) == null) {
                throw new EngineException("HWPX 라이브러리가 문서를 읽지 못했습니다.");
            }
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("HWPX 문서 구조 검증에 실패했습니다.", e);
        }
    }

    static Document parseXml(byte[] bytes, String name) throws EngineException {
        if (bytes.length > MAX_ENTRY_BYTES) throw new EngineException("XML 항목이 너무 큽니다: " + name);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new EngineException("안전하게 해석할 수 없는 XML 항목입니다: " + name, e);
        }
    }

    static byte[] serializeXml(Document document) throws EngineException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new EngineException("수정한 본문 XML을 만들 수 없습니다.", e);
        }
    }

    static byte[] readBounded(InputStream input, long limit, String name) throws IOException, EngineException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        long count = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            count += read;
            if (count > limit) throw new EngineException("HWPX 내부 항목이 너무 큽니다: " + name);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static String safeEntryName(String name) throws EngineException {
        if (name == null || name.length() == 0 || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new EngineException("안전하지 않은 HWPX 내부 경로입니다.");
        }
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("..") || parts[i].equals(".")) {
                throw new EngineException("안전하지 않은 HWPX 내부 경로입니다: " + name);
            }
            if (parts[i].length() == 0 && i != parts.length - 1) {
                throw new EngineException("안전하지 않은 HWPX 내부 경로입니다: " + name);
            }
        }
        return name;
    }

    private static int sectionNumber(String name) {
        int start = name.lastIndexOf("section") + 7;
        int end = name.lastIndexOf(".xml");
        return Integer.parseInt(name.substring(start, end));
    }

    private static final class EntryData {
        final String name;
        final byte[] bytes;
        final int method;
        final long time;
        final String comment;
        final byte[] extra;

        EntryData(String name, byte[] bytes, int method, long time, String comment, byte[] extra) {
            this.name = name;
            this.bytes = bytes;
            this.method = method == ZipEntry.STORED ? ZipEntry.STORED : ZipEntry.DEFLATED;
            this.time = time;
            this.comment = comment;
            this.extra = extra == null ? null : extra.clone();
        }

        static EntryData from(ZipEntry entry, byte[] bytes) {
            return new EntryData(entry.getName(), bytes, entry.getMethod(), entry.getTime(), entry.getComment(), entry.getExtra());
        }

        EntryData withBytes(byte[] replacement) {
            return new EntryData(name, replacement, method, time, comment, extra);
        }

        ZipEntry toZipEntry() {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(method);
            if (time >= 0) entry.setTime(time);
            if (comment != null) entry.setComment(comment);
            if (extra != null) entry.setExtra(extra);
            if (method == ZipEntry.STORED) {
                CRC32 crc = new CRC32();
                crc.update(bytes);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
            }
            return entry;
        }
    }

    private static final class OutputTarget {
        final Path output;
        final Path temp;

        private OutputTarget(Path output, Path temp) {
            this.output = output;
            this.temp = temp;
        }

        static OutputTarget prepare(Path output) throws EngineException {
            Path normalized = output.toAbsolutePath().normalize();
            if (Files.exists(normalized)) {
                throw new EngineException("기존 파일을 덮어쓰지 않습니다. 다른 출력 이름을 선택하세요: " + normalized);
            }
            Path parent = normalized.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new EngineException("출력 폴더가 존재하지 않습니다: " + parent);
            }
            try {
                return new OutputTarget(normalized,
                        Files.createTempFile(parent, "." + normalized.getFileName() + ".", ".tmp"));
            } catch (IOException e) {
                throw new EngineException("출력 폴더에 임시 파일을 만들 수 없습니다.", e);
            }
        }

        void commit() throws EngineException {
            publishTempNoClobber(temp, output);
        }

        void cleanup() {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // A failed temporary-file cleanup is not allowed to hide the original error.
            }
        }
    }
}

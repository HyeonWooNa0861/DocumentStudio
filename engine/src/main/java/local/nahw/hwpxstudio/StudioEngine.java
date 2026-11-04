package local.nahw.hwpxstudio;

import kr.dogfoot.hwp2hwpx.Hwp2Hwpx;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StudioEngine {
    private static final String VERSION = "1.2.1";
    private static final long MAX_EDIT_JSON_BYTES = 2L * 1024L * 1024L;

    private StudioEngine() {}

    public static void main(String[] args) {
        try {
            Map<String, Object> result = execute(args);
            System.out.println(Json.stringify(result));
        } catch (EngineException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (Throwable e) {
            String message = deepestMessage(e);
            System.err.println("문서를 처리하는 중 예상하지 못한 오류가 발생했습니다."
                    + (message.length() == 0 ? "" : " " + message));
            System.exit(3);
        }
    }

    private static Map<String, Object> execute(String[] args) throws Exception {
        if (args.length == 1 && ("version".equals(args[0]) || "--version".equals(args[0]))) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("version", VERSION);
            result.put("hwp2hwpxCommit", "edc05278506b663d5bdd98050a51f54b7ff5e0bc");
            result.put("hwplib", "1.1.10");
            result.put("hwpxlib", "1.0.9");
            result.put("convertInputs", java.util.Arrays.asList("hwp", "docx", "hwpx"));
            LinkedHashMap<String, Object> conversions = new LinkedHashMap<String, Object>();
            conversions.put("hwp", java.util.Arrays.asList("hwpx"));
            conversions.put("docx", java.util.Arrays.asList("hwpx"));
            conversions.put("hwpx", java.util.Arrays.asList("hwp"));
            result.put("conversions", conversions);
            return result;
        }
        if (args.length == 3 && "convert".equals(args[0])) {
            return convert(Paths.get(args[1]), Paths.get(args[2]));
        }
        if (args.length == 2 && "inspect".equals(args[0])) {
            return inspect(Paths.get(args[1]));
        }
        if (args.length == 4 && "edit".equals(args[0])) {
            return edit(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
        }
        if (args.length == 2 && "visual".equals(args[0])) {
            return VisualDocument.snapshot(Paths.get(args[1]));
        }
        if (args.length == 4 && "visual-edit".equals(args[0])) {
            return VisualDocument.edit(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
        }
        throw new EngineException("사용법: StudioEngine convert INPUT.hwp|INPUT.docx OUTPUT.hwpx | convert INPUT.hwpx OUTPUT.hwp | inspect INPUT.hwpx | edit INPUT.hwpx EDITS.json OUTPUT.hwpx | visual INPUT.hwpx | visual-edit INPUT.hwpx REQUEST.json OUTPUT.hwpx");
    }

    private static Map<String, Object> convert(Path input, Path output) throws Exception {
        String name = input.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String outputName = output.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".hwpx")) {
            if (!outputName.endsWith(".hwp")) throw new EngineException("HWPX 역변환의 출력 확장자는 .hwp여야 합니다.");
            return HwpxToHwpConverter.convert(input, output);
        }
        if (!outputName.endsWith(".hwpx")) throw new EngineException("HWP·DOCX 변환의 출력 확장자는 .hwpx여야 합니다.");
        if (name.endsWith(".docx")) return DocxConverter.convert(input, output);
        if (!name.endsWith(".hwp")) throw new EngineException("HWP 또는 DOCX 파일을 선택하세요. DOC 변환은 아직 지원하지 않습니다.");
        HwpxArchive.requireReadableFile(input, "HWP 파일");
        HwpxArchive.ensureDistinctInputOutput(input, output);
        if (Files.size(input) > HwpxArchive.MAX_ARCHIVE_BYTES) {
            throw new EngineException("HWP 파일이 허용 크기(512MB)를 초과합니다.");
        }

        HWPFile hwp;
        try {
            hwp = HWPReader.fromFile(input.toFile());
        } catch (Exception e) {
            String detail = deepestMessage(e).toLowerCase();
            if (detail.contains("password") || detail.contains("encrypt") || detail.contains("decrypt")) {
                throw new EngineException("암호화된 HWP 파일은 변환할 수 없습니다. 한컴오피스에서 암호를 해제한 복사본을 사용하세요.", e);
            }
            throw new EngineException("HWP 파일을 읽을 수 없습니다. HWP5 형식인지, 파일이 손상되지 않았는지 확인하세요.", e);
        }
        if (hwp == null || hwp.getFileHeader() == null) {
            throw new EngineException("올바른 HWP5 문서가 아닙니다.");
        }
        if (hwp.getFileHeader().hasPassword() || hwp.getFileHeader().isEncryptPublicCertification()
                || hwp.getFileHeader().isDRMDocument() || hwp.getFileHeader().isPublicCertificationDRMDocument()) {
            throw new EngineException("암호화되었거나 DRM이 적용된 HWP 파일은 변환할 수 없습니다.");
        }

        try {
            HWPXFile hwpx = Hwp2Hwpx.toHWPX(hwp);
            HwpCompatibility.correctLineSpacing(hwp, hwpx);
            byte[] bytes = HWPXWriter.toBytes(hwpx);
            HwpxArchive.writeGeneratedAtomically(bytes, output);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("HWP 내용을 HWPX로 변환하지 못했습니다.", e);
        }
        return outputResult(output);
    }

    private static Map<String, Object> inspect(Path input) throws EngineException {
        HwpxArchive archive = HwpxArchive.read(input, true);
        ParagraphEditor.Inspection inspection = ParagraphEditor.inspect(archive);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("source", input.toAbsolutePath().normalize().toString());
        result.put("blocks", inspection.jsonBlocks());
        result.put("warnings", new ArrayList<Object>());
        return result;
    }

    private static Map<String, Object> edit(Path input, Path editsPath, Path output) throws Exception {
        HwpxArchive.requireReadableFile(input, "HWPX 파일");
        HwpxArchive.requireReadableFile(editsPath, "수정 JSON 파일");
        HwpxArchive.ensureDistinctInputOutput(input, output);
        if (Files.size(editsPath) > MAX_EDIT_JSON_BYTES) {
            throw new EngineException("수정 JSON 파일이 허용 크기(2MB)를 초과합니다.");
        }
        String json = new String(Files.readAllBytes(editsPath), StandardCharsets.UTF_8);
        Map<String, String> edits = parseEdits(json);
        HwpxArchive archive = HwpxArchive.read(input, true);
        ParagraphEditor.Inspection inspection = ParagraphEditor.inspect(archive);
        ParagraphEditor.apply(inspection, edits);
        archive.writeAtomically(output);
        return outputResult(output);
    }

    private static Map<String, String> parseEdits(String json) throws EngineException {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) throw new EngineException("수정 JSON의 최상위 값은 객체여야 합니다.");
        Object editsValue = ((Map<?, ?>) parsed).get("edits");
        if (!(editsValue instanceof List)) throw new EngineException("수정 JSON에 edits 배열이 필요합니다.");
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        for (Object item : (List<?>) editsValue) {
            if (!(item instanceof Map)) throw new EngineException("edits의 각 항목은 객체여야 합니다.");
            Map<?, ?> map = (Map<?, ?>) item;
            Object id = map.get("id");
            Object text = map.get("text");
            if (!(id instanceof String) || ((String) id).length() == 0) {
                throw new EngineException("각 수정 항목에 문자열 id가 필요합니다.");
            }
            if (!(text instanceof String)) throw new EngineException("각 수정 항목에 문자열 text가 필요합니다.");
            if (result.containsKey(id)) throw new EngineException("같은 수정 id가 두 번 들어 있습니다: " + id);
            result.put((String) id, (String) text);
        }
        return result;
    }

    private static Map<String, Object> outputResult(Path output) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("output", output.toAbsolutePath().normalize().toString());
        return result;
    }

    private static String deepestMessage(Throwable error) {
        Throwable cursor = error;
        String message = "";
        while (cursor != null) {
            if (cursor.getMessage() != null && cursor.getMessage().trim().length() > 0) {
                message = cursor.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
            }
            cursor = cursor.getCause();
        }
        return message;
    }
}

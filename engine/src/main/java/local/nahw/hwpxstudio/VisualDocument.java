package local.nahw.hwpxstudio;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rich, loss-preserving model used by the native visual editor. */
final class VisualDocument {
    private static final long MAX_REQUEST = 2L * 1024L * 1024L;
    private static final long MAX_MODEL = 64L * 1024L * 1024L;
    private static final long MAX_IMAGE = 16L * 1024L * 1024L;
    private static final long MAX_IMAGES = 32L * 1024L * 1024L;
    private static final int MAX_OPERATIONS = 10_000;

    private VisualDocument() {}

    static Map<String, Object> snapshot(Path input) throws Exception {
        HwpxArchive.requireReadableFile(input, "HWPX 파일");
        String digest = sha256(input);
        HwpxArchive archive = HwpxArchive.read(input, true);
        if (!digest.equals(sha256(input))) throw new EngineException("시각 편집 스냅샷을 만드는 동안 원본 문서가 변경되었습니다.");
        Model model = build(archive);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("schemaVersion", 1);
        out.put("source", input.toAbsolutePath().normalize().toString());
        out.put("sourceSha256", digest);
        out.put("sections", model.sections);
        out.put("warnings", model.warnings);
        if (Json.stringify(out).getBytes(StandardCharsets.UTF_8).length > MAX_MODEL) {
            throw new EngineException("시각 편집 모델이 허용 크기(64MB)를 초과합니다.");
        }
        return out;
    }

    static Map<String, Object> edit(Path input, Path requestPath, Path output) throws Exception {
        HwpxArchive.requireReadableFile(input, "HWPX 파일");
        HwpxArchive.requireReadableFile(requestPath, "시각 편집 요청 JSON 파일");
        HwpxArchive.ensureDistinctInputOutput(input, output);
        if (Files.size(requestPath) > MAX_REQUEST) throw new EngineException("시각 편집 요청은 2MB를 넘을 수 없습니다.");
        Object value = Json.parse(new String(Files.readAllBytes(requestPath), StandardCharsets.UTF_8));
        if (!(value instanceof Map)) throw new EngineException("시각 편집 요청의 최상위 값은 객체여야 합니다.");
        Map<?, ?> request = (Map<?, ?>) value;
        if (!numberEquals(request.get("schemaVersion"), 1)) throw new EngineException("지원하지 않는 시각 편집 스키마입니다.");
        String actualDigest = sha256(input);
        if (!(request.get("sourceSha256") instanceof String) || !actualDigest.equals(request.get("sourceSha256"))) {
            throw new EngineException("원본 문서가 시각 편집 스냅샷 이후 변경되었습니다.");
        }

        HwpxArchive archive = HwpxArchive.read(input, true);
        if (!actualDigest.equals(sha256(input))) throw new EngineException("시각 편집 요청을 읽는 동안 원본 문서가 변경되었습니다.");
        ParagraphEditor.Inspection inspection = ParagraphEditor.inspect(archive);
        Request parsed = Request.parse(request);
        if (parsed.operationCount > MAX_OPERATIONS) throw new EngineException("시각 편집 작업은 10,000개를 넘을 수 없습니다.");
        ParagraphEditor.applyStructure(inspection, parsed.structure);
        validateIds(parsed, inspection, archive);
        ParagraphEditor.apply(inspection, parsed.edits);
        applyStyles(archive, inspection, parsed.styles);
        applyTables(archive, parsed.tables);
        if (!actualDigest.equals(sha256(input))) throw new EngineException("저장 준비 중 원본 문서가 변경되어 출력을 만들지 않았습니다.");
        archive.writeAtomically(output);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("output", output.toAbsolutePath().normalize().toString());
        result.put("schemaVersion", 1);
        result.put("sourceSha256", sha256(output));
        return result;
    }

    private static Model build(HwpxArchive archive) throws Exception {
        ParagraphEditor.Inspection inspection = ParagraphEditor.inspect(archive);
        IdentityHashMap<Element, ParagraphEditor.Block> blocks = new IdentityHashMap<Element, ParagraphEditor.Block>();
        for (ParagraphEditor.Block block : inspection.byId.values()) blocks.put(block.paragraph, block);
        Header header = Header.read(archive);
        Images images = Images.read(archive);
        ArrayList<Object> sections = new ArrayList<Object>();
        ArrayList<Object> warnings = new ArrayList<Object>();
        int sectionOrdinal = 0;
        long[] imageBytes = new long[] {0};
        for (Map.Entry<String, Document> entry : inspection.documents.entrySet()) {
            Document doc = entry.getValue();
            LinkedHashMap<String, Object> section = new LinkedHashMap<String, Object>();
            section.put("id", entry.getKey());
            section.put("page", page(doc));
            ArrayList<Object> children = new ArrayList<Object>();
            int[] tableOrdinal = new int[] {0};
            int[] imageOrdinal = new int[] {0};
            int[] shapeOrdinal = new int[] {0};
            for (Element p : elements(doc.getDocumentElement(), "p")) {
                if (ancestor(p, "p") != null || ancestor(p, "tc") != null) continue;
                children.add(paragraphContentJson(p, "section" + sectionOrdinal + ".xml", tableOrdinal,
                        imageOrdinal, shapeOrdinal, blocks, header, images, warnings, imageBytes, null));
            }
            section.put("children", children);
            section.put("headerFooters", headerFooters(doc, "section" + sectionOrdinal + ".xml",
                    tableOrdinal, imageOrdinal, shapeOrdinal, blocks, header, images, warnings, imageBytes));
            sections.add(section);
            sectionOrdinal++;
        }
        return new Model(sections, warnings);
    }

    private static Map<String, Object> paragraphJson(ParagraphEditor.Block block, Header header,
            String readOnlyReason) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("type", "paragraph");
        out.put("id", block.id); out.put("label", block.label); out.put("text", block.text);
        out.put("editable", readOnlyReason == null ? block.editable : false);
        out.put("reason", readOnlyReason == null ? block.reason : readOnlyReason);
        out.put("pageBreakBefore", boolAttr(block.paragraph, "pageBreak"));
        Element firstRun = block.runElement;
        if (firstRun == null) { List<Element> candidates = direct(block.paragraph, "run"); if (!candidates.isEmpty()) firstRun = candidates.get(0); }
        Map<String, Object> style = header.style(firstRun, block.paragraph);
        out.put("style", style);
        ArrayList<Object> runs = new ArrayList<Object>();
        for (Element runElement : direct(block.paragraph, "run")) {
            String text = runText(runElement); if (text.length() == 0 && direct(block.paragraph, "run").size() > 1) continue;
            LinkedHashMap<String, Object> run = new LinkedHashMap<String, Object>();
            run.put("text", text); run.put("style", header.style(runElement, block.paragraph)); runs.add(run);
        }
        if (runs.isEmpty()) { LinkedHashMap<String, Object> run = new LinkedHashMap<String, Object>(); run.put("text", block.text); run.put("style", style); runs.add(run); }
        out.put("runs", runs);
        return out;
    }

    private static Map<String, Object> paragraphContentJson(Element paragraph, String section,
            int[] tableOrdinal, int[] imageOrdinal, int[] shapeOrdinal,
            IdentityHashMap<Element, ParagraphEditor.Block> blocks,
            Header header, Images images, List<Object> warnings, long[] imageBytes,
            String readOnlyReason) {
        ParagraphEditor.Block block = blocks.get(paragraph);
        ArrayList<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>();
        boolean paragraphAdded = false;
        boolean contentControlSeen = false;
        boolean approximateOrderWarned = false;
        NodeList paragraphChildren = paragraph.getChildNodes();
        for (int i = 0; i < paragraphChildren.getLength(); i++) {
            Node child = paragraphChildren.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) child;
            if (is(element, "linesegarray")) continue;
            if (is(element, "run")) {
                NodeList runChildren = element.getChildNodes();
                for (int j = 0; j < runChildren.getLength(); j++) {
                    Node runChild = runChildren.item(j);
                    if (runChild.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element item = (Element) runChild;
                    if (is(item, "t")) {
                        if (paragraphAdded && contentControlSeen && !approximateOrderWarned) {
                            warnings.add(warning("mixed-control-order-approximate", block == null ? section : block.id));
                            approximateOrderWarned = true;
                        }
                        if (!paragraphAdded && block != null) {
                            ordered.add(paragraphJson(block, header, readOnlyReason));
                            paragraphAdded = true;
                        }
                    } else if (is(item, "tbl")) {
                        contentControlSeen = true;
                        ordered.add(tableJson(item, section, tableOrdinal, imageOrdinal, shapeOrdinal, blocks,
                                header, images, warnings, imageBytes, readOnlyReason));
                    } else if (is(item, "pic")) {
                        contentControlSeen = true;
                        ordered.add(imageJson(item, section, imageOrdinal[0]++, images, warnings, imageBytes));
                    } else if (is(item, "rect") || is(item, "line")) {
                        contentControlSeen = true;
                        ordered.add(shapeJson(item, section, tableOrdinal, imageOrdinal, shapeOrdinal,
                                blocks, header, images, warnings, imageBytes));
                    } else if (isKnownNonContentMetadata(item)) {
                        // Section/column/page-number controls affect geometry or decoration,
                        // but do not occupy inline document content in the preview.
                    } else {
                        contentControlSeen = true;
                        ordered.add(placeholderJson(section, "unsupported-inline-control", item));
                        warnings.add(warning("unsupported-inline-control", block == null ? section : block.id));
                    }
                }
            } else if (is(element, "tbl")) {
                contentControlSeen = true;
                ordered.add(tableJson(element, section, tableOrdinal, imageOrdinal, shapeOrdinal, blocks,
                        header, images, warnings, imageBytes, readOnlyReason));
            } else if (is(element, "pic")) {
                contentControlSeen = true;
                ordered.add(imageJson(element, section, imageOrdinal[0]++, images, warnings, imageBytes));
            } else if (is(element, "rect") || is(element, "line")) {
                contentControlSeen = true;
                ordered.add(shapeJson(element, section, tableOrdinal, imageOrdinal, shapeOrdinal,
                        blocks, header, images, warnings, imageBytes));
            } else if (isKnownNonContentMetadata(element)) {
                // Already represented by page geometry or intentionally omitted decoration.
            } else {
                contentControlSeen = true;
                ordered.add(placeholderJson(section, "unsupported-paragraph-control", element));
                warnings.add(warning("unsupported-paragraph-control", block == null ? section : block.id));
            }
        }
        if (!paragraphAdded && ordered.isEmpty() && block != null) {
            ordered.add(paragraphJson(block, header, readOnlyReason));
        } else if (!paragraphAdded && block != null && block.text.length() > 0) {
            ordered.add(0, paragraphJson(block, header, readOnlyReason));
        }
        for (Map<String, Object> item : ordered) decorateParagraphNode(item, block, paragraph);
        if (ordered.size() == 1) return ordered.get(0);
        LinkedHashMap<String, Object> container = new LinkedHashMap<String, Object>();
        container.put("type", "container");
        container.put("id", (block == null ? section : block.id) + ":container");
        container.put("children", ordered);
        decorateParagraphNode(container, block, paragraph);
        return container;
    }

    private static void decorateParagraphNode(Map<String, Object> node, ParagraphEditor.Block block,
            Element paragraph) {
        node.put("anchorParagraphId", block == null ? null : block.id);
        node.put("pageBreakBefore", boolAttr(paragraph, "pageBreak"));
    }

    private static Map<String, Object> placeholderJson(String section, String code, Element element) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("type", "placeholder");
        out.put("id", section + ":placeholder:" + Integer.toHexString(System.identityHashCode(element)));
        out.put("code", code);
        out.put("label", "지원하지 않는 문서 요소: " + localName(element));
        return out;
    }

    private static boolean isKnownNonContentMetadata(Element element) {
        if (is(element, "secPr") || is(element, "colPr") || is(element, "pageNum")) return true;
        if (!is(element, "ctrl")) return false;
        boolean sawElement = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
            sawElement = true;
            Element child = (Element) children.item(i);
            if (!(is(child, "colPr") || is(child, "pageNum")
                    || (is(child, "autoNum") && "PAGE".equals(child.getAttribute("numType")))
                    || is(child, "header") || is(child, "footer")
                    || is(child, "fieldBegin") || is(child, "fieldEnd"))) return false;
        }
        return sawElement;
    }

    private static Map<String, Object> tableJson(Element table, String section, int[] tableOrdinal,
            int[] imageOrdinal, int[] shapeOrdinal,
            IdentityHashMap<Element, ParagraphEditor.Block> blocks, Header header, Images images,
            List<Object> warnings, long[] imageBytes, String readOnlyReason) {
        String id = section + ":tbl:" + tableDocumentOrdinal(table);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("type", "table");
        out.put("id", id);
        Element size = child(table, "sz");
        out.put("widthPt", points(size, "width"));
        out.put("heightPt", points(size, "height"));
        Element outerMargin = child(table, "outMargin");
        LinkedHashMap<String, Object> outerMarginPt = new LinkedHashMap<String, Object>();
        for (String side : new String[] {"top", "right", "bottom", "left"}) {
            outerMarginPt.put(side, points(outerMargin, side));
        }
        out.put("outerMarginPt", outerMarginPt);
        Map<String, Object> position = positionJson(table);
        out.put("position", position);
        out.put("treatAsChar", position.get("treatAsChar"));
        Element hostParagraph = ancestor(table, "p");
        List<Element> hostRuns = direct(hostParagraph, "run");
        Element firstRun = hostRuns.isEmpty() ? null : hostRuns.get(0);
        out.put("anchorAlignment", header.style(firstRun, hostParagraph).get("alignment"));
        String contextReason = readOnlyReason == null
                ? ParagraphEditor.readOnlyContextReason(table) : readOnlyReason;
        String resizeReason = contextReason == null ? tableResizeReason(table) : contextReason;
        out.put("editableSize", resizeReason == null);
        if (resizeReason != null) out.put("reason", resizeReason);
        ArrayList<Object> rows = new ArrayList<Object>();
        int rowIndex = 0;
        for (Element row : direct(table, "tr")) {
            LinkedHashMap<String, Object> rowJson = new LinkedHashMap<String, Object>();
            ArrayList<Object> cells = new ArrayList<Object>();
            int columnIndex = 0;
            for (Element cell : direct(row, "tc")) {
                LinkedHashMap<String, Object> cellJson = new LinkedHashMap<String, Object>();
                cellJson.put("id", id + ":cell:" + rowIndex + ":" + columnIndex++);
                Element span = child(cell, "cellSpan"), cellSize = child(cell, "cellSz"), margin = child(cell, "cellMargin");
                cellJson.put("rowSpan", integer(span, "rowSpan", 1));
                cellJson.put("colSpan", integer(span, "colSpan", 1));
                cellJson.put("widthPt", points(cellSize, "width"));
                cellJson.put("heightPt", points(cellSize, "height"));
                cellJson.put("paddingPt", points(margin, "left"));
                cellJson.put("paddingTopPt", points(margin, "top"));
                cellJson.put("paddingRightPt", points(margin, "right"));
                cellJson.put("paddingBottomPt", points(margin, "bottom"));
                cellJson.put("paddingLeftPt", points(margin, "left"));
                ArrayList<Object> nested = new ArrayList<Object>();
                Element subList = child(cell, "subList");
                String verticalAlignment = enumAttr(subList, "vertAlign", "TOP");
                cellJson.put("verticalAlignment", "CENTER".equals(verticalAlignment) ? "middle"
                        : "BOTTOM".equals(verticalAlignment) ? "bottom" : "top");
                if (subList != null) for (Element paragraph : direct(subList, "p")) {
                    nested.add(paragraphContentJson(paragraph, section, tableOrdinal, imageOrdinal,
                            shapeOrdinal, blocks, header, images, warnings, imageBytes, readOnlyReason));
                }
                cellJson.put("children", nested);
                cells.add(cellJson);
            }
            rowJson.put("cells", cells);
            rows.add(rowJson);
            rowIndex++;
        }
        out.put("rows", rows);
        return out;
    }

    private static int tableDocumentOrdinal(Element table) {
        int ordinal = 0;
        for (Element candidate : elements(table.getOwnerDocument().getDocumentElement(), "tbl")) {
            if (candidate == table) return ordinal;
            ordinal++;
        }
        throw new IllegalArgumentException("문서에 연결되지 않은 표입니다.");
    }

    private static List<Object> headerFooters(Document doc, String section, int[] tableOrdinal,
            int[] imageOrdinal, int[] shapeOrdinal,
            IdentityHashMap<Element, ParagraphEditor.Block> blocks, Header header,
            Images images, List<Object> warnings, long[] imageBytes) {
        ArrayList<Element> controls = new ArrayList<Element>();
        collectHeaderFooters(doc.getDocumentElement(), controls);
        ArrayList<Object> result = new ArrayList<Object>();
        int order = 0;
        for (Element control : controls) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", section + ":header-footer:" + order);
            item.put("kind", is(control, "header") ? "header" : "footer");
            String apply = attr(control, "applyPageType");
            item.put("applyPageType", apply == null || apply.length() == 0 ? "BOTH" : apply.toUpperCase());
            item.put("sourceOrder", order);
            item.put("documentOrder", order++);
            Element ownerParagraph = ancestor(control, "p");
            ParagraphEditor.Block owner = blocks.get(ownerParagraph);
            item.put("startParagraphId", owner == null ? null : owner.id);

            Element subList = child(control, "subList");
            LinkedHashMap<String, Object> geometry = new LinkedHashMap<String, Object>();
            geometry.put("textWidthPt", points(subList, "textWidth"));
            geometry.put("textHeightPt", points(subList, "textHeight"));
            String vertical = attr(subList, "vertAlign");
            geometry.put("vertAlign", vertical == null ? "TOP" : vertical.toUpperCase());
            item.put("geometry", geometry);

            ArrayList<Object> children = new ArrayList<Object>();
            if (subList != null) for (Element paragraph : direct(subList, "p")) {
                children.add(paragraphContentJson(paragraph, section, tableOrdinal, imageOrdinal,
                        shapeOrdinal, blocks, header, images, warnings, imageBytes,
                        "머리말·꼬리말은 현재 읽기 전용입니다."));
            }
            item.put("children", children);
            result.add(item);
        }
        return result;
    }

    private static void collectHeaderFooters(Element element, List<Element> result) {
        if (is(element, "header") || is(element, "footer")) {
            result.add(element);
            return;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectHeaderFooters((Element) child, result);
            }
        }
    }

    private static Map<String, Object> imageJson(Element pic, String section, int ordinal, Images images, List<Object> warnings, long[] aggregate) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(); String id = section + ":image:" + ordinal;
        out.put("type", "image"); out.put("id", id); out.put("alt", "문서 그림");
        Element sz = child(pic, "sz"); out.put("widthPt", points(sz, "width")); out.put("heightPt", points(sz, "height"));
        out.put("position", positionJson(pic));
        Element img = descendant(pic, "img"); String ref = attr(img, "binaryItemIDRef"); ImageAsset asset = images.byId.get(ref);
        String problem = null;
        if (asset == null || asset.bytes == null) problem = "image-missing";
        else if (!asset.supported) problem = "image-unsupported";
        else if (asset.bytes.length > MAX_IMAGE || aggregate[0] + asset.bytes.length > MAX_IMAGES || !asset.safeDimensions) problem = "image-too-large";
        if (problem == null) { aggregate[0] += asset.bytes.length; out.put("src", "data:" + asset.mime + ";base64," + Base64.getEncoder().encodeToString(asset.bytes)); }
        else { out.put("src", null); out.put("placeholder", problem); warnings.add(warning(problem, id)); }
        return out;
    }

    private static Map<String, Object> shapeJson(Element shape, String section, int[] tableOrdinal,
            int[] imageOrdinal, int[] shapeOrdinal, IdentityHashMap<Element, ParagraphEditor.Block> blocks,
            Header header, Images images, List<Object> warnings, long[] imageBytes) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        String kind = is(shape, "rect") ? "rect" : "line";
        out.put("type", "shape");
        out.put("kind", kind);
        out.put("id", section + ":shape:" + shapeOrdinal[0]++);
        Element size = child(shape, "sz");
        Element current = child(shape, "curSz");
        Element original = child(shape, "orgSz");
        out.put("widthPt", shapeDimension(size, current, original, "width"));
        out.put("heightPt", shapeDimension(size, current, original, "height"));
        out.put("position", positionJson(shape));
        Element lineShape = child(shape, "lineShape");
        out.put("strokeColor", attr(lineShape, "color"));
        out.put("strokeWidthPt", points(lineShape, "width"));
        Element brush = descendant(shape, "winBrush");
        out.put("fillColor", attr(brush, "faceColor"));

        ArrayList<Object> children = new ArrayList<Object>();
        Element subList = descendant(shape, "subList");
        if (subList != null) for (Element paragraph : direct(subList, "p")) {
            children.add(paragraphContentJson(paragraph, section, tableOrdinal, imageOrdinal,
                    shapeOrdinal, blocks, header, images, warnings, imageBytes,
                    "이 개체의 글은 현재 읽기 전용입니다."));
        }
        out.put("children", children);
        return out;
    }

    private static double shapeDimension(Element size, Element current, Element original, String attribute) {
        long value = whole(size, attribute, 0);
        if (value <= 0) value = whole(current, attribute, 0);
        if (value <= 0) value = whole(original, attribute, 0);
        return value / 100.0;
    }

    private static Map<String, Object> positionJson(Element object) {
        Element position = child(object, "pos");
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("treatAsChar", boolAttr(position, "treatAsChar"));
        out.put("horzRelTo", enumAttr(position, "horzRelTo", "PARA"));
        out.put("vertRelTo", enumAttr(position, "vertRelTo", "PARA"));
        out.put("horzAlign", enumAttr(position, "horzAlign", "LEFT"));
        out.put("vertAlign", enumAttr(position, "vertAlign", "TOP"));
        out.put("horzOffsetPt", signedPoints(position, "horzOffset"));
        out.put("vertOffsetPt", signedPoints(position, "vertOffset"));
        return out;
    }

    private static String enumAttr(Element element, String name, String fallback) {
        String value = attr(element, name);
        return value == null || value.length() == 0 ? fallback : value.toUpperCase();
    }

    private static double signedPoints(Element element, String attribute) {
        String raw = attr(element, attribute);
        if (raw == null) return 0;
        try {
            long value = Long.parseLong(raw);
            if (value >= 0x80000000L && value <= 0xffffffffL) value -= 0x100000000L;
            return value / 100.0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Map<String, Object> page(Document doc) {
        Element pr = descendant(doc.getDocumentElement(), "pagePr"); Element margin = pr == null ? null : child(pr, "margin");
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        // HWPX keeps paper dimensions in portrait order; NARROWLY means landscape.
        // This matches ForSecPr.pageDirection in the bundled converter.
        boolean landscape = pr != null && "NARROWLY".equals(pr.getAttribute("landscape"));
        out.put("widthPt", points(pr, landscape ? "height" : "width"));
        out.put("heightPt", points(pr, landscape ? "width" : "height"));
        out.put("marginTopPt", points(margin, "top")); out.put("marginRightPt", points(margin, "right"));
        out.put("marginBottomPt", points(margin, "bottom")); out.put("marginLeftPt", points(margin, "left"));
        out.put("marginHeaderPt", points(margin, "header"));
        out.put("marginFooterPt", points(margin, "footer"));
        return out;
    }

    private static String runText(Element run) {
        StringBuilder out = new StringBuilder();
        for (Element text : elements(run, "t")) {
            if (ancestor(text, "run") != run) continue;
            NodeList children = text.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                    out.append(node.getNodeValue());
                } else if (node.getNodeType() == Node.ELEMENT_NODE && is((Element) node, "lineBreak")) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static void validateIds(Request request, ParagraphEditor.Inspection inspection, HwpxArchive archive) throws Exception {
        for (String id : request.edits.keySet()) {
            if (!inspection.byId.containsKey(id)) throw new EngineException("문서에서 수정 항목을 찾을 수 없습니다: " + id);
        }
        for (String id : request.styles.keySet()) {
            ParagraphEditor.Block block = inspection.byId.get(id);
            if (block == null) throw new EngineException("문서에서 서식 항목을 찾을 수 없습니다: " + id);
            if (!block.editable) throw new EngineException("읽기 전용 문단의 서식은 바꿀 수 없습니다: " + id);
        }
        Set<String> tables = tableIds(archive);
        for (String id : request.tables.keySet()) {
            if (!tables.contains(id)) throw new EngineException("문서에서 표를 찾을 수 없습니다: " + id);
        }
    }

    private static void applyStyles(HwpxArchive archive, ParagraphEditor.Inspection inspection, Map<String, StyleEdit> edits) throws Exception {
        if (edits.isEmpty()) return;
        Header header = Header.read(archive);
        IdentityHashMap<Document, Boolean> changed = new IdentityHashMap<Document, Boolean>();
        for (Map.Entry<String, StyleEdit> e : edits.entrySet()) {
            ParagraphEditor.Block block = inspection.byId.get(e.getKey());
            if (block.runElements.isEmpty()) {
                throw new EngineException("문단의 글자 서식을 찾을 수 없습니다: " + e.getKey());
            }
            for (Element run : block.runElements) cloneRunStyle(header, run, e.getValue());
            ParagraphEditor.invalidateLineSegments(block.paragraph);
            changed.put(block.paragraph.getOwnerDocument(), Boolean.TRUE);
        }
        archive.replaceXml("Contents/header.xml", header.doc);
        for (Map.Entry<String, Document> e : inspection.documents.entrySet()) {
            if (changed.containsKey(e.getValue())) archive.replaceXml(e.getKey(), e.getValue());
        }
    }

    private static void cloneRunStyle(Header header, Element run, StyleEdit edit) throws EngineException {
        String oldId = attr(run, "charPrIDRef");
        Element old = header.charPr(oldId);
        if (old == null) throw new EngineException("원본 글자 서식을 찾을 수 없습니다: " + oldId);
        Element clone = (Element) old.cloneNode(true);
        int newId = header.nextCharPrId();
        setAttr(clone, "id", Integer.toString(newId));
        if (edit.size != null) setAttr(clone, "height", Long.toString(Math.round(edit.size * 100.0)));
        if (edit.family != null) header.assignFont(clone, edit.family);
        header.charProperties.appendChild(clone);
        setAttr(header.charProperties, "itemCnt", Integer.toString(direct(header.charProperties, "charPr").size()));
        setAttr(run, "charPrIDRef", Integer.toString(newId));
    }

    private static void applyTables(HwpxArchive archive, Map<String, TableEdit> edits) throws Exception {
        if (edits.isEmpty()) return;
        int sectionNo = 0;
        for (String name : archive.sectionNames()) {
            Document doc = archive.parse(name);
            int ordinal = 0;
            boolean changed = false;
            for (Element table : elements(doc.getDocumentElement(), "tbl")) {
                String id = "section" + sectionNo + ".xml:tbl:" + ordinal++;
                TableEdit edit = edits.get(id);
                if (edit != null) {
                    resizeTable(table, edit);
                    ParagraphEditor.invalidateDescendantLineSegments(table);
                    changed = true;
                }
            }
            if (changed) archive.replaceXml(name, doc);
            sectionNo++;
        }
    }

    private static void resizeTable(Element table, TableEdit edit) throws EngineException {
        String contextReason = ParagraphEditor.readOnlyContextReason(table);
        if (contextReason != null) {
            throw new EngineException("읽기 전용 영역의 표 크기는 변경할 수 없습니다: " + contextReason);
        }
        String reason = tableResizeReason(table);
        if (reason != null) throw new EngineException("표 크기를 안전하게 변경할 수 없습니다: " + reason);
        Element size = child(table, "sz");
        long oldWidth = whole(size, "width", 0), oldHeight = whole(size, "height", 0);
        long newWidth = edit.width == null ? oldWidth : Math.round(edit.width * 100.0);
        long newHeight = edit.height == null ? oldHeight : Math.round(edit.height * 100.0);
        Grid grid = Grid.read(table);
        long[] widths = scale(grid.widths, newWidth), heights = scale(grid.heights, newHeight);
        for (Element cell : elements(table, "tc")) {
            if (ancestorTable(cell) != table) continue;
            Element cellSize = child(cell, "cellSz"), address = child(cell, "cellAddr"), span = child(cell, "cellSpan");
            int column = integer(address, "colAddr", -1), row = integer(address, "rowAddr", -1);
            int columnSpan = integer(span, "colSpan", 1), rowSpan = integer(span, "rowSpan", 1);
            setAttr(cellSize, "width", Long.toString(sum(widths, column, columnSpan)));
            setAttr(cellSize, "height", Long.toString(sum(heights, row, rowSpan)));
        }
        setAttr(size, "width", Long.toString(newWidth));
        setAttr(size, "height", Long.toString(newHeight));
    }

    private static String tableResizeReason(Element table) {
        if (ancestorTable(table) != null || descendantTable(table) != null) {
            return "중첩 표는 크기 변경을 지원하지 않습니다.";
        }
        Element size = child(table, "sz");
        if (size == null || whole(size, "width", 0) <= 0 || whole(size, "height", 0) <= 0) {
            return "표의 외곽 크기를 확인할 수 없습니다.";
        }
        for (Element cell : elements(table, "tc")) {
            if (ancestorTable(cell) == table && (child(cell, "cellSz") == null
                    || child(cell, "cellAddr") == null || child(cell, "cellSpan") == null)) {
                return "셀 크기 격자가 불완전합니다.";
            }
        }
        try {
            Grid.read(table);
        } catch (EngineException e) {
            return e.getMessage();
        }
        return null;
    }

    private static final class Grid {
        final long[] widths, heights;

        Grid(long[] widths, long[] heights) {
            this.widths = widths;
            this.heights = heights;
        }

        static Grid read(Element table) throws EngineException {
            int columnCount = gridInteger(table, "colCnt"), rowCount = gridInteger(table, "rowCnt");
            if (columnCount < 1 || rowCount < 1) throw new EngineException("표의 행/열 수가 올바르지 않습니다.");
            long[] widths = new long[columnCount], heights = new long[rowCount];
            for (Element cell : elements(table, "tc")) {
                if (ancestorTable(cell) != table) continue;
                Element address = child(cell, "cellAddr"), span = child(cell, "cellSpan"), size = child(cell, "cellSz");
                int column = gridInteger(address, "colAddr"), row = gridInteger(address, "rowAddr");
                int columnSpan = gridInteger(span, "colSpan"), rowSpan = gridInteger(span, "rowSpan");
                if (column < 0 || row < 0 || column >= columnCount || row >= rowCount
                        || columnSpan < 1 || rowSpan < 1
                        || columnSpan > columnCount - column || rowSpan > rowCount - row) {
                    throw new EngineException("셀 주소가 표 격자를 벗어납니다.");
                }
                long width = whole(size, "width", 0), height = whole(size, "height", 0);
                if (columnSpan == 1) {
                    if (widths[column] != 0 && widths[column] != width) throw new EngineException("열 너비 격자가 일관되지 않습니다.");
                    widths[column] = width;
                }
                if (rowSpan == 1) {
                    if (heights[row] != 0 && heights[row] != height) throw new EngineException("행 높이 격자가 일관되지 않습니다.");
                    heights[row] = height;
                }
            }
            for (long width : widths) if (width <= 0) throw new EngineException("열 너비 격자를 증명할 수 없습니다.");
            for (long height : heights) if (height <= 0) throw new EngineException("행 높이 격자를 증명할 수 없습니다.");
            validateCells(table, widths, heights);
            Element outerSize = child(table, "sz");
            if (whole(outerSize, "width", 0) != sum(widths, 0, widths.length)
                    || whole(outerSize, "height", 0) != sum(heights, 0, heights.length)) {
                throw new EngineException("표 외곽 크기가 행/열 격자 합과 일치하지 않습니다.");
            }
            return new Grid(widths, heights);
        }

        private static void validateCells(Element table, long[] widths, long[] heights) throws EngineException {
            boolean[][] occupied = new boolean[heights.length][widths.length];
            for (Element cell : elements(table, "tc")) {
                if (ancestorTable(cell) != table) continue;
                Element address = child(cell, "cellAddr"), span = child(cell, "cellSpan"), size = child(cell, "cellSz");
                int column = gridInteger(address, "colAddr"), row = gridInteger(address, "rowAddr");
                int columnSpan = gridInteger(span, "colSpan"), rowSpan = gridInteger(span, "rowSpan");
                if (whole(size, "width", 0) != sum(widths, column, columnSpan)
                        || whole(size, "height", 0) != sum(heights, row, rowSpan)) {
                    throw new EngineException("병합 셀 크기가 행/열 격자 합과 일치하지 않습니다.");
                }
                for (int r = row; r < row + rowSpan; r++) for (int c = column; c < column + columnSpan; c++) {
                    if (occupied[r][c]) throw new EngineException("표 셀 격자가 서로 겹칩니다.");
                    occupied[r][c] = true;
                }
            }
            for (boolean[] row : occupied) for (boolean value : row) {
                if (!value) throw new EngineException("표 셀 격자에 빈 영역이 있습니다.");
            }
        }

        private static int gridInteger(Element element, String attribute) throws EngineException {
            String value = attr(element, attribute);
            if (value == null) throw new EngineException("표 격자 숫자 속성이 없습니다: " + attribute);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new EngineException("표 격자 숫자 속성이 정수 범위를 벗어납니다: " + attribute);
            }
        }
    }

    private static long[] scale(long[] source, long target) {
        long total = sum(source, 0, source.length), seen = 0;
        long[] out = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            long next = i + 1 == source.length ? target : Math.round((double) (seen + source[i]) * target / total);
            out[i] = next - sum(out, 0, i);
            seen += source[i];
        }
        return out;
    }

    private static long sum(long[] values, int start, int count) {
        long result = 0;
        for (int i = start; i < start + count; i++) result += values[i];
        return result;
    }

    private static Set<String> tableIds(HwpxArchive archive) throws Exception {
        HashSet<String> ids = new HashSet<String>();
        int section = 0;
        for (String name : archive.sectionNames()) {
            Document document = archive.parse(name);
            int ordinal = 0;
            for (Element ignored : elements(document.getDocumentElement(), "tbl")) {
                ids.add("section" + section + ".xml:tbl:" + ordinal++);
            }
            section++;
        }
        return ids;
    }

    private static final class Request {
        final LinkedHashMap<String, String> edits = new LinkedHashMap<String, String>();
        final LinkedHashMap<String, StyleEdit> styles = new LinkedHashMap<String, StyleEdit>();
        final LinkedHashMap<String, TableEdit> tables = new LinkedHashMap<String, TableEdit>();
        List<?> structure = java.util.Collections.emptyList();
        int operationCount;

        static Request parse(Map<?, ?> root) throws EngineException {
            Request request = new Request();
            request.structure = list(root, "structure");
            request.operationCount = request.structure.size();
            for (Object item : list(root, "edits")) {
                Map<?, ?> edit = obj(item, "edits");
                String id = id(edit);
                Object text = edit.get("text");
                if (!(text instanceof String)) throw new EngineException("edits.text는 문자열이어야 합니다.");
                unique(request.edits, id, (String) text);
                request.operationCount++;
            }
            parseStyles(root, request);
            parseTables(root, request);
            return request;
        }

        private static void parseStyles(Map<?, ?> root, Request request) throws EngineException {
            for (Object item : list(root, "styles")) {
                Map<?, ?> style = obj(item, "styles");
                String id = id(style), family = null;
                Double size = null;
                if (style.containsKey("fontFamily")) {
                    if (!(style.get("fontFamily") instanceof String)) throw new EngineException("fontFamily는 문자열이어야 합니다.");
                    family = (String) style.get("fontFamily");
                    if (family.length() < 1 || family.length() > 128 || hasControl(family)) {
                        throw new EngineException("글꼴 이름이 안전하지 않거나 허용 길이를 벗어났습니다.");
                    }
                }
                if (style.containsKey("fontSizePt")) {
                    size = finite(style.get("fontSizePt"), "fontSizePt");
                    if (size < 6 || size > 96) throw new EngineException("글꼴 크기는 6~96pt여야 합니다.");
                }
                if (family == null && size == null) throw new EngineException("styles 항목에 변경할 값이 없습니다.");
                unique(request.styles, id, new StyleEdit(family, size));
                request.operationCount++;
            }
        }

        private static void parseTables(Map<?, ?> root, Request request) throws EngineException {
            for (Object item : list(root, "tables")) {
                Map<?, ?> table = obj(item, "tables");
                String id = id(table);
                Double width = null, height = null;
                if (table.containsKey("widthPt")) {
                    width = finite(table.get("widthPt"), "widthPt");
                    if (width < 36 || width > 1440) throw new EngineException("표 너비는 36~1440pt여야 합니다.");
                }
                if (table.containsKey("heightPt")) {
                    height = finite(table.get("heightPt"), "heightPt");
                    if (height < 1 || height > 2880) throw new EngineException("표 높이는 1~2880pt여야 합니다.");
                }
                if (width == null && height == null) throw new EngineException("tables 항목에 변경할 값이 없습니다.");
                unique(request.tables, id, new TableEdit(width, height));
                request.operationCount++;
            }
        }
    }
    private static final class StyleEdit {
        final String family;
        final Double size;
        StyleEdit(String family, Double size) { this.family = family; this.size = size; }
    }

    private static final class TableEdit {
        final Double width, height;
        TableEdit(Double width, Double height) { this.width = width; this.height = height; }
    }

    private static final class Model {
        final List<Object> sections, warnings;
        Model(List<Object> sections, List<Object> warnings) { this.sections = sections; this.warnings = warnings; }
    }

    private static final class Header {
        final Document doc;
        final Element charProperties, paraProperties;
        final List<Element> fontfaces;

        Header(Document doc, Element charProperties, Element paraProperties, List<Element> fontfaces) {
            this.doc = doc;
            this.charProperties = charProperties;
            this.paraProperties = paraProperties;
            this.fontfaces = fontfaces;
        }

        static Header read(HwpxArchive archive) throws Exception {
            Document doc = archive.parse("Contents/header.xml");
            Element chars = descendant(doc.getDocumentElement(), "charProperties");
            Element paragraphs = descendant(doc.getDocumentElement(), "paraProperties");
            if (chars == null || paragraphs == null) throw new EngineException("HWPX 글자/문단 서식 목록이 없습니다.");
            return new Header(doc, chars, paragraphs, elements(doc.getDocumentElement(), "fontface"));
        }

        Element charPr(String id) {
            for (Element element : direct(charProperties, "charPr")) if (id.equals(attr(element, "id"))) return element;
            return null;
        }

        int nextCharPrId() {
            int maximum = -1;
            for (Element element : direct(charProperties, "charPr")) maximum = Math.max(maximum, integer(element, "id", -1));
            return maximum + 1;
        }

        Map<String, Object> style(Element run, Element paragraph) {
            LinkedHashMap<String, Object> style = new LinkedHashMap<String, Object>();
            Element charPr = run == null ? null : charPr(attr(run, "charPrIDRef"));
            String family = fontFamily(charPr), color = charPr == null ? "#000000" : attr(charPr, "textColor");
            style.put("fontFamily", family);
            style.put("fontSizePt", charPr == null ? 10.0 : whole(charPr, "height", 1000) / 100.0);
            style.put("bold", charPr != null && child(charPr, "bold") != null);
            style.put("italic", charPr != null && child(charPr, "italic") != null);
            Element underline = child(charPr, "underline");
            style.put("underline", underline != null && !"NONE".equalsIgnoreCase(attr(underline, "type")));
            style.put("color", color == null ? "#000000" : color);
            addParagraphStyle(style, paraPr(attr(paragraph, "paraPrIDRef")));
            return style;
        }

        private String fontFamily(Element charPr) {
            Element fontRef = child(charPr, "fontRef");
            if (fontRef == null) return "sans-serif";
            String fontId = attr(fontRef, "hangul");
            for (Element fontface : fontfaces) {
                if (!"HANGUL".equalsIgnoreCase(attr(fontface, "lang"))) continue;
                for (Element font : direct(fontface, "font")) if (fontId.equals(attr(font, "id"))) return attr(font, "face");
            }
            return "sans-serif";
        }

        private void addParagraphStyle(Map<String, Object> style, Element paraPr) {
            Element align = child(paraPr, "align"), margin = descendant(paraPr, "margin");
            Element spacing = descendant(paraPr, "lineSpacing");
            style.put("alignment", alignment(attr(align, "horizontal")));
            String spacingType = attr(spacing, "type");
            double spacingValue = whole(spacing, "value", 120);
            if ("PERCENT".equalsIgnoreCase(spacingType)) {
                style.put("lineHeight", spacingValue / 100.0);
                style.put("lineHeightType", "ratio");
            } else {
                style.put("lineHeight", 1.0);
                style.put("lineHeightPt", spacingValue / 100.0);
                style.put("lineHeightType", spacingType == null ? "fixed" : spacingType.toLowerCase());
            }
            style.put("marginTopPt", measureChild(margin, "prev"));
            style.put("marginBottomPt", measureChild(margin, "next"));
            style.put("marginLeftPt", measureChild(margin, "left"));
            style.put("marginRightPt", measureChild(margin, "right"));
            style.put("textIndentPt", measureChild(margin, "intent"));
        }

        Element paraPr(String id) {
            for (Element element : direct(paraProperties, "paraPr")) {
                if (id != null && id.equals(attr(element, "id"))) return element;
            }
            return null;
        }

        void assignFont(Element charPr, String family) {
            Element reference = child(charPr, "fontRef");
            if (reference == null) return;
            for (Element fontface : fontfaces) {
                Element found = null;
                for (Element font : direct(fontface, "font")) {
                    if (family.equals(attr(font, "face"))) { found = font; break; }
                }
                if (found == null) found = appendFont(fontface, family);
                String language = attr(fontface, "lang");
                if (found != null && language != null) setAttr(reference, language.toLowerCase(), attr(found, "id"));
            }
        }

        private Element appendFont(Element fontface, String family) {
            List<Element> fonts = direct(fontface, "font");
            if (fonts.isEmpty()) return null;
            Element clone = (Element) fonts.get(0).cloneNode(true);
            int next = 0;
            for (Element font : fonts) next = Math.max(next, integer(font, "id", -1) + 1);
            setAttr(clone, "id", Integer.toString(next));
            setAttr(clone, "face", family);
            fontface.appendChild(clone);
            setAttr(fontface, "fontCnt", Integer.toString(direct(fontface, "font").size()));
            return clone;
        }
    }

    private static final class Images {
        final Map<String, ImageAsset> byId = new LinkedHashMap<String, ImageAsset>();

        static Images read(HwpxArchive archive) throws Exception {
            Images images = new Images();
            Document manifest = archive.parse("Contents/content.hpf");
            for (Element item : elements(manifest.getDocumentElement(), "item")) {
                String id = attr(item, "id"), href = attr(item, "href");
                if (id != null && href != null && href.startsWith("BinData/")) {
                    images.byId.put(id, new ImageAsset(archive.bytes(href)));
                }
            }
            return images;
        }
    }

    private static final class ImageAsset {
        final byte[] bytes;
        final String mime;
        final boolean supported, safeDimensions;

        ImageAsset(byte[] bytes) {
            this.bytes = bytes;
            this.mime = imageMime(bytes);
            supported = this.mime != null;
            long pixels = imagePixels(bytes, this.mime);
            safeDimensions = pixels > 0 && pixels <= 40_000_000L;
        }
    }

    private static String imageMime(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length >= 8
                && (bytes[0] & 255) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8
                && (bytes[2] & 255) == 0xff) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
            return "image/gif";
        }
        return null;
    }

    private static long imagePixels(byte[] bytes, String mime) {
        if (bytes == null) return -1;
        try {
            int width = 0, height = 0;
            if ("image/png".equals(mime) && bytes.length >= 24) {
                width = be(bytes, 16); height = be(bytes, 20);
            } else if ("image/gif".equals(mime) && bytes.length >= 10) {
                width = le16(bytes, 6); height = le16(bytes, 8);
            } else if ("image/jpeg".equals(mime)) {
                int[] dimensions = jpegDimensions(bytes);
                width = dimensions[0]; height = dimensions[1];
            } else return -1;
            if (width < 1 || height < 1 || width > 16_384 || height > 16_384) return -1;
            return (long) width * height;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static int[] jpegDimensions(byte[] bytes) {
        int index = 2;
        while (index + 8 < bytes.length) {
            if ((bytes[index] & 255) != 255) { index++; continue; }
            int marker = bytes[index + 1] & 255;
            int length = ((bytes[index + 2] & 255) << 8) | (bytes[index + 3] & 255);
            boolean startOfFrame = (marker >= 192 && marker <= 195) || (marker >= 197 && marker <= 199)
                    || (marker >= 201 && marker <= 203) || (marker >= 205 && marker <= 207);
            if (startOfFrame) {
                int height = ((bytes[index + 5] & 255) << 8) | (bytes[index + 6] & 255);
                int width = ((bytes[index + 7] & 255) << 8) | (bytes[index + 8] & 255);
                return new int[] {width, height};
            }
            index += 2 + length;
        }
        return new int[] {0, 0};
    }

    private static List<?> list(Map<?, ?> map, String key) throws EngineException {
        Object value = map.get(key);
        if (value == null) return new ArrayList<Object>();
        if (!(value instanceof List)) throw new EngineException(key + "는 배열이어야 합니다.");
        return (List<?>) value;
    }

    private static Map<?, ?> obj(Object value, String name) throws EngineException {
        if (!(value instanceof Map)) throw new EngineException(name + " 항목은 객체여야 합니다.");
        return (Map<?, ?>) value;
    }

    private static String id(Map<?, ?> map) throws EngineException {
        Object value = map.get("id");
        if (!(value instanceof String) || ((String) value).length() == 0) throw new EngineException("각 작업에 문자열 id가 필요합니다.");
        return (String) value;
    }

    private static <T> void unique(Map<String, T> map, String id, T value) throws EngineException {
        if (map.containsKey(id)) throw new EngineException("같은 id가 두 번 들어 있습니다: " + id);
        map.put(id, value);
    }

    private static Double finite(Object value, String name) throws EngineException {
        if (!(value instanceof Number)) throw new EngineException(name + "는 숫자여야 합니다.");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) throw new EngineException(name + "는 유한한 숫자여야 합니다.");
        return number;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65_536];
        java.io.InputStream input = Files.newInputStream(path);
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        } finally {
            input.close();
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    private static Map<String, Object> warning(String code, String id) {
        LinkedHashMap<String, Object> warning = new LinkedHashMap<String, Object>();
        warning.put("code", code);
        warning.put("id", id);
        warning.put("message", warningMessage(code));
        return warning;
    }

    private static String warningMessage(String code) {
        if ("image-too-large".equals(code)) return "그림이 미리보기 안전 한도를 넘어 자리표시자로 표시됩니다.";
        if ("image-missing".equals(code)) return "그림 파일을 찾지 못해 자리표시자로 표시됩니다.";
        if ("image-unsupported".equals(code)) return "지원하지 않는 그림 형식이라 자리표시자로 표시됩니다.";
        if ("unsupported-inline-control".equals(code)) return "지원하지 않는 문단 요소는 읽기 전용 자리표시자로 표시됩니다.";
        if ("unsupported-paragraph-control".equals(code)) return "지원하지 않는 문단 제어 요소는 읽기 전용 자리표시자로 표시됩니다.";
        if ("mixed-control-order-approximate".equals(code)) return "문단 안의 텍스트와 개체 순서는 근사 미리보기로 표시됩니다.";
        return "문서의 일부 요소는 근사 미리보기로 표시됩니다.";
    }
    private static String alignment(String value) {
        if (value == null) return "left";
        if ("CENTER".equalsIgnoreCase(value)) return "center";
        if ("RIGHT".equalsIgnoreCase(value)) return "right";
        if ("JUSTIFY".equalsIgnoreCase(value) || "DISTRIBUTE".equalsIgnoreCase(value)) return "justify";
        return "left";
    }

    private static double measureChild(Element parent, String name) {
        return whole(child(parent, name), "value", 0) / 100.0;
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        return name == null ? element.getNodeName().replaceFirst("^.*:", "") : name;
    }

    private static boolean numberEquals(Object value, int expected) {
        return value instanceof Number && ((Number) value).intValue() == expected
                && ((Number) value).doubleValue() == expected;
    }

    private static double points(Element element, String attribute) { return whole(element, attribute, 0) / 100.0; }

    private static long whole(Element element, String attribute, long fallback) {
        String value = attr(element, attribute);
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int integer(Element element, String attribute, int fallback) {
        return (int) whole(element, attribute, fallback);
    }

    private static boolean boolAttr(Element element, String attribute) {
        String value = attr(element, attribute);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String attr(Element element, String name) {
        if (element == null) return null;
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String local = attribute.getLocalName() == null
                    ? attribute.getNodeName().replaceFirst("^.*:", "") : attribute.getLocalName();
            if (name.equalsIgnoreCase(local)) return attribute.getNodeValue();
        }
        return null;
    }

    private static void setAttr(Element element, String name, String value) {
        if (element == null) return;
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String local = attribute.getLocalName() == null
                    ? attribute.getNodeName().replaceFirst("^.*:", "") : attribute.getLocalName();
            if (name.equalsIgnoreCase(local)) { attribute.setNodeValue(value); return; }
        }
        element.setAttribute(name, value);
    }

    private static boolean is(Element element, String name) { return name.equalsIgnoreCase(localName(element)); }

    private static Element child(Element element, String name) {
        for (Element candidate : direct(element, name)) return candidate;
        return null;
    }

    private static List<Element> direct(Element element, String name) {
        ArrayList<Element> result = new ArrayList<Element>();
        if (element == null) return result;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE && is((Element) children.item(i), name)) {
                result.add((Element) children.item(i));
            }
        }
        return result;
    }

    private static List<Element> elements(Element element, String name) {
        ArrayList<Element> result = new ArrayList<Element>();
        collect(element, name, result);
        return result;
    }

    private static void collect(Element element, String name, List<Element> result) {
        if (is(element, name)) result.add(element);
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) collect((Element) children.item(i), name, result);
        }
    }

    private static Element descendant(Element element, String name) {
        for (Element candidate : elements(element, name)) if (candidate != element) return candidate;
        return null;
    }

    private static Element ancestor(Element element, String name) {
        Node cursor = element.getParentNode();
        while (cursor instanceof Element) {
            if (is((Element) cursor, name)) return (Element) cursor;
            cursor = cursor.getParentNode();
        }
        return null;
    }

    private static Element ancestorTable(Element element) { return ancestor(element, "tbl"); }

    private static Element descendantTable(Element element) {
        for (Element table : elements(element, "tbl")) if (table != element) return table;
        return null;
    }

    private static int be(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static int le16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
}
